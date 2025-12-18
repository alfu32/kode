package editor.ui

import editor.app.RecentFileEntry
import editor.lib.FileTree
import editor.lib.FileTreeEntry
import editor.lib.IFileTree
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSheet
import react.Tickable
import react.UIEvent
import react.renderer.CanvasRenderer

/**
 * Wraps the file tree with a recent-files section.
 */
class FilesTabView(
    styleSheet: StyleSheet,
    private var tree: IFileTree = FileTree.newFileTree(System.getProperty("user.dir")),
    private val currentRootProvider: () -> String = { System.getProperty("user.dir") },
    private val onChangeWorkspace: () -> Unit = {},
    private val recentFilesProvider: () -> List<RecentFileEntry> = { emptyList() },
    private val currentPathProvider: () -> String? = { null },
    private val onSelectFile: (FileTreeEntry, String?) -> Unit = { _, _ -> },
    private val onSelectRecent: (RecentFileEntry) -> Unit = {},
    private val onRemoveRecent: (RecentFileEntry) -> Unit = {}
) : BaseComponent(styleSheet), Tickable {

    private val fileTreeView = FileTreeView(styleSheet, tree, onSelectFile)
    private var recentHeight: Int = 0
    private var recentScroll: Int = 0
    private var lastRows: Int = 0
    private var lastRefreshMs: Long = 0L
    private val refreshIntervalMs: Long = 4_000L
    private var rootButtonRange: IntRange = IntRange.EMPTY

    fun refreshFileTree() {
        tree.refreshOpenNodes()
    }

    override fun tick(nowMs: Long): Boolean {
        if (nowMs - lastRefreshMs < refreshIntervalMs) return false
        lastRefreshMs = nowMs
        refreshFileTree()
        return true
    }

    fun setRoot(root: String) {
        tree = FileTree.newFileTree(root)
        fileTreeView.setTree(tree)
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(0)
        lastRows = rows
        renderRootBar(canvas, cols)

        val contentRows = (rows - 1).coerceAtLeast(0)
        val recents = recentFilesProvider()
        recentHeight = if (recents.isNotEmpty()) computeRecentHeight(contentRows) else 0
        val visible = (recentHeight - 1).coerceAtLeast(0)
        val maxScroll = (recents.size - visible).coerceAtLeast(0)
        recentScroll = recentScroll.coerceIn(0, maxScroll)
        renderRecentList(canvas, recents, cols, recentHeight)
        val hasRecents = recentHeight > 0
        if (hasRecents) {
            val separatorY = (recentHeight - 1).coerceAtLeast(0)
            if (separatorY < contentRows) {
                val sepStyle = styleSheet.getStyle("splitter")
                canvas.withStyle(sepStyle) {
                    drawText(0, separatorY + 1, "-".repeat(cols))
                }
            }
        }
        val offsetY = 1 + if (hasRecents) recentHeight else 0
        val remaining = (rows - offsetY).coerceAtLeast(0)
        if (remaining <= 0) return

        val clipped = ClippedCanvasRenderer(
            base = canvas,
            offsetX = 0,
            offsetY = offsetY,
            width = cols,
            height = remaining
        )
        fileTreeView.render(clipped)
    }

    override fun dispatch(event: UIEvent): Boolean {
        val rows = (event.rows ?: 0).let { if (it > 0) it else lastRows }
        val y = event.y
        val recents = recentFilesProvider()
        val headerRows = if (recents.isNotEmpty()) computeRecentHeight((rows - 1).coerceAtLeast(0)) else 0
        val hasRecents = headerRows > 0

        if (event.kind.startsWith("mouse")) {
            val mouseY = y ?: 0
            if (event.kind == "mouse_down" && mouseY == 0) {
                val x = event.x ?: -1
                if (rootButtonRange.contains(x)) {
                    onChangeWorkspace()
                    return true
                }
            }
            if (mouseY == 0) return false
            if (hasRecents && mouseY in 1 until (headerRows + 1)) {
                if (event.kind == "mouse_down") {
                    val idx = (mouseY - 2 + recentScroll)
                    if (idx in recents.indices) {
                        val relX = event.x ?: 0
                        // Column 0 is indicator ('x' or '*'), clicking it clears the entry.
                        if (relX == 0) {
                            onRemoveRecent(recents[idx])
                        } else {
                            onSelectRecent(recents[idx])
                        }
                        return true
                    }
                }
                if (event.kind == "mouse_scroll") {
                    val delta = event.scrollDelta ?: 0
                    val visible = (headerRows - 1).coerceAtLeast(0)
                    val maxScroll = (recents.size - visible).coerceAtLeast(0)
                    val prev = recentScroll
                    recentScroll = (recentScroll - delta).coerceIn(0, maxScroll)
                    return recentScroll != prev
                }
                return false
            }
        }
        val forwarded = event.alterCopy(
            UIEvent(
                kind = event.kind,
                x = event.x,
                y = event.y?.let { it - 1 - if (hasRecents) headerRows else 0 },
                relX = event.relX,
                relY = event.relY?.let { it - 1 - if (hasRecents) headerRows else 0 },
                button = event.button,
                scrollDelta = event.scrollDelta,
                key = event.key,
                ctrl = event.ctrl,
                alt = event.alt,
                shift = event.shift,
                meta = event.meta,
                focusId = event.focusId,
                cols = event.cols,
                rows = event.rows?.let { it - 1 - if (hasRecents) headerRows else 0 },
                raw = event.raw
            )
        )
        return fileTreeView.dispatch(forwarded)
    }

    private fun renderRecentList(canvas: CanvasRenderer, recents: List<RecentFileEntry>, cols: Int, height: Int) {
        if (height <= 0 || cols <= 0 || recents.isEmpty()) return
        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        canvas.withStyle(lineStyle) {
            val header = "Recent".take(cols).padEnd(cols, ' ')
            drawText(0, 1, header)
            val available = height - 1
            val slice = recents.drop(recentScroll).take(available)
            val current = currentPathProvider()?.let { it.trim() }
            slice.forEachIndexed { idx, entry ->
                val isSelected = current != null && current == entry.path.trim()
                val style = if (isSelected) selectedStyle else lineStyle
                withStyle(style) {
                    val indicator = if (entry.dirty) "*" else "x"
                    val label = entry.path.take((cols - 2).coerceAtLeast(1))
                    val line = "$indicator $label".padEnd(cols, ' ')
                    drawText(0, idx + 2, line)
                }
            }
        }
    }

    private fun computeRecentHeight(totalRows: Int): Int {
        val target = (totalRows * 0.3).toInt().coerceAtLeast(3)
        return target.coerceAtMost(totalRows)
    }

    private fun renderRootBar(canvas: CanvasRenderer, cols: Int) {
        rootButtonRange = IntRange.EMPTY
        if (cols <= 0) return
        val lineStyle = styleSheet.getStyle("file-entry")
        val changeLabel = "[change]"
        val buttonStart = (cols - changeLabel.length).coerceAtLeast(0)
        val rootPath = currentRootProvider()
        val rootText = rootPath.take(buttonStart).padEnd(buttonStart, ' ')
        canvas.withStyle(lineStyle) {
            drawText(0, 0, rootText.take(cols))
            if (buttonStart < cols) {
                drawText(buttonStart, 0, changeLabel.take(cols - buttonStart))
                rootButtonRange = buttonStart until (buttonStart + changeLabel.length).coerceAtMost(cols)
            }
        }
    }
}
