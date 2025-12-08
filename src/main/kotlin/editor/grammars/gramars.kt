package editor.grammars

import react.Color

// ### Minimal Kotlin façade

data class Token(
    val start: Int,
    val end: Int,
    val scopes: List<String>,
    val line: Int,
    val text: String = "",
    val fg: Color? = null
)

interface SyntaxProvider {
    fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token>
    fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token>
    fun languages(): Set<String>
    fun languageForExtension(ext: String): String?
}

// ### Loader and registry

