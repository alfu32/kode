import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import org.gradle.api.tasks.bundling.Zip

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.serialization") version "2.2.20"
}

group = "org.github.alfu32.kte"
version = "3.3.4"

val baseVersion = version.toString()

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
    implementation("net.java.dev.jna:jna:5.14.0")
    // Tree-sitter bindings and grammars
    implementation("io.github.bonede:tree-sitter:0.25.3")
    implementation("io.github.bonede:tree-sitter-typescript:0.21.1")
    implementation("io.github.bonede:tree-sitter-python:0.23.4")
    implementation("io.github.bonede:tree-sitter-json:0.24.8")
    implementation("io.github.bonede:tree-sitter-javascript:0.23.1")
    implementation("io.github.bonede:tree-sitter-c:0.23.2")
    implementation("io.github.bonede:tree-sitter-kotlin:0.3.8.1")
    implementation("io.github.bonede:tree-sitter-sql:gh-pages")
    implementation("io.github.bonede:tree-sitter-php:0.23.11")
    implementation("io.github.bonede:tree-sitter-css:0.23.1")
    implementation("io.github.bonede:tree-sitter-html:0.23.2")
    implementation("io.github.bonede:tree-sitter-zig:main")
    implementation("io.github.bonede:tree-sitter-markdown:0.7.1")
    implementation("io.github.bonede:tree-sitter-swift:0.5.0")
    implementation("io.github.bonede:tree-sitter-lua:2.1.3")
    implementation("io.github.bonede:tree-sitter-cpp:0.23.4")
    implementation("io.github.bonede:tree-sitter-svelte:0.11.0")
    implementation("io.github.bonede:tree-sitter-bash:0.23.3")
    implementation("io.github.bonede:tree-sitter-go:0.23.3")
    implementation("io.github.bonede:tree-sitter-perl:1.1.0")
    implementation("io.github.bonede:tree-sitter-d:0.4.0")
    implementation("io.github.bonede:tree-sitter-yaml:0.5.0")
    implementation("io.github.bonede:tree-sitter-pascal:0.9.1")
    implementation("io.github.bonede:tree-sitter-ruby:0.23.1")
    implementation("io.github.bonede:tree-sitter-ocaml:0.23.2")
    implementation("io.github.bonede:tree-sitter-c-sharp:0.23.1")
    add("h2Dist", "com.h2database:h2:$h2Version")
}

tasks.test {
    useJUnitPlatform()
}
kotlin {
    jvmToolchain(21)
    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
    }
}

val externalColorMap = layout.projectDirectory.file("token-colors.txt")
val ttydResourceDir = layout.buildDirectory.dir("generated/ttyd-resources")
val ttydReleaseBaseUrl = "https://github.com/alfu32/ttyd/releases/latest/download"
val ttydAssets = listOf(
    "ttyd.macos.dylib",
    "ttyd.msvc.dll",
    "ttyd.linux.so"
)
val downloadBundledTtydLibraries = providers.gradleProperty("downloadTtydLibraries")
    .map { value ->
        value.toBooleanStrictOrNull()
            ?: throw GradleException("downloadTtydLibraries must be true or false, got '$value'")
    }
    .orElse(
        providers.environmentVariable("KODE_DOWNLOAD_TTYD_LIBRARIES").map { value ->
            value.toBooleanStrictOrNull()
                ?: throw GradleException("KODE_DOWNLOAD_TTYD_LIBRARIES must be true or false, got '$value'")
        }
    )
    .getOrElse(true)

tasks.register("downloadTtydLibraries") {
    group = "distribution"
    description = "Download ttyd shared libraries into generated resources for bundled serve mode"
    outputs.dir(ttydResourceDir)
    outputs.upToDateWhen { false }
    doLast {
        val outDir = ttydResourceDir.get().asFile.resolve("native/ttyd")
        outDir.deleteRecursively()
        outDir.mkdirs()
        ttydAssets.forEach { asset ->
            val target = outDir.resolve(asset)
            val temp = outDir.resolve("$asset.download")
            val url = "$ttydReleaseBaseUrl/$asset"
            println("Downloading $url")
            URI(url).toURL().openStream().use { input ->
                temp.outputStream().use { output -> input.copyTo(output) }
            }
            Files.move(
                temp.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING
            )
        }
    }
}

fun Project.latestTagOrVersion(defaultVersion: String = baseVersion): String {
    return try {
        val stdout = ByteArrayOutputStream()
        exec {
            commandLine("git", "describe", "--tags", "--abbrev=0")
            standardOutput = stdout
            isIgnoreExitValue = true
        }
        stdout.toString().trim().ifEmpty { defaultVersion }
    } catch (_: Exception) {
        defaultVersion
    }
}

val bundleVersion = (findProperty("releaseNumber") as String?)
    ?.takeUnless { it.isBlank() }
    ?: latestTagOrVersion(baseVersion)
val includeBundledRuntime = (findProperty("bundleRuntime") as String?)
    ?.toBooleanStrictOrNull()
    ?: false

version = bundleVersion

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
        attributes["Implementation-Version"] = project.version.toString()
    }

    if (downloadBundledTtydLibraries) {
        val ttydLibraries = tasks.named("downloadTtydLibraries")
        dependsOn(ttydLibraries)
        from(ttydResourceDir)
    } else {
        logger.lifecycle("Skipping bundled ttyd native library download; kode serve will require vendored native resources.")
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
    if (includeBundledRuntime) {
        dependsOn("createRuntimeImage")
        from(runtimeImageDir) {
            into("runtime")
        }
    }
    into(distDir)
    doFirst { distDir.asFile.mkdirs() }
    doLast {
        if (includeBundledRuntime) {
            ensureUnixRuntimeExecutables(distDir.asFile)
        }
    }
}

