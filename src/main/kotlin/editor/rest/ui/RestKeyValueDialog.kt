package editor.rest.ui

import react.BaseComponent
import react.StyleSet
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
    private val variableMode = variableMode
    private val fields = if (variableMode) {
        arrayOf(Field(key), Field(value), Field(variableType), Field(description), Field(variableEnabled.toString()))
    } else {
        arrayOf(Field(key), Field(value), Field(thirdValue))
    }
    private var focus = 0
    var result: RestEntryValue? = null
        private set
    var finished = false
        private set

    private class Field(initial: String) {
        var value = initial
        var cursor = initial.length
        fun edit(event: UIEvent): Boolean {
            val key = event.key ?: return false
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
        val width = minOf(cols, 76).coerceAtLeast(36)
        val height = if (variableMode) 12 else 10
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = ((rows - height) / 2).coerceAtLeast(1)
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(panel.fg, panel.bg)
        val button = styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)
        canvas.withStyle(panel) { drawRect(x, y, width, height) }
        canvas.withStyle(panel) { drawText(x + 2, y + 1, title.take(width - 4)) }
        val labels = if (variableMode) listOf("Key", "Value", "Type", "Description", "Enabled true/false") else listOf("Key", "Value", thirdLabel)
        labels.forEachIndexed { index, label ->
            val field = fields[index]
            val text = label + ": " + field.value
            canvas.withStyle(if (focus == index) active else panel) { drawText(x + 2, y + 3 + index, text.take(width - 4).padEnd(width - 4, ' ')) }
        }
        canvas.withStyle(button) { drawText(x + width - 19, y + height - 2, "[OK] [Cancel]") }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val row = (event.y ?: 0)
            val height = if (variableMode) 12 else 10
            val base = ((event.rows ?: 0) - height) / 2 + 3
            if (row in base until base + fields.size) focus = row - base
            else if (row >= base + fields.size + 2) finish(true)
            onInvalidate()
            return true
        }
        if (event.kind != "key_down") return true
        when (event.key?.lowercase()) {
            "escape", "esc" -> finish(false)
            "tab", "down" -> focus = (focus + 1) % fields.size
            "up" -> focus = (focus - 1 + fields.size) % fields.size
            "enter", "return" -> finish(true)
            else -> fields[focus].edit(event)
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
