package editor.codeintel.semantic

import editor.codeintel.index.SemanticSnapshot
import editor.codeintel.model.FileId
import editor.codeintel.model.OccurrenceRecord
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
    UNKNOWN
}

data class SemanticToken(
    val occurrence: OccurrenceRecord,
    val kind: SemanticTokenKind
)

class SemanticTokenService {
    fun tokens(fileId: FileId, snapshot: SemanticSnapshot): Sequence<SemanticToken> =
        snapshot.occurrences(fileId).map { occurrence ->
            val symbolKind = occurrence.resolvedSymbolId?.let(snapshot::symbol)?.kind
            SemanticToken(occurrence, symbolKind.toTokenKind())
        }

    private fun SymbolKind?.toTokenKind(): SemanticTokenKind = when (this) {
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
        else -> SemanticTokenKind.UNKNOWN
    }
}
