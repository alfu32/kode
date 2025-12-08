package editor.ui

import editor.lib.TerminalSession
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File

class TerminalView(
    styleSheet: StyleSheet,
    workingDir: File = File(".")
) : BaseComponent(styleSheet) {

    private val session = TerminalSession(workingDir)
    private var lastCols = 0
    private var lastRows = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        if (cols != lastCols || rows != lastRows) {
            session.resize(cols, rows)
            lastCols = cols
            lastRows = rows
        }
        val style = styleSheet.getStyle("code-editor")
        val cursorStyle = styleSheet.getStyle("code-editor:cursor")
        canvas.withStyle(style) {
            drawRect(0, 0, cols, rows)
            val (lines, cursor) = session.snapshot(rows)
            lines.forEachIndexed { idx, line ->
                if (idx >= rows) return@forEachIndexed
                drawText(0, idx, line.padEnd(cols, ' ').take(cols))
            }
            val (cx, cy) = cursor
            if (cy in 0 until rows && cx in 0 until cols) {
                val ch = lines.getOrNull(cy)?.getOrNull(cx) ?: ' '
                withStyle(cursorStyle) {
                    drawText(cx, cy, ch.toString())
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "key_down") {
            val key = event.key ?: return false
            session.handleKey(key, ctrl = event.ctrl, alt = event.alt, shift = event.shift)
            return true
        }
        return false
    }
}
