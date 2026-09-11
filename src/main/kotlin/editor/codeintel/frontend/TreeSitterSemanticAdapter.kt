package editor.codeintel.frontend

import editor.codeintel.model.FileRecord
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.Confidence
import editor.codeintel.model.ImportRecord
import editor.codeintel.model.LexicalTokenKind
import editor.codeintel.model.LexicalTokenRecord
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.RelationKind
import editor.codeintel.model.RelationRecord
import editor.codeintel.model.ScopeId
import editor.codeintel.model.ScopeKind
import editor.codeintel.model.ScopeRecord
import editor.codeintel.model.SemanticIds
import editor.codeintel.model.SemanticSource
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.SymbolRecord
import editor.lang.TreeSitterParser
import editor.lang.TsNode
import java.util.Locale
import org.treesitter.TSLanguage

/**
 * Conservative common frontend for grammars whose declaration vocabulary is
 * close enough to the shared semantic model. Language-specific adapters can
 * enrich this later without changing storage or query APIs.
 */
class TreeSitterSemanticAdapter(
    override val languageId: String,
    language: TSLanguage
) : LanguageSemanticAdapter {
    private val parser = TreeSitterParser(language)

    override fun extract(file: SourceFile): FileSemanticDelta {
        val root = parser.parse(file.text)
            ?: error("Tree-sitter $languageId parser could not parse ${file.path}")
        return extract(file, SyntaxTree(root))
    }

    override fun extract(file: SourceFile, tree: SyntaxTree): FileSemanticDelta =
        Builder(file, tree.root).build()

    override fun completionContext(
        file: SourceFile,
        tree: SyntaxTree,
        offset: Int
    ): LanguageCompletionContext = completionContext(file, offset)

    override fun completionContext(file: SourceFile, offset: Int): LanguageCompletionContext {
        val cursor = offset.coerceIn(0, file.text.length)
        val before = file.text.substring(0, cursor)
        val prefix = before.takeLastWhile { it.isLetterOrDigit() || it == '_' || it == '$' }
        var dot = before.length - prefix.length - 1
        while (dot >= 0 && before[dot].isWhitespace()) dot--
        if (dot < 0 || before[dot] != '.') {
            return LanguageCompletionContext(false, null, prefix)
        }
        var start = dot - 1
        var depth = 0
        while (start >= 0) {
            val character = before[start]
            when {
                character == ')' || character == ']' -> depth++
                character == '(' || character == '[' -> if (depth > 0) depth-- else break
                depth == 0 && !(character.isLetterOrDigit() || character == '_' || character == '$' || character == '.') -> break
            }
            start--
        }
        val receiverStart = (start + 1).coerceAtMost(dot)
        return LanguageCompletionContext(
            memberAccess = receiverStart < dot,
            receiverRange = if (receiverStart < dot) SourceRange(receiverStart, dot) else null,
            prefix = prefix
        )
    }

    private class Builder(
        private val file: SourceFile,
        private val root: TsNode
    ) {
        private val fileId = SemanticIds.file(file.path)
        private val scopes = mutableListOf<ScopeRecord>()
        private val symbols = mutableListOf<SymbolRecord>()
        private val occurrences = mutableListOf<OccurrenceRecord>()
        private val relations = mutableListOf<RelationRecord>()
        private val imports = mutableListOf<ImportRecord>()
        private val lexicalTokens = mutableListOf<LexicalTokenRecord>()
        private val declarationRanges = mutableMapOf<SourceRange, editor.codeintel.model.SymbolId>()
        private val declarationsByName = mutableMapOf<String, MutableList<SymbolRecord>>()
        private val scopeIds = mutableSetOf<ScopeId>()
        private val importRanges = mutableSetOf<SourceRange>()

        fun build(): FileSemanticDelta {
            val fileScope = addScope(ScopeKind.FILE, root, null, null)
            visit(root, fileScope, null)
            resolveOccurrences()
            val exported = symbols
                .filter { it.ownerSymbolId == null || it.kind in TYPE_KINDS }
                .sortedBy { it.qualifiedName ?: it.name }
                .joinToString("|") { "${it.name}:${it.kind}:${it.flags}" }
            return FileSemanticDelta(
                file = FileRecord(
                    id = fileId,
                    path = file.path,
                    languageId = file.languageId,
                    contentHash = SemanticIds.hash(file.text).toULong().toString(16),
                    parseVersion = file.version,
                    semanticVersion = file.version
                ),
                scopes = scopes.sortedBy { it.range.startOffset },
                symbols = symbols.sortedBy { it.nameRange.startOffset },
                occurrences = occurrences.distinct().sortedBy { it.range.startOffset },
                relations = relations.distinct(),
                unresolvedTypes = emptyList(),
                imports = imports.distinct(),
                lexicalTokens = lexicalTokens.distinct(),
                exportedSurfaceHash = SemanticIds.hash(exported).toULong().toString(16)
            )
        }

        private fun visit(node: TsNode, scope: ScopeRecord, ownerSymbolId: editor.codeintel.model.SymbolId?) {
            collectLexical(node)
            collectImport(node, scope)

            val declaration = declarationFor(node)
            var activeScope = scope
            var activeOwner = ownerSymbolId
            if (declaration != null) {
                val (nameNode, kind) = declaration
                val range = range(nameNode)
                val symbol = SymbolRecord(
                    id = SemanticIds.symbol(fileId, range.startOffset, kind, nameNode.text),
                    fileId = fileId,
                    name = nameNode.text,
                    qualifiedName = qualifiedName(nameNode.text, ownerSymbolId),
                    kind = kind,
                    declarationRange = range(node),
                    nameRange = range,
                    scopeId = scope.id,
                    ownerSymbolId = ownerSymbolId,
                    declaredTypeId = null,
                    inferredTypeId = null,
                    flags = 0L
                )
                if (symbols.none { it.id == symbol.id }) {
                    symbols += symbol
                    declarationsByName.getOrPut(symbol.name) { mutableListOf() } += symbol
                    declarationRanges[range] = symbol.id
                    if (ownerSymbolId != null) {
                        relations += RelationRecord(symbol.id, ownerSymbolId, RelationKind.MEMBER_OF)
                        relations += RelationRecord(ownerSymbolId, symbol.id, RelationKind.CONTAINS)
                    }
                }
                activeOwner = symbol.id
                scopeKindFor(node)?.let { activeScope = addScope(it, node, symbol.id, scope.id) }
            } else {
                scopeKindFor(node)?.let { activeScope = addScope(it, node, ownerSymbolId, scope.id) }
            }

            if (isIdentifier(node)) {
                occurrences += OccurrenceRecord(
                    id = SemanticIds.occurrence(fileId, range(node).startOffset, range(node).endOffset),
                    fileId = fileId,
                    range = range(node),
                    text = node.text,
                    kind = OccurrenceKind.UNKNOWN,
                    scopeId = activeScope.id,
                    resolvedSymbolId = null,
                    receiverOccurrenceId = null,
                    confidence = Confidence(SemanticSource.TREE_SITTER, 0.90f)
                )
            }
            node.children().forEach { child -> visit(child, activeScope, activeOwner) }
        }

        private fun resolveOccurrences() {
            val resolved = occurrences.map { occurrence ->
                val declaration = declarationRanges[occurrence.range]
                val symbol = declaration?.let { id -> symbols.firstOrNull { it.id == id } }
                    ?: declarationsByName[occurrence.text]
                        ?.minByOrNull { symbol -> kotlin.math.abs(symbol.nameRange.startOffset - occurrence.range.startOffset) }
                occurrence.copy(
                    kind = if (declaration != null) OccurrenceKind.DECLARATION else OccurrenceKind.UNKNOWN,
                    resolvedSymbolId = symbol?.id
                )
            }
            occurrences.clear()
            occurrences += resolved
        }

        private fun addScope(
            kind: ScopeKind,
            node: TsNode,
            ownerSymbolId: editor.codeintel.model.SymbolId?,
            parentScopeId: ScopeId?
        ): ScopeRecord {
            val id = SemanticIds.scope(fileId, node.startByte, kind)
            val existing = scopes.firstOrNull { it.id == id }
            if (existing != null) return existing
            val record = ScopeRecord(id, fileId, parentScopeId, ownerSymbolId, kind, range(node))
            if (scopeIds.add(id)) scopes += record
            return record
        }

        private fun collectImport(node: TsNode, scope: ScopeRecord) {
            val type = node.type.lowercase(Locale.ROOT)
            if (!type.contains("import") || type.contains("identifier")) return
            val nodeRange = range(node)
            if (!importRanges.add(nodeRange)) return
            val path = node.text
                .removePrefix("import")
                .removePrefix("use")
                .trim()
                .trimEnd(';')
                .trim()
            if (path.isNotEmpty()) {
                imports += ImportRecord(fileId, scope.id, path, null, path.endsWith(".*"), nodeRange)
            }
        }

        private fun collectLexical(node: TsNode) {
            val type = node.type.lowercase(Locale.ROOT)
            val kind = when {
                type.contains("comment") -> LexicalTokenKind.COMMENT
                type.contains("string") || type.contains("template") -> LexicalTokenKind.STRING
                type.contains("number") || type.contains("integer") || type.contains("float") -> LexicalTokenKind.NUMBER
                node.children().isEmpty() && node.text in KEYWORDS -> LexicalTokenKind.KEYWORD
                else -> null
            } ?: return
            val nodeRange = range(node)
            if (nodeRange.endOffset > nodeRange.startOffset) lexicalTokens += LexicalTokenRecord(fileId, nodeRange, kind)
        }

        private fun declarationFor(node: TsNode): Pair<TsNode, SymbolKind>? {
            val type = node.type.lowercase(Locale.ROOT)
            val kind = when {
                type.contains("interface") || type.contains("protocol") -> SymbolKind.INTERFACE
                type.contains("enum") && (type.contains("declaration") || type.contains("specifier")) -> SymbolKind.ENUM
                type.contains("struct") && (type.contains("declaration") || type.contains("specifier")) -> SymbolKind.STRUCT
                type.contains("type_alias") -> SymbolKind.TYPE_ALIAS
                type == "module" -> SymbolKind.MODULE
                type in TYPE_DECLARATIONS || (type.contains("class") && !type.contains("body")) -> SymbolKind.CLASS
                type.contains("constructor") && type.contains("declaration") -> SymbolKind.CONSTRUCTOR
                type.contains("method") && (type.contains("declaration") || type.contains("definition") || type.contains("signature")) -> SymbolKind.METHOD
                type in FUNCTION_DECLARATIONS ||
                    (type.contains("function") && !type.contains("call") && !type.contains("expression")) ||
                    type.contains("function_declaration") || type.contains("function_definition") -> SymbolKind.FUNCTION
                type in PARAMETER_DECLARATIONS || type.contains("parameter") && !type.contains("parameters") -> SymbolKind.PARAMETER
                type in PROPERTY_DECLARATIONS -> SymbolKind.PROPERTY
                type in VARIABLE_DECLARATIONS -> SymbolKind.VARIABLE
                else -> null
            } ?: return null
            val name = node.children().firstOrNull { child ->
                child.fieldName() == "name" || isIdentifier(child)
            } ?: (if (type in TYPE_DECLARATIONS) firstIdentifier(node) else null)
            ?: return null
            if (name.text.isBlank() || name.text in KEYWORDS) return null
            return name to kind
        }

        private fun firstIdentifier(node: TsNode, depth: Int = 0): TsNode? {
            if (depth > 3) return null
            node.children().firstOrNull(::isIdentifier)?.let { return it }
            return node.children().asSequence()
                .mapNotNull { child -> firstIdentifier(child, depth + 1) }
                .firstOrNull()
        }

        private fun scopeKindFor(node: TsNode): ScopeKind? {
            val type = node.type.lowercase(Locale.ROOT)
            return when {
                type in TYPE_DECLARATIONS || type.contains("class_declaration") || type == "module" -> ScopeKind.TYPE
                type in FUNCTION_DECLARATIONS || type.contains("method_") -> ScopeKind.FUNCTION
                type.contains("lambda") || type.contains("arrow_function") -> ScopeKind.LAMBDA
                type.contains("block") || type.contains("body") || type.contains("compound_statement") -> ScopeKind.BLOCK
                else -> null
            }
        }

        private fun qualifiedName(name: String, owner: editor.codeintel.model.SymbolId?): String? {
            val parent = owner?.let { id -> symbols.firstOrNull { it.id == id }?.qualifiedName }
            return if (parent.isNullOrBlank()) name else "$parent.$name"
        }

        private fun isIdentifier(node: TsNode): Boolean = node.type.lowercase(Locale.ROOT) in IDENTIFIER_TYPES

        private fun range(node: TsNode): SourceRange {
            val start = node.startByte.coerceIn(0, file.text.length)
            val end = node.endByte.coerceIn(start, file.text.length)
            return SourceRange(start, end)
        }
    }

    companion object {
        private val TYPE_KINDS = setOf(SymbolKind.CLASS, SymbolKind.INTERFACE, SymbolKind.STRUCT, SymbolKind.ENUM)
        private val IDENTIFIER_TYPES = setOf(
            "identifier", "field_identifier", "property_identifier", "type_identifier",
            "namespace_identifier", "variable_name", "name"
        )
        private val TYPE_DECLARATIONS = setOf(
            "class", "module", "class_declaration", "class_definition", "class_specifier", "interface_declaration",
            "interface_definition", "struct_specifier", "struct_declaration", "enum_declaration",
            "enum_specifier", "protocol_declaration", "type_declaration", "type_definition", "type_alias_declaration"
        )
        private val FUNCTION_DECLARATIONS = setOf(
            "method", "function_declaration", "function_definition", "function_item", "method_declaration",
            "method_definition", "function_item", "constructor_declaration", "function_signature"
        )
        private val PARAMETER_DECLARATIONS = setOf(
            "parameter", "required_parameter", "optional_parameter", "formal_parameter",
            "typed_parameter", "variadic_parameter", "rest_parameter", "parameter_declaration"
        )
        private val VARIABLE_DECLARATIONS = setOf(
            "variable_declarator", "variable_declaration", "lexical_declaration", "short_var_declaration",
            "var_spec", "const_spec", "init_declarator", "let_declaration", "const_declaration",
            "local_variable_declaration", "assignment_pattern", "property_declaration", "declaration_statement"
        )
        private val PROPERTY_DECLARATIONS = setOf(
            "field_declaration", "property_declaration", "public_field_definition", "pair"
        )
        private val KEYWORDS = setOf(
            "as", "break", "case", "catch", "class", "const", "continue", "default", "do", "else",
            "enum", "export", "extends", "false", "finally", "for", "fun", "function", "if", "import",
            "in", "interface", "let", "new", "null", "object", "package", "private", "protected",
            "public", "return", "static", "struct", "super", "switch", "this", "throw", "true", "try",
            "type", "val", "var", "void", "while", "with", "yield", "def", "from", "async", "await",
            "func", "go", "defer", "map", "range", "select", "chan", "where", "using", "namespace"
        )
    }
}
