package editor.lang.adapters

import editor.lang.NodeContext
import editor.lang.SymbolKind
import editor.lang.SymbolType
import editor.lang.TsNode

object TypescriptTsAdapter {

    data class PathFormula(
        val kind: SymbolKind,
        val description: String,
        val type: SymbolType = typeFor(description),
        val match: (TsNode, NodeContext) -> Boolean
    )

    val topLevelFormulas: List<PathFormula> = listOf(
        PathFormula(
            kind = SymbolKind.PACKAGE,
            description = "program / module_or_namespace_declaration / identifier",
            match = { node, context ->
                node.type == "identifier" &&
                    context.parent?.type in setOf("namespace_declaration", "module_declaration") &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.CLASS,
            description = "program / class_declaration / identifier",
            match = { node, context ->
                node.type in setOf("identifier", "type_identifier") &&
                    context.parent?.type == "class_declaration" &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.TYPE,
            description = "program / interface_declaration / type_identifier",
            match = { node, context ->
                node.type == "type_identifier" &&
                    context.parent?.type == "interface_declaration" &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.TYPE,
            description = "program / type_alias_declaration / type_identifier",
            match = { node, context ->
                node.type == "type_identifier" &&
                    context.parent?.type == "type_alias_declaration" &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.CONSTANT,
            description = "program / lexical_declaration(const) / variable_declarator / identifier",
            match = { node, context ->
                node.type == "identifier" &&
                    context.parent?.type == "variable_declarator" &&
                    isConstDeclaration(context) &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.VARIABLE,
            description = "program / lexical_declaration(let|var) / variable_declarator / identifier",
            match = { node, context ->
                node.type == "identifier" &&
                    context.parent?.type == "variable_declarator" &&
                    isLexicalDeclaration(context) &&
                    !isConstDeclaration(context) &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.FUNCTION,
            description = "program / function_declaration / identifier",
            match = { node, context ->
                node.type == "identifier" &&
                    context.parent?.type == "function_declaration" &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.TYPE,
            description = "program / enum_declaration / identifier",
            match = { node, context ->
                node.type == "identifier" &&
                    context.parent?.type == "enum_declaration" &&
                    isTopLevel(context)
            }
        )
    )

    fun typeFor(description: String): SymbolType {
        val declMarkers = listOf(
            "class_declaration",
            "interface_declaration",
            "type_alias_declaration",
            "enum_declaration",
            "function_declaration",
            "variable_declarator",
            "lexical_declaration",
            "module_declaration",
            "namespace_declaration"
        )
        return if (declMarkers.any { description.contains(it) }) {
            SymbolType.DECLARATION
        } else {
            SymbolType.USAGE
        }
    }

    private fun isTopLevel(context: NodeContext): Boolean =
        context.ancestors.any { it.type == "program" } &&
            context.ancestors.none {
                it.type in setOf(
                    "class_declaration",
                    "function_declaration",
                    "function_expression",
                    "arrow_function",
                    "method_definition",
                    "method_signature",
                    "constructor"
                )
            }

    private fun isConstDeclaration(context: NodeContext): Boolean {
        val lexical = context.ancestors.firstOrNull { it.type == "lexical_declaration" } ?: return false
        return lexical.text.contains("const")
    }

    private fun isLexicalDeclaration(context: NodeContext): Boolean =
        context.ancestors.any { it.type == "lexical_declaration" }
}
