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
}
