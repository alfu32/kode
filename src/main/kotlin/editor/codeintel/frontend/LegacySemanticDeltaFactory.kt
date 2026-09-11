package editor.codeintel.frontend

import editor.codeintel.IdentifierToken
import editor.codeintel.SymbolDef
import editor.codeintel.model.Confidence
import editor.codeintel.model.FileRecord
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.ScopeKind
import editor.codeintel.model.ScopeRecord
import editor.codeintel.model.SemanticIds
import editor.codeintel.model.SemanticSource
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.SymbolRecord

/** Lowest-confidence bridge used only until a language has a semantic adapter. */
object LegacySemanticDeltaFactory {
    fun create(
        path: String,
        language: String,
        text: String,
        version: Long,
        definitions: List<SymbolDef>,
        identifiersByLine: Map<Int, List<IdentifierToken>>
    ): FileSemanticDelta {
        val fileId = SemanticIds.file(path)
        val scope = ScopeRecord(
            id = SemanticIds.scope(fileId, 0, ScopeKind.FILE),
            fileId = fileId,
            parentId = null,
            ownerSymbolId = null,
            kind = ScopeKind.FILE,
            range = SourceRange(0, text.length.coerceAtLeast(1))
        )
        val confidence = Confidence(SemanticSource.HEURISTIC, 0.50f)
        val symbols = definitions.map { definition ->
            val start = definition.range.first.coerceIn(0, text.length)
            val range = SourceRange(start, (definition.range.last + 1).coerceIn(start, text.length))
            val kind = definition.kind.toSemanticKind()
            SymbolRecord(
                id = SemanticIds.symbol(fileId, range.startOffset, kind, definition.name),
                fileId = fileId,
                name = definition.name,
                qualifiedName = listOfNotNull(definition.container, definition.name).joinToString("."),
                kind = kind,
                declarationRange = range,
                nameRange = range,
                scopeId = scope.id,
                ownerSymbolId = null,
                declaredTypeId = null,
                inferredTypeId = null,
                flags = 0L,
                confidence = confidence
            )
        }
        val lineOffsets = lineStartOffsets(text)
        val occurrences = identifiersByLine.values.flatten().mapNotNull { token ->
            val lineOffset = lineOffsets.getOrNull(token.line) ?: return@mapNotNull null
            val start = (lineOffset + token.start).coerceIn(0, text.length)
            val end = (lineOffset + token.end).coerceIn(start, text.length)
            val declaration = symbols.firstOrNull { it.name == token.name && it.nameRange.startOffset == start }
            OccurrenceRecord(
                id = SemanticIds.occurrence(fileId, start, end),
                fileId = fileId,
                range = SourceRange(start, end),
                text = token.name,
                kind = if (token.declaration) OccurrenceKind.DECLARATION else OccurrenceKind.UNKNOWN,
                scopeId = scope.id,
                resolvedSymbolId = declaration?.id,
                receiverOccurrenceId = null,
                confidence = confidence
            )
        }
        val semanticFile = FileRecord(
            id = fileId,
            path = path,
            languageId = language,
            contentHash = SemanticIds.hash(text).toULong().toString(16),
            parseVersion = version,
            semanticVersion = version
        )
        val exported = symbols.sortedBy { it.name }.joinToString("|") {
            "${it.id.value}:${it.qualifiedName}:${it.kind}"
        }
        return FileSemanticDelta(
            file = semanticFile,
            scopes = listOf(scope),
            symbols = symbols,
            occurrences = occurrences,
            relations = emptyList(),
            unresolvedTypes = emptyList(),
            imports = emptyList(),
            exportedSurfaceHash = SemanticIds.hash(exported).toULong().toString(16)
        )
    }

    private fun lineStartOffsets(text: String): IntArray {
        val lines = text.split('\n')
        val result = IntArray(lines.size)
        var offset = 0
        lines.forEachIndexed { index, line ->
            result[index] = offset
            offset += line.length + 1
        }
        return result
    }

    private fun editor.codeintel.SymbolKind.toSemanticKind(): SymbolKind = when (this) {
        editor.codeintel.SymbolKind.CLASS -> SymbolKind.CLASS
        editor.codeintel.SymbolKind.INTERFACE -> SymbolKind.INTERFACE
        editor.codeintel.SymbolKind.FUNCTION -> SymbolKind.FUNCTION
        editor.codeintel.SymbolKind.METHOD -> SymbolKind.METHOD
        editor.codeintel.SymbolKind.FIELD -> SymbolKind.FIELD
        editor.codeintel.SymbolKind.VARIABLE -> SymbolKind.VARIABLE
        editor.codeintel.SymbolKind.ENUM -> SymbolKind.ENUM
        editor.codeintel.SymbolKind.OBJECT -> SymbolKind.CLASS
        editor.codeintel.SymbolKind.MODULE -> SymbolKind.MODULE
        editor.codeintel.SymbolKind.PACKAGE -> SymbolKind.PACKAGE
        editor.codeintel.SymbolKind.KEYWORD -> SymbolKind.UNKNOWN
    }
}
