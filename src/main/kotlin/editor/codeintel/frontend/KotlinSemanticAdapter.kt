package editor.codeintel.frontend

import editor.codeintel.model.Confidence
import editor.codeintel.model.ExpressionTypeRecord
import editor.codeintel.model.FileRecord
import editor.codeintel.model.FileSemanticDelta
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
import editor.codeintel.model.SymbolId
import editor.codeintel.model.SymbolFlags
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.SymbolRecord
import editor.codeintel.model.TypeHint
import editor.codeintel.model.TypeHintKind
import editor.codeintel.model.TypeRef
import editor.codeintel.model.TypeRole
import editor.codeintel.model.UnresolvedTypeRef
import editor.lang.TreeSitterParser
import editor.lang.TreeSitterParsedTree
import editor.lang.TsNode
import org.treesitter.TSInputEdit
import org.treesitter.TSPoint
import org.treesitter.TreeSitterKotlin
import java.nio.charset.StandardCharsets

class KotlinSemanticAdapter : LanguageSemanticAdapter {
    override val languageId: String = "kotlin"

    private val parser = TreeSitterParser(TreeSitterKotlin())
    private data class ParseState(
        val text: String,
        val version: Long,
        val tree: TreeSitterParsedTree
    )
    private val parseStates = LinkedHashMap<String, ParseState>(32, 0.75f, true)

    fun extract(file: SourceFile): FileSemanticDelta {
        val root = parseIncrementally(file)
            ?: error("Tree-sitter Kotlin parser could not parse ${file.path}")
        return extract(file, SyntaxTree(root))
    }

    private fun parseIncrementally(file: SourceFile): TsNode? {
        val previous = synchronized(parseStates) { parseStates[file.path] }
        val edit = previous?.let { computeEdit(it.text, file.text) }
        val parsed = parser.parseTree(file.text, previous?.tree, edit)
            ?: parser.parseTree(file.text)
            ?: return null
        synchronized(parseStates) {
            val current = parseStates[file.path]
            // An immediate reindex can overlap a debounced job. Never let an
            // older parse result replace a newer tree in the reuse cache.
            if (current == null || current.version <= file.version) {
                parseStates[file.path] = ParseState(file.text, file.version, parsed)
            }
            while (parseStates.size > 32) {
                val iterator = parseStates.entries.iterator()
                if (!iterator.hasNext()) break
                iterator.next()
                iterator.remove()
            }
        }
        return parsed.rootNode()
    }

    private fun computeEdit(oldText: String, newText: String): TSInputEdit? {
        if (oldText == newText) return null
        var prefix = 0
        val commonLength = minOf(oldText.length, newText.length)
        while (prefix < commonLength && oldText[prefix] == newText[prefix]) prefix++
        var suffix = 0
        while (
            suffix < oldText.length - prefix &&
            suffix < newText.length - prefix &&
            oldText[oldText.length - suffix - 1] == newText[newText.length - suffix - 1]
        ) {
            suffix++
        }
        val oldEnd = oldText.length - suffix
        val newEnd = newText.length - suffix
        return TSInputEdit(
            byteOffset(oldText, prefix),
            byteOffset(oldText, oldEnd),
            byteOffset(newText, newEnd),
            pointAt(oldText, prefix),
            pointAt(oldText, oldEnd),
            pointAt(newText, newEnd)
        )
    }

    private fun byteOffset(text: String, offset: Int): Int =
        text.substring(0, offset).toByteArray(StandardCharsets.UTF_8).size

    private fun pointAt(text: String, offset: Int): TSPoint {
        val safeOffset = offset.coerceIn(0, text.length)
        val line = text.asSequence().take(safeOffset).count { it == '\n' }
        val lineStart = text.lastIndexOf('\n', safeOffset - 1).let { if (it < 0) 0 else it + 1 }
        val column = text.substring(lineStart, safeOffset).toByteArray(StandardCharsets.UTF_8).size
        return TSPoint(line, column)
    }

    override fun extract(file: SourceFile, tree: SyntaxTree): FileSemanticDelta =
        Builder(file, tree.root).build()

    override fun completionContext(
        file: SourceFile,
        tree: SyntaxTree,
        offset: Int
    ): LanguageCompletionContext = completionContext(file, offset)

