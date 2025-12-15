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
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.time.Instant
import editor.lib.FileTree
import editor.lib.JGitService
import java.io.File
import java.lang.management.ManagementFactory
import com.sun.management.OperatingSystemMXBean
import java.util.Locale
import kotlin.system.exitProcess
import editor.codeintel.CodeIntelService
import editor.lsp.LspManager
import editor.lsp.LspService

fun runApp(app: Component, renderer: CanvasRenderer = AnsiCanvasRenderer(), idleSleepMillis: Long = 8L) {
    val perf = PerformanceTracker()
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
            app.render(contentRenderer)
        }
        perf.afterFrame()
        if (totalRows > 0) {
            drawStatusLine(renderer, app.styleSheet, perf.snapshot(), totalCols, totalRows - 1)
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
        redraw()

        var lastTickMs = System.currentTimeMillis()
        while (renderer.isRunning()) {
            val event = renderer.tryPollEvent()
            if (event != null) {
                needsRender = app.dispatch(event) || event.kind == "resize"
            }
            val now = System.currentTimeMillis()
            val perfDirty = perf.loopTick(now)
            if (now - lastTickMs >= 500) { // lightweight periodic tick
                val ticked = (app as? Tickable)?.tick(now) ?: false
                if (ticked) needsRender = true
                lastTickMs = now
            }

            if (needsRender || perfDirty) {
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

fun main(args: Array<String>) {
    val styleFiles = mutableListOf("styles/app.css")
    val kodeHome = kodeHome()
    resolveResource("styles/app.css", kodeHome)?.let { styleFiles.add(0, it) }
    resolveResource("grammars/tm-scopes.css", kodeHome)?.let { styleFiles += it }
    val styleSheet = StyleSheet.loadFromFiles(styleFiles)
    val buildVersion = resolveBuildVersion()
    val renderer = AnsiCanvasRenderer()
    val workingDir = resolveWorkingDirectory(args)

    lateinit var app: SplitPanelsApp
    app = SplitPanelsApp(styleSheet, buildVersion, workingDir) {
        app.persistSession(force = true)
        renderer.requestExit()
    }

    runApp(app, renderer)
    app.persistSession(force = true)
}

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

private class SplitPanelsApp(
    styleSheet: StyleSheet,
    private val buildVersion: String,
    private var projectRoot: Path,
    private val onQuit: () -> Unit
) : BaseComponent(styleSheet), Tickable {
    // gotcha
    private var sessionManager = ProjectSessionManager(projectRoot)
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
    private val codeIntel = CodeIntelService()
    private val lspManager = LspManager()
    private val lspService = LspService(lspManager, projectRoot)
    private var codeEditor = CodeEditorView(
        styleSheet,
        syntaxProvider = regexProvider,
        codeIntel = codeIntel,
        lsp = lspService,
        navigationHandler = this::navigateTo
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
        onRemoveRecent = { entry -> removeRecent(entry) }
    )
    private val settingsView = SettingsView(styleSheet, lspService, lspManager)
    private var currentOpenPath: String = ""
    private var projectSearchVisible = false
    private var workspacePickerVisible = false
    private var workspacePicker: WorkspacePickerDialog? = null
    private var lastFileRefreshMs: Long = 0L
    private var lastGitRefreshMs: Long = 0L
    private val refreshIntervalMs: Long = 5_000L
    private var activeDiff: GitDiff? = null
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
        titles = listOf("Files", "Git", "About", "Settings"),
        tabComponents = listOf(
            filesTabView,
            gitPanel,
            aboutView,
            settingsView
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

        lastCols = cols
        leftRatio = splitterX.toDouble() / cols.toDouble().coerceAtLeast(1.0)
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (workspacePickerVisible) {
            val handled = workspacePicker?.dispatch(event) ?: false
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
        // Index 1 corresponds to Git tab in leftTabs.
        if (selectedIndex != 1 && activeDiff != null) {
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
        codeIntel.clear()

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

        codeEditor = CodeEditorView(
            styleSheet,
            syntaxProvider = regexProvider,
            codeIntel = codeIntel,
            navigationHandler = this::navigateTo
        )
        focus = FocusTarget.FILES
        rightFocus = FocusTarget.CODE
        filesTabView.setRoot(projectRoot.toString())
        gitPanel.setGitService(createGitService(projectRoot), projectRoot)
        projectSearchDialog.setProjectRoot(projectRoot)
        lastFileRefreshMs = 0L
        lastGitRefreshMs = 0L
        workspacePickerVisible = false
        workspacePicker = null
        restoreLastSession()
        persistSession(force = true)
    }

    private fun createGitService(root: Path): editor.lib.IGitService? {
        val gitDir = File(root.toFile(), ".git")
        if (!gitDir.isDirectory) return null
        return runCatching { JGitService(root.toFile()) }.getOrNull()
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
            FocusTarget.DIFF -> diffViewer.render(clipped)
            FocusTarget.FILES -> codeEditor.render(clipped)
        }
    }

    override fun tick(nowMs: Long): Boolean {
        var needsRender = false
        if (nowMs - lastFileRefreshMs >= refreshIntervalMs) {
            (leftTabs.children.getOrNull(0) as? editor.ui.FilesTabView)?.refreshFileTree()
            lastFileRefreshMs = nowMs
            needsRender = true
        }
        if (nowMs - lastGitRefreshMs >= refreshIntervalMs) {
            gitPanel.refreshData()
            lastGitRefreshMs = nowMs
            needsRender = true
        }
        return needsRender
    }
}

private enum class FocusTarget { FILES, CODE, HEX, IMAGE, DIFF }

private interface Tickable {
    /**
     * Called periodically from the main loop. Return true to request a repaint.
     */
    fun tick(nowMs: Long): Boolean
}

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
    row: Int
) {
    val statusStyle = styleSheet.getStyle("status")
    val textWidth = (cols - 2).coerceAtLeast(0)
    val cpuText = String.format(Locale.US, "%.1f", stats.cpuPercent)
    val label = "Loop: ${stats.loopFps}/s  Draw: ${stats.renderFps}/s  Mem: ${stats.usedMb} MB  CPU: $cpuText%"
    renderer.withStyle(statusStyle) {
        drawRect(0, row, cols, 1)
        drawText(1, row, label.take(textWidth))
    }
}

