import java.io.ByteArrayOutputStream
import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "org.github.alfu32.kte"
version = "1.0-SNAPSHOT"

val h2Version = "2.2.224"
configurations.register("h2Dist")

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
    implementation("com.h2database:h2:$h2Version")
    add("h2Dist", "com.h2database:h2:$h2Version")
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
    dependsOn(configurations.named("h2Dist"))
    val distDir = layout.projectDirectory.dir("dist")
    from(fat.map { it.archiveFile }) { rename { "kode.jar" } }
    val h2Jar = configurations.named("h2Dist").map { it.singleFile }
    from(h2Jar) { rename { "h2.jar" } }
    from(h2Jar.map { zipTree(it) }) {
        include("META-INF/LICENSE*", "LICENSE*")
        rename { "h2-LICENSE.txt" }
    }
    externalColorMap.asFile.takeIf { it.exists() }?.let { from(it) }
    val codeIntelFile = layout.projectDirectory.file("codeintel/definitions.json").asFile
    if (codeIntelFile.exists()) {
        from(codeIntelFile) { into("codeintel") }
    }
    val lspCatalog = layout.projectDirectory.file("lsp/servers.json").asFile
    if (lspCatalog.exists()) {
        from(lspCatalog) { into("lsp") }
    }
    layout.projectDirectory.file("keyword-patterns.txt").asFile.takeIf { it.exists() }?.let { from(it) }
    layout.projectDirectory.file("styles/app.css").asFile.takeIf { it.exists() }?.let { css ->
        from(css) { into("styles") }
    }
    // launchers
    from(launcherDir) {
        include("kode.sh","kode", "kode.bat", "kode.cmd", "kode.ps1")
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
    dependsOn(fat, launchers, configurations.named("h2Dist"))
    val tag = latestTagOrVersion()
    val relDir = layout.projectDirectory.dir("kode-rel-$tag")
    from(fat.map { it.archiveFile }) { rename { "kode.jar" } }
    val h2Jar = configurations.named("h2Dist").map { it.singleFile }
    from(h2Jar) { rename { "h2.jar" } }
    from(h2Jar.map { zipTree(it) }) {
        include("META-INF/LICENSE*", "LICENSE*")
        rename { "h2-LICENSE.txt" }
    }
    val codeIntelFile = layout.projectDirectory.file("codeintel/definitions.json").asFile
    if (codeIntelFile.exists()) {
        from(codeIntelFile) { into("codeintel") }
    }
    val lspCatalog = layout.projectDirectory.file("lsp/servers.json").asFile
    if (lspCatalog.exists()) {
        from(lspCatalog) { into("lsp") }
    }
    listOf("keyword-patterns.txt", "token-colors.txt", "README.md").forEach { path ->
        val file = layout.projectDirectory.file(path).asFile
        if (file.exists()) from(file)
    }
    layout.projectDirectory.file("styles/app.css").asFile.takeIf { it.exists() }?.let { css ->
        from(css) { into("styles") }
    }
    // launchers
    from(launcherDir) {
        include("kode.sh", "kode", "kode.bat", "kode.cmd", "kode.ps1")
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
        val batText = """
            |@echo off
            |set DIR=%~dp0
            |java -Dkode.home="%DIR%" -jar "%DIR%\\kode.jar" %*
            |""".trimMargin()
        outDir.resolve("kode.bat").writeText(batText)
        outDir.resolve("kode.cmd").writeText(batText)
        val psTemplate = """
            |# Powershell launcher for Kode
            |###ErrorActionPreference = 'SilentlyContinue'
            |###PSStyle.OutputRendering = 'Ansi'  # PS 7+ best-effort
            |
            |###dir = Split-Path -LiteralPath ###MyInvocation.MyCommand.Path -Parent
            |# Enable virtual terminal processing on Windows consoles
            |###sig = '[DllImport("kernel32.dll")]public static extern IntPtr GetStdHandle(int n);
            |[DllImport("kernel32.dll")]public static extern bool GetConsoleMode(IntPtr h, out int m);
            |[DllImport("kernel32.dll")]public static extern bool SetConsoleMode(IntPtr h, int m);'
            |Add-Type -Namespace VT -Name Native -MemberDefinition ###sig -ErrorAction SilentlyContinue | Out-Null
            |###h = [VT.Native]::GetStdHandle(-11) # STD_OUTPUT_HANDLE
            |if (###h -ne [IntPtr]::Zero) {
            |  ###m = 0
            |  if ([VT.Native]::GetConsoleMode(###h, [ref]###m)) {
            |    [VT.Native]::SetConsoleMode(###h, ###m -bor 0x4 -bor 0x8) | Out-Null
            |  }
            |}
            |& java -D"kode.home=###dir" -jar (Join-Path ###dir "kode.jar") @args
            |""".trimMargin()
        val psText = psTemplate.replace("###", "$")
        outDir.resolve("kode.ps1").writeText(psText)
    }
}
