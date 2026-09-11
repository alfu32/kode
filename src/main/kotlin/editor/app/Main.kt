package editor.app

import react.BaseComponent
import react.Component
import react.ClippedCanvasRenderer
import react.StyleSheet
import react.TabView
import react.UIEvent
import react.renderer.AnsiCanvasRenderer
import react.renderer.CanvasRenderer
import react.util.enterRawMode
import react.util.restoreStty
import react.util.runCommand
import editor.grammars.KeywordSyntaxProvider
import editor.mime.DefaultMimeTypeDetector
import editor.mime.MimeTypeCategory
import editor.mime.MimeTypeResult
import editor.ui.CodeEditorView
import editor.ui.FilesTabView
import editor.ui.BinaryHexView
import editor.ui.ImageViewerView
import editor.ui.GitPanelView
import editor.ui.GitDiff
import editor.ui.SideBySideDiffView
import editor.ui.ProjectSearchDialog
import editor.ui.AboutView
import editor.ui.WorkspacePickerDialog
import editor.ui.SettingsView
import editor.ui.HelpView
import editor.ui.ProjectSettingsView
import editor.ui.SourceFolderPickerDialog
import editor.db.DbServerManager
import editor.db.DbStatus
import editor.codeintel.DbCodeIntelStore
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import editor.lib.FileTree
import editor.lib.JGitService
import editor.lib.ProjectFolderStatusClassifier
import java.net.URL
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import java.util.Locale
import java.util.Comparator
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.nio.charset.StandardCharsets
import java.nio.file.StandardCopyOption
import kotlin.system.exitProcess
import editor.app.ProjectFileScanner
import editor.codeintel.CodeIntelService
import editor.codeintel.CompositeEditorIntelligenceService
import editor.codeintel.LspEditorIntelligence
import editor.lsp.LspManager
import editor.lsp.LspService

interface StatusLineProvider {
    val someString:String="coucou"
    fun statusRight(): String
}

interface StatusLineOverride {
    fun statusLineText(): String?
}

interface AppTransitioner {
    fun nextApp(): Component?
}

interface AppShutdown {
    fun shutdownApp()
}

interface RenderInvalidator {
    fun consumeInvalidation(): Boolean
}

fun runApp(app: Component, renderer: CanvasRenderer = AnsiCanvasRenderer(), idleSleepMillis: Long = 8L) {
    val perf = PerformanceTracker()
    val frameIntervalMs = System.getenv("KODE_FRAME_MS")?.toLongOrNull()
        ?.coerceIn(8L, 200L) // 8ms ~125fps, 200ms ~5fps
        ?: 8L
    var currentApp: Component = app
    fun redraw() {
        val totalCols = renderer.cols().coerceAtLeast(1)
        val totalRows = renderer.rows().coerceAtLeast(0)
        val contentRows = (totalRows - 1).coerceAtLeast(0)
        renderer.clear()
        val contentRenderer = if (contentRows > 0) {
            ClippedCanvasRenderer(
                base = renderer,
                offsetX = 0,
                offsetY = 0,
                width = totalCols,
                height = contentRows
            )
        } else renderer

        perf.beforeFrame()
        if (contentRows > 0) {
            currentApp.render(contentRenderer)
        }
        perf.afterFrame()
        if (totalRows > 0) {
            val right = (currentApp as? StatusLineProvider)?.statusRight()
            val override = (currentApp as? StatusLineOverride)?.statusLineText()
            drawStatusLine(renderer, currentApp.styleSheet, perf.snapshot(), totalCols, totalRows - 1, right, override)
        }
        renderer.flush()
    }

    // Try to enter raw mode for ANSI terminals so key/mouse events work and echo is off.
    val savedStty = if (renderer is AnsiCanvasRenderer) enterRawMode() else null

    try {

        // Best-effort terminal prep if supported
        (renderer as? AnsiCanvasRenderer)?.enterAlternateScreen()
        renderer.enableMouseTracking()
        renderer.hideCursor()

        var needsRender = true
        var lastRenderMs = System.currentTimeMillis()
        var nextFrameTime = lastRenderMs + frameIntervalMs
        redraw()
        lastRenderMs = System.currentTimeMillis()
        nextFrameTime = lastRenderMs + frameIntervalMs

        var lastAnimationMs = lastRenderMs
        var nextAnimationTime = lastAnimationMs + 500L
        val minSleepMs = idleSleepMillis.coerceAtLeast(1L)

        while (renderer.isRunning()) {
            if ((currentApp as? RenderInvalidator)?.consumeInvalidation() == true) {
                needsRender = true
            }
            val event = renderer.tryPollEvent()
            if (event != null) {
                needsRender = currentApp.dispatch(event) || event.kind == "resize" || needsRender
            }
            (currentApp as? AppTransitioner)?.nextApp()?.let { next ->
                currentApp = next
                if (renderer is AnsiCanvasRenderer) {
                    renderer.invalidateDiffBuffer()
                }
                needsRender = true
            }

            var now = System.currentTimeMillis()

            // Periodic animation_frame dispatch
            if (now >= nextAnimationTime) {
                val ticked = currentApp.dispatch(UIEvent(kind = "animation_frame", timeMs = now))
                if (ticked) needsRender = true
                lastAnimationMs = now
                nextAnimationTime = lastAnimationMs + 500L
                now = System.currentTimeMillis()
            }

            val perfDirty = perf.loopTick(now)
            val wantRender = needsRender || perfDirty

            if (wantRender) {
                if (now < nextFrameTime) {
                    Thread.sleep((nextFrameTime - now).coerceAtLeast(minSleepMs))
                    now = System.currentTimeMillis()
                }
                redraw()
                needsRender = false
                lastRenderMs = now
                nextFrameTime = lastRenderMs + frameIntervalMs
            } else {
                val sleepUntil = minOf(nextFrameTime, nextAnimationTime)
                val sleepMs = (sleepUntil - now).coerceAtLeast(minSleepMs)
                Thread.sleep(sleepMs)
            }
        }
        (currentApp as? AppShutdown)?.shutdownApp()
        if (currentApp !== app) {
            (app as? AppShutdown)?.shutdownApp()
        }
    } finally {

        // CLEANUP GUARANTEED
        renderer.resetAttributes()
        renderer.disableMouseTracking()
        renderer.showCursor()
        renderer.shutdown()
        if (renderer is AnsiCanvasRenderer) {
            restoreStty(savedStty)
            // Safety: ensure terminal is restored even if stty state was missing or broken.
            runCommand("sh", "-c", "stty sane echo icanon isig < /dev/tty")
            renderer.leaveAlternateScreen()
        }
    }
}

fun main(args: Array<String>) {
    if (args.isEmpty()) {
        printHelp()
        return
    }
    when (args[0].lowercase()) {
        "help", "-h", "--help" -> {
            printHelp()
            return
        }
        "version", "-v", "--version" -> {
            println(resolveBuildVersion())
            return
        }
        "update" -> {
            runUpdate()
            return
        }
        "install" -> {
            runInstall()
            return
        }
        "serve" -> {
            runServe(args.drop(1).toTypedArray())
            return
        }
        "cat" -> {
            if (args.size < 2) {
                System.err.println("Missing file path for `kode cat`.")
                printHelp()
                return
            }
            runCat(args[1])
            return
        }
    }
    if (args.size > 1) {
        System.err.println("Expected a single folder argument.")
        printHelp()
        return
    }
    val styleFiles = mutableListOf("styles/app.css")
    val kodeHome = kodeHome()
    resolveResource("styles/app.css", kodeHome)?.let { styleFiles.add(0, it) }
    resolveResource("grammars/tm-scopes.css", kodeHome)?.let { styleFiles += it }
    val styleSheet = StyleSheet.loadFromFiles(styleFiles)
    val buildVersion = resolveBuildVersion()
    val renderer = AnsiCanvasRenderer()
    val workingDir = resolveWorkingDirectory(args)

    lateinit var app: SplitPanelsApp
    app = SplitPanelsApp(
        styleSheet,
        buildVersion,
        workingDir,
        renderer,
        onQuit = {
            app.persistSession(force = true)
            renderer.requestExit()
        },
        runInitialScan = false
    )

    val startup = SplashApp(styleSheet, buildVersion, app, renderer)
    runApp(startup, renderer)

    app.persistSession(force = true)
}

private fun printHelp() {
    println(
        """
        Usage:
          kode <folder>          Open project at folder
          kode cat <file>        Print file with syntax highlighting
          kode serve [options] [folder]
                               Serve Kode through a browser terminal
          kode install           Create launchers (cmd/bat/sh/ps1) in current folder
          kode update            Self-update from GitHub release
          kode version           Print version
          kode help              Show this help

        Serve options:
          --port <number>         HTTP port, default 11045
          --host <address>        Bind address, default 127.0.0.1
          --public                Bind 0.0.0.0
          --credential <u:p>      Basic auth credential passed to ttyd
          -- <args...>            Pass remaining args directly to ttyd
        """.trimIndent()
    )
}

private data class ServeOptions(
    val port: Int = 11045,
    val host: String = "127.0.0.1",
    val credential: String? = null,
    val folder: Path = Paths.get("").toAbsolutePath().normalize(),
    val ttydArgs: List<String> = emptyList()
)

