package editor.codeintel.index

import editor.codeintel.model.FileId
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.SymbolKind
import editor.codeintel.resolver.ResolvedSemanticProject

data class SemanticInvalidationPlan(
    val affectedFiles: Set<FileId>,
    val surfaceChangedFiles: Set<FileId>
)

class SemanticInvalidationPlanner {
    fun plan(
        previous: ResolvedSemanticProject,
        updates: Collection<FileSemanticDelta>
    ): SemanticInvalidationPlan {
        val updatedIds = updates.mapTo(linkedSetOf()) { it.fileId }
        if (previous.deltas.isEmpty()) {
            return SemanticInvalidationPlan(updatedIds, updatedIds)
        }

        val surfaceChanged = updates.filterTo(linkedSetOf()) { update ->
            previous.deltas[update.fileId]?.exportedSurfaceHash != update.exportedSurfaceHash
        }.mapTo(linkedSetOf()) { it.fileId }
        if (surfaceChanged.isEmpty()) {
            return SemanticInvalidationPlan(updatedIds, emptySet())
        }

        val graph = DependencyGraph(previous.dependencies)
        val affected = graph.dependentClosure(surfaceChanged).toMutableSet()
        affected += updatedIds

        // A new definition has no old dependency edge. Wake files containing
        // matching unresolved facts so reverse-order indexing remains correct.
        val changedNames = surfaceChanged.flatMapTo(mutableSetOf()) { fileId ->
            exportedNames(previous.deltas[fileId]) + exportedNames(updates.firstOrNull { it.fileId == fileId })
        }
        previous.deltas.values.asSequence()
            .filter { it.fileId !in affected }
            .filter { delta -> mayReferenceAny(delta, changedNames) }
            .mapTo(affected) { it.fileId }

        return SemanticInvalidationPlan(graph.dependentClosure(affected), surfaceChanged)
    }

    private fun exportedNames(delta: FileSemanticDelta?): Set<String> {
        delta ?: return emptySet()
        val symbolsById = delta.symbols.associateBy { it.id }
        return delta.symbols.asSequence()
            .filter { symbol ->
                symbol.ownerSymbolId == null || symbolsById[symbol.ownerSymbolId]?.kind in TYPE_KINDS
            }
            .flatMap { sequenceOf(it.name, it.qualifiedName).filterNotNull() }
            .flatMap { sequenceOf(it, it.substringAfterLast('.')) }
            .toSet()
    }

    private fun mayReferenceAny(delta: FileSemanticDelta, names: Set<String>): Boolean {
        if (names.isEmpty()) return false
        if (delta.unresolvedTypes.any { it.name in names || it.name.substringAfterLast('.') in names }) return true
        if (delta.typeHints.any { it.referencedName in names || it.referencedName.substringAfterLast('.') in names }) return true
        if (delta.occurrences.any { it.resolvedSymbolId == null && it.text in names }) return true
        return delta.imports.any { record ->
            record.path in names || record.path.substringAfterLast('.') in names
        }
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
