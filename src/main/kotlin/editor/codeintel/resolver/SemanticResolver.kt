package editor.codeintel.resolver

import editor.codeintel.model.Confidence
import editor.codeintel.model.FileId
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.RelationKind
import editor.codeintel.model.RelationRecord
import editor.codeintel.model.ScopeId
import editor.codeintel.model.ScopeRecord
import editor.codeintel.model.SemanticIds
import editor.codeintel.model.SemanticSource
import editor.codeintel.model.SymbolId
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.SymbolRecord
import editor.codeintel.model.TypeHintKind
import editor.codeintel.model.TypeId
import editor.codeintel.model.TypeKnowledgeLevel
import editor.codeintel.model.TypeRecord
import editor.codeintel.model.TypeRef
import editor.codeintel.model.TypeRole

data class ResolvedSemanticProject(
    val deltas: Map<FileId, FileSemanticDelta>,
    val symbols: List<SymbolRecord>,
    val scopes: List<ScopeRecord>,
    val occurrences: List<OccurrenceRecord>,
    val relations: List<RelationRecord>,
    val types: Map<TypeId, TypeRecord>
)

class SemanticResolver {
    fun resolve(deltas: Collection<FileSemanticDelta>): ResolvedSemanticProject {
        val deltaMap = deltas.associateBy { it.fileId }
        val scopes = deltas.flatMap { it.scopes }
        var symbols = deltas.flatMap { it.symbols }
        val types = linkedMapOf<TypeId, TypeRecord>()
        val relations = deltas.flatMap { it.relations }.toMutableList()

        val symbolsById = { symbols.associateBy { it.id } }
        deltas.flatMap { it.unresolvedTypes }.forEach { unresolved ->
            val owner = symbolsById()[unresolved.ownerSymbolId] ?: return@forEach
            val type = resolveNamedType(unresolved.name, unresolved.scopeId, owner.fileId, symbols, scopes)
                ?: primitiveType(unresolved.name)
            if (type == null) return@forEach
            val typeId = typeId(type)
            val level = if (type is TypeRef.Named && symbols.firstOrNull { it.id == type.symbolId }?.fileId != owner.fileId) {
                TypeKnowledgeLevel.CROSS_FILE_RESOLVED
            } else {
                TypeKnowledgeLevel.SYNTACTICALLY_DECLARED
            }
            types[typeId] = TypeRecord(typeId, type, unresolved.confidence, level)
            symbols = symbols.map { symbol ->
                if (symbol.id != owner.id) symbol
                else when (unresolved.role) {
                    TypeRole.DECLARED,
                    TypeRole.PARAMETER,
                    TypeRole.RETURN -> symbol.copy(declaredTypeId = typeId)
                    TypeRole.SUPER_TYPE -> symbol
                }
            }
            if (type is TypeRef.Named) {
                val relationKind = when (unresolved.role) {
                    TypeRole.DECLARED -> RelationKind.TYPE_OF
                    TypeRole.PARAMETER -> RelationKind.PARAMETER_TYPE
                    TypeRole.RETURN -> RelationKind.RETURNS
                    TypeRole.SUPER_TYPE -> RelationKind.EXTENDS
                }
                relations += RelationRecord(owner.id, type.symbolId, relationKind, unresolved.confidence)
            }
        }

        deltas.flatMap { it.typeHints }.forEach { hint ->
            val target = symbols.firstOrNull { it.id == hint.targetSymbolId } ?: return@forEach
            val inferred = when (hint.kind) {
                TypeHintKind.CONSTRUCTOR_CALL -> resolveNamedType(
                    hint.referencedName.substringAfterLast('.'),
                    hint.scopeId,
                    target.fileId,
                    symbols,
                    scopes
                )
                TypeHintKind.INITIALIZER_CALL,
                TypeHintKind.ASSIGNMENT -> {
                    val callable = resolveSymbol(
                        hint.referencedName.substringAfterLast('.'),
                        hint.scopeId,
                        target.fileId,
                        hint.expressionRange.startOffset,
                        symbols,
                        scopes,
                        setOf(SymbolKind.FUNCTION, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR)
                    )
                    callable?.declaredTypeId?.let { types[it]?.ref }
                }
            } ?: return@forEach
            val typeId = typeId(inferred)
            types.putIfAbsent(
                typeId,
                TypeRecord(typeId, inferred, hint.confidence, TypeKnowledgeLevel.LOCALLY_INFERRED)
            )
            symbols = symbols.map { symbol ->
                if (symbol.id == target.id && symbol.declaredTypeId == null) {
                    symbol.copy(inferredTypeId = typeId, confidence = hint.confidence)
                } else {
                    symbol
                }
            }
            if (inferred is TypeRef.Named) {
                relations += RelationRecord(target.id, inferred.symbolId, RelationKind.TYPE_OF, hint.confidence)
            }
        }

        val occurrenceById = linkedMapOf<Long, OccurrenceRecord>()
        deltas.flatMap { it.occurrences }.sortedBy { it.range.startOffset }.forEach { occurrence ->
            val resolved = occurrence.resolvedSymbolId?.let { id -> symbols.firstOrNull { it.id == id } }
                ?: resolveOccurrence(occurrence, occurrenceById, symbols, scopes, relations, types)
            val updated = if (resolved == null) {
                occurrence
            } else {
                occurrence.copy(
                    resolvedSymbolId = resolved.id,
                    confidence = Confidence(SemanticSource.STATIC_INFERENCE, 0.95f)
                )
            }
            occurrenceById[updated.id] = updated
            if (updated.kind == OccurrenceKind.CALL && resolved != null) {
                val enclosing = ownerSymbolForScope(updated.scopeId, scopes)
                if (enclosing != null) {
                    relations += RelationRecord(enclosing, resolved.id, RelationKind.CALLS)
                }
            }
        }

        return ResolvedSemanticProject(
            deltas = deltaMap,
            symbols = symbols,
            scopes = scopes,
            occurrences = occurrenceById.values.toList(),
            relations = relations.distinctBy { Triple(it.from, it.to, it.kind) },
            types = types
        )
    }