private fun runServe(args: Array<String>) {
    val options = parseServeOptions(args) ?: return
    if (!Files.isDirectory(options.folder)) {
        System.err.println("Serve folder must be an existing directory: ${options.folder}")
        exitProcess(1)
    }

    val kodeCommand = resolveKodeChildCommand(options.folder)
    val ttyd = TtydAdapter.resolveLauncher()
    if (ttyd == null) {
        val platform = TtydAdapter.describePlatform()
        val asset = TtydAdapter.assetNameForCurrentPlatform()
        System.err.println("`kode serve` requires bundled ttyd, but no usable ttyd launcher is available for $platform.")
        if (asset != null) {
            System.err.println("Expected bundled asset: /native/ttyd/$asset")
            System.err.println("Vendor ttyd native libraries in src/main/resources/native/ttyd or run `./gradlew -PdownloadTtydLibraries=true downloadTtydLibraries fatJar` before packaging.")
        } else {
            System.err.println("This OS is not mapped to a ttyd native library asset.")
        }
        exitProcess(1)
    }

    val ttydArgs = buildTtydArguments(options, kodeCommand)
    val ttydKind = if (ttyd.mode == TtydLaunchMode.EXECUTABLE) "executable" else "native library"
    println("Serving Kode with bundled ttyd $ttydKind (${ttyd.assetName}) at http://${options.host}:${options.port}")
    if (options.host == "127.0.0.1" || options.host == "localhost") {
        println("Remote access through SSH tunnel: ssh -L ${options.port}:127.0.0.1:${options.port} <host>")
    } else if (options.credential == null) {
        System.err.println("Warning: public writable terminal without --credential.")
    }

    val exit = try {
        TtydAdapter.invoke(ttyd, ttydArgs)
    } catch (ex: Throwable) {
        System.err.println("Failed to start bundled ttyd $ttydKind (${ttyd.assetName}): ${ex.message}")
        1
    }
    exitProcess(exit)
}

private fun parseServeOptions(args: Array<String>): ServeOptions? {
    var port = 11045
    var host = "127.0.0.1"
    var credential: String? = null
    var folder: Path? = null
    val ttydArgs = mutableListOf<String>()
    var i = 0
    while (i < args.size) {
        val arg = args[i]
        when {
            arg == "--" -> {
                ttydArgs += args.drop(i + 1)
                break
            }
            arg == "--port" || arg == "-p" || arg == "--post" -> {
                val value = args.getOrNull(++i)
                if (value == null) {
                    System.err.println("Missing value for $arg")
                    return null
                }
                port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                    System.err.println("Invalid port: $value")
                    return null
                }
            }
            arg.startsWith("--port=") || arg.startsWith("--post=") -> {
                val value = arg.substringAfter('=')
                port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: run {
                    System.err.println("Invalid port: $value")
                    return null
                }
            }
            arg == "--host" -> {
                host = args.getOrNull(++i)?.takeIf { it.isNotBlank() } ?: run {
                    System.err.println("Missing value for --host")
                    return null
                }
            }
            arg.startsWith("--host=") -> host = arg.substringAfter('=').takeIf { it.isNotBlank() } ?: host
            arg == "--public" -> host = "0.0.0.0"
            arg == "--credential" || arg == "-c" -> {
                credential = args.getOrNull(++i)?.takeIf { it.contains(':') } ?: run {
                    System.err.println("Missing or invalid value for $arg, expected user:password")
                    return null
                }
            }
            arg.startsWith("--credential=") -> {
                credential = arg.substringAfter('=').takeIf { it.contains(':') } ?: run {
                    System.err.println("Invalid credential, expected user:password")
                    return null
                }
            }
            arg.startsWith("-") -> {
                ttydArgs += args.drop(i)
                break
            }
            folder == null -> folder = Paths.get(arg).toAbsolutePath().normalize()
            else -> {
                ttydArgs += args.drop(i)
                break
            }
        }
        i++
    }
    return ServeOptions(
        port = port,
        host = host,
        credential = credential,
        folder = folder ?: Paths.get("").toAbsolutePath().normalize(),
        ttydArgs = ttydArgs
    )
}

private fun buildTtydArguments(options: ServeOptions, kodeCommand: List<String>): List<String> {
    val command = mutableListOf(
        "ttyd",
        "-p", options.port.toString(),
        "-i", options.host,
        "-W",
        "-T", "xterm-256color",
        "-w", options.folder.toString(),
        "-t", "titleFixed=Kode"
    )
    options.credential?.let { command += listOf("-c", it) }
    command += options.ttydArgs
    command += kodeCommand
    return command
}

private fun resolveKodeChildCommand(folder: Path): List<String> {
    val jar = resolveSelfJarPath()
    return if (jar != null) {
        listOf(resolveJavaExecutable(jar), "-jar", jar.toAbsolutePath().normalize().toString(), folder.toString())
    } else {
        listOf("kode", folder.toString())
    }
}

private fun resolveJavaExecutable(selfJar: Path? = resolveSelfJarPath()): String {
    val name = if (isWindowsHost()) "java.exe" else "java"
    selfJar?.parent?.let { jarDir ->
        bundledJavaCandidates(jarDir, name).firstOrNull { Files.isExecutable(it) }?.let {
            return it.toString()
        }
    }
    val javaHome = System.getProperty("java.home")?.takeIf { it.isNotBlank() }
    val javaBin = javaHome?.let { Paths.get(it, "bin", name) }
    if (javaBin != null && Files.isExecutable(javaBin)) return javaBin.toString()
    return "java"
}

private fun bundledJavaCandidates(jarDir: Path, name: String): Sequence<Path> = sequenceOf(
    jarDir.resolve("runtime").resolve("bin").resolve(name),
    jarDir.resolve("runtime").resolve("Contents").resolve("Home").resolve("bin").resolve(name)
)

private fun isWindowsHost(): Boolean =
    System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")

private fun downloadUpdate(parent:Path,filename:String) {
    val target = parent.resolve(filename)
    val url = URL("https://github.com/alfu32/kode/releases/latest/download/$filename")
    val temp = parent.resolve("$filename.download")
    runCatching {
        url.openStream().use { input ->
            Files.copy(input, temp, StandardCopyOption.REPLACE_EXISTING)
        }
        runCatching {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }.getOrElse {
            Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING)
        }
        println("Updated: $parent/$filename")
    }.onFailure { ex ->
        runCatching { Files.deleteIfExists(temp) }
        System.err.println("Update failed:$parent / $filename ${ex.message}")
    }
}

private fun runUpdate() {
    val target = resolveSelfJarPath()
        ?: kodeHome()?.resolve("kode.jar")
        ?: Paths.get("kode.jar").toAbsolutePath().normalize()

    runCatching { Files.createDirectories(target.parent) }
    downloadUpdate(target.parent,"kode.jar")
    downloadUpdate(target.parent,"h2.jar")
    downloadUpdate(target.parent,"keyword-patterns.txt")
}

private fun runInstall() {
    val root = Paths.get("").toAbsolutePath().normalize()
    val jarPath = resolveSelfJarPath() ?: root.resolve("kode.jar")
    val jarAbs = jarPath.toAbsolutePath().normalize().toString()
    val jarAbsCmd = jarAbs.replace("/", "\\")
    val sh = buildScript(
        """
        #!/usr/bin/env sh
        exec java -jar "__KODE_JAR__" "$@"
        """.trimIndent() + "\n",
        jarAbs
    )
    val cmd = buildScript(
        """
        @echo off
        java -jar "__KODE_JAR__" %*
        """.trimIndent() + "\r\n",
        jarAbsCmd
    )
    val ps1 = buildScript(
        """
        & java -jar "__KODE_JAR__" @args
        """.trimIndent() + "\r\n",
        jarAbsCmd
    )
    runCatching {
        Files.writeString(root.resolve("kode.sh"), sh, StandardCharsets.UTF_8)
        Files.writeString(root.resolve("kode.cmd"), cmd, StandardCharsets.UTF_8)
        Files.writeString(root.resolve("kode.bat"), cmd, StandardCharsets.UTF_8)
        Files.writeString(root.resolve("kode.ps1"), ps1, StandardCharsets.UTF_8)
        println("Launchers created in $root")
    }.onFailure { ex ->
        System.err.println("Install failed: ${ex.message}")
    }
}

private fun buildScript(template: String, jarPath: String): String =
    template.replace("__KODE_JAR__", jarPath)

private fun runCat(pathArg: String) {
    val path = Paths.get(pathArg).toAbsolutePath().normalize()
    if (!Files.exists(path)) {
        System.err.println("File not found: $path")
        return
    }
    val detector = DefaultMimeTypeDetector()
    val detected = runCatching { detector.detectFile(path) }.getOrNull()
    val language = detected?.language
    val syntaxProvider = KeywordSyntaxProvider
    val lines = runCatching { Files.readAllLines(path, StandardCharsets.UTF_8) }
        .getOrElse {
            System.err.println("Failed to read file: ${it.message}")
            return
        }
    val normalizedLanguage = language?.lowercase(Locale.ROOT)
    val tokensByLine = if (!normalizedLanguage.isNullOrBlank() && syntaxProvider.languages().contains(normalizedLanguage)) {
        runCatching { syntaxProvider.tokensForLines(0, lines, normalizedLanguage).groupBy { it.line } }.getOrNull().orEmpty()
    } else emptyMap()
    val lineNumberWidth = maxOf(2, lines.size.toString().length)
    lines.forEachIndexed { index, line ->
        val tokens = tokensByLine[index].orEmpty().sortedBy { it.start }
        val linePrefix = String.format("%${lineNumberWidth}d | ", index + 1)
        if (tokens.isEmpty()) {
            println(linePrefix + line)
            return@forEachIndexed
        }
        val sb = StringBuilder()
        sb.append(linePrefix)
        var cursor = 0
        tokens.forEach { token ->
            val start = token.start.coerceIn(0, line.length)
            val end = token.end.coerceIn(start, line.length)
            if (start > cursor) sb.append(line.substring(cursor, start))
            val color = token.fg
            if (color != null) {
                sb.append(ansiColor(color))
                sb.append(line.substring(start, end))
                sb.append(ANSI_RESET)
            } else {
                sb.append(line.substring(start, end))
            }
            cursor = end
        }
        if (cursor < line.length) sb.append(line.substring(cursor))
        println(sb.toString())
    }
}

