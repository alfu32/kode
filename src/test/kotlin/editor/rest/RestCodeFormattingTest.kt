package editor.rest

import editor.rest.ui.restLanguageForContentType
import editor.rest.ui.restPrettyPrint
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestCodeFormattingTest {
    @Test
    fun mapsContentTypesToEditorLanguages() {
        assertEquals("json", restLanguageForContentType("application/json; charset=utf-8"))
        assertEquals("javascript", restLanguageForContentType("text/javascript"))
        assertEquals("xml", restLanguageForContentType("application/problem+xml"))
    }

    @Test
    fun prettyPrintingCanBeToggledWithoutChangingInvalidContent() {
        val pretty = restPrettyPrint("{\"ok\":true}", "application/json", enabled = true)
        assertTrue(pretty.contains("\n"))
        assertEquals("{bad", restPrettyPrint("{bad", "application/json", enabled = true))
        assertEquals("{\"ok\":true}", restPrettyPrint("{\"ok\":true}", "application/json", enabled = false))
    }
}
