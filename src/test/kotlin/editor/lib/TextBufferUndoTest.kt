package editor.lib

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TextBufferUndoTest {

    @Test
    fun `undo and redo insertion`() {
        val buffer = TextBuffer()
        buffer.loadText("hello")

        buffer.moveEndOfLine()
        buffer.insertText(" world")
        assertEquals("hello world", buffer.text())
        assertTrue(buffer.isDirty())

        assertTrue(buffer.undo())
        assertEquals("hello", buffer.text())
        assertFalse(buffer.isDirty())

        assertTrue(buffer.redo())
        assertEquals("hello world", buffer.text())
    }

    @Test
    fun `undo delete merges lines`() {
        val buffer = TextBuffer()
        buffer.loadText("abc\ndef")
        buffer.moveDown()
        buffer.deleteBackspace() // merges lines

        assertEquals("abcdef", buffer.text())
        assertTrue(buffer.undo())
        assertEquals("abc\ndef", buffer.text())
    }

    @Test
    fun `redo is cleared after new edit following undo`() {
        val buffer = TextBuffer()
        buffer.loadText("abc")
        buffer.moveEndOfLine()
        buffer.insertText("1")
        assertTrue(buffer.undo())
        buffer.moveEndOfLine()
        buffer.insertText("2")

        assertFalse(buffer.redo())
        assertEquals("abc2", buffer.text())
    }

    @Test
    fun `backspace at end of buffer stays in bounds`() {
        val buffer = TextBuffer()
        buffer.loadText("abc")
        buffer.moveEndOfLine()
        buffer.deleteBackspace()

        assertEquals("ab", buffer.text())
    }
}