private fun ansiColor(color: react.Color): String =
    "\u001B[38;2;${color.r};${color.g};${color.b}m"

private const val ANSI_RESET = "\u001B[0m"

private fun resolveSelfJarPath(): Path? = runCatching {
    val uri = SplitPanelsApp::class.java.protectionDomain.codeSource?.location?.toURI() ?: return null
    val path = Paths.get(uri)
    if (path.toString().lowercase(Locale.ROOT).endsWith(".jar")) path else null
}.getOrNull()

private fun resolveWorkingDirectory(args: Array<String>): Path {
    val defaultDir = Paths.get("").toAbsolutePath().normalize()
    val requested = args.firstOrNull()?.takeIf { it.isNotBlank() } ?: return defaultDir
    val candidate = Paths.get(requested)
    val resolved = (if (candidate.isAbsolute) candidate else defaultDir.resolve(candidate)).toAbsolutePath().normalize()
    if (!Files.exists(resolved) || !Files.isDirectory(resolved)) {
        System.err.println("Working directory must be an existing folder: $resolved")
        exitProcess(1)
    }
    return resolved
}

private fun kodeHome(): java.nio.file.Path? {
    System.getProperty("kode.home")?.let { return Paths.get(it) }
    System.getenv("KODE_HOME")?.let { return Paths.get(it) }
    return try {
        val uri = EditorSessionState::class.java.protectionDomain.codeSource?.location?.toURI()
        uri?.let { Paths.get(it).parent }
    } catch (_: Exception) {
        null
    }
}

private fun resolveResource(rel: String, base: java.nio.file.Path?): String? {
    val candidates = listOfNotNull(
        base?.resolve(rel),
        Paths.get(rel)
    )
    return candidates.firstOrNull { Files.exists(it) }?.toString()
}

private fun resolveBuildVersion(): String {
    val fromPackage = SplitPanelsApp::class.java.`package`?.implementationVersion
    if (!fromPackage.isNullOrBlank()) return fromPackage
    return System.getProperty("kode.version")?.takeIf { it.isNotBlank() } ?: "dev"
}

private class SplashApp(
    styleSheet: StyleSheet,
    private val buildVersion: String,
    private val app: SplitPanelsApp,
    private val renderer: AnsiCanvasRenderer
) : BaseComponent(styleSheet), StatusLineProvider, StatusLineOverride, AppTransitioner, AppShutdown, RenderInvalidator {

    private val scanStatus = AtomicReference("Scan: preparing")
    private val scanFile = AtomicReference("Preparing...")
    private val scanDone = AtomicBoolean(false)
    private val renderDirty = AtomicBoolean(true)
    private var enterPressed = false
    private var splashDismissed = false
    private var lastReportedStatus = ""
    private var lastScanDone = false
    private var lastReportedFile = ""
    private var scanStarted = false
    private var transitioned = false
    private var helpScrollOffset = 0
    private var helpViewportHeight = 0
    private var helpMaxScroll = 0
    private var helpStartY = 0

    private fun startScan() {
        if (app.hasPersistentIndex()) {
            app.loadCodeIntelFromStore()
            scanStatus.set("Scan: skipped (persisted index)")
            scanFile.set("Using existing index")
            scanDone.set(true)
            renderDirty.set(true)
            return
        }
        Thread({
            runCatching {
                app.fullReindex(
                    progress = { msg ->
                        scanStatus.set(msg)
                        renderDirty.set(true)
                    },
                    progressFile = { file ->
                        scanFile.set(file)
                        renderDirty.set(true)
                    },
                )
            }.onFailure { err ->
                scanStatus.set("Indexing: failed")
                scanFile.set(err.message ?: err::class.java.simpleName)
            }
            scanDone.set(true)
            renderDirty.set(true)
        }, "codeintel-startup-scan").apply { isDaemon = true }.start()
    }

    override fun statusRight(): String {
        return if (splashDismissed) app.statusRight() else ""
    }

    override fun statusLineText(): String? {
        return if (splashDismissed) null else scanStatus.get()
    }

    override fun render(canvas: CanvasRenderer) {
        if (splashDismissed) {
            app.render(canvas)
            return
        }
        renderSplash(canvas)
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "key_down" && event.ctrl && event.key?.equals("q", ignoreCase = true) == true) {
            renderer.requestExit()
            return true
        }
        if (!splashDismissed) {
            val dirty = when (event.kind) {
                "key_down" -> {
                    if (event.key?.equals("Enter", ignoreCase = true) == true) {
                        enterPressed = true
                        true
                    } else {
                        val key = event.key?.lowercase() ?: return false
                        val prev = helpScrollOffset
                        when (key) {
                            "up" -> helpScrollOffset = (helpScrollOffset - 1).coerceAtLeast(0)
                            "down" -> helpScrollOffset = (helpScrollOffset + 1).coerceAtMost(helpMaxScroll)
                            "pageup" -> helpScrollOffset = (helpScrollOffset - helpViewportHeight).coerceAtLeast(0)
                            "pagedown" -> helpScrollOffset = (helpScrollOffset + helpViewportHeight).coerceAtMost(helpMaxScroll)
                            "home" -> helpScrollOffset = 0
                            "end" -> helpScrollOffset = helpMaxScroll
                            else -> return false
                        }
                        helpScrollOffset != prev
                    }
                }
                "resize" -> true
                "animation_frame" -> {
                    if (!scanStarted) {
                        scanStarted = true
                        startScan()
                    }
                    var changed = false
                    val status = scanStatus.get()
                    val file = scanFile.get()
                    val done = scanDone.get()
                    if (status != lastReportedStatus) {
                        lastReportedStatus = status
                        changed = true
                    }
                    if (file != lastReportedFile) {
                        lastReportedFile = file
                        changed = true
                    }
                    if (done != lastScanDone) {
                        lastScanDone = done
                        changed = true
                    }
                    changed
                }
                "mouse_scroll" -> {
                    val y = event.y ?: return false
                    if (y < helpStartY || y >= helpStartY + helpViewportHeight) return false
                    val delta = event.scrollDelta ?: return false
                    val prev = helpScrollOffset
                    helpScrollOffset = (helpScrollOffset - delta).coerceIn(0, helpMaxScroll)
                    helpScrollOffset != prev
                }
                else -> false
            }
            return dirty || checkDismiss()
        }
        return app.dispatch(event)
    }

    private fun checkDismiss(): Boolean {
        if (splashDismissed) return false
        if (enterPressed && scanDone.get()) {
            splashDismissed = true
            return true
        }
        return false
    }

    private fun renderSplash(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerLines = listOf(
            "██╗  ██╗  ██████╗  ██████╗  ███████╗",
            "██║ ██╔╝ ██╔═══██╗ ██╔══██╗ ██╔════╝",
            "█████╔╝  ██║   ██║ ██║  ██║ █████╗",
            "██╔═██╗  ██║   ██║ ██║  ██║ ██╔══╝",
            "██║  ██╗ ╚██████╔╝ ██████╔╝ ███████╗",
            "╚═╝  ╚═╝  ╚═════╝  ╚═════╝  ╚══════╝",
            "",
            "Shortcuts:"
        )
        val statusText = scanStatus.get()
        val fileText = if (scanDone.get()) "Scan complete." else scanFile.get()
        val footerLines = mutableListOf(
            "Status:",
            statusText,
            "File:",
            fileText
        )
        if (scanDone.get()) {
            footerLines.add("Scan complete. Press Enter to begin.")
        }
        val shortcutLines = HelpView.shortcutLines()
        val targetWidth = ((cols * 3) / 4).coerceAtLeast(20)
        val targetHeight = ((rows * 3) / 4).coerceAtLeast(6)
        val contentWidth = (headerLines + footerLines + shortcutLines).maxOf { it.length }.coerceAtLeast(20)
        val dialogWidth = (contentWidth + 2).coerceAtLeast(targetWidth).coerceAtMost(cols)
        val dialogHeight = (headerLines.size + footerLines.size + 2).coerceAtLeast(targetHeight).coerceAtMost(rows)
        val startX = ((cols - dialogWidth) / 2).coerceAtLeast(0)
        val startY = ((rows - dialogHeight) / 2).coerceAtLeast(0)
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(style.fg, style.bg)
        val contentX = startX + 1
        val contentY = startY + 1
        val contentHeight = dialogHeight - 2
        val textWidth = (dialogWidth - 2).coerceAtLeast(1)

        canvas.withStyle(style) {
            drawRect(startX, startY, dialogWidth, dialogHeight)
        }
        canvas.withStyle(border) {
            val endX = (startX + dialogWidth - 1).coerceAtLeast(startX)
            val endY = (startY + dialogHeight - 1).coerceAtLeast(startY)
            for (x in startX..endX) {
                drawText(x, startY, "-")
                drawText(x, endY, "-")
            }
            for (y in startY..endY) {
                drawText(startX, y, "|")
                drawText(endX, y, "|")
            }
            drawText(startX, startY, "+")
            drawText(endX, startY, "+")
            drawText(startX, endY, "+")
            drawText(endX, endY, "+")
        }

        canvas.withStyle(style) {
            var cursorY = contentY
            headerLines.forEach { line ->
                if (cursorY >= contentY + contentHeight) return@forEach
                drawText(contentX, cursorY, line.take(textWidth))
                cursorY++
            }

            val footerHeight = footerLines.size
            val listTopY = cursorY
            val listBottomY = (contentY + contentHeight - footerHeight).coerceAtLeast(listTopY)
            val listHeight = (listBottomY - listTopY).coerceAtLeast(0)
            helpViewportHeight = listHeight
            helpMaxScroll = (shortcutLines.size - listHeight).coerceAtLeast(0)
            helpScrollOffset = helpScrollOffset.coerceIn(0, helpMaxScroll)
            helpStartY = listTopY

            val visibleShortcuts = shortcutLines.drop(helpScrollOffset).take(listHeight)
            visibleShortcuts.forEachIndexed { idx, line ->
                drawText(contentX, listTopY + idx, line.take(textWidth))
            }

            var footerY = listBottomY
            footerLines.forEach { line ->
                if (footerY >= contentY + contentHeight) return@forEach
                drawText(contentX, footerY, line.take(textWidth))
                footerY++
            }
        }
    }

    override fun nextApp(): Component? {
        if (transitioned || !splashDismissed) return null
        transitioned = true
        return app
    }

    override fun shutdownApp() {
        app.shutdownApp()
    }

    override fun consumeInvalidation(): Boolean {
        return renderDirty.getAndSet(false)
    }
}

