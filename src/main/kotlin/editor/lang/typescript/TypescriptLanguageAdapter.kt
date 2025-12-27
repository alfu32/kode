package editor.lang.typescript

import editor.lang.ClassifiedNode
import editor.lang.LanguageAdapter
import editor.lang.NodeContext
import editor.lang.SymbolKind
import editor.lang.SymbolType
import editor.lang.TsNode
import editor.lang.TreeSitterIdentifierPipeline
import editor.lang.TreeSitterParser
import editor.lang.adapters.TypescriptTsAdapter
import org.treesitter.TreeSitterTypescript

class TypescriptLanguageAdapter : LanguageAdapter {
    override fun classify(node: TsNode, context: NodeContext): ClassifiedNode? {
        if (!isIdentifierNode(node)) return null
        val name = extractIdentifier(node) ?: return null
        if (name == "_") return null

        TypescriptTsAdapter.topLevelFormulas.firstOrNull { it.match(node, context) }?.let { formula ->
            return ClassifiedNode(formula.kind, formula.type, opensScope = opensScope(formula.kind))
        }

        val parentType = context.parent?.type
        val fieldName = context.fieldName
        val usageKind = when {
            isTypeContext(context) -> SymbolKind.TYPE
            parentType == "call_expression" && fieldName == "function" -> SymbolKind.FUNCTION
            parentType == "member_expression" && fieldName == "property" -> SymbolKind.FIELD
            else -> SymbolKind.VARIABLE
        }
        return ClassifiedNode(usageKind, SymbolType.USAGE, opensScope = false)
    }

    override fun extractIdentifier(node: TsNode): String? {
        if (!isIdentifierNode(node)) return null
        val raw = node.text.trim()
        if (raw.isEmpty()) return null
        val cleaned = when {
            raw.startsWith("#") -> raw.substring(1)
            raw.startsWith("`") && raw.endsWith("`") && raw.length > 1 -> raw.substring(1, raw.length - 1)
            raw.startsWith("\"") && raw.endsWith("\"") && raw.length > 1 -> raw.substring(1, raw.length - 1)
            raw.startsWith("'") && raw.endsWith("'") && raw.length > 1 -> raw.substring(1, raw.length - 1)
            else -> raw
        }
        return cleaned.ifBlank { null }
    }

    private fun isIdentifierNode(node: TsNode): Boolean =
        node.type in identifierTypes

    private fun isTypeContext(context: NodeContext): Boolean {
        val parentType = context.parent?.type
        if (parentType in typeContexts) return true
        if (context.fieldName in typeFields) return true
        return context.ancestors.any { it.type in typeContexts }
    }

    private fun opensScope(kind: SymbolKind): Boolean =
        kind in setOf(SymbolKind.PACKAGE, SymbolKind.CLASS, SymbolKind.TYPE, SymbolKind.FUNCTION)

    companion object {
        private val identifierTypes = setOf(
            "identifier",
            "type_identifier",
            "property_identifier",
            "private_property_identifier",
            "shorthand_property_identifier_pattern"
        )
        private val typeFields = setOf(
            "type",
            "return_type",
            "constraint",
            "extends",
            "implements",
            "value",
            "default",
            "type_parameters",
            "type_arguments"
        )
        private val typeContexts = setOf(
            "type_annotation",
            "type_identifier",
            "type_parameters",
            "type_arguments",
            "type_parameter",
            "union_type",
            "intersection_type",
            "generic_type",
            "mapped_type",
            "conditional_type",
            "function_type",
            "constructor_type",
            "array_type",
            "tuple_type",
            "object_type",
            "readonly_type",
            "parenthesized_type",
            "type_query",
            "indexed_access_type",
            "infer_type",
            "literal_type",
            "implements_clause",
            "extends_clause",
            "heritage_clause"
        )
    }
}

class TypescriptIdentifierExtractor(
    private val adapter: TypescriptLanguageAdapter = TypescriptLanguageAdapter()
) {
    private val pipeline = TreeSitterIdentifierPipeline(
        parser = TreeSitterParser(TreeSitterTypescript()),
        adapter = adapter
    )

    fun extract(text: String, fileName: String, language: String? = "typescript"): List<editor.lang.IdentifierOccurrence> =
        pipeline.extract(text, fileName, language)
}
