package editor.codeintel

import editor.codeintel.frontend.KotlinSemanticAdapter
import editor.codeintel.frontend.SourceFile
import editor.codeintel.index.SemanticIndex
import editor.codeintel.semantic.SemanticTokenKind
import editor.codeintel.semantic.SemanticTokenService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SemanticHighlightingTest {
    @Test
    fun combinesTreeSitterLexicalTokensWithResolvedSymbolKinds() {
        val text = """
            // customer model
            class Customer {
                val id: Long = 42
                val name: String = "Ada"
                val field = true
                val none = null
                fun save(input: Customer) {
                    missing(input)
                }
            }
        """.trimIndent()
        val source = SourceFile("Customer.kt", "kotlin", text, 1L)
        val index = SemanticIndex()
        val snapshot = index.apply(KotlinSemanticAdapter().extract(source))
        val tokens = SemanticTokenService().tokens(source.fileId(), snapshot).toList()

        fun kindsFor(value: String): Set<SemanticTokenKind> = tokens
            .filter { text.substring(it.range.startOffset, it.range.endOffset) == value }
            .mapTo(linkedSetOf()) { it.kind }

        assertTrue(SemanticTokenKind.COMMENT in kindsFor("// customer model"))
        assertTrue(SemanticTokenKind.KEYWORD in kindsFor("class"))
        assertTrue(SemanticTokenKind.NUMBER in kindsFor("42"))
        assertTrue(SemanticTokenKind.STRING in kindsFor("\"Ada\""))
        assertTrue(SemanticTokenKind.KEYWORD in kindsFor("true"))
        assertTrue(SemanticTokenKind.KEYWORD in kindsFor("null"))
        assertTrue(SemanticTokenKind.CLASS in kindsFor("Customer"))
        assertTrue(SemanticTokenKind.PROPERTY in kindsFor("id"))
        assertTrue(SemanticTokenKind.METHOD in kindsFor("save"))
        assertTrue(SemanticTokenKind.PARAMETER in kindsFor("input"))
        assertTrue(SemanticTokenKind.TYPE in kindsFor("Long"))
        assertEquals(setOf(SemanticTokenKind.UNKNOWN), kindsFor("missing"))
        assertEquals(setOf(SemanticTokenKind.PROPERTY), kindsFor("field"))
    }

    @Test
    fun compatibilityFacadeReturnsSemanticAndMultilineLexicalScopes() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val text = """
                /* first
                   second */
                class Customer {
                    val id: Long = 42
                    fun save() = "saved"
                }
            """.trimIndent()
            service.indexDocumentNow("Customer.kt", "kotlin", text, version = 3L)

            val tokens = service.tokens(
                TokensRequest(
                    filePath = "Customer.kt",
                    language = "kotlin",
                    startLine = 0,
                    lines = text.lines(),
                    version = 3L
                )
            )

            assertTrue(tokens.any { it.line == 0 && "semantic.comment" in it.scopes })
            assertTrue(tokens.any { it.line == 1 && "semantic.comment" in it.scopes })
            assertTrue(tokens.any { it.text == "class" && "semantic.keyword" in it.scopes })
            assertTrue(tokens.any { it.text == "Customer" && "semantic.class" in it.scopes })
            assertTrue(tokens.any { it.text == "id" && "semantic.property" in it.scopes })
            assertTrue(tokens.any { it.text == "save" && "semantic.method" in it.scopes })
            assertTrue(tokens.any { it.text == "42" && "semantic.number" in it.scopes })
            assertTrue(tokens.any { it.text == "\"saved\"" && "semantic.string" in it.scopes })
        } finally {
            service.shutdown()
        }
    }

    private fun SourceFile.fileId() = editor.codeintel.model.SemanticIds.file(path)
}
