package editor.database.ui

import editor.ui.FormFieldRenderer
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class SqlSessionSaveDialog(
    styleSheet: StyleSheet,
    defaultName: String,
    private val onSave: (String) -> Unit,
    private val onDismiss: () -> Unit
) : BaseComponent(styleSheet) {
    private var value = defaultName
    private var cursor = value.length
    private var focus = 0 // 0 = filename, 1 = save, 2 = cancel
    private var fieldRange: IntRange = IntRange.EMPTY
    private var saveRange: IntRange = IntRange.EMPTY
    private var cancelRange: IntRange = IntRange.EMPTY
    private var buttonRow = -1

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = minOf(cols, 72).coerceAtLeast(34)
        val height = 8.coerceAtMost(rows).coerceAtLeast(6)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = ((rows - height) / 2).coerceAtLeast(0)
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(panel.fg, panel.bg)
        val button = styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)
        canvas.withStyle(panel) { drawRect(x, y, width, height) }
        drawBorder(canvas, border, x, y, width, height)
        canvas.withStyle(panel) {
            drawText(x + 2, y + 1, "Save SQL session".take(width - 4))
            drawText(x + 2, y + 2, "File name or path:".take(width - 4))
        }
        val fieldX = x + 2
        val fieldY = y + 3
        val fieldWidth = width - 4
        fieldRange = fieldX until (fieldX + fieldWidth)
        FormFieldRenderer.draw(canvas, styleSheet, fieldX, fieldY, fieldWidth, value, cursor, focus == 0)
        buttonRow = y + height - 2
        val saveLabel = " Save "
        val cancelLabel = " Cancel "
        val buttonX = x + width - saveLabel.length - cancelLabel.length - 3
        saveRange = buttonX until buttonX + saveLabel.length
        cancelRange = (buttonX + saveLabel.length + 1) until (buttonX + saveLabel.length + 1 + cancelLabel.length)
        canvas.withStyle(if (focus == 1) button else panel) { drawText(buttonX, buttonRow, saveLabel) }
        canvas.withStyle(if (focus == 2) button else panel) { drawText(cancelRange.first, buttonRow, cancelLabel) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            when {
                y == buttonRow && x in saveRange -> { focus = 1; save() }
                y == buttonRow && x in cancelRange -> { focus = 2; onDismiss() }
                y == buttonRow -> Unit
                x in fieldRange -> {
                    focus = 0
                    cursor = (x - fieldRange.first).coerceIn(0, value.length)
                }
            }
            return true
        }
        if (event.kind != "key_down") return true
        val key = event.key?.lowercase() ?: return true
        when (key) {
            "escape", "esc" -> onDismiss()
            "tab", "down" -> focus = (focus + 1) % 3
            "up" -> focus = (focus + 2) % 3
            "enter", "return" -> when (focus) {
                0, 1 -> save()
                else -> onDismiss()
            }
            "backspace" -> if (focus == 0 && cursor > 0) {
                value = value.removeRange(cursor - 1, cursor)
                cursor--
            }
            "delete" -> if (focus == 0 && cursor < value.length) value = value.removeRange(cursor, cursor + 1)
            "home" -> if (focus == 0) cursor = 0
            "end" -> if (focus == 0) cursor = value.length
            "left" -> if (focus == 0) cursor = (cursor - 1).coerceAtLeast(0) else if (focus == 2) focus = 1
            "right" -> if (focus == 0) cursor = (cursor + 1).coerceAtMost(value.length) else if (focus == 1) focus = 2
            else -> if (focus == 0 && !event.ctrl && !event.alt && !event.meta && event.key?.length == 1) {
                value = value.substring(0, cursor) + event.key + value.substring(cursor)
                cursor++
            }
        }
        return true
    }

    private fun save() {
        if (value.isNotBlank()) onSave(value.trim())
    }

    private fun drawBorder(canvas: CanvasRenderer, style: StyleSet, x: Int, y: Int, width: Int, height: Int) {
        val right = x + width - 1
        val bottom = y + height - 1
        canvas.withStyle(style) {
            for (px in x..right) {
                drawText(px, y, if (px == x || px == right) "+" else "-")
                drawText(px, bottom, if (px == x || px == right) "+" else "-")
            }
            for (py in (y + 1) until bottom) {
                drawText(x, py, "|")
                drawText(right, py, "|")
            }
        }
    }
}
