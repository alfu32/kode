package editor.codeintel.resolver

import editor.codeintel.model.Confidence
import editor.codeintel.model.FileDependencyKind
import editor.codeintel.model.FileDependencyRecord
import editor.codeintel.model.FileId
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.ExpressionTypeRecord
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.RelationKind
import editor.codeintel.model.RelationRecord
import editor.codeintel.model.ScopeId
import editor.codeintel.model.ScopeRecord
import editor.codeintel.model.SemanticIds
import editor.codeintel.model.SemanticSource
import editor.codeintel.model.SymbolId
import editor.codeintel.model.SymbolFlags
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
    val types: Map<TypeId, TypeRecord>,
    val expressionTypes: List<ExpressionTypeRecord> = emptyList(),
    val dependencies: List<FileDependencyRecord> = emptyList(),
    val resolutionGenerations: Map<FileId, Long> = emptyMap()
)

class SemanticResolver {
    fun resolve(deltas: Collection<FileSemanticDelta>): ResolvedSemanticProject =
        resolveInternal(deltas, baseline = null, affectedFiles = deltas.mapTo(linkedSetOf()) { it.fileId })

    fun resolveIncremental(
        deltas: Collection<FileSemanticDelta>,
        baseline: ResolvedSemanticProject,
        affectedFiles: Set<FileId>
    ): ResolvedSemanticProject = resolveInternal(deltas, baseline, affectedFiles)

