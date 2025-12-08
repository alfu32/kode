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
    private val onSelectFile: (FileTreeEntry, String?) -> Unit = { _, _ -> },
    private val onSelectRecent: (RecentFileEntry) -> Unit = {}
) : BaseComponent(styleSheet) {

    private val fileTreeView = FileTreeView(styleSheet, tree, onSelectFile)
    private var recentHeight: Int = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(0)
        val recents = recentFilesProvider()
        recentHeight = computeRecentHeight(recents, rows)
        renderRecentList(canvas, recents, cols, recentHeight)
        val remaining = (rows - recentHeight).coerceAtLeast(0)
        if (remaining <= 0) return

        val clipped = ClippedCanvasRenderer(
            base = canvas,
            offsetX = 0,
            offsetY = recentHeight,
            width = cols,
            height = remaining
        )
        fileTreeView.render(clipped)
    }

    override fun dispatch(event: UIEvent): Boolean {
        val rows = event.rows ?: 0
        val recents = recentFilesProvider()
        val headerRows = computeRecentHeight(recents, rows)
        if (event.kind.startsWith("mouse")) {
            val y = event.y ?: return false
            if (y < headerRows) {
                if (event.kind == "mouse_down") {
                    val idx = y - 1
                    if (idx in recents.indices) {
                        onSelectRecent(recents[idx])
                        return true
                    }
                }
                return false
            }
        }
        val forwarded = event.alterCopy(
            UIEvent(
                kind = event.kind,
                x = event.x,
                y = event.y?.let { it - headerRows },
                relX = event.relX,
                relY = event.relY?.let { it - headerRows },
                button = event.button,
                scrollDelta = event.scrollDelta,
                key = event.key,
                ctrl = event.ctrl,
                alt = event.alt,
                shift = event.shift,
                meta = event.meta,
                focusId = event.focusId,
                cols = event.cols,
                rows = event.rows?.let { it - headerRows },
                raw = event.raw
            )
        )
        return fileTreeView.dispatch(forwarded)
    }

    private fun renderRecentList(canvas: CanvasRenderer, recents: List<RecentFileEntry>, cols: Int, height: Int) {
        if (height <= 0 || cols <= 0 || recents.isEmpty()) return
        val lineStyle = styleSheet.getStyle("file-entry")
        canvas.applyStyle(lineStyle) {
            val header = "Recent".take(cols).padEnd(cols, ' ')
            drawText(0, 0, header)
            recents.take(height - 1).forEachIndexed { idx, entry ->
                val label = entry.path.take(cols).padEnd(cols, ' ')
                drawText(0, idx + 1, label)
            }
        }
    }

    private fun computeRecentHeight(recents: List<RecentFileEntry>, totalRows: Int): Int {
        if (recents.isEmpty()) return 0
        val needed = recents.size + 1 // header + items
        return needed.coerceAtMost(totalRows)
    }
}
