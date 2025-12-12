package editor.ui

import editor.lib.GitCommitEntry
import editor.lib.GitStatusEntry
import editor.lib.IGitService
import editor.mime.MimeTypeResult
import org.eclipse.jgit.api.Git
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.nio.file.Path

data class GitDiff(val path: String, val staged: Boolean, val content: String)

class GitPanelView(
    styleSheet: StyleSheet,
    private var root: Path,
    private var git: IGitService?,
    private val onShowDiff: (GitDiff) -> Unit = {}
) : BaseComponent(styleSheet) {

    private var statusEntries: List<GitStatusEntry> = emptyList()
    private var commitEntries: List<GitCommitEntry> = emptyList()
    private var selectedCommitIdx: Int = -1
    private var selectedStatusIdx: Int = -1
    private var commitScroll: Int = 0
    private val commitEditor = CodeEditorView(styleSheet)
    private val selectStarted: Boolean = false
    private var initButtonRow: Int = -1
    private var initButtonRange: IntRange = IntRange.EMPTY

    init {
        refreshData()
        commitEditor.openFile(
            path = "[commit-message]",
            detection = MimeTypeResult("text/plain", extension = ".txt", language = "plain-text")
        )
    }

    fun setGitService(service: IGitService?, newRoot: Path) {
        root = newRoot
        git = service
        refreshData()
    }

    fun refreshData() {
        val svc = git
        if (svc == null) {
            statusEntries = emptyList()
            commitEntries = emptyList()
            return
        }
        statusEntries = svc.statusPorcelain().sortedBy { it.path }
        commitEntries = svc.listCommits()
        commitScroll = commitScroll.coerceIn(0, (commitEntries.size - 1).coerceAtLeast(0))
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        initButtonRange = IntRange.EMPTY
        initButtonRow = -1
        if (git == null) {
            renderMissingRepo(canvas, cols, rows)
            return
        }
        val sectionHeight = (rows / 3).coerceAtLeast(3)

        val topHeight = sectionHeight
        val midHeight = sectionHeight
        val bottomHeight = rows - topHeight - midHeight

        renderStatus(canvas, cols, topHeight)
        renderCommitBox(canvas, cols, topHeight, midHeight)
        renderCommits(canvas, cols, topHeight + midHeight, bottomHeight)
    }

    private fun renderMissingRepo(canvas: CanvasRenderer, cols: Int, rows: Int) {
        val lineStyle = styleSheet.getStyle("file-entry")
        val buttonStyle = styleSheet.getStyle("button")
        canvas.withStyle(lineStyle) {
            drawText(0, 0, "Not a git repository.".take(cols).padEnd(cols, ' '))
            if (rows > 1) {
                drawText(0, 1, "Click init to run git init here.".take(cols).padEnd(cols, ' '))
            }
        }
        val label = "[ git init ]"
        val start = 0
        val row = 2.coerceAtMost(rows - 1)
        canvas.withStyle(buttonStyle) {
            drawText(start, row, label.take(cols - start))
        }
        initButtonRow = row
        initButtonRange = start until (start + label.length).coerceAtMost(cols)
    }

    private fun renderStatus(canvas: CanvasRenderer, cols: Int, height: Int) {
        if (height <= 0) return
        val branch = runCatching { git?.currentBranch() }.getOrDefault("(no repo)")
        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        canvas.withStyle(lineStyle) {
            drawText(0, 0, ("Branch: $branch").take(cols).padEnd(cols, ' '))
            val visible = (height - 1).coerceAtLeast(0)
            statusEntries.take(visible).forEachIndexed { idx, entry ->
                val style = if (idx == selectedStatusIdx) selectedStyle else lineStyle
                withStyle(style) {
                    val stageFlag = if (entry.staged) "[S]" else "[ ]"
                    val label = "$stageFlag ${entry.code.padEnd(3)} ${entry.path}".take(cols).padEnd(cols, ' ')
                    drawText(0, idx + 1, label)
                }
            }
        }
    }

    private fun renderCommitBox(canvas: CanvasRenderer, cols: Int, offsetY: Int, height: Int) {
        if (height <= 0) return
        val editorHeight = (height - 1).coerceAtLeast(1)
        val editorClip = ClippedCanvasRenderer(canvas, 0, offsetY, cols, editorHeight)
        commitEditor.render(editorClip)
        val buttonStyle = styleSheet.getStyle("button")
        canvas.withStyle(buttonStyle) {
            val label = "[ Commit ]".take(cols).padEnd(cols, ' ')
            drawText(0, offsetY + editorHeight, label)
        }
    }

    private fun renderCommits(canvas: CanvasRenderer, cols: Int, offsetY: Int, height: Int) {
        if (height <= 0) return
        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        val clipped = ClippedCanvasRenderer(canvas, 0, offsetY, cols, height)
        clipped.withStyle(lineStyle) {
            val header = "Commits".take(cols).padEnd(cols, ' ')
            drawText(0, 0, header)
            val visible = (height - 1).coerceAtLeast(0)
            val slice = commitEntries.drop(commitScroll).take(visible)
            slice.forEachIndexed { idx, commit ->
                val absoluteIdx = commitScroll + idx
                val style = if (absoluteIdx == selectedCommitIdx) selectedStyle else lineStyle
                withStyle(style) {
                    val label = commit.message.lineSequence().firstOrNull().orEmpty()
                    val line = label.take(cols).padEnd(cols, ' ')
                    drawText(0, idx + 1, line)
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (git == null) {
            if (event.kind == "mouse_down") {
                val y = event.y ?: return false
                val x = event.x ?: -1
                if (y == initButtonRow && initButtonRange.contains(x)) {
                    runCatching { Git.init().setDirectory(root.toFile()).call() }
                        .onSuccess {
                            git = editor.lib.JGitService(root.toFile())
                            refreshData()
                        }
                    return true
                }
            }
            return false
        }
        return when (event.kind) {
            "mouse_down" -> handleClick(event, allowCommit = false)
            "mouse_up" -> {
                val y = event.y ?: return false
                val rows = event.rows ?: return false
                val section = rows / 3
                val topH = section
                val midH = section
                if (y >= topH && y < section + midH) {
                    handleClick(event, allowCommit = true)
                } else false
            }
            "mouse_move" -> handleDrag(event)
            "mouse_scroll" -> handleScroll(event)
            "key_down" -> handleKey(event)
            else -> false
        }
    }

    private fun handleClick(event: UIEvent, allowCommit: Boolean): Boolean {
        val svc = git ?: return false
        val y = event.y ?: return false
        val x = event.x ?: return false
        val rows = event.rows ?: return false
        val section = rows / 3
        val topH = section
        val midH = section
        when {
            y < topH -> { // status
                val idx = y - 1
                if (idx in statusEntries.indices) {
                    selectedStatusIdx = idx
                    val entry = statusEntries[idx]
                    val stageBoxWidth = 3 // "[S]" or "[ ]"
                    if (x in 0 until stageBoxWidth) {
                        if (entry.staged) svc.unstage(entry.path) else svc.stage(entry.path)
                        refreshData()
                    } else {
                        val diffText = runCatching { svc.diff(entry.path, staged = entry.staged) }
                            .getOrElse { ex -> "Unable to load diff for ${entry.path}:\n${ex.message ?: ex}" }
                        onShowDiff(GitDiff(entry.path, entry.staged, diffText))
                    }
                    return true
                }
            }
            y < topH + midH -> { // commit box
                val relY = y - topH
                val editorHeight = (midH - 1).coerceAtLeast(1)
                if (relY >= editorHeight) {
                    if (!allowCommit) return true
                    val message = commitEditor.textContent()
                    svc.commit(message)
                    commitEditor.loadTextContent("")
                    refreshData()
                    return true
                }
                val forwarded = event.alterCopy(
                    UIEvent(
                        kind = event.kind,
                        x = event.x,
                        y = relY,
                        relX = event.x,
                        relY = relY,
                        button = event.button,
                        scrollDelta = event.scrollDelta,
                        key = event.key,
                        ctrl = event.ctrl,
                        alt = event.alt,
                        shift = event.shift,
                        meta = event.meta,
                        focusId = event.focusId,
                        cols = event.cols,
                        rows = editorHeight,
                        raw = event.raw
                    )
                )
                return commitEditor.dispatch(forwarded)
            }
            else -> { // commits
                val relY = y - (topH + midH)
                val idx = relY - 1 + commitScroll
                if (idx in commitEntries.indices) {
                    selectedCommitIdx = idx
                    val commit = commitEntries[idx]
                    commitEditor.loadTextContent(commit.message)
                    return true
                }
            }
        }
        return false
    }

    private fun handleDrag(event: UIEvent): Boolean {
        val y = event.y ?: return false
        val rows = event.rows ?: return false
        val section = rows / 3
        val topH = section
        val midH = section
        val editorHeight = (midH - 1).coerceAtLeast(1)
        if (y in topH until (topH + editorHeight)) {
            val relY = y - topH
            val forwarded = event.alterCopy(
                UIEvent(
                    kind = event.kind,
                    x = event.x,
                    y = relY,
                    relX = event.x,
                    relY = relY,
                    button = event.button,
                    scrollDelta = event.scrollDelta,
                    key = event.key,
                    ctrl = event.ctrl,
                    alt = event.alt,
                    shift = event.shift,
                    meta = event.meta,
                    focusId = event.focusId,
                    cols = event.cols,
                    rows = editorHeight,
                    raw = event.raw
            )
            )
            return commitEditor.dispatch(forwarded)
        }
        return false
    }

    private fun handleScroll(event: UIEvent): Boolean {
        val delta = event.scrollDelta ?: return false
        val y = event.y ?: return false
        val rows = event.rows ?: return false
        val section = rows / 3
        val topH = section
        val midH = section
        val editorHeight = (midH - 1).coerceAtLeast(1)
        return if (y in topH until (topH + editorHeight)) {
            return commitEditor.dispatch(event.alterCopy(UIEvent(
                kind = event.kind,
                x = event.x,
                y = event.y?.minus(topH),
                relX = event.x,
                relY = event.y?.minus(topH),
                button = event.button,
                scrollDelta = delta,
                key = event.key,
                ctrl = event.ctrl,
                alt = event.alt,
                shift = event.shift,
                meta = event.meta,
                focusId = event.focusId,
                cols = event.cols,
                rows = editorHeight,
                raw = event.raw
            )))
        } else if (y >= topH + midH) {
            val bottomHeight = rows - topH - midH
            val visible = (bottomHeight - 1).coerceAtLeast(0)
            val maxScroll = (commitEntries.size - visible).coerceAtLeast(0)
            val prev = commitScroll
            commitScroll = (commitScroll - delta).coerceIn(0, maxScroll)
            commitScroll != prev
        } else false
    }

    private fun handleKey(event: UIEvent): Boolean = commitEditor.dispatch(event)
}
