import java.io.ByteArrayOutputStream
import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "org.github.alfu32.kte"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.eclipse.org/content/repositories/tm4e-snapshots/") {
        name = "tm4e-snapshots"
        mavenContent { snapshotsOnly() }
    }
    maven("https://www.jetbrains.com/intellij-repository/releases") {
        name = "jetbrains-intellij-releases"
    }
}

dependencies {
    testImplementation(kotlin("test"))
    implementation("org.eclipse.jgit:org.eclipse.jgit:7.4.0.202509020913-r")
    implementation("com.soywiz.korlibs.korim:korim:4.0.10")
    implementation("com.soywiz.korlibs.korio:korio:4.0.10")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(17)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

val externalColorMap = layout.projectDirectory.file("token-colors.txt")

fun Project.latestTagOrVersion(): String {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "describe", "--tags", "--abbrev=0")
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        stdout.toString().trim().ifEmpty { version.toString() }
    } catch (_: Exception) {
        version.toString()
    }
}

/**
 * Build a self-contained executable JAR (fat / uber JAR).
 */
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat JAR with all dependencies"

    archiveBaseName.set("kode")
    archiveClassifier.set("all")        // so name ends with -all.jar

    // optional: if you do not want the version in filename:
    // archiveVersion.set("")

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    manifest {
        attributes["Main-Class"] = "editor.app.MainKt"
        attributes["Implementation-Version"] = project.latestTagOrVersion()
    }

    // include compiled classes/resources of this project
    from(sourceSets.main.get().output)

    // include all runtime dependencies
    val runtimeClasspath = configurations.runtimeClasspath.get()
    from({
        runtimeClasspath
            .filter { it.name.endsWith(".jar") }
            .map { zipTree(it) }
    }) {
        // CRITICAL: exclude all signature-related metadata
        exclude("META-INF/*.SF")
        exclude("META-INF/*.RSA")
        exclude("META-INF/*.DSA")
        exclude("META-INF/*.EC")

        // also safe to exclude unused Maven metadata
        exclude("META-INF/*.kotlin_module")
    }
}

tasks.register<Copy>("distBundle") {
    group = "distribution"
    description = "Bundle fat jar into dist/"
    val fat = tasks.named<Jar>("fatJar")
    val launchers = tasks.named("generateLaunchers")
    dependsOn(fat, launchers)
    val distDir = layout.projectDirectory.dir("dist")
    from(fat.map { it.archiveFile }) { rename { "kode.jar" } }
    externalColorMap.asFile.takeIf { it.exists() }?.let { from(it) }
    layout.projectDirectory.file("keyword-patterns.txt").asFile.takeIf { it.exists() }?.let { from(it) }
    layout.projectDirectory.file("styles/app.css").asFile.takeIf { it.exists() }?.let { css ->
        from(css) { into("styles") }
    }
    // launchers
    from(launcherDir) {
        include("kode.sh","kode", "kode.bat")
        filePermissions {
            unix("755")
        }
    }
    into(distDir)
    doFirst { distDir.asFile.mkdirs() }
}

tasks.register<Copy>("releaseBundle") {
    group = "distribution"
    description = "Bundle fat jar and assets into kode-rel-<latest-tag> with renamed kode.jar"
    doNotTrackState("Release bundle is regenerated fully to include launchers and assets.")
    val fat = tasks.named<Jar>("fatJar")
    val launchers = tasks.named("generateLaunchers")
    dependsOn(fat, launchers)
    val tag = latestTagOrVersion()
    val relDir = layout.projectDirectory.dir("kode-rel-$tag")
    from(fat.map { it.archiveFile }) { rename { "kode.jar" } }
    listOf("keyword-patterns.txt", "token-colors.txt", "README.md").forEach { path ->
        val file = layout.projectDirectory.file(path).asFile
        if (file.exists()) from(file)
    }
    layout.projectDirectory.file("styles/app.css").asFile.takeIf { it.exists() }?.let { css ->
        from(css) { into("styles") }
    }
    // launchers
    from(launcherDir) {
        include("kode.sh", "kode", "kode.bat")
        filePermissions {
            unix("755")
        }
    }
    into(relDir)
    doFirst {
        relDir.asFile.deleteRecursively()
        relDir.asFile.mkdirs()
    }
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Zip the kode-rel-<latest-tag> folder"
    val tag = latestTagOrVersion()
    val relDir = layout.projectDirectory.dir("kode-rel-$tag")
    dependsOn("releaseBundle")
    from(relDir)
    archiveFileName.set("kode-rel-$tag.zip")
    destinationDirectory.set(layout.projectDirectory.asFile)
}

















val launcherDir = layout.buildDirectory.dir("launchers")

tasks.register("generateLaunchers") {
    outputs.dir(launcherDir)
    doLast {
        val outDir = launcherDir.get().asFile
        outDir.mkdirs()
        val shText = """
            |#!/usr/bin/env sh
            |DIR="$(CDPATH= cd -- "$(dirname -- "${'$'}0")" && pwd)"
            |exec java -Dkode.home="${'$'}DIR" -jar "${'$'}DIR/kode.jar" "${'$'}@"
            |""".trimMargin()
        val sh = outDir.resolve("kode.sh")
        sh.writeText(shText)
        sh.setExecutable(true, false)
        val sh2 = outDir.resolve("kode")
        sh2.writeText(shText)
        sh2.setExecutable(true, false)
        val bat = outDir.resolve("kode.bat")
        bat.writeText(
            """
            |@echo off
            |set DIR=%~dp0
            |java -Dkode.home="%DIR%" -jar "%DIR%\\kode.jar" %*
            |""".trimMargin()
        )
    }
}
