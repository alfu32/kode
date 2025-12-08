package editor.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import kotlin.math.max

class ProjectSearchDialog(
    styleSheet: StyleSheet,
    private val onDismiss: () -> Unit
) : BaseComponent(styleSheet) {

    private var bounds: Bounds = Bounds(0, 0, 0, 0)

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val dialogWidth = max(10, cols / 2).coerceAtMost(cols)
        val dialogHeight = (rows - 2).coerceAtLeast(4).coerceAtMost(rows)
        val startX = ((cols - dialogWidth) / 2).coerceAtLeast(0)
        val startY = ((rows - dialogHeight) / 2).coerceAtLeast(0)
        val dialogStyle = styleSheet.getStyle("project-search-dialog").withDefaults()
        val borderStyle = styleSheet.getStyle("project-search-dialog-border").withDefaults(dialogStyle.fg, dialogStyle.bg)

        bounds = Bounds(startX, startY, dialogWidth, dialogHeight)

        // Fill background
        canvas.withStyle(dialogStyle) {
            drawRect(startX, startY, dialogWidth, dialogHeight)
        }

        // Simple border hint
        canvas.withStyle(borderStyle) {
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
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val x = event.x ?: return false
            val y = event.y ?: return false
            val inside = bounds.contains(x, y)
            if (!inside) {
                onDismiss()
            }
            return true
        }
        return true // consume other events while dialog is visible
    }

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Int, py: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            return px in x until (x + width) && py in y until (y + height)
        }
    }
}
