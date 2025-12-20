package editor.ui

import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class HelpView(styleSheet: StyleSheet) : BaseComponent(styleSheet) {
    private var scrollOffset: Int = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val style = styleSheet.getStyle("main-area").withDefaults()
        val lines = shortcutLines()
        val maxOffset = (lines.size - rows).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxOffset)

        canvas.withStyle(style) {
            drawRect(0, 0, cols, rows)
            val visible = lines.drop(scrollOffset).take(rows)
            visible.forEachIndexed { idx, line ->
                drawText(0, idx, line.take(cols))
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val lines = shortcutLines()
        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val maxOffset = (lines.size - (event.rows ?: 0)).coerceAtLeast(0)
                val prev = scrollOffset
                scrollOffset = (scrollOffset - delta).coerceIn(0, maxOffset)
                return scrollOffset != prev
            }
            "key_down" -> {
                val key = event.key?.lowercase() ?: return false
                val rows = event.rows ?: return false
                val maxOffset = (lines.size - rows).coerceAtLeast(0)
                val prev = scrollOffset
                when (key) {
                    "up" -> scrollOffset = (scrollOffset - 1).coerceAtLeast(0)
                    "down" -> scrollOffset = (scrollOffset + 1).coerceAtMost(maxOffset)
                    "pageup" -> scrollOffset = (scrollOffset - rows).coerceAtLeast(0)
                    "pagedown" -> scrollOffset = (scrollOffset + rows).coerceAtMost(maxOffset)
                    "home" -> scrollOffset = 0
                    "end" -> scrollOffset = maxOffset
                    else -> return false
                }
                return scrollOffset != prev
            }
        }
        return false
    }

    companion object {
        fun shortcutLines(): List<String> {
            return listOf(
                "Global:",
                "  Ctrl+Q              Quit",
                "  Ctrl+T              Shell escape",
                "  Alt+F               Project-wide search",
                "  Ctrl+F              Find in file",
                "",
                "Code editor:",
                "  Ctrl+S              Save file",
                "  Ctrl+Space          Suggestions",
                "  Ctrl+Click          Go to definition",
                "  Ctrl+Click (def)    Show usages list",
                "  Alt+Scroll          Scroll 3x faster",
                "  Alt+C/X/V           Copy/Cut/Paste",
                "  Alt+U/R             Undo/Redo",
                "  Ctrl+A              Select all",
                "  Ctrl+Arrows         Jump by word",
                "  Shift+Arrows        Extend selection",
                "  Ctrl+Shift+Arrows   Extend selection by word",
                "  PageUp/PageDown     Page up/down",
                "  Home/End            Line start/end",
                "",
                "Find/Replace bar:",
                "  Enter               Find next",
                "  Ctrl+Enter          Find all",
                "  Ctrl+R              Replace one",
                "  Ctrl+Shift+R        Replace all",
                "  Tab                 Switch field",
                "",
                "Project search dialog:",
                "  Up/Down             Move selection",
                "  PageUp/PageDown     Move page",
                "  Enter               Open selection",
                "  Ctrl+S              Save from embedded editor",
                "",
                "File tree:",
                "  Up/Down             Move selection",
                "  Left/Right          Collapse/Expand",
                "  Enter               Open file / toggle folder",
                "",
                "Workspace picker:",
                "  Up/Down             Move selection",
                "  PageUp/PageDown     Move page",
                "  Home/End            Jump to start/end",
                "  Left/Right          Collapse/Expand",
                "  Enter               Confirm selection",
                "",
                "Hex viewer:",
                "  Arrows              Move cursor",
                "  Home/End            Row start/end",
                "  Alt+C/X/V           Copy/Cut/Paste",
                "  Ctrl+A              Select all",
                "",
                "Image viewer:",
                "  Left/Right          Adjust width",
                "",
                "Settings (DB focus):",
                "  S                   Start/Stop DB",
                "  R                   Restart DB",
                "  C                   Clear index"
            )
        }
    }
}
