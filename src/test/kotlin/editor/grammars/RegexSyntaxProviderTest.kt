package editor.grammars

import java.nio.file.Files
import kotlin.io.path.ExperimentalPathApi
import kotlin.io.path.createTempDirectory
import kotlin.io.path.deleteRecursively
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

@OptIn(ExperimentalPathApi::class)
class RegexSyntaxProviderTest {

    @Test
    fun `parses tokens with qualifier and content`() {
        val grammar = RegexGrammarDefinition(
            language = "demo",
            tokens = linkedMapOf(
                "keyword.control" to "\\b(if|else)\\b",
                "identifier" to "[A-Za-z_][A-Za-z0-9_]*"
            )
        )
        val provider = RegexSyntaxProvider(listOf(grammar))
        val line = "if foo else"

        val tokens = provider.tokensForLine(0, line, "demo")
        println(tokens)
        assertEquals(listOf("keyword.control", "identifier", "keyword.control"), tokens.map { it.scopes.first() })
        assertEquals(listOf("if", "foo", "else"), tokens.map { it.text })
        assertEquals(listOf(0, 3, 7), tokens.map { it.start })
        assertEquals(listOf(2, 6, 11), tokens.map { it.end })
    }

    @Test
    fun `uses language definitions loaded from directory and extension index`() {
        val tempDir = createTempDirectory("regex-grammar").toAbsolutePath()
        try {
            Files.createDirectories(tempDir)
            Files.writeString(tempDir.resolve("index.json"), """{"languages":["templang"]}""")
            Files.writeString(
                tempDir.resolve("templang.json"),
                """{"language":"templang","extensions":[".tmp"],"tokens":{"number":"\\d+","word":"[a-z]+"}}"""
            )

            val definitions = RegexGrammarLoader.loadFromDirectory(tempDir)
            assertEquals(1, definitions.size)

            val provider = RegexSyntaxProvider(definitions)
            assertEquals("templang", provider.languageForExtension("tmp"))

            val tokens = provider.tokensForLines(5, listOf("abc 123"), "templang")
            println(tokens)
            assertEquals(2, tokens.size)
            val first = tokens.first()
            assertEquals(5, first.line)
            assertEquals("word", first.scopes.first())
            assertEquals("abc", first.text)
            assertNotNull(tokens.last().text)
        } finally {
            tempDir.deleteRecursively()
        }
    }
}