    fun completionContext(file: SourceFile, offset: Int): LanguageCompletionContext {
        val before = file.text.substring(0, offset.coerceIn(0, file.text.length))
        val prefix = identifierPrefix(before)
        var cursor = before.length - prefix.length - 1
        while (cursor >= 0 && before[cursor].isWhitespace()) cursor--
        if (cursor < 0 || before[cursor] != '.') {
            return LanguageCompletionContext(memberAccess = false, receiverRange = null, prefix = prefix)
        }
        val receiverRange = receiverRangeBeforeDot(before, cursor)
            ?: return LanguageCompletionContext(memberAccess = false, receiverRange = null, prefix = prefix)
        return LanguageCompletionContext(
            memberAccess = true,
            receiverRange = receiverRange,
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
        private val unresolvedTypes = mutableListOf<UnresolvedTypeRef>()
        private val imports = mutableListOf<ImportRecord>()
        private val typeHints = mutableListOf<TypeHint>()
        private val expressionTypes = mutableListOf<ExpressionTypeRecord>()
        private val lexicalTokens = mutableListOf<LexicalTokenRecord>()
        private val declarationRanges = mutableMapOf<SourceRange, SymbolId>()
        private var packageName: String? = null

        fun build(): FileSemanticDelta {
            val fileScope = ScopeRecord(
                id = SemanticIds.scope(fileId, 0, ScopeKind.FILE),
                fileId = fileId,
                parentId = null,
                ownerSymbolId = null,
                kind = ScopeKind.FILE,
                range = SourceRange(0, file.text.length.coerceAtLeast(1))
            )
            scopes += fileScope
            val contentScope = extractPackage(fileScope) ?: fileScope
            extractImports(contentScope)
            root.children().forEach { child ->
                if (child.type !in setOf("package_header", "import_header", "import_list")) {
                    visit(child, contentScope, ownerSymbolId = null, typeOwnerId = null)
                }
            }
            collectAssignmentHints()
            collectOccurrences(root)
            collectLexicalTokens(root)

            val exportedSymbols = symbols
                .filter { it.ownerSymbolId == null || symbols.any { owner -> owner.id == it.ownerSymbolId && owner.kind in TYPE_KINDS } }
                .sortedBy { it.qualifiedName ?: it.name }
            val exportedIds = exportedSymbols.mapTo(mutableSetOf()) { it.id }
            val exported = buildString {
                append(
                    exportedSymbols.joinToString("|") {
                        "${it.id.value}:${it.qualifiedName}:${it.kind}:${it.flags}"
                    }
                )
                append("#types=")
                append(
                    unresolvedTypes
                        .filter { it.ownerSymbolId in exportedIds }
                        .sortedBy { "${it.ownerSymbolId.value}:${it.role}:${it.name}" }
                        .joinToString("|") { "${it.ownerSymbolId.value}:${it.role}:${it.name}" }
                )
                append("#hints=")
                append(
                    typeHints
                        .filter { it.targetSymbolId in exportedIds }
                        .sortedBy { "${it.targetSymbolId.value}:${it.kind}:${it.referencedName}" }
                        .joinToString("|") { "${it.targetSymbolId.value}:${it.kind}:${it.referencedName}" }
                )
                append("#imports=")
                append(
                    imports
                        .sortedBy { "${it.path}:${it.alias}:${it.wildcard}" }
                        .joinToString("|") { "${it.path}:${it.alias}:${it.wildcard}" }
                )
            }
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
                occurrences = occurrences.sortedBy { it.range.startOffset },
                relations = relations.distinct(),
                unresolvedTypes = unresolvedTypes.distinct(),
                imports = imports.distinct(),
                typeHints = typeHints.distinct(),
                expressionTypes = expressionTypes.distinct(),
                lexicalTokens = lexicalTokens.distinct(),
                exportedSurfaceHash = SemanticIds.hash(exported).toULong().toString(16)
            )
        }

        private fun collectLexicalTokens(node: TsNode) {
            val children = node.allChildren()
            val kind = when {
                node.type.contains("comment") -> LexicalTokenKind.COMMENT
                node.type in STRING_NODES -> LexicalTokenKind.STRING
                node.type in NUMBER_NODES -> LexicalTokenKind.NUMBER
                node.type in BOOLEAN_OR_NULL_NODES -> LexicalTokenKind.KEYWORD
                children.isEmpty() && node.type == node.text && node.text in KOTLIN_KEYWORDS ->
                    LexicalTokenKind.KEYWORD
                else -> null
            }
            if (kind != null && node.endByte > node.startByte) {
                lexicalTokens += LexicalTokenRecord(fileId, node.range(), kind)
                return
            }
            children.forEach(::collectLexicalTokens)
        }

        private fun extractPackage(fileScope: ScopeRecord): ScopeRecord? {
            val header = root.children().firstOrNull { it.type == "package_header" } ?: return null
            val name = header.text.removePrefix("package").trim().takeIf { it.isNotEmpty() } ?: return null
            val localName = name.substringAfterLast('.')
            val nameOffset = findTokenOffset(header, localName) ?: header.startByte
            val extractedSymbol = newSymbol(
                name = localName,
                kind = SymbolKind.PACKAGE,
                node = header,
                nameRange = SourceRange(nameOffset, (nameOffset + localName.length).coerceAtMost(file.text.length)),
                scope = fileScope,
                ownerSymbolId = null
            )
            val symbol = extractedSymbol.copy(qualifiedName = name)
            symbols[symbols.lastIndex] = symbol
            packageName = name
            val packageScope = ScopeRecord(
                id = SemanticIds.scope(fileId, header.startByte, ScopeKind.PACKAGE),
                fileId = fileId,
                parentId = fileScope.id,
                ownerSymbolId = symbol.id,
                kind = ScopeKind.PACKAGE,
                range = SourceRange(header.startByte.coerceAtLeast(0), file.text.length.coerceAtLeast(header.startByte + 1))
            )
            scopes += packageScope
            return packageScope
        }

        private fun extractImports(scope: ScopeRecord) {
            descendants(root)
                .filter { it.type in setOf("import_header", "import_directive") }
                .forEach { node ->
                    val raw = node.text.removePrefix("import").trim()
                    if (raw.isBlank()) return@forEach
                    val path = raw.substringBefore(" as ").trim().removeSuffix(".*")
                    imports += ImportRecord(
                        fileId = fileId,
                        scopeId = scope.id,
                        path = path,
                        alias = raw.substringAfter(" as ", "").trim().ifBlank { null },
                        wildcard = raw.substringBefore(" as ").trim().endsWith(".*"),
                        range = node.range()
                    )
                }
        }

        private fun visit(
            node: TsNode,
            scope: ScopeRecord,
            ownerSymbolId: SymbolId?,
            typeOwnerId: SymbolId?
        ) {
            when (node.type) {
                in TYPE_DECLARATIONS -> visitTypeDeclaration(node, scope, ownerSymbolId)
                "function_declaration" -> visitFunction(node, scope, ownerSymbolId, typeOwnerId)
                "property_declaration" -> visitProperty(node, scope, ownerSymbolId, typeOwnerId)
                "value_parameter", "parameter", "class_parameter" -> visitParameter(node, scope, ownerSymbolId, typeOwnerId)
                "block", "function_body" -> visitBlock(node, scope, ownerSymbolId, typeOwnerId)
                else -> node.children().forEach { visit(it, scope, ownerSymbolId, typeOwnerId) }
            }
        }

        private fun visitTypeDeclaration(node: TsNode, scope: ScopeRecord, ownerSymbolId: SymbolId?) {
            val nameNode = declarationNameNode(node) ?: run {
                node.children().forEach { visit(it, scope, ownerSymbolId, ownerSymbolId) }
                return
            }
            val kind = when (node.type) {
                "interface_declaration" -> SymbolKind.INTERFACE
                "enum_class_declaration", "enum_declaration" -> SymbolKind.ENUM
                "type_alias" -> SymbolKind.TYPE_ALIAS
                else -> SymbolKind.CLASS
            }
            val symbol = newSymbol(nameNode.text.unquote(), kind, node, nameNode.range(), scope, ownerSymbolId)
            val typeScope = ScopeRecord(
                id = SemanticIds.scope(fileId, node.startByte, ScopeKind.TYPE),
                fileId = fileId,
                parentId = scope.id,
                ownerSymbolId = symbol.id,
                kind = ScopeKind.TYPE,
                range = node.range()
            )
            scopes += typeScope
            addOwnership(ownerSymbolId, symbol.id)
            extractSuperTypes(node, symbol, typeScope)
            node.children().filterNot { it.sameRange(nameNode) }.forEach {
                visit(it, typeScope, symbol.id, symbol.id)
            }
        }

        private fun visitFunction(
            node: TsNode,
            scope: ScopeRecord,
            ownerSymbolId: SymbolId?,
            typeOwnerId: SymbolId?
        ) {
            val nameNode = declarationNameNode(node) ?: return
            val kind = if (typeOwnerId != null) SymbolKind.METHOD else SymbolKind.FUNCTION
            val symbol = newSymbol(nameNode.text.unquote(), kind, node, nameNode.range(), scope, ownerSymbolId)
            addOwnership(ownerSymbolId, symbol.id)
            val functionScope = ScopeRecord(
                id = SemanticIds.scope(fileId, node.startByte, ScopeKind.FUNCTION),
                fileId = fileId,
                parentId = scope.id,
                ownerSymbolId = symbol.id,
                kind = ScopeKind.FUNCTION,
                range = node.range()
            )
            scopes += functionScope
            declaredFunctionReturn(node, symbol, functionScope)
            node.children().filterNot { it.sameRange(nameNode) }.forEach {
                visit(it, functionScope, symbol.id, typeOwnerId)
            }
        }

        private fun visitProperty(
            node: TsNode,
            scope: ScopeRecord,
            ownerSymbolId: SymbolId?,
            typeOwnerId: SymbolId?
        ) {
            val variable = descendants(node).firstOrNull { it.type == "variable_declaration" } ?: node
            val nameNode = declarationNameNode(variable) ?: declarationNameNode(node) ?: return
            val kind = when {
                node.text.trimStart().startsWith("const ") -> SymbolKind.CONSTANT
                typeOwnerId != null && nearestScope(scope).kind == ScopeKind.TYPE -> SymbolKind.PROPERTY
                else -> SymbolKind.VARIABLE
            }
            val symbol = newSymbol(nameNode.text.unquote(), kind, node, nameNode.range(), scope, ownerSymbolId)
            if (typeOwnerId != null && nearestScope(scope).kind == ScopeKind.TYPE) addOwnership(typeOwnerId, symbol.id)
            declaredPropertyType(node, symbol, scope)
            initializerHint(node, symbol, scope)
            node.children().filterNot { child -> child.sameRange(nameNode) || child.sameRange(variable) }.forEach {
                visit(it, scope, ownerSymbolId, typeOwnerId)
            }
        }

        private fun visitParameter(
            node: TsNode,
            scope: ScopeRecord,
            ownerSymbolId: SymbolId?,
            typeOwnerId: SymbolId?
        ) {
            val nameNode = declarationNameNode(node) ?: return
            val propertyParameter = node.type == "class_parameter" && PARAMETER_PROPERTY.containsMatchIn(node.text)
            val kind = if (propertyParameter) SymbolKind.PROPERTY else SymbolKind.PARAMETER
            val symbol = newSymbol(nameNode.text.unquote(), kind, node, nameNode.range(), scope, ownerSymbolId)
            if (propertyParameter && typeOwnerId != null) addOwnership(typeOwnerId, symbol.id)
            declaredParameterType(node, symbol, scope)
            node.children().filterNot { it.sameRange(nameNode) }.forEach {
                visit(it, scope, ownerSymbolId, typeOwnerId)
            }
        }

        private fun visitBlock(
            node: TsNode,
            scope: ScopeRecord,
            ownerSymbolId: SymbolId?,
            typeOwnerId: SymbolId?
        ) {
            if (scope.kind == ScopeKind.TYPE) {
                node.children().forEach { visit(it, scope, ownerSymbolId, typeOwnerId) }
                return
            }
            val blockScope = ScopeRecord(
                id = SemanticIds.scope(fileId, node.startByte, ScopeKind.BLOCK),
                fileId = fileId,
                parentId = scope.id,
                ownerSymbolId = ownerSymbolId,
                kind = ScopeKind.BLOCK,
                range = node.range()
            )
            if (scopes.none { it.id == blockScope.id }) scopes += blockScope
            node.children().forEach { visit(it, blockScope, ownerSymbolId, typeOwnerId) }
        }

        private fun newSymbol(
            name: String,
            kind: SymbolKind,
            node: TsNode,
            nameRange: SourceRange,
            scope: ScopeRecord,
            ownerSymbolId: SymbolId?
        ): SymbolRecord {
            val id = SemanticIds.symbol(fileId, nameRange.startOffset, kind, name)
            val ownerName = ownerSymbolId?.let { idValue -> symbols.firstOrNull { it.id == idValue }?.qualifiedName }
            val qualifiedName = listOfNotNull(ownerName ?: packageName, name)
                .filter { it.isNotBlank() }
                .joinToString(".")
                .ifBlank { name }
            val record = SymbolRecord(
                id = id,
                fileId = fileId,
                name = name,
                qualifiedName = qualifiedName,
                kind = kind,
                declarationRange = node.range(),
                nameRange = nameRange,
                scopeId = scope.id,
                ownerSymbolId = ownerSymbolId,
                declaredTypeId = null,
                inferredTypeId = null,
                flags = visibilityFlags(node)
            )
            symbols += record
            declarationRanges[nameRange] = id
            return record
        }

        private fun addOwnership(owner: SymbolId?, member: SymbolId) {
            owner ?: return
            relations += RelationRecord(owner, member, RelationKind.CONTAINS)
            relations += RelationRecord(member, owner, RelationKind.MEMBER_OF)
        }

        private fun extractSuperTypes(node: TsNode, symbol: SymbolRecord, scope: ScopeRecord) {
            val header = node.text.substringBefore('{')
            val match = SUPER_TYPE.find(header) ?: return
            match.groupValues[1].split(',').forEach { raw ->
                val name = raw.trim().substringBefore('(').substringBefore('<').trim()
                if (name.isBlank()) return@forEach
                val localOffset = node.text.indexOf(name)
                val start = (node.startByte + localOffset.coerceAtLeast(0)).coerceAtMost(file.text.length)
                unresolvedTypes += UnresolvedTypeRef(
                    ownerSymbolId = symbol.id,
                    scopeId = scope.id,
                    name = name,
                    role = TypeRole.SUPER_TYPE,
                    range = SourceRange(start, (start + name.length).coerceAtMost(file.text.length))
                )
            }
        }

        private fun declaredPropertyType(node: TsNode, symbol: SymbolRecord, scope: ScopeRecord) {
            val variable = descendants(node).firstOrNull { it.type == "variable_declaration" }
            val typeNode = variable?.let { declaration ->
                descendants(declaration).firstOrNull { it.type in TYPE_CONTEXTS }
            }
            if (typeNode != null) {
                addUnresolvedType(symbol, scope, typeNode, TypeRole.DECLARED)
                return
            }
            val match = DECLARED_TYPE.find(node.text.substringBefore('=')) ?: return
            addUnresolvedType(symbol, scope, match.groupValues[1], TypeRole.DECLARED, node, match.groups[1]?.range?.first ?: 0)
        }

        private fun declaredParameterType(node: TsNode, symbol: SymbolRecord, scope: ScopeRecord) {
            val typeNode = descendants(node).firstOrNull { it.type in TYPE_CONTEXTS }
            if (typeNode != null) {
                addUnresolvedType(symbol, scope, typeNode, TypeRole.PARAMETER)
                return
            }
            val match = DECLARED_TYPE.find(node.text) ?: return
            addUnresolvedType(symbol, scope, match.groupValues[1], TypeRole.PARAMETER, node, match.groups[1]?.range?.first ?: 0)
        }

        private fun declaredFunctionReturn(node: TsNode, symbol: SymbolRecord, scope: ScopeRecord) {
            val parametersEnd = node.children()
                .firstOrNull { it.type == "function_value_parameters" }
                ?.endByte
                ?: symbol.nameRange.endOffset
            val typeNode = node.children().firstOrNull {
                it.startByte >= parametersEnd && it.type in TYPE_CONTEXTS
            }
            if (typeNode != null) {
                addUnresolvedType(symbol, scope, typeNode, TypeRole.RETURN)
                return
            }
            val header = node.text.substringBefore('{').substringBefore('=')
            val match = RETURN_TYPE.find(header) ?: return
            addUnresolvedType(symbol, scope, match.groupValues[1], TypeRole.RETURN, node, match.groups[1]?.range?.first ?: 0)
        }

        private fun addUnresolvedType(
            symbol: SymbolRecord,
            scope: ScopeRecord,
            typeNode: TsNode,
            role: TypeRole
        ) {
            unresolvedTypes += UnresolvedTypeRef(
                ownerSymbolId = symbol.id,
                scopeId = scope.id,
                name = typeNode.text.trim(),
                role = role,
                range = typeNode.range()
            )
        }

        private fun addUnresolvedType(
            symbol: SymbolRecord,
            scope: ScopeRecord,
            rawName: String,
            role: TypeRole,
            node: TsNode,
            relativeOffset: Int
        ) {
            val name = rawName.trim()
            val start = (node.startByte + relativeOffset).coerceIn(0, file.text.length)
            unresolvedTypes += UnresolvedTypeRef(
                ownerSymbolId = symbol.id,
                scopeId = scope.id,
                name = name,
                role = role,
                range = SourceRange(start, (start + name.length).coerceAtMost(file.text.length))
            )
        }

        private fun initializerHint(node: TsNode, symbol: SymbolRecord, scope: ScopeRecord) {
            val initializer = node.children().lastOrNull { child ->
                child.type !in DECLARATION_STRUCTURE_NODES && child.endByte > symbol.nameRange.endOffset
            } ?: return
            addExpressionTypeHint(symbol, scope, initializer)
        }

        private fun collectAssignmentHints() {
            descendants(root)
                .filter { it.type == "assignment" }
                .forEach { assignment ->
                    val children = assignment.children()
                    val targetNode = children.firstOrNull() ?: return@forEach
                    val expression = children.lastOrNull()?.takeUnless { it === targetNode } ?: return@forEach
                    val targetName = descendantsIncluding(targetNode)
                        .firstOrNull { it.type in IDENTIFIER_NODES }
                        ?.text
                        ?.unquote()
                        ?: return@forEach
                    val scope = smallestScope(targetNode.startByte)
                    val target = symbols.asSequence()
                        .filter { it.name == targetName && it.nameRange.startOffset <= targetNode.startByte }
                        .minByOrNull { scopeDistance(scope.id, it.scopeId) }
                        ?: return@forEach
                    addExpressionTypeHint(target, scope, expression)
                }
        }

        private fun addExpressionTypeHint(symbol: SymbolRecord, scope: ScopeRecord, rawExpression: TsNode) {
            val expression = unwrapParentheses(rawExpression)
            val castType = if (expression.type == "as_expression") {
                expression.children().lastOrNull { child -> child.type in TYPE_CONTEXTS }
            } else {
                null
            }
            if (castType != null) {
                typeHints += TypeHint(
                    targetSymbolId = symbol.id,
                    scopeId = scope.id,
                    expressionRange = expression.range(),
                    referencedName = castType.text,
                    kind = TypeHintKind.CAST
                )
                return
            }

            // Navigation expressions require receiver-aware occurrence resolution.
            // Do not turn their first identifier into a confidently wrong assignment type.
            if (expression.type == "navigation_expression") return

            val callable = descendantsIncluding(expression).firstOrNull {
                it.type in setOf("call_expression", "constructor_invocation")
            }
            if (callable != null) {
                val name = descendantsIncluding(callable)
                    .firstOrNull { it.type in IDENTIFIER_NODES }
                    ?.text
                    ?.unquote()
                    ?: return
                typeHints += TypeHint(
                    targetSymbolId = symbol.id,
                    scopeId = scope.id,
                    expressionRange = callable.range(),
                    referencedName = name,
                    kind = if (name.firstOrNull()?.isUpperCase() == true) {
                        TypeHintKind.CONSTRUCTOR_CALL
                    } else {
                        TypeHintKind.INITIALIZER_CALL
                    }
                )
                return
            }

            val reference = descendantsIncluding(expression).firstOrNull {
                it.type in IDENTIFIER_NODES || it.type in setOf("this_expression", "super_expression")
            }
            if (reference != null) {
                typeHints += TypeHint(
                    targetSymbolId = symbol.id,
                    scopeId = scope.id,
                    expressionRange = expression.range(),
                    referencedName = reference.text.unquote(),
                    kind = TypeHintKind.ASSIGNMENT
                )
                return
            }

            literalType(expression)?.let { primitive ->
                typeHints += TypeHint(
                    targetSymbolId = symbol.id,
                    scopeId = scope.id,
                    expressionRange = expression.range(),
                    referencedName = primitive,
                    kind = TypeHintKind.LITERAL
                )
            }
        }

        private fun collectOccurrences(node: TsNode) {
            if (node.type in setOf("this_expression", "super_expression")) {
                val range = node.range()
                occurrences += OccurrenceRecord(
                    id = SemanticIds.occurrence(fileId, range.startOffset, range.endOffset),
                    fileId = fileId,
                    range = range,
                    text = node.text,
                    kind = OccurrenceKind.READ,
                    scopeId = smallestScope(range.startOffset).id,
                    resolvedSymbolId = null,
                    receiverOccurrenceId = null,
                    confidence = Confidence(SemanticSource.TREE_SITTER, 0.90f)
                )
            }
            if (node.type in LITERAL_NODES && node.parent()?.type !in LITERAL_NODES) {
                val range = node.range()
                val primitive = literalType(node)
                if (primitive != null) expressionTypes += ExpressionTypeRecord(
                    fileId = fileId,
                    range = range,
                    type = TypeRef.Primitive(primitive),
                    confidence = Confidence(SemanticSource.TREE_SITTER, 0.90f)
                )
            }
            if (node.type in IDENTIFIER_NODES && node.children().none { it.type in IDENTIFIER_NODES }) {
                val range = node.range()
                val text = node.text.unquote()
                if (text.isNotBlank() && text != "_") {
                    val declaration = declarationRanges[range]
                    val scope = smallestScope(range.startOffset)
                    val kind = when {
                        declaration != null -> OccurrenceKind.DECLARATION
                        node.ancestors().any { it.type in setOf("import_header", "import_directive") } -> OccurrenceKind.IMPORT
                        node.ancestors().any { it.type in TYPE_CONTEXTS } -> OccurrenceKind.TYPE_REFERENCE
                        isMemberReference(range.startOffset) -> OccurrenceKind.MEMBER_REFERENCE
                        isCallReference(range.endOffset) -> OccurrenceKind.CALL
                        else -> OccurrenceKind.READ
                    }
                    val receiverId = if (kind == OccurrenceKind.MEMBER_REFERENCE) {
                        receiverOccurrence(node) ?: receiverBefore(range.startOffset)
                    } else {
                        null
                    }
                    occurrences += OccurrenceRecord(
                        id = SemanticIds.occurrence(fileId, range.startOffset, range.endOffset),
                        fileId = fileId,
                        range = range,
                        text = text,
                        kind = kind,
                        scopeId = scope.id,
                        resolvedSymbolId = declaration,
                        receiverOccurrenceId = receiverId,
                        confidence = Confidence(SemanticSource.TREE_SITTER, if (declaration != null) 0.95f else 0.90f)
                    )
                }
            }
            node.children().forEach(::collectOccurrences)
        }

        private fun receiverOccurrence(memberNode: TsNode): Long? {
            val suffix = memberNode.ancestors().firstOrNull { it.type == "navigation_suffix" } ?: return null
            val navigation = suffix.parent()?.takeIf { it.type == "navigation_expression" } ?: return null
            return occurrences.asSequence()
                .filter {
                    it.range.startOffset >= navigation.startByte &&
                        it.range.endOffset <= suffix.startByte
                }
                .maxByOrNull { it.range.endOffset }
                ?.id
        }

        private fun receiverBefore(memberStart: Int): Long? {
            var cursor = memberStart - 1
            while (cursor >= 0 && file.text[cursor].isWhitespace()) cursor--
            if (cursor < 0 || file.text[cursor] != '.') return null
            cursor--
            while (cursor >= 0 && file.text[cursor].isWhitespace()) cursor--
            val end = cursor + 1
            while (cursor >= 0 && (file.text[cursor].isLetterOrDigit() || file.text[cursor] == '_')) cursor--
            val start = cursor + 1
            if (start >= end) return null
            return SemanticIds.occurrence(fileId, start, end)
        }

        private fun isMemberReference(start: Int): Boolean {
            var cursor = start - 1
            while (cursor >= 0 && file.text[cursor].isWhitespace()) cursor--
            return cursor >= 0 && file.text[cursor] == '.'
        }

        private fun isCallReference(end: Int): Boolean {
            var cursor = end
            while (cursor < file.text.length && file.text[cursor].isWhitespace()) cursor++
            return cursor < file.text.length && file.text[cursor] == '('
        }

        private fun smallestScope(offset: Int): ScopeRecord = scopes
            .filter { it.range.contains(offset) }
            .minByOrNull { it.range.endOffset - it.range.startOffset }
            ?: scopes.first()

        private fun nearestScope(scope: ScopeRecord): ScopeRecord = scope

        private fun scopeDistance(from: ScopeId, target: ScopeId): Int {
            val byId = scopes.associateBy { it.id }
            var cursor: ScopeId? = from
            var distance = 0
            while (cursor != null) {
                if (cursor == target) return distance
                cursor = byId[cursor]?.parentId
                distance++
            }
            return Int.MAX_VALUE
        }

        private fun visibilityFlags(node: TsNode): Long {
            val modifiers = node.children().firstOrNull { it.type == "modifiers" }?.text.orEmpty()
            return when {
                Regex("\\bprivate\\b").containsMatchIn(modifiers) -> SymbolFlags.PRIVATE
                Regex("\\bprotected\\b").containsMatchIn(modifiers) -> SymbolFlags.PROTECTED
                Regex("\\binternal\\b").containsMatchIn(modifiers) -> SymbolFlags.INTERNAL
                else -> SymbolFlags.PUBLIC
            }
        }

        private fun unwrapParentheses(node: TsNode): TsNode {
            var current = node
            while (current.type == "parenthesized_expression") {
                current = current.children().singleOrNull() ?: break
            }
            return current
        }

        private fun literalType(node: TsNode): String? = when (node.type) {
            "string_literal", "line_string_literal", "multi_line_string_literal" -> "String"
            "character_literal" -> "Char"
            "boolean_literal" -> "Boolean"
            "integer_literal" -> if (node.text.endsWith("L", ignoreCase = true)) "Long" else "Int"
            "real_literal" -> if (node.text.endsWith("f", ignoreCase = true)) "Float" else "Double"
            else -> when (node.text.trim()) {
                "true", "false" -> "Boolean"
                else -> null
            }
        }

        private fun declarationNameNode(node: TsNode): TsNode? {
            node.children().firstOrNull { it.fieldName() == "name" && it.type in IDENTIFIER_NODES }?.let { return it }
            return descendants(node)
                .firstOrNull { candidate ->
                    candidate.type in IDENTIFIER_NODES &&
                        candidate.children().none { it.type in IDENTIFIER_NODES } &&
                        candidate.ancestors().takeWhile { it !== node }.none { it.type in TYPE_CONTEXTS }
                }
        }

        private fun findTokenOffset(node: TsNode, token: String): Int? {
            val local = node.text.lastIndexOf(token)
            return if (local >= 0) node.startByte + local else null
        }

        private fun descendants(node: TsNode): Sequence<TsNode> = sequence {
            node.children().forEach { child ->
                yield(child)
                yieldAll(descendants(child))
            }
        }

        private fun descendantsIncluding(node: TsNode): Sequence<TsNode> = sequence {
            yield(node)
            yieldAll(descendants(node))
        }

        private fun TsNode.ancestors(): Sequence<TsNode> = sequence {
            var cursor = parent()
            while (cursor != null) {
                yield(cursor)
                cursor = cursor.parent()
            }
        }

        private fun TsNode.range(): SourceRange = SourceRange(
            startByte.coerceIn(0, file.text.length),
            endByte.coerceIn(startByte.coerceIn(0, file.text.length), file.text.length)
        )

        private fun TsNode.sameRange(other: TsNode): Boolean =
            startByte == other.startByte && endByte == other.endByte
    }

