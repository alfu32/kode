package editor.lang.adapters

import editor.lang.NodeContext
import editor.lang.SymbolKind
import editor.lang.SymbolType
import editor.lang.TsNode

object KotlinTsAdapter {

    data class PathFormula(
        val kind: SymbolKind,
        val description: String,
        val type: SymbolType = typeFor(description),
        val match: (TsNode, NodeContext) -> Boolean
    )

    val topLevelFormulas: List<PathFormula> = listOf(
        PathFormula(
            kind = SymbolKind.PACKAGE,
            description = "package_header / identifier",
            match = { node, context ->
                node.type == "identifier" && context.parent?.type == "package_header"
            }
        ),
        PathFormula(
            kind = SymbolKind.CLASS,
            description = "source_file / class_declaration / type_identifier",
            match = { node, context ->
                node.type == "type_identifier" &&
                    context.parent?.type == "class_declaration" &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.ANONYMOUS_OBJECT,
            description = "source_file / object_declaration / type_identifier",
            match = { node, context ->
                node.type == "type_identifier" &&
                    context.parent?.type == "object_declaration" &&
                    isTopLevel(context)
            }
        ),
        PathFormula(
            kind = SymbolKind.CONSTANT,
            description = "source_file / property_declaration(const) / variable_declaration / simple_identifier",
            match = { node, context ->
                node.type == "simple_identifier" &&
                    context.parent?.type == "variable_declaration" &&
                    isConstProperty(context) &&
                    isTopLevel(context)
            }
        )
    )

    fun typeFor(description: String): SymbolType {
        val declMarkers = listOf(
            "package_header",
            "class_declaration",
            "object_declaration",
            "interface_declaration",
            "enum_class_declaration",
            "enum_declaration",
            "type_alias",
            "function_declaration",
            "property_declaration",
            "variable_declaration",
            "class_parameter",
            "value_parameter",
            "enum_entry"
        )
        return if (declMarkers.any { description.contains(it) }) {
            SymbolType.DECLARATION
        } else {
            SymbolType.USAGE
        }
    }

    private fun isTopLevel(context: NodeContext): Boolean =
        context.ancestors.any { it.type == "source_file" } &&
            context.ancestors.none {
                it.type in setOf(
                    "class_declaration",
                    "object_declaration",
                    "companion_object",
                    "interface_declaration",
                    "enum_class_declaration",
                    "enum_declaration",
                    "function_declaration"
                )
            }

    private fun isConstProperty(context: NodeContext): Boolean {
        val property = context.ancestors.firstOrNull { it.type == "property_declaration" } ?: return false
        return property.children().any { it.type == "modifiers" && it.text.contains("const") }
    }
}
