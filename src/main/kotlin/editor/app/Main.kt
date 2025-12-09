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
import editor.ui.ProjectSearchDialog
import java.nio.file.Files
import java.nio.file.Paths
import java.time.Instant
import editor.lib.FileTree
import editor.lib.JGitService
import java.io.File

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
        app.persistSession(force = true)
        renderer.requestExit()
    }

    runApp(app, renderer)
    app.persistSession(force = true)
}

private class SplitPanelsApp(
    styleSheet: StyleSheet,
    private val onQuit: () -> Unit
) : BaseComponent(styleSheet) {
    // gotcha
    private val sessionManager = ProjectSessionManager(System.getProperty("user.dir"))
    private var recentFiles: MutableList<RecentFileEntry> = mutableListOf()
    private var savedEditors: MutableMap<String, EditorSessionState> = mutableMapOf()
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
    private val codeEditor = CodeEditorView(styleSheet, syntaxProvider = regexProvider)
    private val hexViewer = BinaryHexView(styleSheet)
    private val imageViewer = ImageViewerView(styleSheet)
    private val gitPanel = GitPanelView(styleSheet, JGitService(File(System.getProperty("user.dir"))))
    private val projectSearchDialog = ProjectSearchDialog(
        styleSheet,
        onDismiss = { projectSearchVisible = false },
        syntaxProvider = regexProvider,
        onDirtyFile = { path, state -> recordRecentFromSearch(path, state) },
        projectRoot = java.nio.file.Paths.get(System.getProperty("user.dir"))
    )
    private var currentOpenPath: String = ""
    private var projectSearchVisible = false
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
                onRemoveRecent = { entry -> removeRecent(entry) }
            ),
            gitPanel,
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

        lastCols = cols
        leftRatio = splitterX.toDouble() / cols.toDouble().coerceAtLeast(1.0)
    }

    override fun dispatch(event: UIEvent): Boolean {
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
        }

        if (projectSearchVisible) {
            val handled = projectSearchDialog.dispatch(event)
            return handled
        }

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
                openInViewer(absPath, detected)
            }
        }
    }

    private fun openEditorState(state: EditorSessionState) {
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
        sessionManager.save(ProjectSession(recentFiles = recentsForSave, openEditors = editorsForSave))
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

