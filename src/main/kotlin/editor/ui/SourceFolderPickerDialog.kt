package editor.ui

import editor.lib.FileTree
import editor.lib.FileTreeEntry
import editor.lib.IFileTree
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.nio.file.Path
import kotlin.math.max

class SourceFolderPickerDialog(
    styleSheet: StyleSheet,
    projectRoot: Path,
    private val onConfirm: (Path) -> Unit,
    private val onDismiss: () -> Unit,
    private val foldersOnly: Boolean = true,
    private val title: String = "Select source folder",
) : BaseComponent(styleSheet) {

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Int, py: Int): Boolean =
            px in x until (x + width) && py in y until (y + height)
    }

    private val rootPath: Path = projectRoot.toAbsolutePath().normalize()
    private var tree: IFileTree = FileTree.newFileTree(rootPath.toString())
    private var selectedPath: String = rootPath.toString()
    private var scrollOffset: Int = 0
    private var contentHeight: Int = 0
    private var bounds: Bounds = Bounds(0, 0, 0, 0)
    private val headerRows = 2
    private var needsFocus = true

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val dialogWidth = max(20, (cols * 0.7).toInt()).coerceAtMost(cols)
        val dialogHeight = max(6, (rows * 0.7).toInt()).coerceAtMost(rows)
        val startX = ((cols - dialogWidth) / 2).coerceAtLeast(0)
        val startY = ((rows - dialogHeight) / 2).coerceAtLeast(0)
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(style.fg, style.bg)

        contentHeight = (dialogHeight - 4).coerceAtLeast(1)
        bounds = Bounds(startX, startY, dialogWidth, dialogHeight)

        if (needsFocus) {
            focusSelection(contentHeight)
            needsFocus = false
        }

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

        renderHeader(canvas, startX + 1, startY + 1, dialogWidth - 2)
        renderTree(canvas, startX + 1, startY + 2, dialogWidth - 2, contentHeight)
        renderFooter(canvas, startX + 1, startY + dialogHeight - 2, dialogWidth - 2)
    }

    override fun dispatch(event: UIEvent): Boolean {
        when (event.kind) {
            "mouse_scroll" -> {
                val ex = event.x ?: return false
                val ey = event.y ?: return false
                if (!bounds.contains(ex, ey)) return false
                val localY = ey - bounds.y
                if (localY < headerRows || localY >= headerRows + contentHeight) return false
                val entries = directoryEntries()
                val visible = (contentHeight - 1).coerceAtLeast(0)
                val delta = event.scrollDelta ?: 0
                val maxOffset = (entries.size - visible).coerceAtLeast(0)
                val prev = scrollOffset
                scrollOffset = (scrollOffset - delta).coerceIn(0, maxOffset)
                return scrollOffset != prev
            }
            "mouse_down" -> {
                val ex = event.x ?: return false
                val ey = event.y ?: return false
                if (!bounds.contains(ex, ey)) return false
                val y = ey - bounds.y
                val treeStart = headerRows
                if (y < treeStart || y >= treeStart + contentHeight) return false
                val entries = directoryEntries()
                val visible = (contentHeight - 1).coerceAtLeast(0)
                val idx = scrollOffset + (y - treeStart)
                val entry = entries.getOrNull(idx) ?: return false
                selectedPath = entry.fullPath
                if (entry.typ == "folder") {
                    tree.toggle(entry.fullPath)
                }
                scrollOffset = scrollOffset.coerceIn(0, (entries.size - visible).coerceAtLeast(0))
                return true
            }
            "key_down" -> {
                val key = event.key?.lowercase() ?: return false
                val entries = directoryEntries()
                val currentIdx = entries.indexOfFirst { it.fullPath == selectedPath }.coerceAtLeast(0)
                return when (key) {
                    "up" -> moveSelection(entries, currentIdx, -1)
                    "down" -> moveSelection(entries, currentIdx, 1)
                    "pageup" -> moveSelection(entries, currentIdx, -contentHeight)
                    "pagedown" -> moveSelection(entries, currentIdx, contentHeight)
                    "home" -> moveSelection(entries, currentIdx, -currentIdx)
                    "end" -> moveSelection(entries, currentIdx, entries.lastIndex - currentIdx)
                    "left" -> {
                        entries.getOrNull(currentIdx)?.takeIf { it.typ == "folder" }?.let {
                            tree.toggle(it.fullPath)
                            return true
                        }
                        false
                    }
                    "right" -> {
                        entries.getOrNull(currentIdx)?.takeIf { it.typ == "folder" }?.let {
                            tree.toggle(it.fullPath)
                            return true
                        }
                        false
                    }
                    "enter" -> {
                        onConfirm(Path.of(selectedPath))
                        onDismiss()
                        true
                    }
                    "escape" -> {
                        onDismiss()
                        true
                    }
                    else -> false
                }
            }
        }
        return false
    }

    private fun renderHeader(canvas: CanvasRenderer, x: Int, y: Int, width: Int) {
        val hint = "Enter: select   Esc: cancel"
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        canvas.withStyle(style) {
            drawText(x, y, title.take(width).padEnd(width, ' '))
            drawText(x, y + 1, hint.take(width).padEnd(width, ' '))
        }
    }

    private fun renderTree(canvas: CanvasRenderer, x: Int, y: Int, width: Int, height: Int) {
        if (height <= 0) return
        val entries = directoryEntries()
        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        val visibleRows = (height - 1).coerceAtLeast(0)
        val visibleEntries = entries.drop(scrollOffset).take(visibleRows)
        visibleEntries.forEachIndexed { idx, entry ->
            val rowY = y + idx
            val isSelected = entry.fullPath == selectedPath
            val style = if (isSelected) selectedStyle else lineStyle
            canvas.withStyle(style) {
                val indent = "  ".repeat(entry.padding)
                val prefix = when {
                    entry.typ != "folder" -> "[=] "
                    entry.isOpen -> "[-] "
                    else -> "[+] "
                }
                val name = entry.name
                val line = (indent + prefix + name).take(width).padEnd(width, ' ')
                drawText(x, rowY, line)
            }
        }
    }

    private fun renderFooter(canvas: CanvasRenderer, x: Int, y: Int, width: Int) {
        val style = styleSheet.getStyle("file-entry")
        val label = selectedPath.take(width - "Selected: ".length)
            .padEnd((width - "Selected: ".length).coerceAtLeast(0), ' ')
        canvas.withStyle(style) {
            val text = "Selected: $label".take(width).padEnd(width, ' ')
            drawText(x, y, text)
        }
    }

    private fun moveSelection(entries: List<FileTreeEntry>, currentIdx: Int, delta: Int): Boolean {
        if (entries.isEmpty()) return false
        val targetIdx = (currentIdx + delta).coerceIn(entries.indices)
        if (targetIdx == currentIdx) return false
        val target = entries[targetIdx]
        selectedPath = target.fullPath
        val visible = (contentHeight - 1).coerceAtLeast(0)
        if (targetIdx < scrollOffset) {
            scrollOffset = targetIdx
        } else if (targetIdx >= scrollOffset + visible) {
            scrollOffset = (targetIdx - visible + 1).coerceAtLeast(0)
        }
        return true
    }

    private fun directoryEntries(): List<FileTreeEntry> {
        val rootEntry = FileTreeEntry(
            name = rootPath.fileName?.toString() ?: rootPath.toString(),
            typ = "folder",
            padding = 0,
            fullPath = tree.root,
            isOpen = true
        )
        val children = tree.flattened()
            .filter { !foldersOnly || it.typ == "folder" }
            .map { it.copy(padding = it.padding + 1) }
        return listOf(rootEntry) + children
    }

    private fun focusSelection(visibleRows: Int) {
        tree.openPath(selectedPath)
        val entries = directoryEntries()
        val idx = entries.indexOfFirst { it.fullPath == selectedPath }.coerceAtLeast(0)
        val usableRows = (visibleRows - 1).coerceAtLeast(0)
        scrollOffset = when {
            idx < usableRows -> 0
            else -> (idx - usableRows + 1).coerceAtLeast(0)
        }
    }
}
