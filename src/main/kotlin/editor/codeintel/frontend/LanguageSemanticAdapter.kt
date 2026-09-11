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

    /** Extract facts directly from source, parsing it with this adapter's grammar. */
    fun extract(file: SourceFile): FileSemanticDelta =
        error("${this::class.simpleName} does not provide a source parser")

    fun extract(file: SourceFile, tree: SyntaxTree): FileSemanticDelta

    fun expressionTypeHints(node: TsNode): List<TypeHint> = emptyList()

    fun completionContext(file: SourceFile, offset: Int): LanguageCompletionContext {
        val cursor = offset.coerceIn(0, file.text.length)
        val before = file.text.substring(0, cursor)
        val prefix = before.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
        var dot = before.length - prefix.length - 1
        while (dot >= 0 && before[dot].isWhitespace()) dot--
        return LanguageCompletionContext(
            memberAccess = dot >= 0 && before[dot] == '.',
            receiverRange = null,
            prefix = prefix
        )
    }

    fun completionContext(file: SourceFile, tree: SyntaxTree, offset: Int): LanguageCompletionContext
}
