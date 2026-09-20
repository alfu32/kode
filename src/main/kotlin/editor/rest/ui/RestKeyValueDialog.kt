package editor.rest.ui

import editor.ui.FormFieldRenderer
import editor.ui.ModalDialogFrame
import editor.lib.SystemClipboard
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

data class RestEntryValue(
    val key: String,
    val value: String,
    val description: String,
    val type: String = "string",
    val enabled: Boolean = true
)

class RestKeyValueDialog(
    styleSheet: StyleSheet,
    private val title: String,
    key: String,
    value: String,
    description: String,
    private val onInvalidate: () -> Unit,
    private val thirdLabel: String = "Description",
    thirdValue: String = description,
    variableMode: Boolean = false,
    variableType: String = "string",
    variableEnabled: Boolean = true
) : BaseComponent(styleSheet) {
    private val frame = ModalDialogFrame(styleSheet)
    private val variableMode = variableMode
    private val fields = if (variableMode) {
        arrayOf(Field(key), Field(value), Field(variableType), Field(description), Field(variableEnabled.toString()))
    } else {
        arrayOf(Field(key), Field(value), Field(thirdValue))
    }
    private var focus = 0
    private var fieldRanges: List<IntRange> = emptyList()
    private var okRange: IntRange = IntRange.EMPTY
    private var cancelRange: IntRange = IntRange.EMPTY
    private var firstFieldRow = -1
    private var buttonRow = -1
    var result: RestEntryValue? = null
        private set
    var finished = false
        private set

    private class Field(initial: String) {
        var value = initial
        var cursor = initial.length
        fun edit(event: UIEvent): Boolean {
            val key = event.key ?: return false
            if (event.ctrl) {
                when (key.lowercase()) {
                    "c" -> { SystemClipboard.setText(value); return true }
                    "x" -> { SystemClipboard.setText(value); value = ""; cursor = 0; return true }
                    "v" -> {
                        val paste = SystemClipboard.getText()
                        value = value.substring(0, cursor) + paste + value.substring(cursor)
                        cursor += paste.length
                        return true
                    }
                }
            }
            when (key.lowercase()) {
                "left" -> cursor = (cursor - 1).coerceAtLeast(0)
                "right" -> cursor = (cursor + 1).coerceAtMost(value.length)
                "backspace" -> if (cursor > 0) { value = value.removeRange(cursor - 1, cursor); cursor-- } else return false
                "delete" -> if (cursor < value.length) value = value.removeRange(cursor, cursor + 1) else return false
                "home" -> cursor = 0
                "end" -> cursor = value.length
                else -> if (!event.ctrl && !event.alt && !event.meta && key.length == 1) {
                    value = value.substring(0, cursor) + key + value.substring(cursor)
                    cursor++
                } else return false
            }
            return true
        }
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val preferredWidth = minOf(cols, 76).coerceAtLeast(36)
        val height = if (variableMode) 12 else 10
        val bounds = frame.bounds(canvas, preferredWidth, height)
        val width = bounds.width
        val x = bounds.x
        val y = bounds.y
        val panel = frame.panelStyle()
        val button = frame.buttonStyle()
        frame.render(canvas, bounds, title)
        firstFieldRow = y + 3
        val labels = if (variableMode) listOf("Key", "Value", "Type", "Description", "Enabled true/false") else listOf("Key", "Value", thirdLabel)
        val labelWidth = labels.maxOf { it.length } + 2
        fieldRanges = labels.mapIndexed { index, label ->
            val field = fields[index]
            val row = firstFieldRow + index
            canvas.withStyle(panel) { drawText(x + 2, row, (label + ":").padEnd(labelWidth).take(labelWidth)) }
            FormFieldRenderer.draw(
                canvas = canvas,
                styleSheet = styleSheet,
                x = x + 2 + labelWidth,
                y = row,
                width = width - labelWidth - 4,
                value = field.value,
                cursor = field.cursor,
                focused = focus == index
            )
        }
        val okLabel = " OK "
        val cancelLabel = " Cancel "
        val buttonX = x + width - okLabel.length - cancelLabel.length - 1
        buttonRow = bounds.bottom - 2
        okRange = buttonX until buttonX + okLabel.length
        cancelRange = (okRange.last + 1) until (okRange.last + 1 + cancelLabel.length)
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val row = (event.y ?: 0)
            if (row in firstFieldRow until firstFieldRow + fields.size) {
                focus = row - firstFieldRow
                fieldRanges.getOrNull(focus)?.let { range ->
                    if (event.x != null && event.x in range) fields[focus].cursor = (event.x - range.first).coerceIn(0, fields[focus].value.length)
                }
            }
            else if (row == buttonRow && event.x != null && event.x in okRange) {
                focus = fields.size
                finish(true)
            } else if (row == buttonRow && event.x != null && event.x in cancelRange) {
                focus = fields.size + 1
                finish(false)
            }
            onInvalidate()
            return true
        }
        if (event.kind != "key_down") return true
        when (event.key?.lowercase()) {
            "escape", "esc" -> finish(false)
            "tab", "down" -> focus = (focus + 1) % (fields.size + 2)
            "up" -> focus = (focus - 1 + fields.size + 2) % (fields.size + 2)
            "left" -> if (focus == fields.size + 1) focus = fields.size else if (focus < fields.size) fields[focus].edit(event)
            "right" -> if (focus == fields.size) focus = fields.size + 1 else if (focus < fields.size) fields[focus].edit(event)
            "enter", "return" -> if (focus == fields.size + 1) finish(false) else finish(true)
            else -> if (focus < fields.size) fields[focus].edit(event)
        }
        onInvalidate()
        return true
    }

    private fun finish(apply: Boolean) {
        result = if (apply) {
            if (variableMode) RestEntryValue(
                fields[0].value.trim(),
                fields[1].value,
                fields[3].value,
                fields[2].value.ifBlank { "string" },
                fields[4].value.toBooleanStrictOrNull() ?: true
            ) else RestEntryValue(fields[0].value.trim(), fields[1].value, fields[2].value)
        } else null
        finished = true
    }
}
