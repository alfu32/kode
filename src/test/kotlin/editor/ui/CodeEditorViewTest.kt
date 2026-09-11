package editor.ui

import editor.lib.Position
import editor.lib.TextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import react.StyleSheet
import react.UIEvent

class CodeEditorViewTest {

    @Test
    fun ctrlFPrefillsSearchWithEscapedSelection() {
        val buffer = TextBuffer()
        buffer.loadText("A.*B line")
        buffer.startSelection(Position(0, 0))
        buffer.selectTo(Position(0, 4))
        val view = CodeEditorView(StyleSheet(), buffer)

        val handled = view.dispatch(UIEvent(kind = "key_down", key = "F", ctrl = true))

        assertTrue(handled)
        assertEquals(Regex.escape("A.*B"), buffer.searchState().query)
    }

    @Test
    fun completionDoesNotDeleteReceiverDotWhenPrefixIsEmpty() {
        val buffer = TextBuffer()
        buffer.loadText("customer.")
        buffer.moveCursorTo(Position(0, buffer.text().length), expand = false)
        val view = CodeEditorView(StyleSheet(), buffer)

        applySuggestion(view, "name", "")

        assertEquals("customer.name", buffer.text())
    }

    @Test
    fun completionReplacesOnlyTheTypedIdentifierPrefix() {
        val buffer = TextBuffer()
        buffer.loadText("customer.na")
        buffer.moveCursorTo(Position(0, buffer.text().length), expand = false)
        val view = CodeEditorView(StyleSheet(), buffer)

        applySuggestion(view, "name", "na")

        assertEquals("customer.name", buffer.text())
    }

    private fun applySuggestion(view: CodeEditorView, suggestion: String, prefix: String) {
        val method = CodeEditorView::class.java.getDeclaredMethod(
            "applySuggestion",
            String::class.java,
            String::class.java
        )
        method.isAccessible = true
        method.invoke(view, suggestion, prefix)
    }
}
