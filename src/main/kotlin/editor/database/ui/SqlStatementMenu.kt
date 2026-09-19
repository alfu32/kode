package editor.database.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class SqlStatementMenu(
    styleSheet: StyleSheet,
    private val onStatement: () -> Unit,
    private val onScript: () -> Unit,
    private val onDismiss: () -> Unit,
    private val anchorX: Int? = null,
    private val anchorY: Int? = null
) : BaseComponent(styleSheet) {
    private var x = 0
    private var y = 0
    private var width = 0
    private var statementRange: IntRange = IntRange.EMPTY
    private var scriptRange: IntRange = IntRange.EMPTY
    private var cancelRange: IntRange = IntRange.EMPTY
    private var cancelY = -1

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        width = minOf(cols, 42).coerceAtLeast(1)
        val height = 7.coerceAtMost(rows).coerceAtLeast(5)
        x = (anchorX ?: (cols - width) / 2).coerceIn(0, (cols - width).coerceAtLeast(0))
        val below = (anchorY ?: 0) + 1
        y = if (below + height <= rows) below else ((anchorY ?: rows) - height).coerceAtLeast(0)
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(panel.fg, panel.bg)
        val button = styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)
        canvas.withStyle(panel) { drawRect(x, y, width, height) }
        drawBorder(canvas, border, x, y, width, height)
        canvas.withStyle(panel) {
            drawText(x + 2, y + 1, "Execute SQL".take(width - 4))
        }
        val a = " Execute "
        val b = " Execute entire script "
        statementRange = (x + 2) until (x + 2 + a.length)
        scriptRange = (x + 2) until (x + 2 + b.length)
        canvas.withStyle(button) { drawText(x + 2, y + 2, a.take(width - 4)) }
        canvas.withStyle(button) { drawText(x + 2, y + 3, b.take(width - 4)) }
        val cancel = " Cancel "
        cancelRange = (x + width - cancel.length - 2) until (x + width - 2)
        cancelY = y + height - 2
        canvas.withStyle(button) { drawText(cancelRange.first, cancelY, cancel) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "mouse_down") {
            val mx = event.x ?: return true
            val my = event.y ?: return true
            when {
                my == y + 2 && mx in statementRange -> onStatement()
                my == y + 3 && mx in scriptRange -> onScript()
                my == cancelY && mx in cancelRange -> onDismiss()
            }
            return true
        }
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> onDismiss()
                "enter", "return" -> onStatement()
            }
            return true
        }
        return true
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