private class SplitPanelsApp(
    styleSheet: StyleSheet,
    private val buildVersion: String,
    private var projectRoot: Path,
    private val renderer: AnsiCanvasRenderer,
    private val onQuit: () -> Unit,
    private val runInitialScan: Boolean = true
) : BaseComponent(styleSheet), StatusLineProvider, AppShutdown {
    // gotcha
    private var sessionManager = ProjectSessionManager(projectRoot)
    private var recentFiles: MutableList<RecentFileEntry> = mutableListOf()
    private var savedEditors: MutableMap<String, EditorSessionState> = mutableMapOf()
    private var sourceRoots: MutableList<String> = mutableListOf()
    private var scanExclusions: MutableList<String> = mutableListOf()
    private var lastPersistMs: Long = 0L
    private var pendingPersist: Boolean = false
    private val persistDebounceMs: Long = 3000L
    private var dragging = false
    private var leftWidth = -1
    private var leftRatio = 0.3
    private var lastCols = 0
    private var lastRows = 0
    private var rightWidthState = 0
    private var rightHeightState = 0
    private val minPanelWidth = 8
    private var focus: FocusTarget = FocusTarget.CODE
    private var rightFocus: FocusTarget = FocusTarget.CODE
    private val mimeDetector = DefaultMimeTypeDetector()
    private val regexProvider = KeywordSyntaxProvider
    private val lspManager = LspManager()
    private val lspService = LspService(lspManager, projectRoot)
    private val dbManager = DbServerManager()
    private val codeIntelStore = DbCodeIntelStore { dbManager.jdbcUrl() }
    private val codeIntelIndexer = CodeIntelService(store = codeIntelStore)
    // private val codeIntelFacade = CompositeEditorIntelligenceService(
    //     primary = LspEditorIntelligence(lspService),
    //     fallback = codeIntelIndexer
    // ) // keep it
    private val codeIntelFacade = codeIntelIndexer
    private var gitService: editor.lib.IGitService? = createGitService(projectRoot)
    private var codeEditor = CodeEditorView(
        styleSheet,
        syntaxProvider = regexProvider,
        codeIntelIndexer = codeIntelIndexer,
        codeIntel = codeIntelFacade,
        // lsp = lspService, // keep it
        lsp = null,
        navigationHandler = this::navigateTo,
        projectRootProvider = { projectRoot }
    )
    private val diffViewer = SideBySideDiffView(styleSheet)
    private val hexViewer = BinaryHexView(styleSheet)
    private val imageViewer = ImageViewerView(styleSheet)
    private val gitPanel = GitPanelView(
        styleSheet,
        projectRoot,
        createGitService(projectRoot),
        onShowDiff = { showDiffInMain(it) }
    )
    private val aboutView = AboutView(styleSheet, buildVersion)
    private val projectSearchDialog = ProjectSearchDialog(
        styleSheet,
        onDismiss = { projectSearchVisible = false },
        syntaxProvider = regexProvider,
        onDirtyFile = { path, state -> recordRecentFromSearch(path, state) },
        projectRoot = projectRoot
    )
    private val filesTabView = FilesTabView(
        styleSheet,
        FileTree.newFileTree(projectRoot.toString()),
        currentRootProvider = { projectRoot.toString() },
        onChangeWorkspace = { showWorkspacePicker() },
        recentFilesProvider = {
            recentFiles
                .sortedBy { it.path.lowercase() }
                .map { it.copy(path = sessionManager.toRelative(it.path)) }
        },
        currentPathProvider = { currentRelativePath() },
        onSelectFile = { entry, mime ->
            saveCurrentEditorState()
            val detected = mime?.let { MimeTypeResult(it, language = null) }
                ?: mimeDetector.detectFile(java.nio.file.Path.of(entry.fullPath))
            openInViewer(entry.fullPath, detected)
        },
        onSelectRecent = { entry -> openRecent(entry) },
        onRemoveRecent = { entry -> removeRecent(entry) },
        syntaxProvider = regexProvider,
        folderStatusProvider = ProjectFolderStatusClassifier(
            rootProvider = { projectRoot },
            sourceRootsProvider = { sourceRoots.toList() },
            exclusionsProvider = { scanExclusions.toList() },
            ignorePatternsProvider = { gitService?.ignoredPatterns().orEmpty() }
        )::status
    )
    private val settingsView = SettingsView(
        styleSheet,
        lspService,
        lspManager,
        dbManager,
        codeIntelIndexer
    ) { projectRoot }
    private val projectSettingsView = ProjectSettingsView(
        styleSheet,
        projectRootProvider = { projectRoot },
        sourceRootsProvider = { sourceRoots.toList() },
        onRequestAdd = { showSourcePicker() },
        onRemoveSource = { root -> removeSourceRoot(root) },
        exclusionsProvider = { scanExclusions.toList() },
        onRequestAddExclusion = { showScanExclusionPicker() },
        onRemoveExclusion = { exclusion -> removeScanExclusion(exclusion) },
    )
    private val helpView = HelpView(styleSheet)
    private var currentOpenPath: String = ""
    private var projectSearchVisible = false
    private var workspacePickerVisible = false
    private var workspacePicker: WorkspacePickerDialog? = null
    private var sourcePickerVisible = false
    private var sourcePicker: SourceFolderPickerDialog? = null
    private var activeDiff: GitDiff? = null
    init {
        dbManager.start(projectRoot)
        val loaded = sessionManager.load()
        recentFiles = loaded.recentFiles.map { entry ->
            entry.copy(
                path = sessionManager.toAbsolute(entry.path),
                editor = entry.editor?.copy(path = sessionManager.toAbsolute(entry.editor.path))
            )
        }.toMutableList()
        savedEditors = loaded.openEditors.associateBy { sessionManager.toAbsolute(it.path) }
            .mapValues { it.value.copy(path = sessionManager.toAbsolute(it.value.path)) }
            .toMutableMap()
        sourceRoots = loadSourceRoots(loaded.sourceRoots)
        scanExclusions = loadScanExclusions(loaded.scanExclusions)
        filesTabView.refreshFileTree()
        restoreLastSession()
        if (runInitialScan) {
            if (hasPersistentIndex()) {
                codeIntelIndexer.loadFromStore()
            } else {
                triggerFreshScan()
            }
        }
    }
    private val leftTabs = TabView(
        styleSheet = styleSheet,
        titles = listOf("Files", "Project", "Git", "About", "Settings", "Help"),
        tabComponents = listOf(
            filesTabView,
            projectSettingsView,
            gitPanel,
            aboutView,
            settingsView,
            helpView
        ),
        initialIndex = 0,
        onSelect = { idx -> handleLeftTabChanged(idx) }
    )

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        lastRows = rows

        if (leftWidth < 0 || lastCols != cols) {
            leftWidth = clampWidth((cols * leftRatio).toInt(), cols)
        }

        val splitterX = clampWidth(leftWidth, cols)
        val rightWidth = (cols - splitterX - 1).coerceAtLeast(0)

        val leftStyle = styleSheet.getStyle("sidebar")
        val rightStyle = styleSheet.getStyle("main-area")
        val splitterStyle = styleSheet.getStyle("splitter")

        rightWidthState = rightWidth
        rightHeightState = rows

        canvas.withStyle(leftStyle) {
            if (splitterX > 0) {
                drawRect(0, 0, splitterX, rows)
                renderLeftTabs(this, splitterX, rows)
            }
        }

        canvas.withStyle(splitterStyle) {
            drawRect(splitterX, 0, 1, rows)
            if (rows > 0) {
                for (y in 0 until rows) {
                    drawText(splitterX, y, "|")
                }
            }
        }

        canvas.withStyle(rightStyle) {
            if (rightWidth > 0) {
                drawRect(splitterX + 1, 0, rightWidth, rows)
                val viewerToRender = if (focus == FocusTarget.FILES) rightFocus else focus
                renderRightPane(this, splitterX + 1, rows, rightWidth, cols, viewerToRender)
            }
        }

        if (projectSearchVisible) {
            projectSearchDialog.render(canvas)
        }
        if (workspacePickerVisible) {
            workspacePicker?.render(canvas)
        }
        if (sourcePickerVisible) {
            sourcePicker?.render(canvas)
        }

        lastCols = cols
        leftRatio = splitterX.toDouble() / cols.toDouble().coerceAtLeast(1.0)
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "key_down" && event.ctrl && event.key?.equals("q", ignoreCase = true) == true) {
            onQuit()
            return true
        }
        if (event.kind == "animation_frame") {
            var handled = false
            if (workspacePickerVisible) {
                handled = (workspacePicker?.dispatch(event) ?: false) || handled
            }
            if (sourcePickerVisible) {
                handled = (sourcePicker?.dispatch(event) ?: false) || handled
            }
            if (projectSearchVisible) {
                handled = projectSearchDialog.dispatch(event) || handled
            }
            handled = leftTabs.dispatch(event) || handled
            handled = codeEditor.dispatch(event) || handled
            handled = hexViewer.dispatch(event) || handled
            handled = imageViewer.dispatch(event) || handled
            handled = diffViewer.dispatch(event) || handled
            return handled
        }

        if (workspacePickerVisible) {
            val handled = workspacePicker?.dispatch(event) ?: false
            return handled
        }
        if (sourcePickerVisible) {
            val handled = sourcePicker?.dispatch(event) ?: false
            return handled
        }
        if (event.kind == "key_down") {
            val key = event.key
            if (key != null && !event.ctrl && event.alt && key.equals("f", ignoreCase = true)) {
                val selection = codeEditor.currentSelectionText()
                val prefillQuery = selection?.takeIf { it.isNotEmpty() }?.let { Regex.escape(it) }
                val ext = codeEditor.currentPath().substringAfterLast('.', missingDelimiterValue = "")
                val prefillFilter = ext.takeIf { it.isNotEmpty() }?.let { "\\.${it}" }
                projectSearchDialog.setInitialInputs(prefillQuery, prefillFilter)
                projectSearchVisible = true
                return true
            }
            if (key != null && event.ctrl && key.equals("t", ignoreCase = true)) {
                openShell()
                return true
            }
        }

        if (event.kind.startsWith("mouse") && lastRows > 0) {
            val y = event.y
            if (y != null && y >= lastRows) {
                return false
            }
        }

        if (projectSearchVisible) {
            val handled = projectSearchDialog.dispatch(event)
            return handled
        }

        when (event.kind) {
            "key_down" -> if (event.ctrl && event.key?.lowercase() == "q") {
                onQuit()
            }
            "mouse_down" -> {
                val x = event.x ?: return false
                if (isOnSplitter(x)) {
                    dragging = true
                    return false
                }
            }
            "mouse_up" -> {
                dragging = false
            }
            "mouse_move" -> {
                if (dragging && event.x != null) {
                    leftWidth = clampWidth(event.x, lastCols.coerceAtLeast(1))
                    leftRatio = leftWidth.toDouble() / lastCols.toDouble().coerceAtLeast(1.0)
                    return true
                }
            }
            "resize" -> {
                event.cols?.let { cols ->
                    lastCols = cols
                    leftWidth = clampWidth((cols * leftRatio).toInt(), cols)
                }
                return true
            }
        }

        val x = event.x
        val y = event.y
        val splitter = clampWidth(leftWidth, lastCols.coerceAtLeast(1))
        if (!dragging && x != null && y != null && x < splitter) {
            focus = FocusTarget.FILES
            val forwarded = event.alterCopy(
                UIEvent(
                    kind = event.kind,
                    x = x,
                    y = y,
                    relX = event.relX,
                    relY = event.relY,
                    button = event.button,
                    scrollDelta = event.scrollDelta,
                    key = event.key,
                    ctrl = event.ctrl,
                    alt = event.alt,
                    shift = event.shift,
                    meta = event.meta,
                    focusId = event.focusId,
                    cols = event.cols,
                    rows = event.rows,
                    raw = event.raw,
                    timeMs = event.timeMs
                )
            )
            return leftTabs.dispatch(forwarded)
        } else if (!dragging && x != null && y != null) {
            val startX = splitter + 1
            if (x >= startX) {
                val forwarded = event.alterCopy(
                    UIEvent(
                        kind = event.kind,
                        x = x - startX,
                        y = y,
                        relX = event.relX,
                        relY = event.relY,
                        button = event.button,
                        scrollDelta = event.scrollDelta,
                        key = event.key,
                        ctrl = event.ctrl,
                    alt = event.alt,
                    shift = event.shift,
                    meta = event.meta,
                    focusId = event.focusId,
                    cols = rightWidthState.coerceAtLeast(0),
                    rows = rightHeightState.coerceAtLeast(0),
                    raw = event.raw,
                    timeMs = event.timeMs
                )
            )
            val handled = dispatchToRight(forwarded)
            persistSession()
            return handled
            }
        }

        if (event.kind == "key_down") {
            val handled = when (focus) {
                FocusTarget.FILES -> leftTabs.dispatch(event)
                FocusTarget.CODE -> codeEditor.dispatch(event)
                FocusTarget.HEX -> hexViewer.dispatch(event)
                FocusTarget.IMAGE -> imageViewer.dispatch(event)
                FocusTarget.DIFF -> diffViewer.dispatch(event)
            }
            schedulePersist()
            return handled
        }

        schedulePersist()
        return false
    }

    private fun isOnSplitter(x: Int): Boolean = x == clampWidth(leftWidth, lastCols.coerceAtLeast(1))

    private fun openInViewer(path: String, detected: MimeTypeResult) {
        saveCurrentEditorState()
        clearDiffViewer()
        when (detected.mimeTypeCategory) {
            MimeTypeCategory.IMAGE -> {
                imageViewer.openFile(path, detected)
                rightFocus = FocusTarget.IMAGE
                focus = rightFocus
                currentOpenPath = path
                recordRecent(path, null, ViewerType.IMAGE)
            }
            MimeTypeCategory.TEXT -> {
                val (grammarLang, grammarAvailable) = resolveGrammar(path, detected)
                codeEditor.openFile(path, detected, grammarAvailable, grammarLang)
                rightFocus = FocusTarget.CODE
                focus = rightFocus
                currentOpenPath = path
                recordRecent(path, codeEditor.captureState(fileLastModified(path)), ViewerType.CODE)
            }
            MimeTypeCategory.BINARY, MimeTypeCategory.UNKNOWN -> {
                // Unknown defaults to hex viewer.
                hexViewer.openFile(path, detected)
                rightFocus = FocusTarget.HEX
                focus = rightFocus
                currentOpenPath = path
                recordRecent(path, null, ViewerType.HEX)
            }
        }
    }

    private fun openShell() {
        renderer.disableMouseTracking()
        renderer.showCursor()
        renderer.resetAttributes()
        renderer.leaveAlternateScreen()
        restoreStty(null)

        val shell = System.getenv("SHELL")?.takeIf { it.isNotBlank() } ?: "/bin/bash"
        println("\n[ kode ] Dropping into shell: $shell\nType 'exit' to return to Kode.\n")
        runCatching {
            ProcessBuilder(shell)
                .directory(projectRoot.toFile())
                .inheritIO()
                .start()
                .waitFor()
        }

        enterRawMode()
        renderer.enterAlternateScreen()
        renderer.enableMouseTracking()
        renderer.hideCursor()
        renderer.invalidateDiffBuffer()
    }

    private fun showDiffInMain(diff: GitDiff) {
        activeDiff = diff
        diffViewer.setOnRestoreChunk { chunkId -> restoreDiffChunk(chunkId) }
        diffViewer.showDiff(diff.path, diff.oldContent, diff.newContent)
        rightFocus = FocusTarget.DIFF
        focus = rightFocus
    }

    private fun clearDiffViewer() {
        activeDiff = null
        if (rightFocus == FocusTarget.DIFF) {
            rightFocus = FocusTarget.CODE
            focus = rightFocus
        }
    }

    private fun handleLeftTabChanged(selectedIndex: Int) {
        // Index 2 corresponds to Git tab in leftTabs.
        if (selectedIndex != 2 && activeDiff != null) {
            clearDiffViewer()
        }
    }

    private fun restoreDiffChunk(chunkId: Int) {
        val diff = activeDiff ?: return
        // Only operate on unstaged (working tree) for now.
        if (diff.staged) return
        val svc = gitPanel.currentGitService() ?: return
        val (oldText, newText) = svc.diffContents(diff.path, staged = false)
        val edits = org.eclipse.jgit.diff.HistogramDiff().diff(
            org.eclipse.jgit.diff.RawTextComparator.DEFAULT,
            org.eclipse.jgit.diff.RawText(oldText.toByteArray()),
            org.eclipse.jgit.diff.RawText(newText.toByteArray())
        )
        val targetEdit = edits.filter { it.type != org.eclipse.jgit.diff.Edit.Type.EMPTY }.getOrNull(chunkId) ?: return
        val oldLines = oldText.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)
        val newLines = newText.split("\n", ignoreCase = false, limit = Int.MAX_VALUE).toMutableList()
        val replacement = when (targetEdit.type) {
            org.eclipse.jgit.diff.Edit.Type.INSERT -> emptyList()
            org.eclipse.jgit.diff.Edit.Type.DELETE, org.eclipse.jgit.diff.Edit.Type.REPLACE ->
                oldLines.subList(targetEdit.beginA, targetEdit.endA)
            org.eclipse.jgit.diff.Edit.Type.EMPTY -> emptyList()
        }
        val start = targetEdit.beginB
        val end = targetEdit.endB
        val prefix = newLines.subList(0, start)
        val suffix = newLines.subList(end, newLines.size)
        val updated = (prefix + replacement + suffix).joinToString("\n")
        val targetFile = projectRoot.resolve(diff.path).toFile()
        runCatching { targetFile.writeText(updated) }.getOrElse { return }
        // refresh views
        gitPanel.refreshData()
        if (currentOpenPath == targetFile.absolutePath) {
            val detected = mimeDetector.detectFile(targetFile.toPath())
            openInViewer(targetFile.absolutePath, detected)
        }
        val refreshed = svc.diffContents(diff.path, staged = false)
        showDiffInMain(GitDiff(diff.path, diff.staged, refreshed.first, refreshed.second))
    }

    private fun showWorkspacePicker() {
        workspacePicker = WorkspacePickerDialog(
            styleSheet,
            projectRoot,
            onConfirm = { newRoot -> changeWorkspace(newRoot) },
            onDismiss = {
                workspacePickerVisible = false
                workspacePicker = null
            }
        )
        workspacePickerVisible = true
    }

    private fun showSourcePicker() {
        sourcePicker = SourceFolderPickerDialog(
            styleSheet,
            projectRoot,
            onConfirm = { selected ->
                val msg = addSourceRoot(selected)
                projectSettingsView.setMessage(msg)
            },
            onDismiss = {
                sourcePickerVisible = false
                sourcePicker = null
            }
        )
        sourcePickerVisible = true
    }

    private fun changeWorkspace(newRoot: Path) {
        val normalized = newRoot.toAbsolutePath().normalize()
        if (normalized == projectRoot) {
            workspacePickerVisible = false
            workspacePicker = null
            return
        }
        persistSession(force = true)
        projectRoot = normalized
        sessionManager = ProjectSessionManager(projectRoot)
        recentFiles.clear()
        savedEditors.clear()
        currentOpenPath = ""
        projectSearchVisible = false
        codeIntelIndexer.clear()
        dbManager.stop()
        clearProjectDb(projectRoot)
        dbManager.start(projectRoot)

        val loaded = sessionManager.load()
        recentFiles = loaded.recentFiles.map { entry ->
            entry.copy(
                path = sessionManager.toAbsolute(entry.path),
                editor = entry.editor?.copy(path = sessionManager.toAbsolute(entry.editor.path))
            )
        }.toMutableList()
        savedEditors = loaded.openEditors.associateBy { sessionManager.toAbsolute(it.path) }
            .mapValues { it.value.copy(path = sessionManager.toAbsolute(it.value.path)) }
            .toMutableMap()
        sourceRoots = loadSourceRoots(loaded.sourceRoots)
        scanExclusions = loadScanExclusions(loaded.scanExclusions)
        codeIntelIndexer.loadFromStore()

        codeEditor = CodeEditorView(
            styleSheet,
            syntaxProvider = regexProvider,
            codeIntelIndexer = codeIntelIndexer,
            codeIntel = codeIntelFacade,
            navigationHandler = this::navigateTo,
            projectRootProvider = { projectRoot }
        )
        focus = FocusTarget.FILES
        rightFocus = FocusTarget.CODE
        filesTabView.setRoot(projectRoot.toString())
        gitService = createGitService(projectRoot)
        gitPanel.setGitService(gitService, projectRoot)
        projectSearchDialog.setProjectRoot(projectRoot)
        workspacePickerVisible = false
        workspacePicker = null
        restoreLastSession()
        triggerFreshScan()
        persistSession(force = true)
    }

    private fun loadSourceRoots(roots: List<String>): MutableList<String> {
        val normalized = roots.mapNotNull { normalizeSourceRoot(it) }.distinct().toMutableList()
        return if (normalized.isEmpty()) mutableListOf(".") else normalized
    }

    private fun loadScanExclusions(exclusions: List<String>): MutableList<String> =
        exclusions.mapNotNull { normalizeScanExclusion(it) }.distinct().toMutableList()

    private fun addSourceRoot(path: Path): String {
        val abs = path.toAbsolutePath().normalize()
        if (!Files.isDirectory(abs)) {
            return "Select a folder to add."
        }
        if (!abs.startsWith(projectRoot)) {
            return "Source roots must be inside the project root."
        }
        val rel = projectRoot.relativize(abs).toString()
        val normalized = normalizeSourceRoot(rel) ?: return "Invalid source folder."
        if (sourceRoots.any { normalizeSourceRoot(it) == normalized }) {
            return "Source root already added: ${formatSourceRoot(normalized)}"
        }
        sourceRoots.add(normalized)
        filesTabView.refreshFileTree()
        val reindexed = applySourceRootsChanged()
        return if (reindexed) {
            "Added source root: ${formatSourceRoot(normalized)}"
        } else {
            "Added source root, but failed to clear index."
        }
    }

    private fun removeSourceRoot(root: String): String? {
        val normalized = normalizeSourceRoot(root) ?: return "Invalid source root."
        val removed = sourceRoots.removeIf { normalizeSourceRoot(it) == normalized }
        if (!removed) return "Source root not found."
        filesTabView.refreshFileTree()
        val reindexed = applySourceRootsChanged()
        return if (reindexed) {
            "Removed source root: ${formatSourceRoot(normalized)}"
        } else {
            "Removed source root, but failed to clear index."
        }
    }

    private fun showScanExclusionPicker() {
        sourcePicker = SourceFolderPickerDialog(
            styleSheet,
            projectRoot,
            onConfirm = { selected ->
                val msg = addScanExclusion(selected)
                projectSettingsView.setMessage(msg)
            },
            onDismiss = {
                sourcePickerVisible = false
                sourcePicker = null
            },
            foldersOnly = false,
            title = "Select file or folder to exclude",
        )
        sourcePickerVisible = true
    }

    private fun addScanExclusion(path: Path): String {
        val abs = path.toAbsolutePath().normalize()
        if (!Files.exists(abs)) return "Selected path does not exist."
        if (!abs.startsWith(projectRoot) || abs == projectRoot) {
            return "Exclusions must be a file or folder inside the project root."
        }
        val normalized = normalizeScanExclusion(projectRoot.relativize(abs).toString())
            ?: return "Invalid scan exclusion."
        if (scanExclusions.any { normalizeScanExclusion(it) == normalized }) {
            return "Scan exclusion already added: $normalized"
        }
        scanExclusions.add(normalized)
        filesTabView.refreshFileTree()
        val reindexed = applyScanExclusionsChanged()
        return if (reindexed) "Added scan exclusion: $normalized"
        else "Added scan exclusion, but failed to clear index."
    }

    private fun removeScanExclusion(exclusion: String): String? {
        val normalized = normalizeScanExclusion(exclusion) ?: return "Invalid scan exclusion."
        val removed = scanExclusions.removeIf { normalizeScanExclusion(it) == normalized }
        if (!removed) return "Scan exclusion not found."
        filesTabView.refreshFileTree()
        val reindexed = applyScanExclusionsChanged()
        return if (reindexed) "Removed scan exclusion: $normalized"
        else "Removed scan exclusion, but failed to clear index."
    }

    private fun normalizeScanExclusion(value: String?): String? {
        val trimmed = value?.trim()?.replace('\\', '/') ?: return null
        if (trimmed.isEmpty() || trimmed == ".") return null
        val normalized = trimmed.removePrefix("./").trim('/').replace(Regex("/+"), "/")
        if (normalized.isEmpty() || normalized == "." || normalized.split('/').any { it == ".." }) return null
        return normalized
    }

    private fun applyScanExclusionsChanged(): Boolean {
        val cleared = dbManager.clearIndex()
        codeIntelIndexer.clear()
        if (!cleared) {
            persistSession(force = true)
            return false
        }
        triggerFreshScan()
        persistSession(force = true)
        return true
    }

    private fun applySourceRootsChanged(): Boolean {
        val cleared = dbManager.clearIndex()
        codeIntelIndexer.clear()
        if (!cleared) {
            persistSession(force = true)
            return false
        }
        triggerFreshScan()
        persistSession(force = true)
        return true
    }

    private fun resolveSourceRoots(): List<Path> {
        if (sourceRoots.isEmpty()) return emptyList()
        return sourceRoots.mapNotNull { root ->
            val normalized = normalizeSourceRoot(root) ?: return@mapNotNull null
            if (normalized == ".") projectRoot else projectRoot.resolve(normalized).normalize()
        }.distinct()
    }

    private fun normalizeSourceRoot(value: String?): String? {
        val trimmed = value?.trim() ?: return null
        if (trimmed.isEmpty()) return "."
        val normalized = trimmed.removePrefix("./").trimEnd(File.separatorChar)
        return if (normalized.isEmpty()) "." else normalized
    }

    private fun formatSourceRoot(root: String): String =
        when {
            root.isBlank() || root == "." -> "(project root)"
            root.startsWith("./") -> root.removePrefix("./")
            else -> root
        }

    private fun clearProjectDb(root: Path) {
        val dbDir = root.resolve(".kode/db")
        if (!Files.exists(dbDir)) return
        runCatching {
            Files.walk(dbDir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
            Files.createDirectories(dbDir)
        }
    }

    private fun createGitService(root: Path): editor.lib.IGitService? {
        return runCatching { JGitService(root.toFile()) }.getOrNull()
    }

    override fun statusRight(): String {
        val dbStatus = dbManager.status()
        val dbLabel = when (dbStatus.state) {
            DbStatus.State.RUNNING -> "DB:up"
            DbStatus.State.STARTING -> "DB:starting"
            DbStatus.State.ERROR -> "DB:err"
            DbStatus.State.STOPPED -> "DB:down"
        }
        val stats = codeIntelIndexer.stats()
        val idxLabel = "Idx:${stats.files}f/${stats.symbols}s"
        return "$dbLabel  $idxLabel"
    }

    override fun shutdownApp() {
        dbManager.stop()
    }

    fun fullReindex(
        progress: ((String) -> Unit)? = null,
        progressFile: ((String) -> Unit)? = null,
    ) {
        progress?.invoke("Indexing: preparing")
        val startMs = System.currentTimeMillis()
        val files = listFilesForIndex()
        progress?.invoke("Indexing: 0/${files.size}")
        progressFile?.invoke("Preparing file list...")
        var indexed = 0
        codeIntelIndexer.beginSemanticBatch()
        try {
            files.forEachIndexed { idx, path ->
                val detected = mimeDetector.detectFile(path)
                val lang = detected?.language
                val rel = projectRoot.relativize(path).toString()
                progressFile?.invoke(rel)
                if (!lang.isNullOrBlank()) {
                    val text = runCatching { Files.readString(path) }.getOrNull()
                    if (text != null) {
                        codeIntelIndexer.indexDocumentNow(path.toString(), lang, text, version = 0L)
                        indexed++
                    }
                }
                progress?.invoke("Indexing: ${idx + 1}/${files.size}")
            }
        } finally {
            codeIntelIndexer.endSemanticBatch()
        }
        indexSdkSources()
        val elapsed = System.currentTimeMillis() - startMs
        progress?.invoke("Indexing: complete ($indexed/${files.size}) in ${elapsed}ms")
    }

    private fun triggerFreshScan() {
        Thread({
            runCatching { fullReindex() }
                .onFailure { System.err.println("Background reindex failed: ${it.message}") }
        }, "codeintel-freshscan").apply { isDaemon = true }.start()
    }

    private fun listFilesForIndex(): List<Path> {
        val ignorePatterns = gitService?.ignoredPatterns().orEmpty()
        val roots = resolveSourceRoots()
        return ProjectFileScanner.listFilesForIndex(projectRoot, roots, ignorePatterns, scanExclusions)
    }

    fun hasPersistentIndex(): Boolean {
        if (dbManager.wasFreshStart()) return false
        return codeIntelIndexer.hasPersistentData()
    }

    fun loadCodeIntelFromStore() {
        codeIntelIndexer.loadFromStore()
    }

    private fun indexSdkSources() {
        // SDK indexing disabled for now (JDK/stdlib)
    }

    private fun indexZip(path: Path, language: String) {
        runCatching {
            java.util.zip.ZipFile(path.toFile()).use { zip ->
                zip.entries().asSequence()
                    .filter { !it.isDirectory && (it.name.endsWith(".kt") || it.name.endsWith(".java")) }
                    .forEach { entry ->
                        val sizeOk = entry.size <= 512_000
                        if (!sizeOk) {
                            println("skip   ${entry.name} (too large)")
                            return@forEach
                        }
                        val text = zip.getInputStream(entry).bufferedReader().readText()
                        codeIntelIndexer.indexDocument("${path.fileName}:${entry.name}", language, text, version = 0L)
                        println("loaded ${entry.name} (sdk)")
                    }
            }
        }.onFailure { ex ->
            println("Failed to index $path: ${ex.message}")
        }
    }

    private fun findJdkSources(): Path? {
        val javaHome = System.getenv("JAVA_HOME")?.let { Paths.get(it) }
            ?: runCatching { Paths.get(System.getProperty("java.home")).parent }.getOrNull()
        val srcZip = javaHome?.resolve("lib/src.zip")
        return srcZip?.takeIf { Files.exists(it) }
    }

    private fun findKotlinStdlibSources(): Path? {
        val kotlinHome = System.getenv("KOTLIN_HOME")?.let { Paths.get(it) }
            ?: System.getProperty("kotlin.home")?.let { Paths.get(it) }
        val candidates = listOfNotNull(
            kotlinHome?.resolve("lib/kotlin-stdlib-sources.jar"),
            projectRoot.resolve("kotlinc/lib/kotlin-stdlib-sources.jar")
        )
        return candidates.firstOrNull { Files.exists(it) }
    }

    private fun navigateTo(path: String, position: editor.lib.Position) {
        val absPath = if (java.io.File(path).isAbsolute) path else sessionManager.toAbsolute(path)
        val detected = mimeDetector.detectFile(java.nio.file.Path.of(absPath))
        openInViewer(absPath, detected)
        codeEditor.setSelection(position, position, center = true)
    }

    private fun openRecent(entry: RecentFileEntry) {
        val absPath = sessionManager.toAbsolute(entry.path)
        val state = entry.editor ?: savedEditors[absPath]
        val currentMtime = fileLastModified(absPath)
        if (state != null && !isStale(state, currentMtime)) {
            openEditorState(state.copy(path = absPath, lastModifiedMillis = currentMtime))
            recordRecent(absPath, codeEditor.captureState(currentMtime))
            return
        }
        when (entry.viewerType) {
            ViewerType.IMAGE -> {
                val detected = mimeDetector.detectFile(java.nio.file.Path.of(absPath))
                imageViewer.openFile(absPath, detected)
                rightFocus = FocusTarget.IMAGE
                focus = rightFocus
                currentOpenPath = absPath
                recordRecent(absPath, null, ViewerType.IMAGE)
            }
            ViewerType.HEX -> {
                val detected = mimeDetector.detectFile(java.nio.file.Path.of(absPath))
                hexViewer.openFile(absPath, detected)
                rightFocus = FocusTarget.HEX
                focus = rightFocus
                currentOpenPath = absPath
                recordRecent(absPath, null, ViewerType.HEX)
            }
            ViewerType.CODE -> {
                val detected = mimeDetector.detectFile(java.nio.file.Path.of(absPath))
                if (state != null) {
                    val cursor = state.buffer.cursor
                    val scroll = state.scrollTop
                    openInViewer(absPath, detected)
                    codeEditor.restoreViewport(editor.lib.PositionState(cursor.line, cursor.column), scroll)
                    recordRecent(absPath, codeEditor.captureState(currentMtime))
                } else {
                    openInViewer(absPath, detected)
                }
            }
        }
    }

    private fun openEditorState(state: EditorSessionState) {
        clearDiffViewer()
        codeEditor.restoreState(state)
        rightFocus = FocusTarget.CODE
        focus = rightFocus
        currentOpenPath = state.path
    }

    private fun dispatchToRight(event: UIEvent): Boolean {
        val targetFocus = if (focus == FocusTarget.FILES) rightFocus else focus
        if (event.kind == "mouse_down" || event.kind == "mouse_up" || event.kind == "mouse_move") {
            focus = targetFocus
        }
        val handled = when (targetFocus) {
            FocusTarget.CODE -> codeEditor.dispatch(event)
            FocusTarget.HEX -> hexViewer.dispatch(event)
            FocusTarget.IMAGE -> imageViewer.dispatch(event)
            FocusTarget.DIFF -> diffViewer.dispatch(event)
            FocusTarget.FILES -> codeEditor.dispatch(event)
        }
        if (handled && targetFocus != FocusTarget.FILES) {
            rightFocus = targetFocus
        }
        schedulePersist()
        return handled
    }

    private fun clampWidth(value: Int, cols: Int): Int {
        val available = (cols - 1).coerceAtLeast(1) // leave a column for the splitter
        val minLeft = minPanelWidth.coerceAtMost(available)
        val maxLeft = (cols - minPanelWidth - 1).coerceAtLeast(minLeft)
        return value.coerceIn(minLeft, maxLeft)
    }

    private fun isGrammarAvailable(path: String, detected: MimeTypeResult): Boolean {
        return resolveGrammar(path, detected).second
    }

    fun persistSession(force: Boolean = false) {
        if (!force) {
            val now = System.currentTimeMillis()
            if (now - lastPersistMs < persistDebounceMs) {
                pendingPersist = true
                return
            }
        }
        saveCurrentEditorState()
        val mergedEditors = savedEditors.values.toMutableList()
        val editorsForSave = mergedEditors.map { it.copy(path = sessionManager.toRelative(it.path)) }
        val recentsForSave = recentFiles.map { entry ->
            entry.copy(
                path = sessionManager.toRelative(entry.path),
                editor = entry.editor?.copy(path = sessionManager.toRelative(entry.editor.path))
            )
        }
        sessionManager.save(
            ProjectSession(
                recentFiles = recentsForSave,
                openEditors = editorsForSave,
                sourceRoots = sourceRoots.toList(),
                scanExclusions = scanExclusions.toList()
            )
        )
        lastPersistMs = System.currentTimeMillis()
        pendingPersist = false
    }

    private fun recordRecent(path: String, state: EditorSessionState?, viewerType: ViewerType = ViewerType.CODE) {
        val abs = sessionManager.toAbsolute(path)
        val now = Instant.now().toEpochMilli()
        val mtime = fileLastModified(abs)
        val existingIdx = recentFiles.indexOfFirst { sessionManager.toAbsolute(it.path) == abs }
        val dirtyFlag = state?.buffer?.dirty ?: savedEditors[abs]?.buffer?.dirty ?: false
        val entry = RecentFileEntry(
            path = abs,
            lastOpenedEpochMillis = now,
            lastModifiedMillis = mtime,
            editor = state ?: savedEditors[abs],
            dirty = dirtyFlag,
            viewerType = viewerType
        )
        if (existingIdx >= 0) recentFiles[existingIdx] = entry else recentFiles.add(entry)
        recentFiles = recentFiles.sortedBy { it.path.lowercase() }.take(20).toMutableList()
        if (state != null) {
            savedEditors[abs] = state.copy(lastModifiedMillis = mtime)
        }
    }

    private fun recordRecentFromSearch(path: String, state: EditorSessionState?) {
        val abs = sessionManager.toAbsolute(path)
        recordRecent(abs, state, ViewerType.CODE)
    }

    private fun removeRecent(entry: RecentFileEntry) {
        val abs = sessionManager.toAbsolute(entry.path)
        recentFiles.removeIf { sessionManager.toAbsolute(it.path) == abs }
        savedEditors.remove(abs)
        persistSession(force = true)
    }

    private fun restoreLastSession() {
        val ordered = recentFiles.sortedByDescending { it.lastOpenedEpochMillis }
        val stateFromRecents = ordered.firstNotNullOfOrNull { entry ->
            val abs = sessionManager.toAbsolute(entry.path)
            val state = entry.editor ?: savedEditors[abs]
            val currentMtime = fileLastModified(abs)
            if (state != null && !isStale(state, currentMtime)) {
                state.copy(path = abs, lastModifiedMillis = currentMtime)
            } else null
        }
        val fallback = savedEditors.entries.firstOrNull()?.let { (path, state) ->
            val currentMtime = fileLastModified(path)
            if (!isStale(state, currentMtime)) state.copy(path = path, lastModifiedMillis = currentMtime) else null
        }
        val state = stateFromRecents ?: fallback
        if (state != null) {
            openEditorState(state)
        }
    }

    private fun saveCurrentEditorState() {
        val currentPath = codeEditor.currentPath()
        if (currentPath.isEmpty()) return
        val mtime = fileLastModified(currentPath)
        codeEditor.captureState(mtime)?.let { state ->
            val abs = sessionManager.toAbsolute(state.path)
            savedEditors[abs] = state.copy(lastModifiedMillis = mtime)
            recordRecent(abs, state.copy(lastModifiedMillis = mtime), ViewerType.CODE)
        }
    }

    private fun fileLastModified(path: String): Long? =
        runCatching { java.nio.file.Files.getLastModifiedTime(java.nio.file.Path.of(path)).toMillis() }.getOrNull()

    private fun isStale(state: EditorSessionState, currentMtime: Long?): Boolean {
        val stored = state.lastModifiedMillis ?: return false
        val now = currentMtime ?: return false
        return now > stored + 1000
    }

    private fun currentRelativePath(): String? =
        currentOpenPath.takeIf { it.isNotEmpty() }?.let { sessionManager.toRelative(it) }

    private fun schedulePersist() {
        val now = System.currentTimeMillis()
        if (pendingPersist && now - lastPersistMs >= persistDebounceMs) {
            persistSession(force = true)
            return
        }
        persistSession(force = false)
    }

    private fun resolveGrammar(path: String, detected: MimeTypeResult): Pair<String?, Boolean> {
        detected.language?.let { lang ->
            if (regexProvider.languages().contains(lang)) return lang to true
        }
        val ext = path.substringAfterLast('.', missingDelimiterValue = "")
        if (ext.isNotEmpty()) {
            regexProvider.languageForExtension(ext)?.let { lang ->
                return lang to true
            }
        }
        return null to false
    }

    private fun renderLeftTabs(canvas: CanvasRenderer, width: Int, height: Int) {
        if (height <= 0 || width <= 0) return
        val clipped = ClippedCanvasRenderer(
            base = canvas,
            offsetX = 0,
            offsetY = 0,
            width = width,
            height = height
        )
        leftTabs.render(clipped)
    }

    private fun renderRightPane(canvas: CanvasRenderer, startX: Int, height: Int, width: Int, totalCols: Int, viewer: FocusTarget) {
        if (height <= 0 || width <= 0) return
        val clipped = ClippedCanvasRenderer(
            base = canvas,
            offsetX = startX,
            offsetY = 0,
            width = width,
            height = height
        )
        when (viewer) {
            FocusTarget.CODE -> codeEditor.render(clipped)
            FocusTarget.HEX -> hexViewer.render(clipped)
            FocusTarget.IMAGE -> imageViewer.render(clipped)
            FocusTarget.DIFF -> diffViewer.render(clipped)
            FocusTarget.FILES -> codeEditor.render(clipped)
        }
    }

}

