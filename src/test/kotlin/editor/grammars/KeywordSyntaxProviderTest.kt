package editor.grammars

import kotlin.test.Test
import kotlin.test.assertTrue

class KeywordSyntaxProviderTest {
    @Test
    fun extractsRustKeywordAlternativesForCompletion() {
        val keywords = KeywordSyntaxProvider.keywords("rust").toSet()

        assertTrue("trait" in keywords)
        assertTrue("impl" in keywords)
        assertTrue("Self" in keywords)
        assertTrue("macro" in keywords)
    }
}
