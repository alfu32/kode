package editor.codeintel.model

@JvmInline
value class FileId(val value: Long)

@JvmInline
value class SymbolId(val value: Long)

@JvmInline
value class ScopeId(val value: Long)

@JvmInline
value class TypeId(val value: Long)

data class SourceRange(
    val startOffset: Int,
    val endOffset: Int
) {
    init {
        require(startOffset >= 0) { "startOffset must be non-negative" }
        require(endOffset >= startOffset) { "endOffset must not precede startOffset" }
    }

    fun contains(offset: Int): Boolean = offset in startOffset until endOffset
}

enum class SymbolKind {
    PACKAGE,
    MODULE,
    NAMESPACE,
    CLASS,
    INTERFACE,
    STRUCT,
    ENUM,
    TYPE_ALIAS,
    FUNCTION,
    METHOD,
    CONSTRUCTOR,
    FIELD,
    PROPERTY,
    VARIABLE,
    PARAMETER,
    CONSTANT,
    ENUM_MEMBER,
    LABEL,
    MACRO,
    UNKNOWN
}

enum class ScopeKind {
    FILE,
    PACKAGE,
    MODULE,
    NAMESPACE,
    TYPE,
    FUNCTION,
    BLOCK,
    LAMBDA
}

enum class OccurrenceKind {
    DECLARATION,
    READ,
    WRITE,
    READ_WRITE,
    CALL,
    TYPE_REFERENCE,
    IMPORT,
    MEMBER_REFERENCE,
    UNKNOWN
}

enum class RelationKind {
    CONTAINS,
    MEMBER_OF,
    EXTENDS,
    IMPLEMENTS,
    TYPE_OF,
    RETURNS,
    PARAMETER_TYPE,
    IMPORTS,
    CALLS,
    OVERRIDES,
    ALIAS_OF
}

enum class SemanticSource {
    TREE_SITTER,
    STATIC_INFERENCE,
    LSP,
    COMPILER,
    SCIP,
    HEURISTIC,
    ML
}

data class Confidence(
    val source: SemanticSource,
    val value: Float
) {
    init {
        require(value in 0.0f..1.0f) { "confidence must be between 0 and 1" }
    }
}

data class FileRecord(
    val id: FileId,
    val path: String,
    val languageId: String,
    val contentHash: String,
    val parseVersion: Long,
    val semanticVersion: Long,
    val dependencyGeneration: Long = 0L
)

data class SymbolRecord(
    val id: SymbolId,
    val fileId: FileId,
    val name: String,
    val qualifiedName: String?,
    val kind: SymbolKind,
    val declarationRange: SourceRange,
    val nameRange: SourceRange,
    val scopeId: ScopeId,
    val ownerSymbolId: SymbolId?,
    val declaredTypeId: TypeId?,
    val inferredTypeId: TypeId?,
    val flags: Long,
    val confidence: Confidence = Confidence(SemanticSource.TREE_SITTER, 0.90f)
)

data class ScopeRecord(
    val id: ScopeId,
    val fileId: FileId,
    val parentId: ScopeId?,
    val ownerSymbolId: SymbolId?,
    val kind: ScopeKind,
    val range: SourceRange
)

data class OccurrenceRecord(
    val id: Long,
    val fileId: FileId,
    val range: SourceRange,
    val text: String,
    val kind: OccurrenceKind,
    val scopeId: ScopeId,
    val resolvedSymbolId: SymbolId?,
    val receiverOccurrenceId: Long?,
    val confidence: Confidence
)

sealed interface TypeRef {
    data class Named(val symbolId: SymbolId) : TypeRef
    data class Primitive(val name: String) : TypeRef
    data class Generic(val base: TypeRef, val arguments: List<TypeRef>) : TypeRef
    data class Function(val parameters: List<TypeRef>, val returns: TypeRef?) : TypeRef
    data class Union(val alternatives: List<TypeRef>) : TypeRef
    data class Nullable(val inner: TypeRef) : TypeRef
    data object Unknown : TypeRef
}

data class TypeRecord(
    val id: TypeId,
    val ref: TypeRef,
    val confidence: Confidence,
    val knowledgeLevel: TypeKnowledgeLevel
)

enum class TypeKnowledgeLevel {
    UNKNOWN,
    SYNTACTICALLY_DECLARED,
    LOCALLY_INFERRED,
    CROSS_FILE_RESOLVED,
    EXTERNAL_PROVIDER
}

data class RelationRecord(
    val from: SymbolId,
    val to: SymbolId,
    val kind: RelationKind,
    val confidence: Confidence = Confidence(SemanticSource.STATIC_INFERENCE, 0.95f)
)

enum class TypeRole {
    DECLARED,
    RETURN,
    PARAMETER,
    SUPER_TYPE
}

data class UnresolvedTypeRef(
    val ownerSymbolId: SymbolId,
    val scopeId: ScopeId,
    val name: String,
    val role: TypeRole,
    val range: SourceRange,
    val confidence: Confidence = Confidence(SemanticSource.TREE_SITTER, 0.90f)
)

enum class TypeHintKind {
    INITIALIZER_CALL,
    CONSTRUCTOR_CALL,
    ASSIGNMENT
}

data class TypeHint(
    val targetSymbolId: SymbolId,
    val scopeId: ScopeId,
    val expressionRange: SourceRange,
    val referencedName: String,
    val kind: TypeHintKind,
    val confidence: Confidence = Confidence(SemanticSource.STATIC_INFERENCE, 0.85f)
)

data class ImportRecord(
    val fileId: FileId,
    val scopeId: ScopeId,
    val path: String,
    val alias: String?,
    val wildcard: Boolean,
    val range: SourceRange
)

data class FileSemanticDelta(
    val file: FileRecord,
    val scopes: List<ScopeRecord>,
    val symbols: List<SymbolRecord>,
    val occurrences: List<OccurrenceRecord>,
    val relations: List<RelationRecord>,
    val unresolvedTypes: List<UnresolvedTypeRef>,
    val imports: List<ImportRecord>,
    val typeHints: List<TypeHint> = emptyList(),
    val exportedSurfaceHash: String
) {
    val fileId: FileId get() = file.id
    val version: Long get() = file.semanticVersion
}

object SemanticIds {
    fun file(path: String): FileId = FileId(hash("file:$path"))

    fun symbol(fileId: FileId, startOffset: Int, kind: SymbolKind, name: String): SymbolId =
        SymbolId(hash("symbol:${fileId.value}:$startOffset:${kind.name}:$name"))

    fun scope(fileId: FileId, startOffset: Int, kind: ScopeKind): ScopeId =
        ScopeId(hash("scope:${fileId.value}:$startOffset:${kind.name}"))

    fun type(description: String): TypeId = TypeId(hash("type:$description"))

    fun occurrence(fileId: FileId, startOffset: Int, endOffset: Int): Long =
        hash("occurrence:${fileId.value}:$startOffset:$endOffset")

    fun hash(value: String): Long {
        var result = -0x340d631b7bdddcdbL
        value.encodeToByteArray().forEach { byte ->
            result = result xor (byte.toLong() and 0xffL)
            result *= 0x100000001b3L
        }
        return result
    }
}
