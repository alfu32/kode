package editor.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import editor.ui.SearchReplaceBar.SearchCommand
import react.StyleSheet
import react.UIEvent
import react.renderer.NoopRenderer

class SearchReplaceBarTest {

    @Test
    fun preservesUppercaseInputInFindField() {
        var lastChange: SearchCommand.Change? = null
        val bar = SearchReplaceBar(StyleSheet()) { cmd ->
            if (cmd is SearchCommand.Change) lastChange = cmd
        }

        val handled = bar.dispatch(UIEvent(kind = "key_down", key = "A"))

        assertTrue(handled)
        val change = assertNotNull(lastChange)
        assertEquals("A", change.query)
    }

    @Test
    fun clickingReplaceFieldMovesFocusAndCapturesInput() {
        var lastChange: SearchCommand.Change? = null
        val bar = SearchReplaceBar(StyleSheet()) { cmd ->
            if (cmd is SearchCommand.Change) lastChange = cmd
        }
        val canvas = NoopRenderer(cols = 80, rows = 2)
        bar.render(canvas)

        val clickHandled = bar.dispatch(UIEvent(kind = "mouse_down", x = 10, y = 1))
        val keyHandled = bar.dispatch(UIEvent(kind = "key_down", key = "B"))

        assertTrue(clickHandled)
        assertTrue(keyHandled)
        val change = assertNotNull(lastChange)
        assertEquals("", change.query)
        assertEquals("B", change.replacement)
    }
}
