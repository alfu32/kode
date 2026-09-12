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
    fun pageKeysTraverseWrappedRowsWithinOneLogicalLine() {
        val buffer = TextBuffer().apply { loadText("\t".repeat(100)) }
        val view = CodeEditorView(StyleSheet(), buffer)
        val renderer = StringSnapshotRenderer(cols = 23, rows = 4)
        view.render(renderer)
        view.dispatch(UIEvent(kind = "key_down", key = "PageDown", shift = true))
        // Twenty content cells per row fit five tabs; three rows make one page.
        assertEquals(Position(0, 15), buffer.cursorPosition())
        assertEquals("\t".repeat(15), buffer.selectionText())
        view.dispatch(UIEvent(kind = "key_down", key = "PageUp"))
        assertEquals(Position(0, 0), buffer.cursorPosition())
    }


    @Test
    fun pageKeysMoveCursorAndViewportByVisibleRows() {
        val buffer = TextBuffer().apply { loadText((0..99).joinToString("\n") { "line $it" }) }
        val view = CodeEditorView(StyleSheet(), buffer)
        val renderer = StringSnapshotRenderer(cols = 35, rows = 11)
        view.render(renderer)
        assertTrue(view.dispatch(UIEvent(kind = "key_down", key = "PageDown")))
        assertEquals(Position(10, 0), buffer.cursorPosition())
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 10"))
        view.dispatch(UIEvent(kind = "key_down", key = "PageUp"))
        assertEquals(Position(0, 0), buffer.cursorPosition())
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 0"))
    }

    @Test
    fun wheelMovesThreeRowsAndAltWheelMovesNine() {
        val buffer = TextBuffer().apply { loadText((0..99).joinToString("\n") { "line $it" }) }
        val view = CodeEditorView(StyleSheet(), buffer)
        val renderer = StringSnapshotRenderer(cols = 35, rows = 11)
        view.render(renderer)
        view.dispatch(UIEvent(kind = "mouse_scroll", scrollDelta = -1))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 3"))
        view.dispatch(UIEvent(kind = "mouse_scroll", scrollDelta = -1, alt = true))
        view.render(renderer)
        assertTrue(renderer.snapshot().lines()[1].contains("line 12"))
        assertEquals(Position(0, 0), buffer.cursorPosition())
    }

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
    fun tabsRenderAtFourColumnStopsWithoutShiftingTokens() {
        val source = "\tfor (i = 0; i < len; i++) {\n\t\treturn i;"
        val view = CodeEditorView(StyleSheet(), syntaxProvider = KeywordSyntaxProvider)
        view.loadVirtualContent("test.c", source, "c")
        val renderer = StringSnapshotRenderer(cols = 100, rows = 8)

        view.render(renderer)

        val rendered = renderer.snapshot().lines().drop(1).take(2).map { it.drop(3) }
        assertTrue(rendered[0].startsWith("|-->for (i = 0; i < len; i++) {"))
        assertTrue(rendered[1].startsWith("|-->|-->return i;"))
    }

    @Test
    fun controlCharactersUseSingleCursorStepEscapes() {
        val ctor = Class.forName("editor.ui.CodeEditorView\$VisualLine").getDeclaredConstructor(String::class.java)
        ctor.isAccessible = true
        val line = ctor.newInstance("a\u0001b")
        val visualText = line.javaClass.getDeclaredField("visualText").apply { isAccessible = true }.get(line) as String
        assertEquals("a\\x01b", visualText)
        val visualColumn = line.javaClass.getDeclaredMethod("visualColumn", Int::class.javaPrimitiveType!!)
        visualColumn.isAccessible = true
        assertEquals(1, visualColumn.invoke(line, 1))
        assertEquals(5, visualColumn.invoke(line, 2))
    }

    @Test
    fun logicalCursorStepsTreatTabAndControlAsOneCharacterEach() {
        val buffer = TextBuffer()
        buffer.loadText("\t\u0001x")

        buffer.moveRight()
        assertEquals(Position(0, 1), buffer.cursorPosition())
        buffer.moveRight()
        assertEquals(Position(0, 2), buffer.cursorPosition())
        buffer.moveLeft()
        assertEquals(Position(0, 1), buffer.cursorPosition())
    }

    @Test
    fun cSemanticTokensStayWithinIdentifierSpans() {
        val source = "for (i = 0; i < len; i++) {\n    printf(\"%d\\n\", v[i]);\n}"
        val service = editor.codeintel.CodeIntelService(debounceMs = 0L)
        try {
            service.indexDocumentNow("test.c", "c", source, 1L)
            val tokens = service.tokens(editor.codeintel.TokensRequest("test.c", "c", 0, source.split("\n"), 1L))
            tokens.forEach { token ->
                assertEquals(token.text, source.lines()[token.line].substring(token.start, token.end))
                if (token.scopes.any { it == "codeintel.declaration" || it == "codeintel.usage" }) {
                    assertTrue(token.text.all { it.isLetterOrDigit() || it == '_' })
                }
            }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun usagePathsCollapseDirectoriesAndPreserveFilename() {
        val view = CodeEditorView(StyleSheet())
        val method = CodeEditorView::class.java.getDeclaredMethod(
            "compactPath",
            String::class.java,
            Int::class.javaPrimitiveType!!
        )
        method.isAccessible = true

        val compact = method.invoke(
            view,
            "src/test/kotlin/editor/grammars/RegexSyntaxProviderTest.kt",
            48
        ) as String

        assertTrue(compact.length <= 48)
        assertTrue(compact != "src/test/kotlin/editor/grammars/RegexSyntaxProviderTest.kt")
        assertTrue(compact.endsWith("RegexSyntaxProviderTest.kt"))
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
