package editor.codeintel

import editor.lsp.LspPosition
import editor.lsp.LspService
import java.net.URI
import java.nio.file.Paths

class LspCodeIntelProvider(
    private val lsp: LspService?
) : CodeIntelProvider {

    override fun tokensForLines(path: String, startLine: Int, lines: List<String>, currentVersion: Long) = emptyList<editor.grammars.Token>()

    override fun definitions(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation> {
        val lang = language ?: return emptyList()
        val hits = lsp?.definitions(path, lang, LspPosition(position.line, position.column)).orEmpty()
        return hits.mapNotNull { loc ->
            val uri = runCatching { URI(loc.uri) }.getOrNull()
            val file = uri?.let { Paths.get(it).toString() } ?: loc.uri
            CodeLocation(file, loc.range.start.line, loc.range.start.character)
        }
    }

    override fun references(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation> {
        val lang = language ?: return emptyList()
        val hits = lsp?.references(path, lang, LspPosition(position.line, position.column)).orEmpty()
        return hits.mapNotNull { loc ->
            val uri = runCatching { URI(loc.uri) }.getOrNull()
            val file = uri?.let { Paths.get(it).toString() } ?: loc.uri
            CodeLocation(file, loc.range.start.line, loc.range.start.character)
        }
    }

    override fun completions(path: String, language: String?, position: CodePosition, prefix: String): List<CompletionItem> {
        val lang = language ?: return emptyList()
        val hits = lsp?.completions(path, lang, LspPosition(position.line, position.column)).orEmpty()
        return hits.map { CompletionItem(it, "lsp") }
    }
}
