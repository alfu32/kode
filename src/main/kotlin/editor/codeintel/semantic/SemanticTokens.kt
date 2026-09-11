package editor.codeintel.semantic

import editor.codeintel.index.SemanticSnapshot
import editor.codeintel.model.FileId
import editor.codeintel.model.LexicalTokenKind
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolKind

enum class SemanticTokenKind {
    KEYWORD,
    TYPE,
    CLASS,
    INTERFACE,
    FUNCTION,
    METHOD,
    FIELD,
    PROPERTY,
    VARIABLE,
    PARAMETER,
    CONSTANT,
    NAMESPACE,
    MODULE,
    LABEL,
    MACRO,
    STRING,
    NUMBER,
    COMMENT,
    ATTRIBUTE,
    LIFETIME,
    OPERATOR,
    UNKNOWN
}

data class SemanticToken(
    val range: SourceRange,
    val kind: SemanticTokenKind,
    val occurrence: OccurrenceRecord? = null
)

class SemanticTokenService {
    fun tokens(
        fileId: FileId,
        snapshot: SemanticSnapshot,
        range: SourceRange? = null
    ): Sequence<SemanticToken> {
        val lexical = (range?.let { snapshot.lexicalTokens(fileId, it) } ?: snapshot.lexicalTokens(fileId))
            .map { token ->
            SemanticToken(token.range, token.kind.toTokenKind())
        }
        val semantic = (range?.let { snapshot.occurrences(fileId, it) } ?: snapshot.occurrences(fileId))
            .map { occurrence ->
                val symbolKind = occurrence.resolvedSymbolId?.let(snapshot::symbol)?.kind
                SemanticToken(
                    range = occurrence.range,
                    kind = symbolKind.toTokenKind(occurrence.kind),
                    occurrence = occurrence
                )
            }
        return (lexical + semantic).sortedWith(
            compareBy<SemanticToken> { it.range.startOffset }
                .thenByDescending { it.range.endOffset }
                .thenBy { if (it.occurrence == null) 0 else 1 }
        )
    }

    private fun SymbolKind?.toTokenKind(occurrenceKind: OccurrenceKind): SemanticTokenKind = when (this) {
        SymbolKind.CLASS -> SemanticTokenKind.CLASS
        SymbolKind.INTERFACE -> SemanticTokenKind.INTERFACE
        SymbolKind.STRUCT,
        SymbolKind.ENUM,
        SymbolKind.TYPE_ALIAS -> SemanticTokenKind.TYPE
        SymbolKind.FUNCTION,
        SymbolKind.CONSTRUCTOR -> SemanticTokenKind.FUNCTION
        SymbolKind.METHOD -> SemanticTokenKind.METHOD
        SymbolKind.FIELD -> SemanticTokenKind.FIELD
        SymbolKind.PROPERTY -> SemanticTokenKind.PROPERTY
        SymbolKind.VARIABLE -> SemanticTokenKind.VARIABLE
        SymbolKind.PARAMETER -> SemanticTokenKind.PARAMETER
        SymbolKind.CONSTANT,
        SymbolKind.ENUM_MEMBER -> SemanticTokenKind.CONSTANT
        SymbolKind.NAMESPACE,
        SymbolKind.PACKAGE -> SemanticTokenKind.NAMESPACE
        SymbolKind.MODULE -> SemanticTokenKind.MODULE
        SymbolKind.LABEL -> SemanticTokenKind.LABEL
        SymbolKind.MACRO -> SemanticTokenKind.MACRO
        else -> if (occurrenceKind == OccurrenceKind.TYPE_REFERENCE) {
            SemanticTokenKind.TYPE
        } else {
            SemanticTokenKind.UNKNOWN
        }
    }

    private fun LexicalTokenKind.toTokenKind(): SemanticTokenKind = when (this) {
        LexicalTokenKind.KEYWORD -> SemanticTokenKind.KEYWORD
        LexicalTokenKind.STRING -> SemanticTokenKind.STRING
        LexicalTokenKind.NUMBER -> SemanticTokenKind.NUMBER
        LexicalTokenKind.COMMENT -> SemanticTokenKind.COMMENT
        LexicalTokenKind.ATTRIBUTE -> SemanticTokenKind.ATTRIBUTE
        LexicalTokenKind.LIFETIME -> SemanticTokenKind.LIFETIME
        LexicalTokenKind.OPERATOR -> SemanticTokenKind.OPERATOR
    }
}
