package editor.ui

import editor.lib.FileTree
import editor.lib.FileTreeEntry
import editor.lib.IFileTree
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class FileTreeView(
    styleSheet: StyleSheet,
    private val tree: IFileTree = FileTree.newFileTree(System.getProperty("user.dir")),
    private val onSelect: (FileTreeEntry, String?) -> Unit = { _, _ -> }
) : BaseComponent(styleSheet) {

    private var selectedPath: String? = null
    private var scrollOffset: Int = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(0)

        tree.refreshOpenNodes()
        val entries = tree.flattened()

        val maxOffset = (entries.size - rows).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)

        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")

        val visible = entries.drop(scrollOffset).take(rows)
        visible.forEachIndexed { idx, entry ->
            val isSelected = entry.fullPath == selectedPath
            val style = if (isSelected) selectedStyle else lineStyle
            canvas.withStyle(style) {
                val prefix = when {
                    entry.typ == "folder" && entry.isOpen -> "[-] "
                    entry.typ == "folder" -> "[+] "
                    else -> "[=] "
                }
                val indent = "  ".repeat(entry.padding)
                val text = (indent + prefix + entry.name).take(cols)
                drawText(0, idx, text.padEnd(cols, ' '))
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val entries = tree.flattened()
        if (entries.isEmpty()) return false
        val rows = (event.rows ?: 0).coerceAtLeast(0)
        val visibleCount = rows.coerceAtLeast(0)

        return when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: 0
                if (delta == 0) return false
                val maxOffset = (entries.size - visibleCount).coerceAtLeast(0)
                val prev = scrollOffset
                scrollOffset = (scrollOffset - delta).coerceIn(0, maxOffset)
                scrollOffset != prev
            }

            "mouse_down" -> {
                val y = event.y ?: return false
                val targetIdx = scrollOffset + y
                if (targetIdx !in entries.indices) return false
                val entry = entries[targetIdx]
                selectedPath = entry.fullPath
                if (entry.typ == "folder") {
                    tree.toggle(entry.fullPath)
                } else {
                    onSelect(entry, entry.detectType())
                }
                ensureSelectionVisible(entries, visibleCount)
                true
            }

            "key_down" -> handleKeys(event, entries, visibleCount)

            else -> false
        }
    }

    private fun handleKeys(event: UIEvent, entries: List<FileTreeEntry>, visibleCount: Int): Boolean {
        val key = event.key?.lowercase() ?: return false
        if (entries.isEmpty()) return false

        var currentIndex = selectedIndex(entries).coerceAtLeast(0)

        when (key) {
            "up" -> {
                if (currentIndex > 0) currentIndex--
                selectedPath = entries[currentIndex].fullPath
            }
            "down" -> {
                if (currentIndex < entries.lastIndex) currentIndex++
                selectedPath = entries[currentIndex].fullPath
            }
            "left" -> {
                val current = entries[currentIndex]
                if (current.typ == "folder" && current.isOpen) {
                    tree.toggle(current.fullPath)
                }
            }
            "right", "enter" -> {
                val current = entries[currentIndex]
                if (current.typ == "folder") {
                    tree.toggle(current.fullPath)
                } else {
                    onSelect(current, current.detectType())
                }
            }
            else -> return false
        }

        ensureSelectionVisible(entries, visibleCount)
        return true
    }

    private fun selectedIndex(entries: List<FileTreeEntry>): Int {
        val sel = selectedPath
        if (sel == null) return 0
        val idx = entries.indexOfFirst { it.fullPath == sel }
        return if (idx >= 0) idx else 0
    }

    private fun ensureSelectionVisible(entries: List<FileTreeEntry>, visibleCount: Int) {
        val idx = selectedIndex(entries)
        val maxOffset = (entries.size - visibleCount).coerceAtLeast(0)
        if (idx < scrollOffset) {
            scrollOffset = idx
        } else if (idx >= scrollOffset + visibleCount) {
            scrollOffset = (idx - visibleCount + 1).coerceIn(0, maxOffset)
        }
    }
}
