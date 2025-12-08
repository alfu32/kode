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
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import editor.app.ProjectSessionManager
import editor.app.RecentFileEntry
import editor.app.EditorSessionState
import editor.app.ProjectSession
import editor.lib.FileTree

fun runApp(app: Component, renderer: CanvasRenderer = AnsiCanvasRenderer(), idleSleepMillis: Long = 8L) {
    fun redraw() {
        renderer.clear()
        app.render(renderer)
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
        redraw()

        while (renderer.isRunning()) {
            val event = renderer.tryPollEvent()
            if (event != null) {
                needsRender = app.dispatch(event) || event.kind == "resize"
            }

            if (needsRender) {
                redraw()
                needsRender = false
            } else {
                Thread.sleep(idleSleepMillis)
            }
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

fun main() {
    val styleFiles = mutableListOf("styles/app.css")
    if (Files.exists(Paths.get("grammars/tm-scopes.css"))) {
        styleFiles += "grammars/tm-scopes.css"
    }
    val styleSheet = StyleSheet.loadFromFiles(styleFiles)
    val renderer = AnsiCanvasRenderer()

    lateinit var app: SplitPanelsApp
    app = SplitPanelsApp(styleSheet) {
        app.persistSession()
        renderer.requestExit()
    }

    runApp(app, renderer)
    app.persistSession()
}

private class SplitPanelsApp(
    styleSheet: StyleSheet,
    private val onQuit: () -> Unit
) : BaseComponent(styleSheet) {
    private val sessionManager = ProjectSessionManager(System.getProperty("user.dir"))
    private var recentFiles: MutableList<RecentFileEntry> = mutableListOf()
    private var savedEditors: MutableMap<String, EditorSessionState> = mutableMapOf()
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
    private val codeEditor = CodeEditorView(styleSheet, syntaxProvider = regexProvider)
    private val hexViewer = BinaryHexView(styleSheet)
    private val imageViewer = ImageViewerView(styleSheet)
    init {
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
        restoreLastSession()
    }
    private val leftTabs = TabView(
        styleSheet = styleSheet,
        titles = listOf("Files", "Git", "Settings"),
        tabComponents = listOf(
            FilesTabView(
                styleSheet,
                FileTree.newFileTree(System.getProperty("user.dir")),
                recentFilesProvider = {
                    recentFiles
                        .sortedByDescending { it.lastOpenedEpochMillis }
                        .take(10)
                        .map { it.copy(path = sessionManager.toRelative(it.path)) }
                },
                onSelectFile = { entry, mime ->
                    val detected = mime?.let { MimeTypeResult(it, language = null) }
                        ?: mimeDetector.detectFile(java.nio.file.Path.of(entry.fullPath))
                    openInViewer(entry.fullPath, detected)
                },
                onSelectRecent = { entry -> openRecent(entry) }
            ),
            PlaceholderPane(styleSheet, "Git"),
            PlaceholderPane(styleSheet, "Settings")
        ),
        initialIndex = 0
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

        val viewerToRender = if (focus == FocusTarget.FILES) rightFocus else focus

        canvas.withStyle(rightStyle) {
            if (rightWidth > 0) {
                drawRect(splitterX + 1, 0, rightWidth, rows)
                renderRightPane(this, splitterX + 1, rows, rightWidth, cols, viewerToRender)
            }
        }

        lastCols = cols
        leftRatio = splitterX.toDouble() / cols.toDouble().coerceAtLeast(1.0)
    }

    override fun dispatch(event: UIEvent): Boolean {
        when (event.kind) {
            "key_down" -> if (event.key?.lowercase() == "q") {
                onQuit()
            }
            "mouse_down" -> {
                val x = event.x ?: return false
                if (isOnSplitter(x)) {
                    dragging = true
                    return false
                }
            }
            "mouse_up" -> dragging = false
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
                    raw = event.raw
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
                        raw = event.raw
                    )
                )
                return dispatchToRight(forwarded)
            }
        }

        if (event.kind == "key_down") {
            return when (focus) {
                FocusTarget.FILES -> leftTabs.dispatch(event)
                FocusTarget.CODE -> codeEditor.dispatch(event)
                FocusTarget.HEX -> hexViewer.dispatch(event)
                FocusTarget.IMAGE -> imageViewer.dispatch(event)
            }
        }

        return false
    }

    private fun isOnSplitter(x: Int): Boolean = x == clampWidth(leftWidth, lastCols.coerceAtLeast(1))

    private fun openInViewer(path: String, detected: MimeTypeResult) {
        when (detected.mimeTypeCategory) {
            MimeTypeCategory.IMAGE -> {
                imageViewer.openFile(path, detected)
                rightFocus = FocusTarget.IMAGE
                focus = rightFocus
                recordRecent(path, null)
            }
            MimeTypeCategory.TEXT -> {
                val (grammarLang, grammarAvailable) = resolveGrammar(path, detected)
                codeEditor.openFile(path, detected, grammarAvailable, grammarLang)
                rightFocus = FocusTarget.CODE
                focus = rightFocus
                recordRecent(path, codeEditor.captureState())
            }
            MimeTypeCategory.BINARY, MimeTypeCategory.UNKNOWN -> {
                // Unknown defaults to hex viewer.
                hexViewer.openFile(path, detected)
                rightFocus = FocusTarget.HEX
                focus = rightFocus
                recordRecent(path, null)
            }
        }
    }

    private fun openRecent(entry: RecentFileEntry) {
        val absPath = sessionManager.toAbsolute(entry.path)
        val state = entry.editor ?: savedEditors[absPath]
        if (state != null) {
            openEditorState(state.copy(path = absPath))
            recordRecent(absPath, codeEditor.captureState())
            return
        }
        val detected = mimeDetector.detectFile(java.nio.file.Path.of(absPath))
        openInViewer(absPath, detected)
    }

    private fun openEditorState(state: EditorSessionState) {
        codeEditor.restoreState(state)
        rightFocus = FocusTarget.CODE
        focus = rightFocus
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
            FocusTarget.FILES -> codeEditor.dispatch(event)
        }
        if (handled && targetFocus != FocusTarget.FILES) {
            rightFocus = targetFocus
        }
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

    fun persistSession() {
        val currentState = codeEditor.captureState()?.let { it.copy(path = sessionManager.toAbsolute(it.path)) }
        currentState?.let { savedEditors[sessionManager.toAbsolute(it.path)] = it }
        val mergedEditors = savedEditors.values.toMutableList()
        currentState?.let { state ->
            mergedEditors.removeIf { it.path == state.path }
            mergedEditors.add(state)
        }

        val editorsForSave = mergedEditors.map { it.copy(path = sessionManager.toRelative(it.path)) }
        val recentsForSave = recentFiles.map { entry ->
            entry.copy(
                path = sessionManager.toRelative(entry.path),
                editor = entry.editor?.copy(path = sessionManager.toRelative(entry.editor.path))
            )
        }
        sessionManager.save(ProjectSession(recentFiles = recentsForSave, openEditors = editorsForSave))
    }

    private fun recordRecent(path: String, state: EditorSessionState?) {
        val abs = sessionManager.toAbsolute(path)
        val now = Instant.now().toEpochMilli()
        val existingIdx = recentFiles.indexOfFirst { sessionManager.toAbsolute(it.path) == abs }
        val entry = RecentFileEntry(path = abs, lastOpenedEpochMillis = now, editor = state ?: savedEditors[abs])
        if (existingIdx >= 0) recentFiles[existingIdx] = entry else recentFiles.add(entry)
        if (recentFiles.size > 20) {
            recentFiles = recentFiles.sortedByDescending { it.lastOpenedEpochMillis }.take(20).toMutableList()
        }
        if (state != null) {
            savedEditors[abs] = state
        }
    }

    private fun restoreLastSession() {
        val ordered = recentFiles.sortedByDescending { it.lastOpenedEpochMillis }
        val stateFromRecents = ordered.firstNotNullOfOrNull { it.editor ?: savedEditors[sessionManager.toAbsolute(it.path)] }
        val fallback = savedEditors.values.firstOrNull()
        val state = stateFromRecents ?: fallback
        if (state != null) {
            openEditorState(state)
        }
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

    private inline fun CanvasRenderer.withStyle(style: react.StyleSet, block: CanvasRenderer.() -> Unit) {
        style.bg?.let { setBackgroundColor(it.r, it.g, it.b) }
        style.fg?.let { setColor(it.r, it.g, it.b) }
        block()
        resetAttributes()
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
            FocusTarget.FILES -> codeEditor.render(clipped)
        }
    }
}

private enum class FocusTarget { FILES, CODE, HEX, IMAGE }

private class PlaceholderPane(
    styleSheet: StyleSheet,
    private val label: String
) : BaseComponent(styleSheet) {
    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        if (cols > 2) {
            canvas.drawText(1, 0, label.take(cols - 2))
        }
    }

    override fun dispatch(event: UIEvent): Boolean = false
}
