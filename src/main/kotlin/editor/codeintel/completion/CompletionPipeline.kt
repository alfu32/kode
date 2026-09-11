package editor.codeintel.completion

import editor.codeintel.index.SemanticSnapshot
import editor.codeintel.model.FileId
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolId
import editor.codeintel.model.SymbolKind
import editor.codeintel.resolver.ExpressionTypeResolver

data class CompletionContext(
    val fileId: FileId,
    val languageId: String,
    val offset: Int,
    val prefix: String,
    val memberAccess: Boolean = false,
    val receiverRange: SourceRange? = null,
    val importContext: Boolean = false
)

data class CompletionScore(
    val prefix: Float = 0.0f,
    val scope: Float = 0.0f,
    val type: Float = 0.0f,
    val recency: Float = 0.0f,
    val frequency: Float = 0.0f,
    val ml: Float = 0.0f
) {
    val total: Float
        get() = prefix * 0.30f + scope * 0.15f + type * 0.25f + recency * 0.10f + frequency * 0.10f + ml * 0.10f
}

data class CompletionCandidate(
    val label: String,
    val detail: String,
    val kind: SymbolKind,
    val symbolId: SymbolId? = null,
    val scopeDistance: Int = Int.MAX_VALUE,
    val sameFileCount: Int = 0,
    val workspaceFrequency: Int = 0,
    val member: Boolean = false,
    val score: CompletionScore = CompletionScore()
)

interface CompletionProvider {
    fun supports(context: CompletionContext): Boolean

    fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate>
}

class LocalScopeCompletionProvider : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean = !context.memberAccess && !context.importContext

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> =
        snapshot.visibleSymbols(context.fileId, context.offset)
            .filter { it.kind in LOCAL_KINDS }
            .map { it.toCandidate("visible symbol", context, snapshot, scopeDistance = 0) }

    companion object {
        private val LOCAL_KINDS = setOf(
            SymbolKind.VARIABLE,
            SymbolKind.PARAMETER,
            SymbolKind.FIELD,
            SymbolKind.PROPERTY,
            SymbolKind.METHOD
        )
    }
}

class MemberCompletionProvider(
    private val expressionTypeResolver: ExpressionTypeResolver
) : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean =
        context.memberAccess && context.receiverRange != null

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> {
        val receiver = context.receiverRange ?: return emptySequence()
        val type = expressionTypeResolver.typeOf(context.fileId, receiver, snapshot)
        return snapshot.members(type).filter { symbol ->
            snapshot.isAccessible(symbol, context.fileId, context.offset)
        }.map { symbol ->
            symbol.toCandidate("member", context, snapshot, scopeDistance = 0, member = true)
        }
    }
}

class TypeCompletionProvider : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean = !context.memberAccess

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> =
        snapshot.workspaceSymbols()
            .filter { it.kind in TYPE_KINDS && snapshot.isAccessible(it, context.fileId, context.offset) }
            .map { it.toCandidate(it.qualifiedName ?: "type", context, snapshot, scopeDistance = 4) }

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

class ImportCompletionProvider : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean = context.importContext

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> =
        snapshot.workspaceSymbols()
            .filter { it.qualifiedName != null && snapshot.isAccessible(it, context.fileId, context.offset) }
            .map { it.toCandidate(it.qualifiedName ?: it.name, context, snapshot, scopeDistance = 8) }
}

class KeywordCompletionProvider(
    private val keywords: (String) -> Collection<String>
) : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean = !context.memberAccess && !context.importContext

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> =
        keywords(context.languageId).asSequence().map {
            CompletionCandidate(label = it, detail = "keyword", kind = SymbolKind.UNKNOWN)
        }
}

class WorkspaceSymbolCompletionProvider : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean = !context.memberAccess && !context.importContext

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> =
        snapshot.workspaceSymbols()
            .filter { snapshot.isAccessible(it, context.fileId, context.offset) }
            .map { it.toCandidate("workspace symbol", context, snapshot, scopeDistance = 10) }
}

class SnippetCompletionProvider : CompletionProvider {
    override fun supports(context: CompletionContext): Boolean = !context.memberAccess && !context.importContext

    override fun candidates(context: CompletionContext, snapshot: SemanticSnapshot): Sequence<CompletionCandidate> {
        if (context.languageId.lowercase() !in setOf("kotlin", "kt")) return emptySequence()
        return sequenceOf(
            CompletionCandidate("fun", "function declaration", SymbolKind.UNKNOWN),
            CompletionCandidate("when", "when expression", SymbolKind.UNKNOWN)
        )
    }
}

class CompletionCandidateEngine(
    private val providers: List<CompletionProvider>,
    private val ranker: editor.codeintel.ml.CompletionRanker
) {
    fun complete(context: CompletionContext, snapshot: SemanticSnapshot, limit: Int = 50): List<CompletionCandidate> {
        val candidates = providers.asSequence()
            .filter { it.supports(context) }
            .flatMap { it.candidates(context, snapshot) }
            .filter { context.prefix.isBlank() || it.label.startsWith(context.prefix, ignoreCase = true) }
            .distinctBy { it.symbolId ?: "${it.kind}:${it.label}" }
            .toList()
        return ranker.rank(context, candidates).take(limit)
    }
}

private fun editor.codeintel.model.SymbolRecord.toCandidate(
    detail: String,
    context: CompletionContext,
    snapshot: SemanticSnapshot,
    scopeDistance: Int,
    member: Boolean = false
): CompletionCandidate = CompletionCandidate(
    label = name,
    detail = detail,
    kind = kind,
    symbolId = id,
    scopeDistance = scopeDistance,
    sameFileCount = snapshot.occurrences(id).count { it.fileId == context.fileId },
    workspaceFrequency = snapshot.occurrences(id).count(),
    member = member
)