    private fun resolveOccurrence(
        occurrence: OccurrenceRecord,
        occurrenceById: Map<Long, OccurrenceRecord>,
        symbols: List<SymbolRecord>,
        scopes: List<ScopeRecord>,
        relations: List<RelationRecord>,
        types: Map<TypeId, TypeRecord>
    ): SymbolRecord? {
        if (occurrence.kind == OccurrenceKind.MEMBER_REFERENCE) {
            val receiver = occurrence.receiverOccurrenceId?.let(occurrenceById::get)
            val receiverSymbol = receiver?.resolvedSymbolId?.let { id -> symbols.firstOrNull { it.id == id } }
            val receiverType = receiverSymbol?.let { symbol ->
                (symbol.declaredTypeId ?: symbol.inferredTypeId)?.let(types::get)?.ref
            }
            if (receiverType is TypeRef.Named) {
                return memberSymbols(receiverType.symbolId, symbols, relations)
                    .firstOrNull { it.name == occurrence.text }
            }
        }
        val kinds = when (occurrence.kind) {
            OccurrenceKind.TYPE_REFERENCE -> TYPE_KINDS
            OccurrenceKind.CALL -> CALLABLE_KINDS + TYPE_KINDS
            else -> null
        }
        return resolveSymbol(
            occurrence.text.substringAfterLast('.'),
            occurrence.scopeId,
            occurrence.fileId,
            occurrence.range.startOffset,
            symbols,
            scopes,
            kinds
        )
    }

    private fun resolveNamedType(
        name: String,
        scopeId: ScopeId,
        fileId: FileId,
        symbols: List<SymbolRecord>,
        scopes: List<ScopeRecord>
    ): TypeRef.Named? {
        val normalized = name.removeSuffix("?").substringBefore('<')
        val exactQualified = symbols.filter { it.kind in TYPE_KINDS && it.qualifiedName == normalized }
        val symbol = exactQualified.firstOrNull() ?: resolveSymbol(
            normalized.substringAfterLast('.'),
            scopeId,
            fileId,
            Int.MAX_VALUE,
            symbols,
            scopes,
            TYPE_KINDS
        )
        return symbol?.let { TypeRef.Named(it.id) }
    }

