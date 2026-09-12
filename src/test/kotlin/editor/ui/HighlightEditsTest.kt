package editor.ui

import editor.grammars.Token
import kotlin.test.Test
import kotlin.test.assertEquals

class HighlightEditsTest {
    private fun token(line: Int, start: Int, text: String) =
        Token(start, start + text.length, listOf("variable"), line, text)

    @Test
    fun discardsTokensWhenTypingJoinsAnIdentifierAtItsBoundary() {
        assertEquals(emptyList(), remapHighlightTokens("foo", "foobar", listOf(token(0, 0, "foo"))))
        assertEquals(emptyList(), remapHighlightTokens("foo", "barfoo", listOf(token(0, 0, "foo"))))
    }

    @Test
    fun shiftsUnchangedTokensAcrossInsertedLinesAndTabs() {
        val old = "val a\n\ta.save()"
        val before = token(0, 4, "a")
        val after = token(1, 3, "save")
        val updated = remapHighlightTokens(old, "val a\n// new\n\t\ta.save()", listOf(before, after))
        assertEquals(listOf(before, token(2, 4, "save")), updated)
    }

    @Test
    fun dropsEditedTokensAndRemapsAgainAfterDeletion() {
        val old = "val first = second"
        val edited = "val replacement = second"
        val updated = remapHighlightTokens(old, edited, listOf(token(0, 4, "first"), token(0, 12, "second")))
        assertEquals(listOf(token(0, 18, "second")), updated)
        assertEquals(listOf(token(0, 0, "second")), remapHighlightTokens(edited, "second", updated))
    }
}
