package editor.ui

import editor.lib.GitCommitEntry
import editor.lib.GitStatusEntry
import editor.lib.IGitService
import editor.mime.MimeTypeResult
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class GitPanelView(
    styleSheet: StyleSheet,
    private val git: IGitService
) : BaseComponent(styleSheet) {

    private var statusEntries: List<GitStatusEntry> = emptyList()
    private var commitEntries: List<GitCommitEntry> = emptyList()
    private var selectedCommitIdx: Int = -1
    private var selectedStatusIdx: Int = -1
    private var commitScroll: Int = 0
    private val commitEditor = CodeEditorView(styleSheet)
    private val selectStarted: Boolean = false

    init {
        refreshData()
        commitEditor.openFile(
            path = "[commit-message]",
            detection = MimeTypeResult("text/plain", extension = ".txt", language = "plain-text")
        )
    }

    fun refreshData() {
        statusEntries = git.statusPorcelain().sortedBy { it.path }
        commitEntries = git.listCommits()
        commitScroll = commitScroll.coerceIn(0, (commitEntries.size - 1).coerceAtLeast(0))
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val sectionHeight = (rows / 3).coerceAtLeast(3)

        val topHeight = sectionHeight
        val midHeight = sectionHeight
        val bottomHeight = rows - topHeight - midHeight

        renderStatus(canvas, cols, topHeight)
        renderCommitBox(canvas, cols, topHeight, midHeight)
        renderCommits(canvas, cols, topHeight + midHeight, bottomHeight)
    }

    private fun renderStatus(canvas: CanvasRenderer, cols: Int, height: Int) {
        if (height <= 0) return
        val branch = runCatching { git.currentBranch() }.getOrDefault("(no repo)")
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
        val y = event.y ?: return false
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
                    if (entry.staged) git.unstage(entry.path) else git.stage(entry.path)
                    refreshData()
                    return true
                }
            }
            y < topH + midH -> { // commit box
                val relY = y - topH
                val editorHeight = (midH - 1).coerceAtLeast(1)
                if (relY >= editorHeight) {
                    if (!allowCommit) return true
                    git.commit(commitEditor.textContent())
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
            commitEditor.dispatch(event.alterCopy(UIEvent(
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
