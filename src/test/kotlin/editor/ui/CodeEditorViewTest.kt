package editor.ui

import editor.lib.Position
import editor.lib.TextBuffer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import react.StyleSheet
import react.UIEvent
import editor.grammars.Token
import react.StyleSet
import react.renderer.StringSnapshotRenderer
import editor.grammars.KeywordSyntaxProvider

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

    @Test
    fun overlappingSyntaxTokensNeverCreateOverlappingDrawSegments() {
        val view = CodeEditorView(StyleSheet())
        val method = CodeEditorView::class.java.getDeclaredMethod(
            "buildSegments",
            String::class.java,
            List::class.java,
            StyleSet::class.java
        )
        method.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val segments = method.invoke(
            view,
            "abcdef",
            listOf(
                Token(1, 4, listOf("keyword"), 0),
                Token(3, 6, listOf("type"), 0)
            ),
            StyleSet()
        ) as List<Any>
        val ranges = segments.map { segment ->
            val type = segment.javaClass
            type.getDeclaredField("start").apply { isAccessible = true }.getInt(segment) to
                type.getDeclaredField("end").apply { isAccessible = true }.getInt(segment)
        }
        assertTrue(ranges.zipWithNext().all { (left, right) -> left.second <= right.first })
        assertEquals(6, ranges.sumOf { it.second - it.first })
    }

    @Test
    fun cKeywordHighlightingPreservesPunctuationAndSourceText() {
        val source = listOf(
            "for (i = 0; i < len; i++) {",
            "    printf(\"%d\\n\", v[i]);",
            "}"
        ).joinToString("\n")
        val view = CodeEditorView(StyleSheet(), syntaxProvider = KeywordSyntaxProvider)
        view.loadVirtualContent("test.c", source, "c")
        val renderer = StringSnapshotRenderer(cols = 100, rows = 8)

        view.render(renderer)

        val rendered = renderer.snapshot().lines().drop(1).take(3).map { it.drop(3) }
        source.split("\n").forEachIndexed { index, line ->
            assertTrue(rendered[index].startsWith(line))
        }
    }

    @Test
    fun cSemanticTokensStayWithinIdentifierSpans() {
        val source = "for (i = 0; i < len; i++) {\n    printf(\"%d\\n\", v[i]);\n}"
        val service = editor.codeintel.CodeIntelService(debounceMs = 0L)
        try {
            service.indexDocumentNow("test.c", "c", source, 1L)
            val tokens = service.tokens(editor.codeintel.TokensRequest("test.c", "c", 0, source.split("\n"), 1L))
            tokens.forEach { token ->
                assertTrue(token.text.all { it.isLetterOrDigit() || it == '_' })
            }
        } finally {
            service.shutdown()
        }
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
