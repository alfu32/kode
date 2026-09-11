package editor.codeintel.frontend

import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.SourceRange
import editor.codeintel.model.TypeHint
import editor.lang.TsNode

data class SourceFile(
    val path: String,
    val languageId: String,
    val text: String,
    val version: Long
)

data class SyntaxTree(val root: TsNode)

data class LanguageCompletionContext(
    val memberAccess: Boolean,
    val receiverRange: SourceRange?,
    val prefix: String
)

interface LanguageSemanticAdapter {
    val languageId: String

    fun extract(file: SourceFile, tree: SyntaxTree): FileSemanticDelta

    fun expressionTypeHints(node: TsNode): List<TypeHint> = emptyList()

    fun completionContext(file: SourceFile, tree: SyntaxTree, offset: Int): LanguageCompletionContext
}
