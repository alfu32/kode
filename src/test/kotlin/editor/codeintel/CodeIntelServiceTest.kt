package editor.codeintel

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import editor.codeintel.SymbolKind
import editor.codeintel.ReferenceRequest
import editor.codeintel.TextPosition
import editor.codeintel.TokensRequest
import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText

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

        val tokens = service.tokens(
            TokensRequest(
                filePath = path,
                language = "kotlin",
                startLine = 0,
                lines = text.split("\n"),
                version = 1
            )
        )
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
        val staleTokens = service.tokens(
            TokensRequest(
                filePath = path,
                language = "kotlin",
                startLine = 0,
                lines = textV1.split("\n"),
                version = 2
            )
        )
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

        val tokensV2 = service.tokens(
            TokensRequest(
                filePath = path,
                language = "kotlin",
                startLine = 0,
                lines = textV2.split("\n"),
                version = 2
            )
        )
        assertFalse(tokensV2.isEmpty())
        assertTrue(tokensV2.any { it.scopes.contains("codeintel.usage") && it.text == "Widget" })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun loadsPatternsFromJsonRegistry() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val goPath = "main.go"
            val goText = """
                // should ignore this fake struct Foo
                package main
                func main() {}
                type User struct {}
            """.trimIndent()
            service.indexDocument(goPath, "go", goText, version = 1)

            val sqlPath = "report.sql"
            val sqlText = """
                -- ignore fake CREATE FUNCTION bogus()
                CREATE FUNCTION report_sales() RETURNS void AS $$
                BEGIN
                    RETURN;
                END;
                $$ LANGUAGE plpgsql;
            """.trimIndent()
            service.indexDocument(sqlPath, "sql", sqlText, version = 1)

            val tsPath = "index.ts"
            val tsText = """
                // TypeScript alias test
                function greet() {}
            """.trimIndent()
            service.indexDocument(tsPath, "ts", tsText, version = 1)

            service.waitForIdle()

            val goOutline = service.documentOutline(goPath)
            assertTrue(goOutline.any { it.name == "main" && it.kind == SymbolKind.FUNCTION })
            assertTrue(goOutline.any { it.name == "User" && it.kind == SymbolKind.CLASS })

            val sqlOutline = service.documentOutline(sqlPath)
            assertTrue(sqlOutline.any { it.name.equals("report_sales", ignoreCase = true) })

            val tsOutline = service.documentOutline(tsPath)
            assertTrue(tsOutline.any { it.name == "greet" })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun externalDefinitionsOverrideResource() {
        val tempDir = createTempDirectory()
        val externalFile = tempDir.resolve("defs.json")
        val custom = """
            {
              "languages": [
                {
                  "language": "customlang",
                  "patterns": [
                    {"kind": "FUNCTION", "regex": "\\bspice_([A-Za-z_][A-Za-z0-9_]*)"}
                  ]
                }
              ]
            }
        """.trimIndent()
        Files.writeString(externalFile, custom)
        val oldProp = System.getProperty("kode.codeintel.path")
        System.setProperty("kode.codeintel.path", externalFile.toString())
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val path = "test.custom"
            val text = "spice_run()"
            service.indexDocument(path, "customlang", text, version = 1)
            service.waitForIdle()
            val outline = service.documentOutline(path)
            assertTrue(outline.any { it.name == "run" && it.kind == SymbolKind.FUNCTION })
        } finally {
            if (oldProp != null) System.setProperty("kode.codeintel.path", oldProp) else System.clearProperty("kode.codeintel.path")
            service.shutdown()
            Files.deleteIfExists(externalFile)
            Files.deleteIfExists(tempDir)
        }
    }

    @Test
    fun usagesIncludeOtherFiles() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val defPath = "a/Foo.kt"
            val defText = """
                package a
                class Foo
                fun maker(): Foo = Foo()
            """.trimIndent()
            val usePath = "b/Use.kt"
            val useText = """
                package b
                fun make(): a.Foo {
                    return Foo()
                }
            """.trimIndent()
            service.indexDocument(defPath, "kotlin", defText, version = 1)
            service.indexDocument(usePath, "kotlin", useText, version = 1)
            service.waitForIdle()

            val refs = service.references(
                ReferenceRequest(
                    filePath = defPath,
                    language = "kotlin",
                    position = TextPosition(0, 0),
                    symbol = "Foo"
                )
            )
            assertTrue(refs.any { it.filePath == usePath })
            assertTrue(refs.any { it.filePath == defPath })
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun regexExtractorDetectsDeclarationsAndSkipsKeywords() {
        val service = CodeIntelService(debounceMs = 0L)
        try {
            val path = "Sample.kt"
            val text = """
                package demo
                class Foo {
                    fun bar(foo: Foo) {
                        val baz = Foo()
                        if (baz != null) {
                            val qux = foo
                        }
                    }
                }
            """.trimIndent()

            service.indexDocument(path, "kotlin", text, version = 1)
            service.waitForIdle()

            val outline = service.documentOutline(path)
            val outlineNames = outline.map { it.name }.toSet()
            assertTrue(outlineNames.containsAll(listOf("Foo", "bar", "baz", "qux")))

            val tokens = service.tokens(
                TokensRequest(
                    filePath = path,
                    language = "kotlin",
                    startLine = 0,
                    lines = text.split("\n"),
                    version = 1
                )
            )
            val declarations = tokens.filter { it.scopes.contains("codeintel.declaration") }.map { it.text }.toSet()
            val usages = tokens.filter { it.scopes.contains("codeintel.usage") }.map { it.text }.toSet()

            assertTrue(declarations.containsAll(listOf("Foo", "bar", "baz", "qux")))
            assertTrue(usages.containsAll(listOf("Foo", "foo", "baz")))
            assertFalse(tokens.any { it.text == "if" || it.text == "package" }) // keywords should be ignored
        } finally {
            service.shutdown()
        }
    }
}
