package editor.codeintel

import editor.codeintel.completion.CompletionCandidate
import editor.codeintel.completion.CompletionCandidateEngine
import editor.codeintel.completion.CompletionContext
import editor.codeintel.completion.MemberCompletionProvider
import editor.codeintel.frontend.KotlinSemanticAdapter
import editor.codeintel.frontend.SourceFile
import editor.codeintel.index.SemanticIndex
import editor.codeintel.index.SemanticSnapshot
import editor.codeintel.ml.HeuristicCompletionRanker
import editor.codeintel.model.SymbolRecord
import editor.codeintel.model.TypeRef
import editor.codeintel.resolver.BestEffortExpressionTypeResolver

internal class SemanticTestHarness(
    private val index: SemanticIndex = SemanticIndex()
) {
    private val adapter = KotlinSemanticAdapter()
    private val completion = CompletionCandidateEngine(
        listOf(MemberCompletionProvider(BestEffortExpressionTypeResolver())),
        HeuristicCompletionRanker()
    )
    private val sources = mutableMapOf<String, SourceFile>()

    fun index(path: String, annotatedSource: String, version: Long = 1L): SemanticSnapshot {
        val text = annotatedSource.replace(CARET, "")
        val source = SourceFile(path, "kotlin", text, version)
        sources[path] = source
        return index.apply(adapter.extract(source))
    }

    fun snapshot(): SemanticSnapshot = index.snapshot()

    fun symbol(name: String, path: String? = null): SymbolRecord = snapshot().workspaceSymbols()
        .first { it.name == name && (path == null || snapshot().file(it.fileId)?.path == path) }

    fun typeOf(symbol: SymbolRecord): TypeRef = snapshot()
        .type(symbol.declaredTypeId ?: symbol.inferredTypeId)
        ?.ref
        ?: TypeRef.Unknown

    fun completions(path: String, annotatedSource: String): List<CompletionCandidate> {
        val offset = annotatedSource.indexOf(CARET).also { require(it >= 0) { "fixture requires $CARET" } }
        val source = sources.getValue(path)
        val languageContext = adapter.completionContext(source, offset)
        val file = snapshot().file(path) ?: error("$path is not indexed")
        return completion.complete(
            CompletionContext(
                fileId = file.id,
                languageId = "kotlin",
                offset = offset,
                prefix = languageContext.prefix,
                memberAccess = languageContext.memberAccess,
                receiverRange = languageContext.receiverRange
            ),
            snapshot()
        )
    }

    companion object {
        const val CARET = "/*caret*/"
    }
}
