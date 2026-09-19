package editor.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

/** Geometry and shared chrome for modal surfaces in a component viewport. */
data class ModalDialogBounds(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
) {
    val right: Int get() = x + width - 1
    val bottom: Int get() = y + height - 1
    val contentX: Int get() = x + 2
    val contentWidth: Int get() = (width - 4).coerceAtLeast(1)
}

class ModalDialogFrame(private val styleSheet: StyleSheet) {
    fun bounds(canvas: CanvasRenderer, preferredWidth: Int, preferredHeight: Int): ModalDialogBounds {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = preferredWidth.coerceAtMost(cols).coerceAtLeast(1)
        val height = preferredHeight.coerceAtMost(rows).coerceAtLeast(1)
        return ModalDialogBounds(
            x = ((cols - width) / 2).coerceAtLeast(0),
            y = ((rows - height) / 2).coerceAtLeast(0),
            width = width,
            height = height
        )
    }

    fun render(canvas: CanvasRenderer, bounds: ModalDialogBounds, title: String) {
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(panel.fg, panel.bg)
        canvas.withStyle(panel) { drawRect(bounds.x, bounds.y, bounds.width, bounds.height) }
        canvas.withStyle(border) {
            if (bounds.width == 1 || bounds.height == 1) {
                drawText(bounds.x, bounds.y, title.take(bounds.width))
                return@withStyle
            }
            for (x in bounds.x..bounds.right) {
                drawText(x, bounds.y, if (x == bounds.x || x == bounds.right) "+" else "-")
                drawText(x, bounds.bottom, if (x == bounds.x || x == bounds.right) "+" else "-")
            }
            for (y in (bounds.y + 1) until bounds.bottom) {
                drawText(bounds.x, y, "|")
                drawText(bounds.right, y, "|")
            }
            canvas.withStyle(panel) {
                drawText(bounds.contentX, bounds.y, (" $title ").take(bounds.contentWidth))
            }
        }
    }

    fun panelStyle(): StyleSet = styleSheet.getStyle("project-search-dialog").withDefaults()

    fun buttonStyle(): StyleSet {
        val panel = panelStyle()
        return styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)
    }
}

/**
 * Standard modal for one-line text values such as names, paths, and filenames.
 * Focus order is explicit and stable: field, OK, Cancel.
 */
open class ModalTextInputDialog(
    styleSheet: StyleSheet,
    val title: String,
    private val label: String,
    initial: String,
    private val onConfirm: ((String) -> Unit)? = null,
    private val onDismiss: () -> Unit = {},
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private val frame = ModalDialogFrame(styleSheet)
    private var value = initial
    private var cursor = value.length
    private var focus = 0 // 0 = field, 1 = OK, 2 = Cancel
    private var fieldRange: IntRange = IntRange.EMPTY
    private var fieldRow = -1
    private var okRange: IntRange = IntRange.EMPTY
    private var cancelRange: IntRange = IntRange.EMPTY
    private var buttonRow = -1

    var result: String? = null
        private set
    var finished: Boolean = false
        private set

    override fun render(canvas: CanvasRenderer) {
        val width = minOf(canvas.cols().coerceAtLeast(1), maxOf(48, label.length + 30))
        val bounds = frame.bounds(canvas, width, 9)
        val panel = frame.panelStyle()
        val button = frame.buttonStyle()
        frame.render(canvas, bounds, title)

        val fieldRow = bounds.y + 3
        this.fieldRow = fieldRow
        canvas.withStyle(panel) {
            drawText(bounds.contentX, fieldRow, ("$label: ").take(bounds.contentWidth))
        }
        val fieldX = bounds.contentX + label.length + 2
        val fieldWidth = (bounds.right - fieldX - 1).coerceAtLeast(3)
        fieldRange = FormFieldRenderer.draw(
            canvas = canvas,
            styleSheet = styleSheet,
            x = fieldX,
            y = fieldRow,
            width = fieldWidth,
            value = value,
            cursor = cursor,
            focused = focus == 0
        )

        buttonRow = bounds.bottom - 2
        val okLabel = " OK "
        val cancelLabel = " Cancel "
        val buttonX = bounds.right - okLabel.length - cancelLabel.length - 1
        okRange = buttonX until buttonX + okLabel.length
        cancelRange = (okRange.last + 1) until (okRange.last + 1 + cancelLabel.length)
        canvas.withStyle(if (focus == 1) button else panel) { drawText(okRange.first, buttonRow, okLabel) }
        canvas.withStyle(if (focus == 2) button else panel) { drawText(cancelRange.first, buttonRow, cancelLabel) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            when {
                y == buttonRow && x in okRange -> finish(true)
                y == buttonRow && x in cancelRange -> finish(false)
                y == fieldRow && x in fieldRange -> {
                    focus = 0
                    cursor = cursorAt(x)
                }
            }
            onInvalidate()
            return true
        }
        if (event.kind != "key_down") return true
        when (event.key?.lowercase()) {
            "escape", "esc" -> finish(false)
            "tab", "down" -> focus = (focus + 1) % 3
            "up" -> focus = (focus + 2) % 3
            "left" -> when (focus) {
                0 -> cursor = (cursor - 1).coerceAtLeast(0)
                2 -> focus = 1
            }
            "right" -> when (focus) {
                0 -> cursor = (cursor + 1).coerceAtMost(value.length)
                1 -> focus = 2
            }
            "enter", "return" -> finish(focus != 2)
            "backspace" -> if (focus == 0 && cursor > 0) {
                value = value.removeRange(cursor - 1, cursor)
                cursor--
            }
            "delete" -> if (focus == 0 && cursor < value.length) value = value.removeRange(cursor, cursor + 1)
            "home" -> if (focus == 0) cursor = 0
            "end" -> if (focus == 0) cursor = value.length
            else -> if (focus == 0 && !event.ctrl && !event.alt && !event.meta && event.key?.length == 1) {
                value = value.substring(0, cursor) + event.key + value.substring(cursor)
                cursor++
            }
        }
        onInvalidate()
        return true
    }

    private fun finish(confirm: Boolean) {
        finished = true
        if (confirm) {
            result = value.trim()
            onConfirm?.invoke(result.orEmpty())
        } else {
            result = null
            onDismiss()
        }
    }

    private fun cursorAt(x: Int): Int = (cursorWindowStart() + (x - fieldRange.first)).coerceIn(0, value.length)

    private fun cursorWindowStart(): Int = (cursor - fieldRange.count().coerceAtLeast(1) + 1).coerceAtLeast(0)

}
