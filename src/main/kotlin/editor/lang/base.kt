package editor.lang

/* =========================================================
 *  CORE ENUMERATIONS
 * ========================================================= */

enum class SymbolKind {
    PACKAGE,
    CLASS,
    TYPE,
    ANONYMOUS_OBJECT,
    FUNCTION,
    METHOD,
    FIELD,
    CONSTANT,
    VARIABLE
}

enum class SymbolType {
    DECLARATION,
    USAGE
}

/* =========================================================
 *  SOURCE LOCATION
 * ========================================================= */

data class Point(
    val row: Int,      // 0-based
    val column: Int    // 0-based
)

/* =========================================================
 *  TREE-SITTER ABSTRACTION
 * ========================================================= */

interface TsNode {
    val type: String
    val text: String

    val startByte: Int
    val endByte: Int

    val startPoint: Point
    val endPoint: Point

    fun parent(): TsNode?
    fun children(): List<TsNode>
    fun allChildren(): List<TsNode> = children()
    fun fieldName(): String?
}

/* =========================================================
 *  LINEARIZED NODE
 * ========================================================= */

data class LinearNode(
    val node: TsNode,
    val path: List<String>,
    val depth: Int
)

/* =========================================================
 *  CONTEXT PASSED TO LANGUAGE ADAPTERS
 * ========================================================= */

data class NodeContext(
    val parent: TsNode?,
    val siblings: List<TsNode>,
    val ancestors: List<TsNode>,
    val fieldName: String?
) {
    companion object {
        fun from(linearNode: LinearNode): NodeContext {
            val ancestors = mutableListOf<TsNode>()
            var p = linearNode.node.parent()
            while (p != null) {
                ancestors += p
                p = p.parent()
            }
            return NodeContext(
                parent = linearNode.node.parent(),
                siblings = linearNode.node.parent()?.children() ?: emptyList(),
                ancestors = ancestors,
                fieldName = linearNode.node.fieldName()
            )
        }
    }
}

/* =========================================================
 *  LANGUAGE ADAPTER CONTRACT
 * ========================================================= */

interface LanguageAdapter {

    fun classify(
        node: TsNode,
        context: NodeContext
    ): ClassifiedNode?

    fun extractIdentifier(node: TsNode): String?
}

/* =========================================================
 *  CLASSIFICATION RESULT
 * ========================================================= */

data class ClassifiedNode(
    val kind: SymbolKind,
    val type: SymbolType,
    val opensScope: Boolean
)

/* =========================================================
 *  FINAL PIPELINE OUTPUT (AUTHORITATIVE)
 * ========================================================= */

data class IdentifierOccurrence(
    val identifier: String,   // fully-qualified, canonical
    val kind: SymbolKind,
    val type: SymbolType,
    val language: String?,
    val parentIdentifier: String?,
    val parentKey: String?,

    val fileName: String,
    val lineNumber: Int,      // 0-based
    val charPosition: Int     // 0-based
)

/* =========================================================
 *  IDENTIFIER COLLECTOR (PIPELINE COLLAPSE)
 * ========================================================= */

class IdentifierCollector {

    fun collect(
        linearNodes: List<LinearNode>,
        adapter: LanguageAdapter,
        fileName: String,
        language: String?
    ): List<IdentifierOccurrence> {

        data class ScopeEntry(val name: String, val depth: Int, val key: String)

        val scopeStack = ArrayDeque<ScopeEntry>()
        val result = mutableListOf<IdentifierOccurrence>()

        for (ln in linearNodes) {
            while (scopeStack.isNotEmpty() && ln.depth <= scopeStack.last().depth) {
                scopeStack.removeLast()
            }
            val classification = adapter.classify(
                ln.node,
                NodeContext.from(ln)
            ) ?: continue

            val localName = adapter.extractIdentifier(ln.node)
                ?: continue

            val parentIdentifier = scopeStack.joinToString(".") { it.name }.ifBlank { null }
            val parentKey = scopeStack.lastOrNull()?.key
            val fqName =
                if (parentIdentifier == null)
                    localName
                else
                    parentIdentifier + "." + localName

            result += IdentifierOccurrence(
                identifier = fqName,
                kind = classification.kind,
                type = classification.type,
                language = language,
                parentIdentifier = parentIdentifier,
                parentKey = parentKey,
                fileName = fileName,
                lineNumber = ln.node.startPoint.row,
                charPosition = ln.node.startPoint.column
            )

            if (classification.type == SymbolType.DECLARATION &&
                classification.opensScope
            ) {
                val scopeDepth = if (classification.kind == SymbolKind.PACKAGE) 0 else (ln.depth - 1).coerceAtLeast(0)
                val scopeKey = makeKey(
                    fileName,
                    ln.node.startPoint.row,
                    ln.node.startPoint.column,
                    classification.kind,
                    fqName
                )
                scopeStack.addLast(ScopeEntry(localName, scopeDepth, scopeKey))
            }
        }

        return result
    }

    private fun makeKey(
        fileName: String,
        lineNumber: Int,
        charPosition: Int,
        kind: SymbolKind,
        identifier: String
    ): String = listOf(fileName, lineNumber, charPosition, kind.name, identifier).joinToString("$")
}
