package editor.codeintel.ml

import editor.codeintel.completion.CompletionCandidate
import editor.codeintel.completion.CompletionContext
import editor.codeintel.completion.CompletionScore
import kotlin.math.ln

interface CompletionRanker {
    fun rank(context: CompletionContext, candidates: List<CompletionCandidate>): List<CompletionCandidate>
}

class HeuristicCompletionRanker : CompletionRanker {
    override fun rank(context: CompletionContext, candidates: List<CompletionCandidate>): List<CompletionCandidate> =
        candidates.map { candidate ->
            candidate.copy(score = score(context, candidate))
        }.sortedWith(
            compareByDescending<CompletionCandidate> { it.score.total }
                .thenBy { it.label.lowercase() }
                .thenBy { it.kind.name }
        )

    private fun score(context: CompletionContext, candidate: CompletionCandidate): CompletionScore {
        val prefix = when {
            context.prefix.isEmpty() -> 0.5f
            candidate.label.equals(context.prefix, ignoreCase = true) -> 1.0f
            candidate.label.startsWith(context.prefix, ignoreCase = true) -> 0.9f
            else -> 0.0f
        }
        val scope = if (candidate.scopeDistance == Int.MAX_VALUE) 0.0f else 1.0f / (1.0f + candidate.scopeDistance)
        val type = if (context.memberAccess && candidate.member) 1.0f else if (candidate.member) 0.6f else 0.3f
        val frequency = (ln(1.0 + candidate.workspaceFrequency) / ln(16.0)).coerceIn(0.0, 1.0).toFloat()
        val recency = if (candidate.sameFileCount > 0) 0.75f else 0.0f
        return CompletionScore(prefix, scope, type, recency, frequency, ml = 0.0f)
    }
}

/** Optional rankers may add an ML score, but never add or resolve candidates. */
interface OnnxCompletionRanker : CompletionRanker