tasks.register<Copy>("releaseBundle") {
    group = "distribution"
    description = "Bundle fat jar and assets into kode-rel-<latest-tag> with renamed kode.jar"
    doNotTrackState("Release bundle is regenerated fully to include launchers and assets.")
    val fat = tasks.named<Jar>("fatJar")
    val launchers = tasks.named("generateLaunchers")
    dependsOn(fat, launchers, configurations.named("h2Dist"))
    val tag = project.version.toString()
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
    if (includeBundledRuntime) {
        dependsOn("createRuntimeImage")
        from(runtimeImageDir) {
            into("runtime")
        }
    }
    into(relDir)
    doFirst {
        relDir.asFile.deleteRecursively()
        relDir.asFile.mkdirs()
    }
    doLast {
        if (includeBundledRuntime) {
            ensureUnixRuntimeExecutables(relDir.asFile)
        }
    }
}

tasks.register<Zip>("releaseZip") {
    group = "distribution"
    description = "Zip the kode-rel-<latest-tag> folder"
    val tag = project.version.toString()
    val relDir = layout.projectDirectory.dir("kode-rel-$tag")
    dependsOn("releaseBundle")
    from(relDir)
    archiveFileName.set("kode-rel-$tag.zip")
    destinationDirectory.set(layout.projectDirectory.asFile)
}

















val runtimeImageDir = layout.buildDirectory.dir("runtime-image")

fun ensureUnixRuntimeExecutables(installDir: File) {
    if (System.getProperty("os.name").lowercase().contains("windows")) {
        return
    }
    listOf("kode", "kode.sh").forEach { name ->
        installDir.resolve(name).takeIf { it.exists() }?.let {
            it.setExecutable(true, false)
            it.setReadable(true, false)
        }
    }
    val javaBinDir = installDir.resolve("runtime/bin")
    if (javaBinDir.exists()) {
        javaBinDir.walkTopDown()
            .filter { it.isFile }
            .forEach {
                it.setExecutable(true, false)
                it.setReadable(true, false)
            }
    }
    listOf("runtime/lib/jspawnhelper", "runtime/lib/jexec").forEach { relPath ->
        installDir.resolve(relPath).takeIf { it.exists() }?.let {
            it.setExecutable(true, false)
            it.setReadable(true, false)
        }
    }
}

tasks.register("createRuntimeImage") {
    group = "distribution"
    description = "Build a self-contained Java runtime image with jlink"
    outputs.dir(runtimeImageDir)
    doLast {
        val runtimeDir = runtimeImageDir.get().asFile
        runtimeDir.deleteRecursively()
        runtimeDir.parentFile?.mkdirs()

        val javaHome = file(System.getProperty("java.home"))
        val jmodsDir = javaHome.resolve("jmods")
        if (!jmodsDir.isDirectory) {
            throw GradleException("JDK jmods directory not found at ${jmodsDir.absolutePath}")
        }

        val jlinkName = if (System.getProperty("os.name").lowercase().contains("windows")) "jlink.exe" else "jlink"
        val jlinkExecutable = javaHome.resolve("bin/$jlinkName")
        if (!jlinkExecutable.isFile) {
            throw GradleException("jlink executable not found at ${jlinkExecutable.absolutePath}")
        }

        exec {
            commandLine(
                jlinkExecutable.absolutePath,
                "--module-path", jmodsDir.absolutePath,
                "--add-modules", "ALL-MODULE-PATH",
                "--compress=2",
                "--strip-debug",
                "--no-header-files",
                "--no-man-pages",
                "--output", runtimeDir.absolutePath,
            )
        }

        ensureUnixRuntimeExecutables(runtimeDir)
    }
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
            |JAVA_BIN="${'$'}DIR/runtime/bin/java"
            |if [ ! -x "${'$'}JAVA_BIN" ] && [ -x "${'$'}DIR/runtime/Contents/Home/bin/java" ]; then
            |  JAVA_BIN="${'$'}DIR/runtime/Contents/Home/bin/java"
            |fi
            |if [ ! -x "${'$'}JAVA_BIN" ]; then
            |  JAVA_BIN="java"
            |fi
            |exec "${'$'}JAVA_BIN" -Dkode.home="${'$'}DIR" -jar "${'$'}DIR/kode.jar" "${'$'}@"
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
            |set JAVA_EXE=%DIR%runtime\bin\java.exe
            |if exist "%JAVA_EXE%" (
            |  "%JAVA_EXE%" -Dkode.home="%DIR%" -jar "%DIR%\\kode.jar" %*
            |) else (
            |  java -Dkode.home="%DIR%" -jar "%DIR%\\kode.jar" %*
            |)
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
            |###javaBin = Join-Path ###dir "runtime\bin\java.exe"
            |if (!(Test-Path ###javaBin)) {
            |  ###javaBin = "java"
            |}
            |& ###javaBin -D"kode.home=###dir" -jar (Join-Path ###dir "kode.jar") @args
            |""".trimMargin()
        val psText = psTemplate.replace("###", "$")
        outDir.resolve("kode.ps1").writeText(psText)
    }
}
