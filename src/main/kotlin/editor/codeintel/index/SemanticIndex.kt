package editor.codeintel.index

import editor.codeintel.model.FileId
import editor.codeintel.model.FileRecord
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.RelationKind
import editor.codeintel.model.RelationRecord
import editor.codeintel.model.ScopeId
import editor.codeintel.model.ScopeRecord
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolId
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.SymbolRecord
import editor.codeintel.model.TypeId
import editor.codeintel.model.TypeRecord
import editor.codeintel.model.TypeRef
import editor.codeintel.resolver.ResolvedSemanticProject
import editor.codeintel.resolver.SemanticResolver
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

interface SemanticSnapshot : AutoCloseable {
    val version: Long

    fun file(id: FileId): FileRecord?
    fun file(path: String): FileRecord?
    fun symbol(id: SymbolId): SymbolRecord?
    fun symbolAt(fileId: FileId, offset: Int): SymbolRecord?
    fun occurrenceAt(fileId: FileId, offset: Int): OccurrenceRecord?
    fun occurrences(symbolId: SymbolId): Sequence<OccurrenceRecord>
    fun occurrences(fileId: FileId): Sequence<OccurrenceRecord>
    fun members(type: TypeRef): Sequence<SymbolRecord>
    fun visibleSymbols(fileId: FileId, offset: Int): Sequence<SymbolRecord>
    fun symbols(fileId: FileId): Sequence<SymbolRecord>
    fun workspaceSymbols(): Sequence<SymbolRecord>
    fun scopes(fileId: FileId): Sequence<ScopeRecord>
    fun relations(from: SymbolId, kind: RelationKind? = null): Sequence<RelationRecord>
    fun type(id: TypeId?): TypeRecord?

    override fun close() = Unit
}

interface SemanticStore {
    fun replaceFile(delta: FileSemanticDelta, resolved: ResolvedSemanticProject)
    fun loadFiles(): List<FileSemanticDelta>
    fun hasData(): Boolean
}

