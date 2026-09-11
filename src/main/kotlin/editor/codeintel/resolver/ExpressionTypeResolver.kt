package editor.codeintel.resolver

import editor.codeintel.index.SemanticSnapshot
import editor.codeintel.model.FileId
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.TypeRef

interface ExpressionTypeResolver {
    fun typeOf(fileId: FileId, range: SourceRange, snapshot: SemanticSnapshot): TypeRef
}

class BestEffortExpressionTypeResolver : ExpressionTypeResolver {
    override fun typeOf(fileId: FileId, range: SourceRange, snapshot: SemanticSnapshot): TypeRef {
        snapshot.expressionType(fileId, range)?.let { return it }
        val occurrence = snapshot.occurrences(fileId)
            .filter { it.range.startOffset >= range.startOffset && it.range.endOffset <= range.endOffset }
            .lastOrNull()
            ?: snapshot.occurrenceAt(fileId, range.startOffset)
            ?: return TypeRef.Unknown
        val symbol = occurrence.resolvedSymbolId?.let(snapshot::symbol) ?: return TypeRef.Unknown
        if (
            symbol.kind in TYPE_KINDS &&
            (occurrence.kind in setOf(OccurrenceKind.CALL, OccurrenceKind.TYPE_REFERENCE) ||
                occurrence.text in setOf("this", "super"))
        ) {
            return TypeRef.Named(symbol.id)
        }
        return snapshot.type(symbol.declaredTypeId ?: symbol.inferredTypeId)?.ref ?: TypeRef.Unknown
    }

    companion object {
        private val TYPE_KINDS = setOf(
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.STRUCT,
            SymbolKind.ENUM,
            SymbolKind.TYPE_ALIAS
        )
    }
}