    companion object {
        private val IDENTIFIER_NODES = setOf("simple_identifier", "identifier", "type_identifier", "package_identifier")
        private val TYPE_DECLARATIONS = setOf(
            "class_declaration",
            "object_declaration",
            "interface_declaration",
            "enum_class_declaration",
            "enum_declaration",
            "type_alias"
        )
        private val TYPE_KINDS = setOf(
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.STRUCT,
            SymbolKind.ENUM,
            SymbolKind.TYPE_ALIAS
        )
        private val TYPE_CONTEXTS = setOf(
            "type_identifier",
            "user_type",
            "nullable_type",
            "type_reference",
            "type_argument_list",
            "type_projection",
            "type_constraint",
            "supertype"
        )
        private val DECLARATION_STRUCTURE_NODES = setOf(
            "modifiers",
            "binding_pattern_kind",
            "variable_declaration",
            "type_parameters",
            "function_value_parameters"
        )
        private val LITERAL_NODES = setOf(
            "string_literal",
            "line_string_literal",
            "multi_line_string_literal",
            "character_literal",
            "boolean_literal",
            "integer_literal",
            "real_literal"
        )
        private val STRING_NODES = setOf(
            "string_literal",
            "line_string_literal",
            "multi_line_string_literal",
            "character_literal"
        )
        private val NUMBER_NODES = setOf("integer_literal", "real_literal")
        private val BOOLEAN_OR_NULL_NODES = setOf("boolean_literal", "null_literal")
        private val KOTLIN_KEYWORDS = setOf(
            "as", "break", "class", "continue", "do", "else", "false", "for", "fun", "if", "in",
            "interface", "is", "null", "object", "package", "return", "super", "this", "throw", "true",
            "try", "typealias", "typeof", "val", "var", "when", "while", "by", "catch", "constructor",
            "delegate", "dynamic", "field", "file", "finally", "get", "import", "init", "param", "property",
            "receiver", "set", "setparam", "where", "actual", "abstract", "annotation", "companion", "const",
            "crossinline", "data", "enum", "expect", "external", "final", "infix", "inline", "inner",
            "internal", "lateinit", "noinline", "open", "operator", "out", "override", "private", "protected",
            "public", "reified", "sealed", "suspend", "tailrec", "vararg", "value", "context"
        )
        private val DECLARED_TYPE = Regex(":\\s*([A-Za-z_][A-Za-z0-9_.]*(?:<[^>]+>)?\\??)")
        private val RETURN_TYPE = Regex("\\)\\s*:\\s*([A-Za-z_][A-Za-z0-9_.]*(?:<[^>]+>)?\\??)")
        private val SUPER_TYPE = Regex(":\\s*([^\\n{]+)")
        private val PARAMETER_PROPERTY = Regex("\\b(?:val|var)\\b")

        private fun identifierPrefix(text: String): String =
            text.takeLastWhile { it.isLetterOrDigit() || it == '_' }

        private fun receiverRangeBeforeDot(text: String, dotOffset: Int): SourceRange? {
            var end = dotOffset
            while (end > 0 && text[end - 1].isWhitespace()) end--
            if (end > 0 && text[end - 1] == '?') end--
            while (end > 0 && text[end - 1].isWhitespace()) end--
            if (end <= 0) return null

            var cursor = end - 1
            var parentheses = 0
            var brackets = 0
            while (cursor >= 0) {
                when (val character = text[cursor]) {
                    ')' -> parentheses++
                    '(' -> if (parentheses > 0) parentheses-- else break
                    ']' -> brackets++
                    '[' -> if (brackets > 0) brackets-- else break
                    '\n', '\r', ';', ',', '=', '{', '}' -> if (parentheses == 0 && brackets == 0) break
                    '+', '-', '*', '/', '%', '&', '|', '!' -> if (parentheses == 0 && brackets == 0) break
                    else -> Unit
                }
                cursor--
            }
            val start = (cursor + 1).let { raw ->
                var trimmed = raw
                while (trimmed < end && text[trimmed].isWhitespace()) trimmed++
                trimmed
            }
            return if (start < end) SourceRange(start, end) else null
        }

        private fun String.unquote(): String =
            if (startsWith('`') && endsWith('`') && length > 1) substring(1, length - 1) else this
    }
}
