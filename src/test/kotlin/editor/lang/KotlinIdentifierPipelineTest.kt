package editor.lang

import editor.lang.kotlin.KotlinIdentifierExtractor
import editor.lang.kotlin.KotlinLanguageAdapter
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KotlinIdentifierPipelineTest {

    @Test
    fun extractsScopedDeclarationsAndUsages() {
        val text = """
            package demo

            class Box {
                val size = 1
                fun grow(delta: Int): Int {
                    val result = size + delta
                    return result
                }
            }

            fun top(box: Box): Box = Box()
        """.trimIndent()

        val extractor = KotlinIdentifierExtractor(KotlinLanguageAdapter())
        val occurrences = extractor.extract(text, "Sample.kt")

        assertTrue(hasDecl(occurrences, "demo"))
        assertTrue(hasDecl(occurrences, "demo.Box"))
        assertTrue(hasDecl(occurrences, "demo.Box.size"))
        assertTrue(hasDecl(occurrences, "demo.Box.grow"))
        assertTrue(hasDecl(occurrences, "demo.Box.grow.delta"))
        assertTrue(hasDecl(occurrences, "demo.Box.grow.result"))
        assertTrue(hasDecl(occurrences, "demo.top"))
        assertTrue(hasDecl(occurrences, "demo.top.box"))

        assertTrue(occurrences.any { it.identifier == "demo.Box.grow.size" && it.type == SymbolType.USAGE })
        assertTrue(occurrences.any { it.identifier == "demo.top.Box" && it.type == SymbolType.USAGE })
        assertFalse(occurrences.any { it.identifier == "demo.Box.top" && it.type == SymbolType.DECLARATION })
    }

    private fun hasDecl(list: List<IdentifierOccurrence>, name: String): Boolean =
        list.any { it.identifier == name && it.type == SymbolType.DECLARATION }
}
