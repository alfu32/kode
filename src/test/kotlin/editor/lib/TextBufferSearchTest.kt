package editor.lib

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TextBufferSearchTest {

    @Test
    fun updateSearchTracksMatchesAcrossLines() {
        val buffer = TextBuffer()
        buffer.loadText("foo bar\nfoo baz\nfoo")

        buffer.updateSearch("foo")

        val tokens = buffer.foundTokens()
        assertEquals(3, tokens.size)
        assertEquals(listOf(0, 1, 2), tokens.map { it.line })
        val state = buffer.searchState()
        assertEquals(3, state.matchCount)
        assertEquals(-1, state.activeIndex)
    }

    @Test
    fun findNextCyclesThroughMatches() {
        val buffer = TextBuffer()
        buffer.loadText("alpha beta alpha")
        buffer.updateSearch("alpha")

        val first = buffer.findNext()
        val second = buffer.findNext()

        assertEquals(SelectionRange(Position(0, 0), Position(0, 5)), first)
        assertEquals(SelectionRange(Position(0, 11), Position(0, 16)), second)
        assertEquals(1, buffer.searchState().activeIndex)
    }

    @Test
    fun replaceCurrentAndAllUpdateMatches() {
        val buffer = TextBuffer()
        buffer.loadText("one two one two")
        buffer.updateSearch("one", replacement = "1")

        val replacedSingle = buffer.replaceCurrent()
        assertTrue(replacedSingle)
        assertEquals("1 two one two", buffer.text())
        assertEquals(1, buffer.searchState().matchCount)

        val replacedAll = buffer.replaceAll()
        assertEquals(1, replacedAll)
        assertEquals("1 two 1 two", buffer.text())
        assertEquals(0, buffer.searchState().matchCount)
    }

    @Test
    fun replaceHonorsRegexGroups() {
        val buffer = TextBuffer()
        buffer.loadText("abc123 xyz")
        buffer.updateSearch("(abc)(123)", replacement = "$2-$1")

        buffer.replaceCurrent()

        assertEquals("123-abc xyz", buffer.text())
    }

    @Test
    fun savingClearsDirtyFlag() {
        val buffer = TextBuffer()
        buffer.loadText("content")
        assertEquals(false, buffer.isDirty())
        buffer.moveCursorTo(Position(0, buffer.text().length), expand = false)
        buffer.insertText("!")
        assertEquals(true, buffer.isDirty())

        val tmp = kotlin.io.path.createTempFile()
        try {
            val saved = buffer.saveToFile(tmp.toString())
            assertTrue(saved)
            assertEquals(false, buffer.isDirty())
            assertEquals("content!", tmp.toFile().readText())
        } finally {
            java.nio.file.Files.deleteIfExists(tmp)
        }
    }

    @Test
    fun invalidRegexReportsError() {
        val buffer = TextBuffer()
        buffer.loadText("text")
        buffer.updateSearch("[")

        val state = buffer.searchState()
        assertTrue(state.patternError?.isNotBlank() == true)
        assertEquals(0, state.matchCount)
        assertTrue(buffer.foundTokens().isEmpty())
    }

    @Test
    fun searchResultsRefreshAfterEditing() {
        val buffer = TextBuffer()
        buffer.loadText("match")
        buffer.updateSearch("match")
        assertEquals(1, buffer.searchState().matchCount)

        buffer.moveCursorTo(Position(0, 5), expand = false)
        buffer.deleteBackspace() // drop the last character

        val refreshed = buffer.searchState()
        assertEquals(0, refreshed.matchCount)
        assertTrue(buffer.foundTokens().isEmpty())
    }
}
