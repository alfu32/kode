package editor.ui

import editor.lib.FileTree
import editor.lib.FileTreeEntry
import editor.lib.IFileTree
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File

class FileTreeView(
    styleSheet: StyleSheet,
    private var tree: IFileTree = FileTree.newFileTree(System.getProperty("user.dir")),
    private val onSelect: (FileTreeEntry, String?) -> Unit = { _, _ -> }
) : BaseComponent(styleSheet) {

    private var selectedPath: String? = null
    private var scrollOffset: Int = 0
    private val buttonAreas = mutableListOf<ButtonArea>()
    private val headerButtons = mutableListOf<ButtonArea>()
    private var renameTarget: String? = null
    private var renameRow: Int = -1
    private val renameInput = InputState()
    private var lastRows: Int = 0

    fun refreshFileTree() {
        tree.refreshOpenNodes()
    }

    fun setTree(newTree: IFileTree) {
        tree = newTree
        selectedPath = null
        scrollOffset = 0
        renameTarget = null
        renameRow = -1
        renameInput.text = ""
        renameInput.cursor = 0
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(0)
        lastRows = rows
        buttonAreas.clear()
        headerButtons.clear()

        tree.refreshOpenNodes()
        val entries = tree.flattened()

        val availableRows = (rows - 1).coerceAtLeast(0) // leave room for header
        val maxOffset = (entries.size - availableRows).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)

        renderHeader(canvas, cols)

        val lineStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        val visible = entries.drop(scrollOffset).take(availableRows)
        visible.forEachIndexed { idx, entry ->
            val rowY = idx + 1
            val isSelected = entry.fullPath == selectedPath
            val style = if (isSelected) selectedStyle else lineStyle
            canvas.withStyle(style) {
                val buttonsText = when (entry.typ) {
                    "folder" -> "[r][+d][+f][-]"
                    else -> "[r][-]"
                }
                val buttonsWidth = buttonsText.length
                val indent = "  ".repeat(entry.padding)
                val prefix = when {
                    entry.typ == "folder" && entry.isOpen -> "[-] "
                    entry.typ == "folder" -> "[+] "
                    else -> "[=] "
                }
                val baseText = indent + prefix
                val buttonStart = (cols - buttonsWidth).coerceAtLeast(baseText.length + 1)
                val nameSpace = (buttonStart - baseText.length).coerceAtLeast(1)
                val name = entry.name.take(nameSpace)
                val lineText = (baseText + name).padEnd(buttonStart, ' ')
                drawText(0, rowY, lineText.take(cols))

                var cursor = buttonStart
                val actions = if (entry.typ == "folder") {
                    listOf(
                        Button(entry.fullPath, ButtonType.RENAME),
                        Button(entry.fullPath, ButtonType.NEW_DIR),
                        Button(entry.fullPath, ButtonType.NEW_FILE),
                        Button(entry.fullPath, ButtonType.DELETE)
                    )
                } else {
                    listOf(
                        Button(entry.fullPath, ButtonType.RENAME),
                        Button(entry.fullPath, ButtonType.DELETE)
                    )
                }
                actions.forEach { btn ->
                    val label = btn.label
                    val start = cursor
                    val end = (cursor + label.length).coerceAtMost(cols)
                    if (start < cols) {
                        drawText(start, rowY, label.take(cols - start))
                        buttonAreas += ButtonArea(rowY, start until end, btn)
                    }
                    cursor += label.length
                }

                if (renameTarget == entry.fullPath && rowY == renameRow) {
                    renderRenameInput(canvas, rowY, baseText.length, nameSpace, cols)
                }
            }
        }
    }

    private fun renderHeader(canvas: CanvasRenderer, cols: Int) {
        val rootPath = tree.root
        val headerStyle = styleSheet.getStyle("file-entry")
        val buttons = listOf(
            Button(rootPath, ButtonType.NEW_DIR),
            Button(rootPath, ButtonType.NEW_FILE)
        )
        val buttonsText = buttons.joinToString("") { it.label }
        val startButtons = (cols - buttonsText.length).coerceAtLeast(0)
        val pathText = rootPath.take(startButtons).padEnd(startButtons, ' ')
        canvas.withStyle(headerStyle) {
            drawText(0, 0, pathText.take(cols))
            var cursor = startButtons
            buttons.forEach { btn ->
                val label = btn.label
                val start = cursor
                val end = (cursor + label.length).coerceAtMost(cols)
                if (start < cols) {
                    drawText(start, 0, label.take(cols - start))
                    headerButtons += ButtonArea(0, start until end, btn)
                }
                cursor += label.length
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val entries = tree.flattened()
        if (entries.isEmpty()) return false
        val rows = (event.rows ?: 0).coerceAtLeast(0)
        val visibleCount = (rows - 1).coerceAtLeast(0)

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
                val x = event.x ?: -1
                if (renameTarget != null && y != renameRow) {
                    cancelRename()
                }
                headerButtons.firstOrNull { it.row == y && it.range.contains(x) }?.let { btn ->
                    handleButton(btn.button)
                    return true
                }
                if (y == 0) return false
                val targetIdx = scrollOffset + (y - 1)
                if (targetIdx !in entries.indices) return false
                val entry = entries[targetIdx]
                selectedPath = entry.fullPath
                val buttonHit = buttonAreas.firstOrNull { it.row == y && it.range.contains(x) }
                if (buttonHit != null) {
                    handleButton(buttonHit.button)
                } else if (renameTarget == entry.fullPath && y == renameRow) {
                    // keep editing
                } else if (entry.typ == "folder") {
                    tree.toggle(entry.fullPath)
                } else {
                    onSelect(entry, entry.detectType())
                }
                if (selectedPath != null) {
                    ensureSelectionVisible(entries, visibleCount)
                }
                true
            }

            "key_down" -> handleKeys(event, entries, visibleCount)

            else -> false
        }
    }

    private fun handleKeys(event: UIEvent, entries: List<FileTreeEntry>, visibleCount: Int): Boolean {
        if (renameTarget != null) {
            val key = event.key ?: event.raw ?: return true
            val lower = key.lowercase()
            if (lower == "enter") {
                performRename()
                return true
            }
            if (key.equals("Escape", ignoreCase = true)) {
                cancelRename()
                return true
            }
            renameInput.handleKey(key, event)
            return true
        }

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

    private fun renderRenameInput(canvas: CanvasRenderer, row: Int, startX: Int, width: Int, cols: Int) {
        val cursor = renameInput.cursor.coerceIn(0, renameInput.text.length)
        val available = width.coerceAtLeast(1)
        val windowStart = (cursor - available + 1).coerceAtLeast(0)
        val visibleText = renameInput.text.substring(windowStart).take(available)
        val padText = visibleText.padEnd(available, ' ')
        val style = styleSheet.getStyle("file-entry:selected")
        val cursorStyle = styleSheet.getStyle("code-search-cursor").withDefaults(style.bg, style.fg)
        canvas.withStyle(style) {
            drawText(startX, row, padText.take(cols - startX))
        }
        val cursorX = startX + (cursor - windowStart).coerceAtLeast(0).coerceAtMost(available - 1)
        if (cursorX < cols) {
            canvas.withStyle(cursorStyle) {
                val ch = padText.getOrElse(cursorX - startX) { ' ' }
                drawText(cursorX, row, ch.toString())
            }
        }
    }

    private fun handleButton(button: Button) {
        when (button.type) {
            ButtonType.NEW_DIR -> createEntry(button.path, isDir = true)
            ButtonType.NEW_FILE -> createEntry(button.path, isDir = false)
            ButtonType.DELETE -> deleteEntry(button.path)
            ButtonType.RENAME -> startRename(button.path)
        }
    }

    private fun startRename(path: String) {
        renameTarget = path
        renameInput.text = File(path).name
        renameInput.cursor = renameInput.text.length
        val entries = tree.flattened()
        val idx = entries.indexOfFirst { it.fullPath == path }
        if (idx >= 0) {
            val availableRows = (lastRows - 1).coerceAtLeast(1)
            val maxOffset = (entries.size - availableRows).coerceAtLeast(0)
            if (idx < scrollOffset) {
                scrollOffset = idx
            } else if (idx >= scrollOffset + availableRows) {
                scrollOffset = (idx - availableRows + 1).coerceIn(0, maxOffset)
            }
            renameRow = 1 + (idx - scrollOffset)
        } else {
            renameRow = -1
        }
    }

    private fun cancelRename() {
        renameTarget = null
        renameRow = -1
    }

    private fun performRename() {
        val target = renameTarget ?: return
        val newName = renameInput.text.trim()
        if (newName.isEmpty()) {
            cancelRename()
            return
        }
        val src = File(target)
        val dest = src.resolveSibling(newName)
        if (dest.path.equals(src.path, ignoreCase = false)) {
            cancelRename()
            return
        }
        runCatching {
            dest.parentFile?.mkdirs()
            src.renameTo(dest)
        }
        selectedPath = dest.path
        cancelRename()
        tree.refreshOpenNodes()
    }

    private fun createEntry(path: String, isDir: Boolean) {
        val baseDir = File(path).let { if (it.isDirectory) it else it.parentFile }
        if (baseDir == null) return
        val baseName = if (isDir) "new-dir" else "new-file.txt"
        val target = uniquePath(baseDir, baseName)
        runCatching {
            if (isDir) target.mkdirs() else target.createNewFile()
        }
        tree.refreshOpenNodes()
        selectedPath = target.path
        startRename(target.path)
    }

    private fun deleteEntry(path: String) {
        val file = File(path)
        runCatching {
            if (file.isDirectory) file.deleteRecursively() else file.delete()
        }
        if (selectedPath == path) selectedPath = null
        tree.refreshOpenNodes()
    }

    private fun uniquePath(baseDir: File, baseName: String): File {
        var idx = 0
        while (true) {
            val candidate = if (idx == 0) File(baseDir, baseName) else File(baseDir, "$baseName-$idx")
            if (!candidate.exists()) return candidate
            idx++
        }
    }

    private fun findRowForPath(path: String): Int {
        val entries = tree.flattened()
        val idx = entries.indexOfFirst { it.fullPath == path }
        if (idx < 0) return -1
        val availableRows = (lastRows - 1).coerceAtLeast(0)
        if (availableRows <= 0) return -1
        if (idx < scrollOffset) scrollOffset = idx
        if (idx >= scrollOffset + availableRows) {
            scrollOffset = (idx - availableRows + 1).coerceAtLeast(0)
        }
        return (idx - scrollOffset) + 1
    }

    private data class ButtonArea(val row: Int, val range: IntRange, val button: Button)

    private data class Button(val path: String, val type: ButtonType) {
        val label: String
            get() = when (type) {
                ButtonType.NEW_DIR -> "[+d]"
                ButtonType.NEW_FILE -> "[+f]"
                ButtonType.DELETE -> "[-]"
                ButtonType.RENAME -> "[r]"
            }
    }

    private enum class ButtonType { NEW_DIR, NEW_FILE, DELETE, RENAME }

    private class InputState(var text: String = "", var cursor: Int = 0) {
        fun handleKey(key: String, ev: UIEvent) {
            val normalized = key.lowercase()
            when (normalized) {
                "left" -> if (cursor > 0) cursor--
                "right" -> if (cursor < text.length) cursor++
                "home" -> cursor = 0
                "end" -> cursor = text.length
                "backspace" -> if (cursor > 0) {
                    text = text.removeRange(cursor - 1, cursor)
                    cursor--
                }
                "delete" -> if (cursor < text.length) {
                    text = text.removeRange(cursor, cursor + 1)
                }
                else -> if (!ev.ctrl && !ev.alt && !ev.meta) {
                    val ch = if (key.length == 1) key else ev.raw?.takeIf { it.length == 1 } ?: return
                    text = text.substring(0, cursor) + ch + text.substring(cursor)
                    cursor++
                }
            }
        }
    }
}
