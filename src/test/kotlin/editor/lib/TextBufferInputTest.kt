package editor.lib

import react.UIEvent
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextBufferInputTest {
    @Test
    fun acceptsTerminalVariantsForEnter() {
        val buffer = TextBuffer()
        buffer.loadText("first")
        buffer.moveEndOfLine()

        assertTrue(handleKeyForBuffer(buffer, UIEvent(kind = "key_down", key = "enter")))
        assertEquals("first\n", buffer.text())

        assertTrue(handleKeyForBuffer(buffer, UIEvent(kind = "key_down", key = "RETURN")))
        assertEquals("first\n\n", buffer.text())
    }
}
