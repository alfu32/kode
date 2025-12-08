package editor.ui

import editor.app.RecentFileEntry
import editor.lib.FileTree
import editor.lib.FileTreeEntry
import editor.lib.IFileTree
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

/**
 * Wraps the file tree with a recent-files section.
 */
class FilesTabView(
    styleSheet: StyleSheet,
    private val tree: IFileTree = FileTree.newFileTree(System.getProperty("user.dir")),
    private val recentFilesProvider: () -> List<RecentFileEntry> = { emptyList() },
    private val currentPathProvider: () -> String? = { null },
    private val onSelectFile: (FileTreeEntry, String?) -> Unit = { _, _ -> },
    private val onSelectRecent: (RecentFileEntry) -> Unit = {}
) : BaseComponent(styleSheet) {

    private val fileTreeView = FileTreeView(styleSheet, tree, onSelectFile)
    private var recentHeight: Int = 0
    private var recentScroll: Int = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(0)
        val recents = recentFilesProvider()
        recentHeight = computeRecentHeight(rows)
        renderRecentList(canvas, recents, cols, recentHeight)
        val hasRecents = recentHeight > 0
        if (hasRecents) {
            val separatorY = (recentHeight - 1).coerceAtLeast(0)
            if (separatorY < rows) {
                val sepStyle = styleSheet.getStyle("splitter")
                canvas.applyStyle(sepStyle) {
                    drawText(0, separatorY, "-".repeat(cols))
                }
            }
        }
        val offsetY = if (hasRecents) recentHeight + 1 else 0
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
        val rows = event.rows ?: 0
        val recents = recentFilesProvider()
        val headerRows = computeRecentHeight(rows)
        val hasRecents = headerRows > 0
        if (event.kind.startsWith("mouse")) {
            val y = event.y ?: return false
            if (hasRecents && y < headerRows) {
                if (event.kind == "mouse_down") {
                    val idx = (y - 1 + recentScroll)
                    if (idx in recents.indices) {
                        onSelectRecent(recents[idx])
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
                y = event.y?.let { it - if (hasRecents) headerRows + 1 else 0 },
                relX = event.relX,
                relY = event.relY?.let { it - if (hasRecents) headerRows + 1 else 0 },
                button = event.button,
                scrollDelta = event.scrollDelta,
                key = event.key,
                ctrl = event.ctrl,
                alt = event.alt,
                shift = event.shift,
                meta = event.meta,
                focusId = event.focusId,
                cols = event.cols,
                rows = event.rows?.let { it - if (hasRecents) headerRows + 1 else 0 },
                raw = event.raw
            )
        )
        return fileTreeView.dispatch(forwarded)
    }

    private fun renderRecentList(canvas: CanvasRenderer, recents: List<RecentFileEntry>, cols: Int, height: Int) {
        if (height <= 0 || cols <= 0 || recents.isEmpty()) return
        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        canvas.applyStyle(lineStyle) {
            val header = "Recent".take(cols).padEnd(cols, ' ')
            drawText(0, 0, header)
            val available = height - 1
            val slice = recents.drop(recentScroll).take(available)
            val current = currentPathProvider()?.let { it.trim() }
            slice.forEachIndexed { idx, entry ->
                val isSelected = current != null && current == entry.path.trim()
                val style = if (isSelected) selectedStyle else lineStyle
                applyStyle(style) {
                    val label = entry.path.take(cols).padEnd(cols, ' ')
                    drawText(0, idx + 1, label)
                }
            }
        }
    }

    private fun computeRecentHeight(totalRows: Int): Int {
        val target = (totalRows * 0.3).toInt().coerceAtLeast(3)
        return target.coerceAtMost(totalRows)
    }
}