class SemanticIndex(
    private val store: SemanticStore? = null,
    private val resolver: SemanticResolver = SemanticResolver()
) {
    private val revision = AtomicLong(0L)
    private val state = AtomicReference(SnapshotState.empty())
    private val updateLock = Any()

    fun apply(delta: FileSemanticDelta): SemanticSnapshot = applyAll(listOf(delta))

    fun applyAll(updates: Collection<FileSemanticDelta>): SemanticSnapshot = synchronized(updateLock) {
        if (updates.isEmpty()) return@synchronized Snapshot(state.get())
        val deltas = state.get().project.deltas.toMutableMap()
        updates.forEach { delta -> deltas[delta.fileId] = delta }
        val resolved = resolver.resolve(deltas.values)
        updates.forEach { delta -> store?.replaceFile(delta, resolved) }
        val next = SnapshotState(revision.incrementAndGet(), resolved)
        state.set(next)
        Snapshot(next)
    }

    fun load(): SemanticSnapshot = synchronized(updateLock) {
        val resolved = resolver.resolve(store?.loadFiles().orEmpty())
        val next = SnapshotState(revision.incrementAndGet(), resolved)
        state.set(next)
        Snapshot(next)
    }

    fun snapshot(): SemanticSnapshot = Snapshot(state.get())

    fun clearMemory() {
        synchronized(updateLock) {
            state.set(SnapshotState.empty(revision.incrementAndGet()))
        }
    }

    fun hasPersistentData(): Boolean = store?.hasData() ?: false

    private data class SnapshotState(
        val version: Long,
        val project: ResolvedSemanticProject
    ) {
        companion object {
            fun empty(version: Long = 0L): SnapshotState = SnapshotState(
                version,
                ResolvedSemanticProject(emptyMap(), emptyList(), emptyList(), emptyList(), emptyList(), emptyMap())
            )
        }
    }

    private class Snapshot(private val state: SnapshotState) : SemanticSnapshot {
        override val version: Long = state.version
        private val filesById = state.project.deltas.values.associate { it.fileId to it.file }
        private val filesByPath = filesById.values.associateBy { it.path }
        private val symbolsById = state.project.symbols.associateBy { it.id }
        private val scopesById = state.project.scopes.associateBy { it.id }
        private val occurrencesByFile = state.project.occurrences.groupBy { it.fileId }
        private val occurrencesBySymbol = state.project.occurrences
            .filter { it.resolvedSymbolId != null }
            .groupBy { requireNotNull(it.resolvedSymbolId) }
        private val relationsFrom = state.project.relations.groupBy { it.from }

        override fun file(id: FileId): FileRecord? = filesById[id]

        override fun file(path: String): FileRecord? = filesByPath[path]

        override fun symbol(id: SymbolId): SymbolRecord? = symbolsById[id]

        override fun symbolAt(fileId: FileId, offset: Int): SymbolRecord? {
            val occurrence = occurrenceAt(fileId, offset)
            if (occurrence?.resolvedSymbolId != null) return symbol(occurrence.resolvedSymbolId)
            return state.project.symbols.firstOrNull { it.fileId == fileId && it.nameRange.contains(offset) }
        }

        override fun occurrenceAt(fileId: FileId, offset: Int): OccurrenceRecord? =
            occurrencesByFile[fileId].orEmpty().firstOrNull { it.range.contains(offset) || it.range.endOffset == offset }

        override fun occurrences(symbolId: SymbolId): Sequence<OccurrenceRecord> =
            occurrencesBySymbol[symbolId].orEmpty().asSequence()

        override fun occurrences(fileId: FileId): Sequence<OccurrenceRecord> =
            occurrencesByFile[fileId].orEmpty().asSequence()

        override fun members(type: TypeRef): Sequence<SymbolRecord> {
            if (type !is TypeRef.Named) return emptySequence()
            val visited = mutableSetOf<SymbolId>()
            val result = mutableListOf<SymbolRecord>()
            fun collect(owner: SymbolId) {
                if (!visited.add(owner)) return
                relationsFrom[owner].orEmpty()
                    .filter { it.kind == RelationKind.CONTAINS }
                    .mapNotNull { symbolsById[it.to] }
                    .forEach(result::add)
                relationsFrom[owner].orEmpty()
                    .filter { it.kind in setOf(RelationKind.EXTENDS, RelationKind.IMPLEMENTS) }
                    .forEach { collect(it.to) }
            }
            collect(type.symbolId)
            return result.distinctBy { it.name to it.kind }.asSequence()
        }

        override fun visibleSymbols(fileId: FileId, offset: Int): Sequence<SymbolRecord> {
            val fileScopes = state.project.scopes.filter {
                it.fileId == fileId && (it.range.contains(offset) || it.range.endOffset == offset)
            }
            val current = fileScopes.minByOrNull { it.range.endOffset - it.range.startOffset }
            val chain = scopeChain(current?.id)
            val result = mutableListOf<SymbolRecord>()
            chain.forEach { scopeId ->
                state.project.symbols
                    .filter { it.scopeId == scopeId && isVisibleAt(it, fileId, offset) }
                    .forEach(result::add)
            }
            val owningType = chain.asSequence()
                .mapNotNull(scopesById::get)
                .mapNotNull { it.ownerSymbolId }
                .mapNotNull(symbolsById::get)
                .mapNotNull { owner ->
                    when {
                        owner.kind in TYPE_KINDS -> owner
                        else -> relationsFrom[owner.id].orEmpty()
                            .firstOrNull { it.kind == RelationKind.MEMBER_OF }
                            ?.to
                            ?.let(symbolsById::get)
                    }
                }
                .firstOrNull()
            if (owningType != null) result += members(TypeRef.Named(owningType.id))
            state.project.symbols
                .filter { it.kind in GLOBAL_KINDS }
                .forEach(result::add)
            return result.distinctBy { it.name }.asSequence()
        }

        override fun symbols(fileId: FileId): Sequence<SymbolRecord> =
            state.project.symbols.asSequence().filter { it.fileId == fileId }

        override fun workspaceSymbols(): Sequence<SymbolRecord> = state.project.symbols.asSequence()

        override fun scopes(fileId: FileId): Sequence<ScopeRecord> =
            state.project.scopes.asSequence().filter { it.fileId == fileId }

        override fun relations(from: SymbolId, kind: RelationKind?): Sequence<RelationRecord> =
            relationsFrom[from].orEmpty().asSequence().filter { kind == null || it.kind == kind }

        override fun type(id: TypeId?): TypeRecord? = id?.let(state.project.types::get)

        private fun scopeChain(start: ScopeId?): List<ScopeId> {
            val result = mutableListOf<ScopeId>()
            var cursor = start
            while (cursor != null) {
                result += cursor
                cursor = scopesById[cursor]?.parentId
            }
            return result
        }

        private fun isVisibleAt(symbol: SymbolRecord, fileId: FileId, offset: Int): Boolean =
            symbol.fileId != fileId || symbol.kind !in LOCAL_KINDS || symbol.nameRange.startOffset <= offset

        companion object {
            private val TYPE_KINDS = setOf(
                SymbolKind.CLASS,
                SymbolKind.INTERFACE,
                SymbolKind.STRUCT,
                SymbolKind.ENUM,
                SymbolKind.TYPE_ALIAS
            )
            private val GLOBAL_KINDS = TYPE_KINDS + setOf(
                SymbolKind.PACKAGE,
                SymbolKind.MODULE,
                SymbolKind.NAMESPACE,
                SymbolKind.FUNCTION,
                SymbolKind.CONSTANT
            )
            private val LOCAL_KINDS = setOf(SymbolKind.VARIABLE, SymbolKind.PARAMETER)
        }
    }
}
