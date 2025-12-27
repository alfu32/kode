package editor.lang.kotlin

import editor.lang.ClassifiedNode
import editor.lang.LanguageAdapter
import editor.lang.NodeContext
import editor.lang.SymbolKind
import editor.lang.SymbolType
import editor.lang.TsNode
import org.treesitter.TreeSitterKotlin
import editor.lang.TreeSitterIdentifierPipeline
import editor.lang.TreeSitterParser

class KotlinLanguageAdapter : LanguageAdapter {
    override fun classify(node: TsNode, context: NodeContext): ClassifiedNode? {
        if (!isIdentifierNode(node)) return null
        val name = extractIdentifier(node) ?: return null
        if (name == "_") return null

        val parent = context.parent
        val parentType = parent?.type
        val fieldName = context.fieldName

        if (parentType == "package_header" && node.type == "identifier") {
            return ClassifiedNode(SymbolKind.PACKAGE, SymbolType.DECLARATION, opensScope = true)
        }

        if (parentType == "identifier" && context.ancestors.any { it.type == "package_header" }) return null

        if (fieldName == "name") {
            when (parentType) {
                "class_declaration",
                "object_declaration",
                "companion_object" -> {
                    return ClassifiedNode(SymbolKind.CLASS, SymbolType.DECLARATION, opensScope = true)
                }
                "interface_declaration",
                "enum_class_declaration",
                "enum_declaration" -> {
                    return ClassifiedNode(SymbolKind.TYPE, SymbolType.DECLARATION, opensScope = true)
                }
                "type_alias",
                "type_parameter" -> {
                    return ClassifiedNode(SymbolKind.TYPE, SymbolType.DECLARATION, opensScope = false)
                }
                "function_declaration" -> {
                    val kind = if (isMember(context)) SymbolKind.METHOD else SymbolKind.FUNCTION
                    return ClassifiedNode(kind, SymbolType.DECLARATION, opensScope = true)
                }
                "property_declaration" -> {
                    val kind = when {
                        isConst(parent) -> SymbolKind.CONSTANT
                        isMember(context) -> SymbolKind.FIELD
                        else -> SymbolKind.VARIABLE
                    }
                    return ClassifiedNode(kind, SymbolType.DECLARATION, opensScope = false)
                }
                "value_parameter",
                "parameter" -> {
                    return ClassifiedNode(SymbolKind.VARIABLE, SymbolType.DECLARATION, opensScope = false)
                }
                "enum_entry" -> {
                    return ClassifiedNode(SymbolKind.CONSTANT, SymbolType.DECLARATION, opensScope = false)
                }
            }
        }

        when (parentType) {
            "class_declaration",
            "object_declaration",
            "companion_object" -> {
                if (node.type == "type_identifier" || node.type == "simple_identifier") {
                    return ClassifiedNode(SymbolKind.CLASS, SymbolType.DECLARATION, opensScope = true)
                }
            }
            "interface_declaration",
            "enum_class_declaration",
            "enum_declaration" -> {
                if (node.type == "type_identifier" || node.type == "simple_identifier") {
                    return ClassifiedNode(SymbolKind.TYPE, SymbolType.DECLARATION, opensScope = true)
                }
            }
            "type_alias",
            "type_parameter" -> {
                if (node.type == "type_identifier" || node.type == "simple_identifier") {
                    return ClassifiedNode(SymbolKind.TYPE, SymbolType.DECLARATION, opensScope = false)
                }
            }
            "function_declaration" -> {
                if (node.type == "simple_identifier") {
                    val kind = if (isMember(context)) SymbolKind.METHOD else SymbolKind.FUNCTION
                    return ClassifiedNode(kind, SymbolType.DECLARATION, opensScope = true)
                }
            }
            "value_parameter",
            "parameter" -> {
                if (node.type == "simple_identifier") {
                    return ClassifiedNode(SymbolKind.VARIABLE, SymbolType.DECLARATION, opensScope = false)
                }
            }
            "enum_entry" -> {
                if (node.type == "simple_identifier") {
                    return ClassifiedNode(SymbolKind.CONSTANT, SymbolType.DECLARATION, opensScope = false)
                }
            }
            "class_parameter" -> {
                if (node.type == "simple_identifier") {
                    val kind = if (isPropertyParameter(context.parent)) SymbolKind.FIELD else SymbolKind.VARIABLE
                    return ClassifiedNode(kind, SymbolType.DECLARATION, opensScope = false)
                }
            }
            "variable_declaration" -> {
                if (node.type == "simple_identifier") {
                    val propertyOwner = context.ancestors.firstOrNull { it.type == "property_declaration" }
                    val kind = when {
                        isConst(propertyOwner) -> SymbolKind.CONSTANT
                        isMember(context) -> SymbolKind.FIELD
                        else -> SymbolKind.VARIABLE
                    }
                    return ClassifiedNode(kind, SymbolType.DECLARATION, opensScope = false)
                }
            }
        }

        val usageKind = when {
            isTypeContext(context) -> SymbolKind.TYPE
            parentType == "call_expression" && fieldName in setOf("callee", "function") -> SymbolKind.FUNCTION
            else -> SymbolKind.VARIABLE
        }
        return ClassifiedNode(usageKind, SymbolType.USAGE, opensScope = false)
    }

    override fun extractIdentifier(node: TsNode): String? {
        if (!isIdentifierNode(node)) return null
        val raw = node.text.trim()
        if (raw.isEmpty()) return null
        return if (raw.startsWith("`") && raw.endsWith("`") && raw.length > 1) {
            raw.substring(1, raw.length - 1)
        } else {
            raw
        }
    }

    private fun isIdentifierNode(node: TsNode): Boolean =
        node.type in setOf("simple_identifier", "identifier", "type_identifier")

    private fun isMember(context: NodeContext): Boolean =
        context.ancestors.any { it.type in memberContainers }

    private fun isConst(parent: TsNode?): Boolean {
        parent ?: return false
        return parent.children().any { child ->
            child.type == "modifiers" && child.text.contains("const")
        }
    }

    private fun isPropertyParameter(node: TsNode?): Boolean {
        node ?: return false
        return node.text.contains("val") || node.text.contains("var")
    }

    private fun isTypeContext(context: NodeContext): Boolean {
        val parentType = context.parent?.type
        if (parentType in typeContexts) return true
        if (context.fieldName in setOf("type", "receiver", "supertype")) return true
        return context.ancestors.any { it.type in typeContexts }
    }

    companion object {
        private val memberContainers = setOf(
            "class_declaration",
            "object_declaration",
            "companion_object",
            "interface_declaration",
            "enum_class_declaration",
            "enum_declaration"
        )
        private val typeContexts = setOf(
            "type_identifier",
            "user_type",
            "nullable_type",
            "type_reference",
            "type_argument_list",
            "type_projection",
            "type_constraint",
            "supertype"
        )
    }
}

class KotlinIdentifierExtractor(
    private val adapter: KotlinLanguageAdapter = KotlinLanguageAdapter()
) {
    private val pipeline = TreeSitterIdentifierPipeline(
        parser = TreeSitterParser(TreeSitterKotlin()),
        adapter = adapter
    )

    fun extract(text: String, fileName: String, language: String? = "kotlin"): List<editor.lang.IdentifierOccurrence> =
        pipeline.extract(text, fileName, language)
}
