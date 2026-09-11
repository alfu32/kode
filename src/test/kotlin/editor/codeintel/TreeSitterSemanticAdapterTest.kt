package editor.codeintel

import editor.codeintel.frontend.SourceFile
import editor.codeintel.frontend.TreeSitterSemanticAdapters
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class TreeSitterSemanticAdapterTest {
    @Test
    fun firstAdapterBatchProducesSemanticDeltas() {
        val fixtures = mapOf(
            "c" to "int add(int left, int right) { return left + right; }",
            "cpp" to "class Customer { public: int id; };",
            "go" to "package demo\nfunc add(left int, right int) int { return left + right }",
            "javascript" to "class Customer { save() {} }",
            "typescript" to "interface Customer { id: number }",
            "html" to "<main><h1>Hello</h1></main>",
            "css" to ".customer { color: red; }",
            "json" to "{\"customer\": {\"id\": 1}}",
            "python" to "class Customer:\n    def save(self):\n        return 1"
        )

        fixtures.forEach { (language, text) ->
            val adapter = assertNotNull(TreeSitterSemanticAdapters.forLanguage(language))
            val delta = adapter.extract(SourceFile("Fixture.$language", language, text, 1L))
            assertEquals(language, delta.file.languageId)
            assertTrue(delta.scopes.isNotEmpty(), "missing file scope for $language")
        }
    }
}
