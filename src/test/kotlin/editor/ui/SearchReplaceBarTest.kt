package editor.ui

import editor.ui.SearchReplaceBar.SearchCommand
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

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
        val canvas = StubCanvas()
        bar.render(canvas)

        val clickHandled = bar.dispatch(UIEvent(kind = "mouse_down", x = 10, y = 1))
        val keyHandled = bar.dispatch(UIEvent(kind = "key_down", key = "B"))

        assertTrue(clickHandled)
        assertTrue(keyHandled)
        val change = assertNotNull(lastChange)
        assertEquals("", change.query)
        assertEquals("B", change.replacement)
    }

    private class StubCanvas : CanvasRenderer {
        override fun cols(): Int = 80
        override fun rows(): Int = 2
        override fun clear() {}
        override fun setColor(r: Int, g: Int, b: Int) {}
        override fun setBackgroundColor(r: Int, g: Int, b: Int) {}
        override fun bold(enabled: Boolean) {}
        override fun italic(enabled: Boolean) {}
        override fun underline(enabled: Boolean) {}
        override fun blink(enabled: Boolean) {}
        override fun drawRect(x: Int, y: Int, width: Int, height: Int) {}
        override fun drawText(x: Int, y: Int, text: String) {}
        override fun setCursorPosition(x: Int, y: Int) {}
        override fun flush() {}
        override fun pollEvent(): UIEvent? = null
        override fun tryPollEvent(): UIEvent? = null
        override fun enableMouseTracking() {}
        override fun disableMouseTracking() {}
        override fun hideCursor() {}
        override fun showCursor() {}
        override fun resetAttributes() {}
        override fun isRunning(): Boolean = false
        override fun requestExit() {}
        override fun shutdown() {}
    }
}
