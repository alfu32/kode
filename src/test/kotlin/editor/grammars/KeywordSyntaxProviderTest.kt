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

    @Test
    fun classifiesJsxKeywordsTagsAttributesAndClassNamesSeparately() {
        val tokens = KeywordSyntaxProvider.tokensForLines(
            0,
            listOf("function App() { return <Button className=\"primary\" /> }"),
            "jsx"
        )

        assertTrue(tokens.any { it.text == "function" && "keyword" in it.scopes })
        assertTrue(tokens.any { it.text == "Button" && "tag" in it.scopes })
        assertTrue(tokens.any { it.text == "className" && "css.class" in it.scopes })
        assertTrue(tokens.any { it.text == "\"primary\"" && "string" in it.scopes })
    }

    @Test
    fun separatesXmlTagNamesFromAttributesAndCssSelectors() {
        val xml = KeywordSyntaxProvider.tokensForLines(
            0,
            listOf("<rest access-permission=\"app.access\">"),
            "xml"
        )
        assertTrue(xml.any { it.text == "rest" && "tag" in it.scopes })
        assertTrue(xml.any { it.text == "access-permission" && "attribute" in it.scopes })
        assertTrue(xml.any { it.text == "\"app.access\"" && "string" in it.scopes })

        val css = KeywordSyntaxProvider.tokensForLines(0, listOf(".myBox { color: red; }"), "css")
        assertTrue(css.any { it.text == ".myBox" && "css.class" in it.scopes })
        assertTrue(css.any { it.text.contains("color") && "keyword" in it.scopes })
    }
}
