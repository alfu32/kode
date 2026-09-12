package editor.codeintel.frontend

import editor.codeintel.model.*
import editor.lang.TreeSitterParser
import editor.lang.TsNode
import org.treesitter.TreeSitterC

/** C declarators name the variable separately from the tag/type specifier. */
class CSemanticAdapter : LanguageSemanticAdapter {
    override val languageId = "c"
    private val parser = TreeSitterParser(TreeSitterC())
    override fun extract(file: SourceFile) = extract(file, SyntaxTree(requireNotNull(parser.parse(file.text))))
    override fun completionContext(file: SourceFile, offset: Int): LanguageCompletionContext {
        val before = file.text.take(offset.coerceIn(0, file.text.length))
        val prefix = before.takeLastWhile { it.isLetterOrDigit() || it == '_' }
        val operatorEnd = before.dropLast(prefix.length).trimEnd().length
        val operatorStart = when {
            before.take(operatorEnd).endsWith("->") -> operatorEnd - 2
            before.take(operatorEnd).endsWith('.') -> operatorEnd - 1
            else -> return LanguageCompletionContext(false, null, prefix)
        }
        val receiverEnd = before.take(operatorStart).trimEnd().length
        var start = receiverEnd - 1
        var depth = 0
        while (start >= 0) {
            val ch = before[start]
            when {
                ch == ')' || ch == ']' -> depth++
                ch == '(' || ch == '[' -> if (depth > 0) depth-- else break
                depth == 0 && !(ch.isLetterOrDigit() || ch in "_.->") -> break
            }
            start--
        }
        val receiver = SourceRange(start + 1, receiverEnd)
        return LanguageCompletionContext(receiver.startOffset < receiverEnd, receiver, prefix)
    }
    override fun completionContext(file: SourceFile, tree: SyntaxTree, offset: Int) = completionContext(file, offset)
    override fun extract(file: SourceFile, tree: SyntaxTree): FileSemanticDelta = Builder(file, tree.root).build()

