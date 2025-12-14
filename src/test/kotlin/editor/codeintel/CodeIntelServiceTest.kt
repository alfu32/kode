package editor.codeintel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeIntelServiceTest {

    @Test
    fun indexesDeclarationsAndUsagesPerLine() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val path = "Foo.kt"
            val text = """
            class Foo {
                fun bar() {
                    Foo()
                    val baz = Foo()
                }
            }
        """.trimIndent()

        service.indexDocument(path, "kotlin", text, version = 1)
        service.waitForIdle()

        val tokens = service.tokensForLines(path, 0, text.split("\n"), currentVersion = 1)
        val decls = tokens.filter { it.scopes.contains("codeintel.declaration") }
        val usages = tokens.filter { it.scopes.contains("codeintel.usage") }

        assertTrue(decls.any { it.text == "Foo" && it.line == 0 })
        assertTrue(decls.any { it.text == "bar" && it.line == 1 })
        assertTrue(usages.any { it.text == "Foo" && it.line >= 2 })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun workspaceAndVersionGating() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val path = "Widget.kt"
            val textV1 = """
            class Widget
        """.trimIndent()
        service.indexDocument(path, "kotlin", textV1, version = 1)
        service.waitForIdle()

        // Tokens should be empty if caller asks for a stale version.
        val staleTokens = service.tokensForLines(path, 0, textV1.split("\n"), currentVersion = 2)
        assertTrue(staleTokens.isEmpty())

        val outline = service.documentOutline(path)
        assertEquals(1, outline.size)
        assertEquals("Widget", outline.first().name)

        val symbols = service.workspaceSymbols("widget")
        assertTrue(symbols.any { it.name == "Widget" })

        val textV2 = """
            class Widget
            fun helper(widget: Widget) = widget
        """.trimIndent()
        service.indexDocument(path, "kotlin", textV2, version = 2)
        service.waitForIdle()

        val tokensV2 = service.tokensForLines(path, 0, textV2.split("\n"), currentVersion = 2)
        assertFalse(tokensV2.isEmpty())
        assertTrue(tokensV2.any { it.scopes.contains("codeintel.usage") && it.text == "Widget" })
        } finally {
            service.shutdown()
        }
    }
}