    private fun resolveInternal(
        deltas: Collection<FileSemanticDelta>,
        baseline: ResolvedSemanticProject?,
        affectedFiles: Set<FileId>
    ): ResolvedSemanticProject {
        val deltaMap = deltas.associateBy { it.fileId }
        val scopes = deltas.flatMap { it.scopes }.distinctBy { it.id }
        // A frontend may report the same stable declaration more than once while
        // recovering from an incomplete tree. Stable identity makes the duplicate
        // safe to collapse before persistence (which enforces symbol_id uniqueness).
        val rawSymbols = deltas.flatMap { it.symbols }.distinctBy { it.id }
        val baselineSymbols = baseline?.symbols.orEmpty().associateBy { it.id }
        var symbols = rawSymbols.map { raw ->
            if (raw.fileId in affectedFiles) raw else baselineSymbols[raw.id] ?: raw
        }
        val types = linkedMapOf<TypeId, TypeRecord>().apply { putAll(baseline?.types.orEmpty()) }
        val symbolFileById = rawSymbols.associate { it.id to it.fileId }
        val validSymbolIds = symbolFileById.keys
        val relations = (
            deltas.flatMap { it.relations } +
                baseline?.relations.orEmpty().filter { relation ->
                    symbolFileById[relation.from] !in affectedFiles &&
                        relation.from in validSymbolIds && relation.to in validSymbolIds
                }
            ).distinctBy { Triple(it.from, it.to, it.kind) }.toMutableList()

        val symbolsById = { symbols.associateBy { it.id } }
        deltas.asSequence()
            .filter { it.fileId in affectedFiles }
            .flatMap { it.unresolvedTypes }
            .forEach { unresolved ->
                val owner = symbolsById()[unresolved.ownerSymbolId] ?: return@forEach
                val type = resolveTypeRef(unresolved.name, unresolved.scopeId, owner.fileId, symbols, scopes)
                if (type == null) return@forEach
                val typeId = typeId(type)
                val crossesFileBoundary = namedTypes(type).any { named ->
                    symbols.firstOrNull { it.id == named.symbolId }?.fileId != owner.fileId
                }
                val level = if (crossesFileBoundary) {
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
                primaryNamedType(type)?.let { named ->
                    val relationKind = when (unresolved.role) {
                        TypeRole.DECLARED -> RelationKind.TYPE_OF
                        TypeRole.PARAMETER -> RelationKind.PARAMETER_TYPE
                        TypeRole.RETURN -> RelationKind.RETURNS
                        TypeRole.SUPER_TYPE -> RelationKind.EXTENDS
                    }
                    relations += RelationRecord(owner.id, named.symbolId, relationKind, unresolved.confidence)
                }
            }

        val pendingTypeHints = deltas.asSequence()
            .filter { it.fileId in affectedFiles }
            .flatMap { it.typeHints }
            .toList()
        for (pass in 0..pendingTypeHints.size) {
            var changed = false
            pendingTypeHints.forEach { hint ->
                val target = symbols.firstOrNull { it.id == hint.targetSymbolId } ?: return@forEach
                if (target.declaredTypeId != null) return@forEach
                val inferred = when (hint.kind) {
                    TypeHintKind.CONSTRUCTOR_CALL -> resolveNamedType(
                        hint.referencedName.substringAfterLast('.'),
                        hint.scopeId,
                        target.fileId,
                        symbols,
                        scopes
                    )
                    TypeHintKind.INITIALIZER_CALL -> {
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
                    TypeHintKind.ASSIGNMENT -> resolveAssignedType(
                        hint.referencedName,
                        hint.scopeId,
                        target.fileId,
                        hint.expressionRange.startOffset,
                        symbols,
                        scopes,
                        relations,
                        types
                    )
                    TypeHintKind.CAST -> resolveTypeRef(
                        hint.referencedName,
                        hint.scopeId,
                        target.fileId,
                        symbols,
                        scopes
                    )
                    TypeHintKind.LITERAL -> primitiveType(hint.referencedName)
                }
                    ?: return@forEach
                val existingType = target.inferredTypeId?.let(types::get)?.ref
                val mergedType = mergeInferredTypes(existingType, inferred)
                val typeId = typeId(mergedType)
                if (target.inferredTypeId == typeId) return@forEach
                types[typeId] = TypeRecord(
                    typeId,
                    mergedType,
                    hint.confidence,
                    TypeKnowledgeLevel.LOCALLY_INFERRED
                )
                symbols = symbols.map { symbol ->
                    if (symbol.id == target.id && symbol.declaredTypeId == null) {
                        symbol.copy(inferredTypeId = typeId, confidence = hint.confidence)
                    } else {
                        symbol
                    }
                }
                changed = true
                namedTypes(mergedType).forEach { named ->
                    relations += RelationRecord(target.id, named.symbolId, RelationKind.TYPE_OF, hint.confidence)
                }
            }
            if (!changed) break
        }

        val occurrenceById = linkedMapOf<Long, OccurrenceRecord>()
        baseline?.occurrences.orEmpty()
            .filter { it.fileId !in affectedFiles && it.fileId in deltaMap }
            .forEach { occurrenceById[it.id] = it }
        deltas.asSequence()
            .filter { it.fileId in affectedFiles }
            .flatMap { it.occurrences }
            .sortedWith(compareBy<OccurrenceRecord> { it.fileId.value }.thenBy { it.range.startOffset })
            .forEach { occurrence ->
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

        val generations = deltaMap.keys.associateWith { fileId ->
            val previous = baseline?.resolutionGenerations?.get(fileId)
                ?: baseline?.deltas?.get(fileId)?.file?.dependencyGeneration
                ?: deltaMap[fileId]?.file?.dependencyGeneration
                ?: 0L
            when {
                fileId !in affectedFiles -> previous
                baseline == null && previous > 0L -> previous
                else -> previous + 1L
            }
        }
        val resolvedDeltas = deltaMap.mapValues { (fileId, delta) ->
            delta.copy(file = delta.file.copy(dependencyGeneration = generations.getValue(fileId)))
        }
        val finalRelations = relations.distinctBy { Triple(it.from, it.to, it.kind) }
        val finalOccurrences = occurrenceById.values.toList()
        val finalExpressionTypes = resolvedDeltas.values
            .flatMap { it.expressionTypes }
            .filter { it.range.endOffset > it.range.startOffset }
            .groupBy { Triple(it.fileId, it.range.startOffset, it.range.endOffset) }
            .values
            .mapNotNull { candidates -> candidates.maxByOrNull { it.confidence.value } }
        val dependencies = deriveDependencies(
            resolvedDeltas.values,
            symbols,
            finalOccurrences,
            finalRelations,
            generations,
            affectedFiles,
            baseline?.dependencies.orEmpty()
        )
        return ResolvedSemanticProject(
            deltas = resolvedDeltas,
            symbols = symbols,
            scopes = scopes,
            occurrences = finalOccurrences,
            relations = finalRelations,
            types = types,
            expressionTypes = finalExpressionTypes,
            dependencies = dependencies,
            resolutionGenerations = generations
        )
    }

    private fun deriveDependencies(
        deltas: Collection<FileSemanticDelta>,
        symbols: List<SymbolRecord>,
        occurrences: List<OccurrenceRecord>,
        relations: List<RelationRecord>,
        generations: Map<FileId, Long>,
        affectedFiles: Set<FileId>,
        baselineDependencies: List<FileDependencyRecord>
    ): List<FileDependencyRecord> {
        val symbolsById = symbols.associateBy { it.id }
        val validFiles = deltas.mapTo(mutableSetOf()) { it.fileId }
        val result = baselineDependencies.filterTo(mutableListOf()) {
            it.fromFileId !in affectedFiles && it.fromFileId in validFiles && it.toFileId in validFiles
        }
        fun add(from: FileId, to: FileId, kind: FileDependencyKind) {
            if (from == to) return
            result += FileDependencyRecord(from, to, kind, generations[from] ?: 0L)
        }

        relations.forEach { relation ->
            val fromFile = symbolsById[relation.from]?.fileId ?: return@forEach
            val toFile = symbolsById[relation.to]?.fileId ?: return@forEach
            if (fromFile !in affectedFiles) return@forEach
            val kind = when (relation.kind) {
                RelationKind.EXTENDS,
                RelationKind.IMPLEMENTS -> FileDependencyKind.INHERITANCE
                RelationKind.CALLS -> FileDependencyKind.CALL
                RelationKind.IMPORTS -> FileDependencyKind.IMPORT
                RelationKind.TYPE_OF,
                RelationKind.RETURNS,
                RelationKind.PARAMETER_TYPE,
                RelationKind.ALIAS_OF -> FileDependencyKind.TYPE_REFERENCE
                else -> FileDependencyKind.REFERENCE
            }
            add(fromFile, toFile, kind)
        }
        occurrences.forEach { occurrence ->
            if (occurrence.fileId !in affectedFiles) return@forEach
            val target = occurrence.resolvedSymbolId?.let(symbolsById::get) ?: return@forEach
            val kind = when (occurrence.kind) {
                OccurrenceKind.CALL -> FileDependencyKind.CALL
                OccurrenceKind.TYPE_REFERENCE -> FileDependencyKind.TYPE_REFERENCE
                OccurrenceKind.MEMBER_REFERENCE -> FileDependencyKind.MEMBER_REFERENCE
                OccurrenceKind.IMPORT -> FileDependencyKind.IMPORT
                else -> FileDependencyKind.REFERENCE
            }
            add(occurrence.fileId, target.fileId, kind)
        }
        deltas.filter { it.fileId in affectedFiles }.forEach { delta ->
            delta.imports.forEach { import ->
                symbols.asSequence()
                    .filter { candidate ->
                        if (import.wildcard) {
                            candidate.qualifiedName?.startsWith("${import.path}.") == true
                        } else {
                            candidate.qualifiedName == import.path
                        }
                    }
                    .map { it.fileId }
                    .distinct()
                    .forEach { add(delta.fileId, it, FileDependencyKind.IMPORT) }
            }
        }
        return result.distinctBy { Triple(it.fromFileId, it.toFileId, it.kind) }
    }

    private fun resolveOccurrence(
        occurrence: OccurrenceRecord,
        occurrenceById: Map<Long, OccurrenceRecord>,
        symbols: List<SymbolRecord>,
        scopes: List<ScopeRecord>,
        relations: List<RelationRecord>,
        types: Map<TypeId, TypeRecord>
    ): SymbolRecord? {
        if (occurrence.text == "this") {
            return ownerTypeForScope(occurrence.scopeId, scopes, symbols)
        }
        if (occurrence.text == "super") {
            val owner = ownerTypeForScope(occurrence.scopeId, scopes, symbols) ?: return null
            val superId = relations.firstOrNull {
                it.from == owner.id && it.kind in setOf(RelationKind.EXTENDS, RelationKind.IMPLEMENTS)
            }?.to
            return superId?.let { id -> symbols.firstOrNull { it.id == id } }
        }
        if (occurrence.kind == OccurrenceKind.MEMBER_REFERENCE) {
            val receiver = occurrence.receiverOccurrenceId?.let(occurrenceById::get)
            val receiverSymbol = receiver?.resolvedSymbolId?.let { id -> symbols.firstOrNull { it.id == id } }
            val receiverType = receiverSymbol?.let { symbol -> typeOfOccurrence(receiver, symbol, types) }
            val receiverTypeId = receiverType?.let(::primaryNamedType)?.symbolId
            if (receiverTypeId != null) {
                return memberSymbols(receiverTypeId, symbols, relations)
                    .firstOrNull {
                        it.name == occurrence.text &&
                            isAccessibleMember(it, occurrence.fileId, occurrence.scopeId, scopes, symbols, relations)
                    }
            }
            return null
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

    private fun resolveTypeRef(
        raw: String,
        scopeId: ScopeId,
        fileId: FileId,
        symbols: List<SymbolRecord>,
        scopes: List<ScopeRecord>
    ): TypeRef? {
        val text = raw.trim()
        if (text.isEmpty()) return null
        if (text.endsWith('?')) {
            return resolveTypeRef(text.dropLast(1), scopeId, fileId, symbols, scopes)?.let(TypeRef::Nullable)
        }
        val arrow = topLevelArrow(text)
        if (arrow >= 0) {
            val parameterText = text.substring(0, arrow).trim().removeSurrounding("(", ")")
            val parameters = splitTopLevel(parameterText).mapNotNull {
                resolveTypeRef(it, scopeId, fileId, symbols, scopes)
            }
            val returns = resolveTypeRef(text.substring(arrow + 2), scopeId, fileId, symbols, scopes)
            return TypeRef.Function(parameters, returns)
        }
        val genericStart = text.indexOf('<')
        if (genericStart > 0 && text.endsWith('>')) {
            val baseName = text.substring(0, genericStart).trim()
            val base = resolveNamedType(baseName, scopeId, fileId, symbols, scopes)
                ?: primitiveType(baseName)
                ?: TypeRef.Unknown
            val arguments = splitTopLevel(text.substring(genericStart + 1, text.length - 1)).map { argument ->
                val normalized = argument.trim().removePrefix("out ").removePrefix("in ")
                if (normalized == "*") TypeRef.Unknown
                else resolveTypeRef(normalized, scopeId, fileId, symbols, scopes) ?: TypeRef.Unknown
            }
            return TypeRef.Generic(base, arguments)
        }
        return resolveNamedType(text, scopeId, fileId, symbols, scopes) ?: primitiveType(text)
    }

    private fun resolveAssignedType(
        name: String,
        scopeId: ScopeId,
        fileId: FileId,
        offset: Int,
        symbols: List<SymbolRecord>,
        scopes: List<ScopeRecord>,
        relations: List<RelationRecord>,
        types: Map<TypeId, TypeRecord>
    ): TypeRef? {
        if (name == "this") return ownerTypeForScope(scopeId, scopes, symbols)?.let { TypeRef.Named(it.id) }
        if (name == "super") {
            val owner = ownerTypeForScope(scopeId, scopes, symbols) ?: return null
            return relations.firstOrNull {
                it.from == owner.id && it.kind in setOf(RelationKind.EXTENDS, RelationKind.IMPLEMENTS)
            }?.to?.let(TypeRef::Named)
        }
        val source = resolveSymbol(name.substringAfterLast('.'), scopeId, fileId, offset, symbols, scopes)
            ?: return null
        if (source.kind in TYPE_KINDS) return TypeRef.Named(source.id)
        return (source.declaredTypeId ?: source.inferredTypeId)?.let(types::get)?.ref
    }

    private fun typeOfOccurrence(
        occurrence: OccurrenceRecord,
        symbol: SymbolRecord,
        types: Map<TypeId, TypeRecord>
    ): TypeRef? {
        if (symbol.kind in TYPE_KINDS && (occurrence.kind == OccurrenceKind.CALL || occurrence.text in setOf("this", "super"))) {
            return TypeRef.Named(symbol.id)
        }
        return (symbol.declaredTypeId ?: symbol.inferredTypeId)?.let(types::get)?.ref
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
            .filter { it.flags and SymbolFlags.PRIVATE == 0L || it.fileId == fileId }
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

    private fun ownerTypeForScope(
        scopeId: ScopeId,
        scopes: List<ScopeRecord>,
        symbols: List<SymbolRecord>
    ): SymbolRecord? {
        val scopesById = scopes.associateBy { it.id }
        val symbolsById = symbols.associateBy { it.id }
        var cursor: ScopeId? = scopeId
        while (cursor != null) {
            val scope = scopesById[cursor] ?: break
            val owner = scope.ownerSymbolId?.let(symbolsById::get)
            if (owner?.kind in TYPE_KINDS) return owner
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

    private fun isAccessibleMember(
        symbol: SymbolRecord,
        fileId: FileId,
        scopeId: ScopeId,
        scopes: List<ScopeRecord>,
        symbols: List<SymbolRecord>,
        relations: List<RelationRecord>
    ): Boolean {
        val visibility = symbol.flags and SymbolFlags.VISIBILITY_MASK
        if (visibility == 0L || visibility == SymbolFlags.PUBLIC || visibility == SymbolFlags.INTERNAL) return true
        val owner = symbol.ownerSymbolId ?: return symbol.fileId == fileId
        val currentType = ownerTypeForScope(scopeId, scopes, symbols) ?: return false
        if (visibility == SymbolFlags.PRIVATE) return currentType.id == owner
        if (visibility != SymbolFlags.PROTECTED) return true

        val visited = mutableSetOf<SymbolId>()
        fun inherits(current: SymbolId): Boolean {
            if (!visited.add(current)) return false
            return relations.asSequence()
                .filter { it.from == current && it.kind in setOf(RelationKind.EXTENDS, RelationKind.IMPLEMENTS) }
                .any { it.to == owner || inherits(it.to) }
        }
        return currentType.id == owner || inherits(currentType.id)
    }

    private fun typeId(type: TypeRef): TypeId = when (type) {
        is TypeRef.Named -> SemanticIds.type("named:${type.symbolId.value}")
        is TypeRef.Primitive -> SemanticIds.type("primitive:${type.name}")
        else -> SemanticIds.type(type.toString())
    }

    private fun mergeInferredTypes(existing: TypeRef?, inferred: TypeRef): TypeRef {
        if (existing == null || existing == inferred) return inferred
        val alternatives = buildList {
            if (existing is TypeRef.Union) addAll(existing.alternatives) else add(existing)
            if (inferred is TypeRef.Union) addAll(inferred.alternatives) else add(inferred)
        }.distinct()
        return TypeRef.Union(alternatives)
    }

    private fun primaryNamedType(type: TypeRef): TypeRef.Named? = when (type) {
        is TypeRef.Named -> type
        is TypeRef.Generic -> primaryNamedType(type.base)
        is TypeRef.Nullable -> primaryNamedType(type.inner)
        is TypeRef.Union -> type.alternatives.firstNotNullOfOrNull(::primaryNamedType)
        else -> null
    }

    private fun namedTypes(type: TypeRef): Sequence<TypeRef.Named> = sequence {
        when (type) {
            is TypeRef.Named -> yield(type)
            is TypeRef.Generic -> {
                yieldAll(namedTypes(type.base))
                type.arguments.forEach { yieldAll(namedTypes(it)) }
            }
            is TypeRef.Function -> {
                type.parameters.forEach { yieldAll(namedTypes(it)) }
                type.returns?.let { yieldAll(namedTypes(it)) }
            }
            is TypeRef.Union -> type.alternatives.forEach { yieldAll(namedTypes(it)) }
            is TypeRef.Nullable -> yieldAll(namedTypes(type.inner))
            else -> Unit
        }
    }

    private fun topLevelArrow(text: String): Int {
        var depth = 0
        for (index in 0 until text.length - 1) {
            when (text[index]) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                '-' -> if (depth == 0 && text[index + 1] == '>') return index
            }
        }
        return -1
    }

    private fun splitTopLevel(text: String): List<String> {
        if (text.isBlank()) return emptyList()
        val result = mutableListOf<String>()
        var start = 0
        var depth = 0
        text.forEachIndexed { index, character ->
            when (character) {
                '<', '(' -> depth++
                '>', ')' -> depth--
                ',' -> if (depth == 0) {
                    result += text.substring(start, index).trim()
                    start = index + 1
                }
            }
        }
        result += text.substring(start).trim()
        return result
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
