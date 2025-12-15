package editor.codeintel

import editor.lsp.LspPosition
import editor.lsp.LspService
import java.net.URI
import java.nio.file.Paths

class LspEditorIntelligence(
    private val lsp: LspService?
) : EditorIntelligenceService {

    override fun tokens(request: TokensRequest) = emptyList<editor.grammars.Token>()

    override fun definitions(request: DefinitionRequest): List<NavigationTarget> {
        val lang = request.language ?: return emptyList()
        val hits = lsp?.definitions(request.filePath, lang, LspPosition(request.position.line, request.position.column)).orEmpty()
        return hits.mapNotNull { loc ->
            val uri = runCatching { URI(loc.uri) }.getOrNull()
            val file = uri?.let { Paths.get(it).toString() } ?: loc.uri
            NavigationTarget(
                filePath = file,
                range = TextRange(
                    start = TextPosition(loc.range.start.line, loc.range.start.character),
                    end = TextPosition(loc.range.end.line, loc.range.end.character)
                )
            )
        }
    }

    override fun references(request: ReferenceRequest): List<NavigationTarget> {
        val lang = request.language ?: return emptyList()
        val hits = lsp?.references(request.filePath, lang, LspPosition(request.position.line, request.position.column)).orEmpty()
        return hits.mapNotNull { loc ->
            val uri = runCatching { URI(loc.uri) }.getOrNull()
            val file = uri?.let { Paths.get(it).toString() } ?: loc.uri
            NavigationTarget(
                filePath = file,
                range = TextRange(
                    start = TextPosition(loc.range.start.line, loc.range.start.character),
                    end = TextPosition(loc.range.end.line, loc.range.end.character)
                )
            )
        }
    }

    override fun completions(request: CompletionRequest): List<CompletionItem> {
        val lang = request.language ?: return emptyList()
        val hits = lsp?.completions(request.filePath, lang, LspPosition(request.position.line, request.position.column)).orEmpty()
        return hits.map { CompletionItem(it, detail = "lsp") }
    }
}
