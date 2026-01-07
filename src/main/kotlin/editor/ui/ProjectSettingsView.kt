package editor.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.nio.file.Path

class ProjectSettingsView(
    styleSheet: StyleSheet,
    private val projectRootProvider: () -> Path,
    private val sourceRootsProvider: () -> List<String>,
    private val onRequestAdd: () -> Unit,
    private val onRemoveSource: (String) -> String?
) : BaseComponent(styleSheet) {

    private var selectedIdx: Int = 0
    private var scrollTop: Int = 0
    private var lastMessage: String = ""
    private val buttonHits = mutableMapOf<Int, List<ButtonHit>>()

    override fun render(canvas: CanvasRenderer) {
        buttonHits.clear()
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val baseStyle = styleSheet.getStyle("content")
        canvas.withStyle(baseStyle) {
            drawRect(0, 0, cols, rows)
        }
        val header = headerLines()
        header.take(rows).forEachIndexed { idx, line ->
            canvas.withStyle(baseStyle) {
                drawText(0, idx, line.take(cols).padEnd(cols, ' '))
            }
        }

        val listStart = header.size
        val listRows = (rows - listStart).coerceAtLeast(0)
        renderSources(canvas, cols, listStart, listRows, baseStyle)
    }

    override fun dispatch(event: UIEvent): Boolean {
        val rows = event.rows ?: 0
        val headerSize = headerLines().size
        val listStart = headerSize
        val listRows = (rows - listStart).coerceAtLeast(0)
        val roots = sourceRootsProvider()
        selectedIdx = if (roots.isEmpty()) 0 else selectedIdx.coerceIn(roots.indices)

        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            val x = event.x ?: return false
            val hit = buttonHits[y]?.firstOrNull { x in it.range }
            if (hit != null) {
                handleAction(hit.action, roots)
                return true
            }
            if (y >= listStart) {
                val idx = scrollTop + (y - listStart)
                if (idx in roots.indices) {
                    selectedIdx = idx
                    ensureSelectionVisible(listRows, roots.size)
                    return true
                }
            }
            return false
        }

        if (event.kind == "mouse_scroll") {
            val delta = event.scrollDelta ?: return false
            val maxScroll = (roots.size - listRows).coerceAtLeast(0)
            val prev = scrollTop
            scrollTop = (scrollTop - delta).coerceIn(0, maxScroll)
            return prev != scrollTop
        }

        if (event.kind != "key_down") return false
        val key = event.key?.lowercase()
        return when (key) {
            "up" -> moveSelection(roots, -1, listRows)
            "down" -> moveSelection(roots, 1, listRows)
            "pageup" -> moveSelection(roots, -listRows, listRows)
            "pagedown" -> moveSelection(roots, listRows, listRows)
            "home" -> moveSelection(roots, -selectedIdx, listRows)
            "end" -> moveSelection(roots, (roots.lastIndex - selectedIdx).coerceAtLeast(0), listRows)
            "a" -> {
                onRequestAdd()
                true
            }
            "d", "backspace", "delete" -> {
                handleRemove(roots)
                true
            }
            else -> false
        }
    }

    fun setMessage(message: String) {
        lastMessage = message
    }

    private fun headerLines(): List<String> {
        val root = projectRootProvider().toString()
        val sources = sourceRootsProvider().size
        val lines = mutableListOf<String>()
        lines += "Project Settings"
        lines += "Root: $root"
        lines += "Source roots: $sources"
        lines += lastMessage
        lines += ""
        return lines
    }

    private fun renderSources(canvas: CanvasRenderer, cols: Int, startRow: Int, rows: Int, baseStyle: StyleSet) {
        if (rows <= 0) return
        val roots = sourceRootsProvider()
        val listStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        val buttonStyle = styleSheet.getStyle("lsp-button")
        val buttonRow = (startRow - 1).coerceAtLeast(0)
        renderButtons(canvas, buttonRow, cols, buttonStyle)
        if (roots.isEmpty()) {
            val msg = "No source roots. Press [add(a)] to include folders."
            canvas.withStyle(baseStyle) {
                drawText(0, startRow, msg.take(cols).padEnd(cols, ' '))
            }
            return
        }
        selectedIdx = if (roots.isEmpty()) 0 else selectedIdx.coerceIn(roots.indices)
        val maxScroll = (roots.size - rows).coerceAtLeast(0)
        scrollTop = scrollTop.coerceIn(0, maxScroll)
        val visible = roots.drop(scrollTop).take(rows)
        visible.forEachIndexed { idx, root ->
            val rowY = startRow + idx
            val absIdx = scrollTop + idx
            val style = if (absIdx == selectedIdx) selectedStyle else listStyle
            val label = formatRoot(root)
            canvas.withStyle(style) {
                drawText(0, rowY, label.take(cols).padEnd(cols, ' '))
            }
        }
    }

    private fun renderButtons(canvas: CanvasRenderer, y: Int, cols: Int, buttonStyle: StyleSet) {
        if (y < 0) return
        val baseStyle = styleSheet.getStyle("content")
        val buttons = listOf(
            "[add(a)]" to ButtonAction.ADD,
            "[remove(d)]" to ButtonAction.REMOVE
        )
        var cursor = 0
        val hits = mutableListOf<ButtonHit>()
        buttons.forEach { (label, action) ->
            val padded = " $label "
            if (cursor + padded.length > cols) return
            canvas.withStyle(buttonStyle) { drawText(cursor, y, padded) }
            hits += ButtonHit(cursor until (cursor + padded.length), action)
            cursor += padded.length + 1
        }
        if (hits.isNotEmpty()) {
            buttonHits[y] = hits
        }
        if (cursor < cols) {
            canvas.withStyle(baseStyle) { drawText(cursor, y, " ".repeat(cols - cursor)) }
        }
    }

    private fun formatRoot(value: String): String =
        when {
            value.isBlank() || value == "." -> "(project root)"
            value.startsWith("./") -> value.removePrefix("./")
            else -> value
        }

    private fun moveSelection(roots: List<String>, delta: Int, listRows: Int): Boolean {
        if (roots.isEmpty()) return false
        val target = (selectedIdx + delta).coerceIn(roots.indices)
        if (target == selectedIdx) return false
        selectedIdx = target
        ensureSelectionVisible(listRows, roots.size)
        return true
    }

    private fun ensureSelectionVisible(listRows: Int, total: Int) {
        if (listRows <= 0) return
        if (selectedIdx < scrollTop) {
            scrollTop = selectedIdx
        } else if (selectedIdx >= scrollTop + listRows) {
            scrollTop = (selectedIdx - listRows + 1).coerceAtLeast(0)
        }
        scrollTop = scrollTop.coerceIn(0, (total - listRows).coerceAtLeast(0))
    }

    private fun handleAction(action: ButtonAction, roots: List<String>) {
        when (action) {
            ButtonAction.ADD -> onRequestAdd()
            ButtonAction.REMOVE -> handleRemove(roots)
        }
    }

    private fun handleRemove(roots: List<String>) {
        val target = roots.getOrNull(selectedIdx) ?: return
        lastMessage = onRemoveSource(target) ?: ""
    }

    private data class ButtonHit(val range: IntRange, val action: ButtonAction)
    private enum class ButtonAction { ADD, REMOVE }
}