    private fun primitiveType(name: String): TypeRef.Primitive? {
        val normalized = name.removeSuffix("?").substringBefore('<')
        return normalized.takeIf { it in PRIMITIVES }?.let(TypeRef::Primitive)
    }

    private fun resolveSymbol(
        name: String,
        scopeId: ScopeId,
        fileId: FileId,
        offset: Int,
        symbols: List<SymbolRecord>,
        scopes: List<ScopeRecord>,
        kinds: Set<SymbolKind>? = null
    ): SymbolRecord? {
        val chain = scopeChain(scopeId, scopes)
        return symbols.asSequence()
            .filter { it.name == name && (kinds == null || it.kind in kinds) }
            .filter { symbol ->
                symbol.kind !in LOCAL_KINDS || symbol.fileId != fileId || symbol.nameRange.startOffset <= offset
            }
            .map { symbol ->
                val scopeDistance = chain.indexOf(symbol.scopeId).let { if (it < 0) 10_000 else it }
                val filePenalty = if (symbol.fileId == fileId) 0 else 1_000
                val globalPenalty = if (scopeDistance == 10_000) 100 else 0
                symbol to (scopeDistance + filePenalty + globalPenalty)
            }
            .sortedWith(compareBy<Pair<SymbolRecord, Int>> { it.second }.thenBy { it.first.qualifiedName ?: it.first.name })
            .firstOrNull()
            ?.first
    }

    private fun scopeChain(scopeId: ScopeId, scopes: List<ScopeRecord>): List<ScopeId> {
        val byId = scopes.associateBy { it.id }
        val result = mutableListOf<ScopeId>()
        var cursor: ScopeId? = scopeId
        while (cursor != null) {
            result += cursor
            cursor = byId[cursor]?.parentId
        }
        return result
    }

    private fun ownerSymbolForScope(scopeId: ScopeId, scopes: List<ScopeRecord>): SymbolId? {
        val byId = scopes.associateBy { it.id }
        var cursor: ScopeId? = scopeId
        while (cursor != null) {
            val scope = byId[cursor] ?: break
            if (scope.ownerSymbolId != null) return scope.ownerSymbolId
            cursor = scope.parentId
        }
        return null
    }

    private fun memberSymbols(
        typeSymbolId: SymbolId,
        symbols: List<SymbolRecord>,
        relations: List<RelationRecord>
    ): List<SymbolRecord> {
        val byId = symbols.associateBy { it.id }
        val visited = mutableSetOf<SymbolId>()
        val result = mutableListOf<SymbolRecord>()
        fun collect(owner: SymbolId) {
            if (!visited.add(owner)) return
            relations.asSequence()
                .filter { it.from == owner && it.kind == RelationKind.CONTAINS }
                .mapNotNull { byId[it.to] }
                .forEach(result::add)
            relations.asSequence()
                .filter { it.from == owner && it.kind in setOf(RelationKind.EXTENDS, RelationKind.IMPLEMENTS) }
                .forEach { collect(it.to) }
        }
        collect(typeSymbolId)
        return result.distinctBy { it.name to it.kind }
    }

    private fun typeId(type: TypeRef): TypeId = when (type) {
        is TypeRef.Named -> SemanticIds.type("named:${type.symbolId.value}")
        is TypeRef.Primitive -> SemanticIds.type("primitive:${type.name}")
        else -> SemanticIds.type(type.toString())
    }

    companion object {
        private val TYPE_KINDS = setOf(
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.STRUCT,
            SymbolKind.ENUM,
            SymbolKind.TYPE_ALIAS
        )
        private val CALLABLE_KINDS = setOf(SymbolKind.FUNCTION, SymbolKind.METHOD, SymbolKind.CONSTRUCTOR)
        private val LOCAL_KINDS = setOf(SymbolKind.VARIABLE, SymbolKind.PARAMETER)
        private val PRIMITIVES = setOf(
            "Any", "Boolean", "Byte", "Char", "Double", "Float", "Int", "Long", "Nothing", "Short", "String", "Unit"
        )
    }
}