private enum class FocusTarget { FILES, CODE, HEX, IMAGE, DIFF }

private data class PerfSnapshot(
    val loopFps: Int,
    val renderFps: Int,
    val usedMb: Long,
    val cpuPercent: Double
)

private class PerformanceTracker {
    private val osBean: OperatingSystemMXBean? =
        ManagementFactory.getOperatingSystemMXBean() as? OperatingSystemMXBean
    private var lastCpuTimeNs: Long = osBean?.processCpuTime ?: 0L
    private var lastCpuSampleMs: Long = System.currentTimeMillis()
    private var cpuPercent: Double = 0.0
    private var loopCount: Int = 0
    private var loopFps: Int = 0
    private var lastLoopSampleMs: Long = System.currentTimeMillis()
    private var renderCount: Int = 0
    private var renderFps: Int = 0
    private var lastRenderSampleMs: Long = System.currentTimeMillis()

    fun loopTick(nowMs: Long): Boolean {
        var dirty = false
        loopCount++
        if (nowMs - lastLoopSampleMs >= 1000) {
            loopFps = loopCount
            loopCount = 0
            lastLoopSampleMs = nowMs
            dirty = true
        }
        if (updateCpu(nowMs)) dirty = true
        return dirty
    }

    fun beforeFrame() {
        renderCount++
    }

