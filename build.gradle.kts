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
    implementation("org.jetbrains.pty4j:pty4j:0.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
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
    dependsOn(fat)
    val distDir = layout.projectDirectory.dir("dist")
    from(fat.map { it.archiveFile })
    externalColorMap.asFile.takeIf { it.exists() }?.let { from(it) }
    layout.projectDirectory.file("keyword-patterns.txt").asFile.takeIf { it.exists() }?.let { from(it) }
    layout.projectDirectory.file("styles/app.css").asFile.takeIf { it.exists() }?.let { css ->
        from(css) { into("styles") }
    }
    into(distDir)
    doFirst { distDir.asFile.mkdirs() }
}

tasks.register<Copy>("releaseBundle") {
    group = "distribution"
    description = "Bundle fat jar and assets into kode-rel-<latest-tag> with renamed kode.jar"
    val fat = tasks.named<Jar>("fatJar")
    dependsOn(fat)
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
    into(relDir)
    doFirst { relDir.asFile.mkdirs() }
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


















