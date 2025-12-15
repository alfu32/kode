package editor.codeintel

import editor.grammars.Token

data class TextPosition(val line: Int, val column: Int)

data class TextRange(val start: TextPosition, val end: TextPosition)

enum class SymbolKind {
    CLASS,
    INTERFACE,
    FUNCTION,
    METHOD,
    FIELD,
    VARIABLE,
    ENUM,
    OBJECT,
    MODULE,
    PACKAGE
}

data class Symbol(
    val name: String,
    val kind: SymbolKind,
    val language: String? = null,
    val filePath: String,
    val range: TextRange,
    val container: String? = null
)

data class NavigationTarget(
    val filePath: String,
    val range: TextRange,
    val kind: SymbolKind? = null,
    val name: String? = null
)

data class CompletionItem(
    val label: String,
    val detail: String? = null,
    val kind: SymbolKind? = null,
    val insertText: String? = null
)

enum class DiagnosticSeverity { ERROR, WARNING, INFO }

data class Diagnostic(
    val message: String,
    val severity: DiagnosticSeverity,
    val range: TextRange
)

data class TokensRequest(
    val filePath: String,
    val language: String?,
    val startLine: Int,
    val lines: List<String>,
    val version: Long
)

data class DefinitionRequest(
    val filePath: String,
    val language: String?,
    val position: TextPosition,
    val symbol: String
)

data class ReferenceRequest(
    val filePath: String,
    val language: String?,
    val position: TextPosition,
    val symbol: String
)

data class CompletionRequest(
    val filePath: String,
    val language: String?,
    val position: TextPosition,
    val prefix: String
)

/**
    Unified facade for editor code intelligence. Implementations can be backed by
    regex scanning, LSP, or any other provider. Consumers should not depend on
    provider-specific behavior.
 */
interface EditorIntelligenceService {
    fun tokens(request: TokensRequest): List<Token>
    fun definitions(request: DefinitionRequest): List<NavigationTarget>
    fun references(request: ReferenceRequest): List<NavigationTarget>
    fun completions(request: CompletionRequest): List<CompletionItem>
    fun diagnostics(path: String): List<Diagnostic> = emptyList()
    fun documentSymbols(path: String): List<Symbol> = emptyList()
    fun prepareRename(request: DefinitionRequest): Boolean = false
}

class CompositeEditorIntelligenceService(
    private val primary: EditorIntelligenceService?,
    private val fallback: EditorIntelligenceService?
) : EditorIntelligenceService {
    override fun tokens(request: TokensRequest): List<Token> =
        fallback?.tokens(request).orEmpty()

    override fun definitions(request: DefinitionRequest): List<NavigationTarget> {
        val primaryHits = primary?.definitions(request).orEmpty()
        if (primaryHits.isNotEmpty()) return primaryHits
        return fallback?.definitions(request).orEmpty()
    }

    override fun references(request: ReferenceRequest): List<NavigationTarget> {
        val primaryHits = primary?.references(request).orEmpty()
        if (primaryHits.isNotEmpty()) return primaryHits
        return fallback?.references(request).orEmpty()
    }

    override fun completions(request: CompletionRequest): List<CompletionItem> {
        val primaryHits = primary?.completions(request).orEmpty()
        if (primaryHits.isNotEmpty()) return primaryHits
        return fallback?.completions(request).orEmpty()
    }

    override fun diagnostics(path: String): List<Diagnostic> {
        val primaryDiag = primary?.diagnostics(path).orEmpty()
        val fallbackDiag = fallback?.diagnostics(path).orEmpty()
        return primaryDiag.ifEmpty { fallbackDiag }
    }

    override fun documentSymbols(path: String): List<Symbol> {
        val primarySymbols = primary?.documentSymbols(path).orEmpty()
        if (primarySymbols.isNotEmpty()) return primarySymbols
        return fallback?.documentSymbols(path).orEmpty()
    }

    override fun prepareRename(request: DefinitionRequest): Boolean {
        val primaryReady = primary?.prepareRename(request) ?: false
        if (primaryReady) return true
        return fallback?.prepareRename(request) ?: false
    }
}
