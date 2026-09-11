package editor.lang

import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TSInputEdit
import org.treesitter.TSTree

class TreeSitterNode(
    private val node: TSNode,
    private val source: String,
    private val parentNode: TreeSitterNode? = null,
    private val field: String? = null
) : TsNode {
    override val type: String
        get() = node.type

    override val text: String
        get() = safeSubstring(node.startByte, node.endByte)

    override val startByte: Int
        get() = node.startByte

    override val endByte: Int
        get() = node.endByte

    override val startPoint: Point
        get() = node.startPoint.toPoint()

    override val endPoint: Point
        get() = node.endPoint.toPoint()

    override fun parent(): TsNode? = parentNode

    override fun children(): List<TsNode> {
        val count = node.namedChildCount
        if (count <= 0) return emptyList()
        val list = ArrayList<TsNode>(count)
        for (i in 0 until count) {
            val child = node.getNamedChild(i)
            if (child == null || child.isNull) continue
            val fieldName = node.getFieldNameForNamedChild(i)
            list.add(TreeSitterNode(child, source, this, fieldName))
        }
        return list
    }

    override fun allChildren(): List<TsNode> {
        val count = node.childCount
        if (count <= 0) return emptyList()
        val list = ArrayList<TsNode>(count)
        for (i in 0 until count) {
            val child = node.getChild(i)
            if (child == null || child.isNull) continue
            val fieldName = node.getFieldNameForChild(i)
            list.add(TreeSitterNode(child, source, this, fieldName))
        }
        return list
    }

    override fun fieldName(): String? = field

    private fun safeSubstring(start: Int, end: Int): String {
        if (start < 0 || end <= start || end > source.length) return ""
        return source.substring(start, end)
    }
}

class TreeSitterParser(private val language: TSLanguage) {
    fun parse(text: String): TsNode? {
        return parseTree(text)?.rootNode()
    }

    fun parseTree(
        text: String,
        previous: TreeSitterParsedTree? = null,
        edit: TSInputEdit? = null
    ): TreeSitterParsedTree? {
        val parser = TSParser()
        val setOk = runCatching { parser.setLanguage(language) }.getOrDefault(false)
        if (!setOk) return null
        val oldTree = previous?.tree?.copy()
        if (oldTree != null && edit != null) oldTree.edit(edit)
        val tree = runCatching { parser.parseString(oldTree, text) }.getOrNull() ?: return null
        val root = tree.rootNode
        if (root.isNull) return null
        return TreeSitterParsedTree(tree, text)
    }
}

class TreeSitterParsedTree internal constructor(
    internal val tree: TSTree,
    private val source: String
) {
    internal fun rootNode(): TsNode = TreeSitterNode(tree.rootNode, source)
}

object TreeSitterLinearizer {
    fun linearize(root: TsNode): List<LinearNode> {
        val result = mutableListOf<LinearNode>()
        fun walk(node: TsNode, path: List<String>, depth: Int) {
            result.add(LinearNode(node, path, depth))
            node.children().forEach { child ->
                walk(child, path + child.type, depth + 1)
            }
        }
        walk(root, listOf(root.type), 0)
        return result
    }
}

class TreeSitterIdentifierPipeline(
    private val parser: TreeSitterParser,
    private val adapter: LanguageAdapter
) {
    fun extract(text: String, fileName: String, language: String?): List<IdentifierOccurrence> {
        val root = parser.parse(text) ?: return emptyList()
        val linear = TreeSitterLinearizer.linearize(root)
        return IdentifierCollector().collect(linear, adapter, fileName, language)
    }
}

private fun TSPoint.toPoint(): Point = Point(row, column)
