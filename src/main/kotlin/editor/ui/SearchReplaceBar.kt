package editor.ui

import editor.lib.SearchState
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import kotlin.math.max

class SearchReplaceBar(
    styleSheet: StyleSheet,
    private val onAction: (SearchCommand) -> Unit
) : BaseComponent(styleSheet) {

    enum class Field { FIND, REPLACE }

    sealed class SearchCommand {
        data class Change(val query: String, val replacement: String) : SearchCommand()
        object FindNext : SearchCommand()
        object FindAll : SearchCommand()
        object ReplaceOne : SearchCommand()
        object ReplaceAll : SearchCommand()
        object Close : SearchCommand()
    }

    private data class InputState(var text: String = "", var cursor: Int = 0) {
        fun clampCursor() {
            cursor = cursor.coerceIn(0, text.length)
        }

        fun handleKey(key: String, ev: UIEvent): Boolean {
            val normalized = key.lowercase()
            when (normalized) {
                "left" -> {
                    if (cursor > 0) {
                        cursor--
                        return true
                    }
                }
                "right" -> {
                    if (cursor < text.length) {
                        cursor++
                        return true
                    }
                }
                "home" -> {
                    if (cursor != 0) {
                        cursor = 0
                        return true
                    }
                }
                "end" -> {
                    val end = text.length
                    if (cursor != end) {
                        cursor = end
                        return true
                    }
                }
                "backspace" -> {
                    if (cursor > 0) {
                        text = text.removeRange(cursor - 1, cursor)
                        cursor--
                        return true
                    }
                }
                "delete" -> {
                    if (cursor < text.length) {
                        text = text.removeRange(cursor, cursor + 1)
                        return true
                    }
                }
                else -> if (!ev.ctrl && !ev.alt && !ev.meta && key.length == 1) {
                    text = text.substring(0, cursor) + key + text.substring(cursor)
                    cursor++
                    return true
                }
            }
            return false
        }
    }

    private var focusedField: Field = Field.FIND
    private var focusEnabled: Boolean = true
    private var findFieldBounds: IntRange = 0 until 0
    private var replaceFieldBounds: IntRange = 0 until 0
    private var findState = InputState()
    private var replaceState = InputState()
    private var matchLabel: String = "no matches"
    private var regexInvalid: Boolean = false
    private val barHeight = 2
    private var closeButtonX: Int = -1
    private val closeButtonWidth = 3

    fun preferredHeight(): Int = barHeight

    fun setFocusEnabled(enabled: Boolean) {
        focusEnabled = enabled
    }

    fun updateFromSearchState(state: SearchState) {
        if (findState.text != state.query) {
            findState = findState.copy(text = state.query, cursor = state.query.length)
        }
        if (replaceState.text != state.replacement) {
            replaceState = replaceState.copy(text = state.replacement, cursor = state.replacement.length)
        }
        regexInvalid = state.patternError != null
        updateMatchLabel(state.activeIndex, state.matchCount, state.patternError)
    }

    fun updateMatchLabel(activeIndex: Int, total: Int, error: String? = null) {
        matchLabel = when {
            error != null -> "regex error"
            total <= 0 -> "no matches"
            activeIndex < 0 -> "0/$total"
            else -> "${activeIndex + 1}/$total"
        }
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val barStyle = styleSheet.getStyle("code-search-bar").withDefaults()
        val labelStyle = styleSheet.getStyle("code-search-label").withDefaults(barStyle.fg, barStyle.bg)
        val fieldStyle = styleSheet.getStyle("code-search-field").withDefaults(barStyle.fg, barStyle.bg)
        val activeFieldStyle = styleSheet.getStyle("code-search-field-active").withDefaults(fieldStyle.fg, fieldStyle.bg)
        val cursorStyle = styleSheet.getStyle("code-search-cursor").withDefaults(barStyle.bg ?: fieldStyle.bg, barStyle.fg ?: fieldStyle.fg)
        val statusStyle = styleSheet.getStyle("code-search-status").withDefaults(labelStyle.fg, labelStyle.bg)
        val hintStyle = styleSheet.getStyle("code-search-hint").withDefaults(labelStyle.fg, labelStyle.bg)
        val errorFieldStyle = styleSheet.getStyle("code-search-field-error").withDefaults(fieldStyle.fg, fieldStyle.bg)
        val errorStatusStyle = styleSheet.getStyle("code-search-error").withDefaults(statusStyle.fg, statusStyle.bg)
        val closeStyle = styleSheet.getStyle("code-search-close").withDefaults(barStyle.fg, barStyle.bg)

        canvas.withStyle(barStyle) {
            drawRect(0, 0, cols, barHeight)
        }

        renderField(
            canvas = canvas,
            y = 0,
            field = Field.FIND,
            label = "Find:",
            state = findState,
            cols = cols,
            active = focusedField == Field.FIND,
            labelStyle = labelStyle,
            fieldStyle = if (regexInvalid) errorFieldStyle else fieldStyle,
            activeFieldStyle = activeFieldStyle,
            cursorStyle = cursorStyle,
            trailing = matchLabel,
            trailingStyle = if (regexInvalid) errorStatusStyle else statusStyle,
            reservedRight = closeButtonWidth + 1
        )

        val hints = "Enter:Next  Ctrl+Enter:All  Ctrl+R:Replace  Ctrl+Shift+R:All"
        renderField(
            canvas = canvas,
            y = 1,
            field = Field.REPLACE,
            label = "Replace:",
            state = replaceState,
            cols = cols,
            active = focusedField == Field.REPLACE,
            labelStyle = labelStyle,
            fieldStyle = fieldStyle,
            activeFieldStyle = activeFieldStyle,
            cursorStyle = cursorStyle,
            trailing = hints,
            trailingStyle = hintStyle,
            reservedRight = 0
        )

        closeButtonX = (cols - closeButtonWidth).coerceAtLeast(0)
        val closeText = "[x]".take(closeButtonWidth).padEnd(closeButtonWidth, ' ')
        canvas.withStyle(closeStyle) {
            drawText(closeButtonX, 0, closeText)
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val x = event.x ?: return false
            val y = event.y ?: return false
            if (y == 0 && x in closeButtonX until (closeButtonX + closeButtonWidth)) {
                onAction(SearchCommand.Close)
                return true
            }
            if (y == 0 && x in findFieldBounds) {
                focusedField = Field.FIND
                return true
            }
            if (y == 1 && x in replaceFieldBounds) {
                focusedField = Field.REPLACE
                return true
            }
        }
        if (event.kind != "key_down") return false
        val rawKey = event.key ?: return false
        val key = rawKey.lowercase()

        if (event.ctrl && key == "enter") {
            onAction(SearchCommand.FindAll)
            return true
        }
        if (event.ctrl && key == "r" && event.shift) {
            onAction(SearchCommand.ReplaceAll)
            return true
        }
        if (event.ctrl && key == "r") {
            onAction(SearchCommand.ReplaceOne)
            return true
        }
        when (key) {
            "enter" -> {
                onAction(SearchCommand.FindNext)
                return true
            }
            "tab" -> {
                focusedField = if (focusedField == Field.FIND) Field.REPLACE else Field.FIND
                return true
            }
        }

        val target = if (focusedField == Field.FIND) findState else replaceState
        val changed = target.handleKey(rawKey, event)
        if (changed) {
            if (focusedField == Field.FIND) {
                findState = target
            } else {
                replaceState = target
            }
            onAction(SearchCommand.Change(findState.text, replaceState.text))
            return true
        }

        return false
    }

    private fun renderField(
        canvas: CanvasRenderer,
        y: Int,
        field: Field,
        label: String,
        state: InputState,
        cols: Int,
        active: Boolean,
        labelStyle: StyleSet,
        fieldStyle: StyleSet,
        activeFieldStyle: StyleSet,
        cursorStyle: StyleSet,
        trailing: String,
        trailingStyle: StyleSet,
        reservedRight: Int
    ) {
        val labelText = "$label "
        canvas.withStyle(labelStyle) {
            drawText(0, y, labelText.take(cols).padEnd(labelText.length.coerceAtMost(cols), ' '))
        }
        val startX = labelText.length
        val trailingText = trailing.take(max(0, cols - startX - reservedRight))
        val trailingStart = cols - reservedRight - trailingText.length
        val available = (trailingStart - startX).coerceAtLeast(1)

        val cursor = state.cursor.coerceIn(0, state.text.length)
        val windowStart = (cursor - available + 1).coerceAtLeast(0)
        val visibleText = state.text.substring(windowStart).take(available)
        val padText = visibleText.padEnd(available, ' ')
        when (field) {
            Field.FIND -> findFieldBounds = startX until (startX + available)
            Field.REPLACE -> replaceFieldBounds = startX until (startX + available)
        }

        val fieldStyleToUse = if (active) activeFieldStyle else fieldStyle
        canvas.withStyle(fieldStyleToUse) {
            drawText(startX, y, padText)
        }

        if (active && focusEnabled) {
            val cursorX = startX + (cursor - windowStart).coerceAtLeast(0).coerceAtMost(available - 1)
            canvas.withStyle(cursorStyle) {
                val ch = padText.getOrElse(cursorX - startX) { ' ' }
                drawText(cursorX, y, ch.toString())
            }
        }

        canvas.withStyle(trailingStyle) {
            drawText(trailingStart, y, trailingText)
        }
    }
}
