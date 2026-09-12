package editor.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

data class RescanProgress(
    val status: String,
    val file: String,
    val done: Boolean,
    val aborted: Boolean = false
)

/** Modal progress surface used for an explicit, user-triggered project rescan. */
class RescanProgressDialog(
    styleSheet: StyleSheet,
    private val progressProvider: () -> RescanProgress,
    private val onAbort: () -> Unit,
    private val onDismiss: () -> Unit
) : BaseComponent(styleSheet) {
    private var cancelHit: IntRange = IntRange.EMPTY
    private var dismissHit: IntRange = IntRange.EMPTY
    private var buttonRow: Int = -1

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val progress = progressProvider()
        val width = minOf(cols, 72).coerceAtLeast(28)
        val height = minOf(rows, 9).coerceAtLeast(7)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = ((rows - height) / 2).coerceAtLeast(0)
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)
        val text = styleSheet.getStyle("content").withDefaults(panel.fg, panel.bg)

        cancelHit = IntRange.EMPTY
        dismissHit = IntRange.EMPTY
        buttonRow = y + height - 2
        canvas.withStyle(panel) {
            drawRect(x, y, width, height)
            drawText(x + 2, y + 1, "Project rescan".take(width - 4))
            drawText(x + 2, y + 3, progress.status.take(width - 4))
            drawText(x + 2, y + 4, progress.file.take(width - 4))
        }
        val label = if (progress.done) " [close (Enter)] " else " [abort (Esc)] "
        val buttonX = x + width - label.length - 2
        canvas.withStyle(button) {
            drawText(buttonX, buttonRow, label)
        }
        if (progress.done) dismissHit = buttonX until (buttonX + label.length)
        else cancelHit = buttonX until (buttonX + label.length)
        canvas.withStyle(text) {
            drawText(x + 2, y + height - 2, " ".repeat((buttonX - x - 2).coerceAtLeast(0)))
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val progress = progressProvider()
        if (event.kind == "animation_frame") return true
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> {
                    if (progress.done) onDismiss() else onAbort()
                    return true
                }
                "enter", "return" -> {
                    if (progress.done) onDismiss()
                    return true
                }
            }
            return true
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            if (y == buttonRow && (x in cancelHit || x in dismissHit)) {
                if (progress.done) onDismiss() else onAbort()
            }
            return true
        }
        return true
    }
}