    private class Builder(val file: SourceFile, val root: TsNode) {
        val id = SemanticIds.file(file.path)
        val scopes = mutableListOf<ScopeRecord>()
        val symbols = linkedMapOf<SymbolId, SymbolRecord>()
        val declarations = mutableMapOf<SourceRange, SymbolId>()
        val occurrences = mutableListOf<OccurrenceRecord>()
        val relations = mutableListOf<RelationRecord>()
        val types = mutableListOf<UnresolvedTypeRef>()
        val lexical = mutableListOf<LexicalTokenRecord>()
        val imports = mutableListOf<ImportRecord>()
        fun range(n: TsNode) = SourceRange(n.startByte, n.endByte)
        fun field(n: TsNode, name: String) = n.children().firstOrNull { it.fieldName() == name }
        fun declarator(n: TsNode): TsNode? = when (n.type) {
            "identifier", "field_identifier", "type_identifier" -> n
            else -> field(n, "declarator")?.let(::declarator)
        }
        fun scope(n: TsNode, kind: ScopeKind, parent: ScopeRecord?, owner: SymbolId?): ScopeRecord {
            val record = ScopeRecord(SemanticIds.scope(id, n.startByte, kind), id, parent?.id, owner, kind, range(n))
            scopes += record
            return record
        }
        fun declare(n: TsNode, name: TsNode?, kind: SymbolKind, scope: ScopeRecord, owner: SymbolId?): SymbolRecord {
            val text = name?.text ?: "__anonymous_at_${n.startByte}"
            val nameRange = name?.let(::range) ?: SourceRange(n.startByte, n.startByte)
            val symbol = SymbolRecord(SemanticIds.symbol(id, nameRange.startOffset, kind, text), id,
                text, owner?.let { symbols[it]?.qualifiedName }?.let { "$it.$text" } ?: text,
                kind, range(n), nameRange, scope.id, owner, null, null, 0L)
            symbols[symbol.id] = symbol
            if (name != null) declarations[nameRange] = symbol.id
            if (owner != null) {
                relations += RelationRecord(owner, symbol.id, RelationKind.CONTAINS)
                relations += RelationRecord(symbol.id, owner, RelationKind.MEMBER_OF)
            }
            return symbol
        }
        fun typeName(n: TsNode?): String? = n?.let {
            if (it.type in setOf("struct_specifier", "union_specifier", "enum_specifier")) {
                field(it, "name")?.text ?: "__anonymous_at_${it.startByte}"
            } else it.text
        }
        fun visit(n: TsNode, parent: ScopeRecord, owner: SymbolId?) {
            var active = parent
            var activeOwner = owner
            if (n.type in setOf("struct_specifier", "union_specifier", "enum_specifier") && field(n, "body") != null) {
                val symbol = declare(n, field(n, "name"), if (n.type == "enum_specifier") SymbolKind.ENUM else SymbolKind.STRUCT, parent, owner)
                activeOwner = symbol.id
                active = scope(n, ScopeKind.TYPE, parent, symbol.id)
            } else if (n.type == "function_definition") {
                val symbol = declare(n, field(n, "declarator")?.let(::declarator), SymbolKind.FUNCTION, parent, owner)
                typeName(field(n, "type"))?.let { types += UnresolvedTypeRef(symbol.id, parent.id, it, TypeRole.RETURN, range(n)) }
                activeOwner = symbol.id
                active = scope(n, ScopeKind.FUNCTION, parent, symbol.id)
            } else if (n.type == "compound_statement") {
                active = scope(n, ScopeKind.BLOCK, parent, owner)
            } else if (n.type in setOf("declaration", "field_declaration", "parameter_declaration", "type_definition")) {
                val kind = when (n.type) {
                    "type_definition" -> SymbolKind.TYPE_ALIAS
                    "field_declaration" -> SymbolKind.FIELD
                    "parameter_declaration" -> SymbolKind.PARAMETER
                    else -> SymbolKind.VARIABLE
                }
                n.children().filter { it.fieldName() == "declarator" }.forEach { decl ->
                    val name = declarator(decl) ?: return@forEach
                    val symbol = declare(n, name, kind, parent, owner)
                    typeName(field(n, "type"))?.let { types += UnresolvedTypeRef(symbol.id, parent.id, it,
                        if (kind == SymbolKind.PARAMETER) TypeRole.PARAMETER else TypeRole.DECLARED, range(n)) }
                }
            }
            if (n.type == "preproc_include") {
                field(n, "path")?.let { imports += ImportRecord(id, parent.id, it.text.trim('"', '<', '>'), null, false, range(n)) }
            }
            val lexicalKind = when {
                n.type.contains("comment") -> LexicalTokenKind.COMMENT
                n.type == "string_literal" || n.type == "char_literal" -> LexicalTokenKind.STRING
                n.type == "number_literal" -> LexicalTokenKind.NUMBER
                else -> null
            }
            if (lexicalKind != null) lexical += LexicalTokenRecord(id, range(n), lexicalKind)
            if (n.type in setOf("identifier", "field_identifier", "type_identifier")) {
                val p = n.parent()
                val receiver = if (p?.type == "field_expression" && n.fieldName() == "field") field(p, "argument") else null
                fun lastIdentifier(node: TsNode): TsNode? = if (node.type in setOf("identifier", "field_identifier")) node
                    else node.children().asReversed().firstNotNullOfOrNull(::lastIdentifier)
                val receiverNode = receiver?.let(::lastIdentifier)
                val kind = when {
                    declarations.containsKey(range(n)) -> OccurrenceKind.DECLARATION
                    receiver != null -> OccurrenceKind.MEMBER_REFERENCE
                    n.type == "type_identifier" -> OccurrenceKind.TYPE_REFERENCE
                    p?.type == "call_expression" && n.fieldName() == "function" -> OccurrenceKind.CALL
                    else -> OccurrenceKind.READ
                }
                occurrences += OccurrenceRecord(SemanticIds.occurrence(id, n.startByte, n.endByte), id, range(n), n.text,
                    kind, active.id, declarations[range(n)], receiverNode?.let { SemanticIds.occurrence(id, it.startByte, it.endByte) },
                    Confidence(SemanticSource.TREE_SITTER, 0.9f))
            }
            n.children().forEach { visit(it, active, activeOwner) }
        }
        fun build(): FileSemanticDelta {
            visit(root, scope(root, ScopeKind.FILE, null, null), null)
            val exported = symbols.values.filter {
                it.ownerSymbolId == null || symbols[it.ownerSymbolId]?.kind in setOf(SymbolKind.STRUCT, SymbolKind.ENUM)
            }
            val exportedIds = exported.mapTo(mutableSetOf()) { it.id }
            // IDs currently contain offsets, so moving a public declaration also invalidates links to it.
            val surface = exported.joinToString { "${it.id}:${it.qualifiedName}:${it.kind}" } +
                types.filter { it.ownerSymbolId in exportedIds }.joinToString { "${it.ownerSymbolId}:${it.name}" } +
                imports.joinToString { it.path }
            return FileSemanticDelta(FileRecord(id, file.path, "c", SemanticIds.hash(file.text).toULong().toString(16), file.version, file.version),
                scopes.distinctBy { it.id }, symbols.values.toList(), occurrences.sortedBy { it.range.startOffset }, relations,
                types, imports, lexicalTokens = lexical, exportedSurfaceHash = SemanticIds.hash(surface).toString())
        }
    }
}