    fun afterFrame() {
        val now = System.currentTimeMillis()
        if (now - lastRenderSampleMs >= 1000) {
            renderFps = renderCount
            renderCount = 0
            lastRenderSampleMs = now
        }
    }

    private fun updateCpu(nowMs: Long): Boolean {
        val bean = osBean ?: return false
        val elapsedMs = nowMs - lastCpuSampleMs
        if (elapsedMs < 500) return false
        val cpuTimeNs = bean.processCpuTime
        val deltaCpuMs = (cpuTimeNs - lastCpuTimeNs) / 1_000_000.0
        val cores = bean.availableProcessors.toDouble().coerceAtLeast(1.0)
        if (elapsedMs > 0) {
            cpuPercent = ((deltaCpuMs / (elapsedMs * cores)) * 100.0).coerceIn(0.0, 100.0)
        }
        lastCpuTimeNs = cpuTimeNs
        lastCpuSampleMs = nowMs
        return true
    }

    fun snapshot(): PerfSnapshot {
        val runtime = Runtime.getRuntime()
        val usedMb = ((runtime.totalMemory() - runtime.freeMemory()) / 1_048_576L).coerceAtLeast(0)
        return PerfSnapshot(loopFps = loopFps, renderFps = renderFps, usedMb = usedMb, cpuPercent = cpuPercent)
    }
}

private fun drawStatusLine(
    renderer: CanvasRenderer,
    styleSheet: StyleSheet,
    stats: PerfSnapshot,
    cols: Int,
    row: Int,
    rightText: String?,
    overrideText: String?
) {
    val statusStyle = styleSheet.getStyle("status")
    val textWidth = (cols - 2).coerceAtLeast(0)
    val padded = if (overrideText != null) {
        overrideText.take(textWidth)
    } else {
        val cpuText = String.format(Locale.US, "%.1f", stats.cpuPercent)
        val label = "Loop: ${stats.loopFps}/s  Draw: ${stats.renderFps}/s  Mem: ${stats.usedMb} MB  CPU: $cpuText%"
        val right = rightText.orEmpty()
        if (right.isNotBlank() && label.length + right.length + 4 < textWidth) {
            val spaces = " ".repeat(textWidth - label.length - right.length - 1)
            "$label$spaces$right"
        } else {
            label
        }
    }
    renderer.withStyle(statusStyle) {
        drawRect(0, row, cols, 1)
        drawText(1, row, padded.take(textWidth))
    }
}

