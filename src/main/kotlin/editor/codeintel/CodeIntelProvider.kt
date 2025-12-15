package editor.codeintel

import editor.grammars.Token

data class CodeLocation(val filePath: String, val line: Int, val column: Int)

data class CompletionItem(val label: String, val detail: String? = null)

data class CodePosition(val line: Int, val column: Int)

/**
 * Uniform contract for code-intel sources. Implementations can be backed by regex
 * scanning, LSP, or any other provider.
 */
interface CodeIntelProvider {
    fun tokensForLines(path: String, startLine: Int, lines: List<String>, currentVersion: Long): List<Token>
    fun definitions(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation>
    fun references(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation>
    fun completions(path: String, language: String?, position: CodePosition, prefix: String): List<CompletionItem>
}

class CompositeCodeIntelProvider(
    private val primary: CodeIntelProvider?,
    private val fallback: CodeIntelProvider?
) : CodeIntelProvider {
    override fun tokensForLines(path: String, startLine: Int, lines: List<String>, currentVersion: Long): List<Token> =
        fallback?.tokensForLines(path, startLine, lines, currentVersion).orEmpty()

    override fun definitions(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation> {
        val primaryHits = primary?.definitions(path, language, position, name).orEmpty()
        if (primaryHits.isNotEmpty()) return primaryHits
        return fallback?.definitions(path, language, position, name).orEmpty()
    }

    override fun references(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation> {
        val primaryHits = primary?.references(path, language, position, name).orEmpty()
        if (primaryHits.isNotEmpty()) return primaryHits
        return fallback?.references(path, language, position, name).orEmpty()
    }

    override fun completions(path: String, language: String?, position: CodePosition, prefix: String): List<CompletionItem> {
        val primaryHits = primary?.completions(path, language, position, prefix).orEmpty()
        if (primaryHits.isNotEmpty()) return primaryHits
        return fallback?.completions(path, language, position, prefix).orEmpty()
    }
}
