package editor.ui

import react.StyleSheet
import react.renderer.CanvasRenderer

/** Draws a bounded single-line form field with an explicit focus and cursor state. */
object FormFieldRenderer {
    fun draw(
        canvas: CanvasRenderer,
        styleSheet: StyleSheet,
        x: Int,
        y: Int,
        width: Int,
        value: String,
        cursor: Int,
        focused: Boolean,
        mask: Boolean = false,
        showCursor: Boolean = focused
    ): IntRange {
        val safeWidth = width.coerceAtLeast(3)
        val innerWidth = (safeWidth - 2).coerceAtLeast(1)
        val displayValue = if (mask) "*".repeat(value.length) else value
        val clampedCursor = cursor.coerceIn(0, displayValue.length)
        val windowStart = (clampedCursor - innerWidth + 1).coerceAtLeast(0)
        val visible = displayValue.substring(windowStart).take(innerWidth).padEnd(innerWidth, ' ')
        val fieldStyle = styleSheet.getStyle("code-search-field").withDefaults()
        val activeStyle = styleSheet.getStyle("code-search-field-active").withDefaults(fieldStyle.fg, fieldStyle.bg)
        val cursorStyle = styleSheet.getStyle("code-search-cursor").withDefaults(
            activeStyle.bg ?: fieldStyle.bg,
            activeStyle.fg ?: fieldStyle.fg
        )
        val style = if (focused) activeStyle else fieldStyle
        canvas.withStyle(style) {
            drawText(x, y, ("[" + visible + "]").take(safeWidth).padEnd(safeWidth, ' '))
        }
        if (showCursor) {
            val cursorX = x + 1 + (clampedCursor - windowStart).coerceIn(0, innerWidth - 1)
            canvas.withStyle(cursorStyle) {
                drawText(cursorX, y, visible.getOrElse(cursorX - x - 1) { ' ' }.toString())
            }
        }
        return (x + 1) until (x + safeWidth - 1)
    }
}
