package editor.codeintel

import editor.grammars.KeywordSyntaxProvider
import editor.grammars.Token
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.treesitter.TSLanguage
import org.treesitter.TSNode
import org.treesitter.TSParser
import org.treesitter.TSPoint
import org.treesitter.TreeSitterC
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterJson
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterTypescript
import org.treesitter.TreeSitterKotlin
import org.treesitter.TreeSitterSql
import org.treesitter.TreeSitterPhp
import org.treesitter.TreeSitterCss
import org.treesitter.TreeSitterHtml
import org.treesitter.TreeSitterZig
import org.treesitter.TreeSitterMarkdown
import org.treesitter.TreeSitterSwift
import org.treesitter.TreeSitterLua
import org.treesitter.TreeSitterCpp
import org.treesitter.TreeSitterSvelte
import org.treesitter.TreeSitterBash
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterPerl
import org.treesitter.TreeSitterD
import org.treesitter.TreeSitterYaml
import org.treesitter.TreeSitterPascal
import org.treesitter.TreeSitterRuby
import org.treesitter.TreeSitterOcaml
import org.treesitter.TreeSitterCSharp
import react.Color
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.ArrayDeque
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

data class SymbolDef(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val range: IntRange,
    val container: String? = null,
    val line: Int? = null,
    val startColumn: Int? = null,
    val language: String? = null,
    val tsLanguage: String? = null,
    val tsParent: String? = null,
    val tsKind: String? = null,
    val tsIsNamed: Boolean? = null,
    val tsFieldNames: String? = null
)

data class IdentifierToken(
    val line: Int,
    val start: Int,
    val end: Int,
    val declaration: Boolean,
    val name: String,
    val filePath: String? = null,
    val container: String? = null,
    val tsLanguage: String? = null,
    val tsParent: String? = null,
    val tsKind: String? = null,
    val tsIsNamed: Boolean? = null,
    val tsFieldNames: String? = null
)

data class LocalSymbol(
    val name: String,
    val kind: SymbolKind,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val startColumn: Int,
    val endColumn: Int,
    val filePath: String,
    val container: String? = null,
    val tsLanguage: String? = null,
    val tsParent: String? = null,
    val tsKind: String? = null,
    val tsIsNamed: Boolean? = null,
    val tsFieldNames: String? = null
)

data class ExtractedSymbols(
    val symbols: List<LocalSymbol>,
    val identifiersByLine: Map<Int, List<IdentifierToken>>
)

private data class DocumentIndex(
    val version: Long,
    val definitions: List<SymbolDef>,
    val identifiersByLine: Map<Int, List<IdentifierToken>>
)

interface DefinitionExtractor {
    fun extract(path: String, text: String, language: String?): ExtractedSymbols
}

class CodeIntelService(
    private val extractor: DefinitionExtractor = TreeSitterDefinitionExtractor(RegexDefinitionExtractor()),
    private val debounceMs: Long = 200L,
    private val store: DbCodeIntelStore? = null
) : EditorIntelligenceService {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "code-intel").apply { isDaemon = true }
    }
    private val latestVersionByPath = mutableMapOf<String, Long>()
    private val pendingJobs = mutableMapOf<String, ScheduledFuture<*>>()
    private val documents = mutableMapOf<String, DocumentIndex>()
    private val defsByPath = mutableMapOf<String, List<SymbolDef>>()
    private val workspaceIndex = mutableMapOf<String, MutableList<SymbolDef>>()
    private val usagesIndex = mutableMapOf<String, MutableList<IdentifierToken>>()
    private val lock = Any()

    fun indexDocument(path: String, language: String?, text: String, version: Long) {
        if (language.isNullOrBlank()) return
        synchronized(lock) {
            latestVersionByPath[path] = version
            pendingJobs.remove(path)?.cancel(false)
            val future = executor.schedule(
                { performIndex(path, language, text, version) },
                debounceMs,
                TimeUnit.MILLISECONDS
            )
            pendingJobs[path] = future
        }
    }

    fun indexDocumentNow(path: String, language: String?, text: String, version: Long) {
        if (language.isNullOrBlank()) return
        synchronized(lock) {
            latestVersionByPath[path] = version
            pendingJobs.remove(path)?.cancel(false)
        }
        performIndex(path, language, text, version)
    }

    override fun definitions(request: DefinitionRequest): List<NavigationTarget> {
        if (request.symbol.isBlank()) return emptyList()
        val lower = request.symbol.lowercase(Locale.ROOT)
        val doc = synchronized(lock) { documents[request.filePath] }
        val defAtCursor = definitionAt(doc, request.position)
        if (defAtCursor != null) {
            return listOf(
                NavigationTarget(
                    filePath = defAtCursor.filePath,
                    range = TextRange(
                        start = TextPosition(defAtCursor.line ?: 0, defAtCursor.startColumn ?: 0),
                        end = TextPosition(
                            defAtCursor.line ?: 0,
                            (defAtCursor.startColumn ?: 0) + defAtCursor.name.length
                        )
                    ),
                    kind = defAtCursor.kind,
                    name = defAtCursor.name,
                    tsLanguage = defAtCursor.tsLanguage,
                    tsParent = defAtCursor.tsParent,
                    tsKind = defAtCursor.tsKind,
                    tsIsNamed = defAtCursor.tsIsNamed,
                    tsFieldNames = defAtCursor.tsFieldNames
                )
            )
        }
        val defs = synchronized(lock) { workspaceIndex[lower]?.toList().orEmpty() }
            .filter { request.language == null || it.language == null || it.language.equals(request.language, ignoreCase = true) }

        val (_, _) = identifierContext(doc, request.position)
        val narrowed = defs

        return narrowed.mapNotNull { def ->
            val line = def.line ?: return@mapNotNull null
            val col = def.startColumn ?: 0
            NavigationTarget(
                filePath = def.filePath,
                range = TextRange(
                    start = TextPosition(line, col),
                    end = TextPosition(line, col + def.name.length)
                ),
                kind = def.kind,
                name = def.name,
                tsLanguage = def.tsLanguage,
                tsParent = def.tsParent,
                tsKind = def.tsKind,
                tsIsNamed = def.tsIsNamed,
                tsFieldNames = def.tsFieldNames
            )
        }
    }

    override fun references(request: ReferenceRequest): List<NavigationTarget> {
        if (request.symbol.isBlank()) return emptyList()
        val lower = request.symbol.lowercase(Locale.ROOT)
        val results = mutableListOf<NavigationTarget>()
        val doc = synchronized(lock) { documents[request.filePath] }
        val clickedDef = definitionAt(doc, request.position)
        val defs = synchronized(lock) { workspaceIndex[lower]?.toList().orEmpty() }
            .filter { request.language == null || it.language == null || it.language.equals(request.language, ignoreCase = true) }
        if (clickedDef == null) {
            defs.forEach { def ->
                val line = def.line
                val col = def.startColumn
                if (line != null && col != null) {
                    results.add(
                        NavigationTarget(
                            filePath = def.filePath,
                            range = TextRange(
                                start = TextPosition(line, col),
                                end = TextPosition(line, col + def.name.length)
                            ),
                            kind = def.kind,
                            name = def.name
                        )
                    )
                }
            }
        }
        val tokens = synchronized(lock) { usagesIndex[lower]?.toList().orEmpty() }
        tokens.forEach { tok ->
            val file = tok.filePath
            if (file != null) {
                results.add(
                    NavigationTarget(
                        filePath = file,
                        range = TextRange(
                            start = TextPosition(tok.line, tok.start),
                            end = TextPosition(tok.line, tok.end)
                        ),
                        name = tok.name,
                        tsLanguage = tok.tsLanguage,
                        tsParent = tok.tsParent,
                        tsKind = tok.tsKind,
                        tsIsNamed = tok.tsIsNamed,
                        tsFieldNames = tok.tsFieldNames
                    )
                )
            }
        }
        return results
    }

    override fun completions(request: CompletionRequest): List<CompletionItem> {
        val trimmed = request.prefix.trim()
        val items = linkedSetOf<CompletionItem>()

        synchronized(lock) {
            val source = if (trimmed.isEmpty()) {
                workspaceIndex.values.flatten()
            } else {
                val lower = trimmed.lowercase(Locale.ROOT)
                workspaceIndex.entries
                    .filter { (name, _) -> name.startsWith(lower) }
                    .flatMap { it.value }
            }
            source
                .filter { request.language == null || it.language == null || it.language.equals(request.language, ignoreCase = true) }
                .forEach { def ->
                    items.add(
                        CompletionItem(
                            label = def.name,
                            detail = java.io.File(def.filePath).name,
                            kind = def.kind
                        )
                    )
                }
        }

        // If no receiver (no dot), offer language keywords too.
        if (!request.prefix.contains(".")) {
            val lang = request.language
            val kws = KeywordSyntaxProvider.keywords(lang)
            val lower = trimmed.lowercase(Locale.ROOT)
            kws.filter { kw ->
                lower.isEmpty() || kw.lowercase(Locale.ROOT).startsWith(lower)
            }.forEach { kw ->
                items.add(
                    CompletionItem(
                        label = kw,
                        detail = "keyword",
                        kind = SymbolKind.KEYWORD
                    )
                )
            }
        }

        return items.take(50)
    }

    override fun documentSymbols(path: String): List<Symbol> {
        val doc = synchronized(lock) { documents[path] } ?: return emptyList()
        return doc.definitions.mapNotNull { def ->
            val line = def.line ?: return@mapNotNull null
            val col = def.startColumn ?: 0
            Symbol(
                name = def.name,
                kind = def.kind,
                filePath = def.filePath,
                range = TextRange(
                    start = TextPosition(line, col),
                    end = TextPosition(line, col + def.name.length)
                )
            )
        }
    }

    fun documentOutline(path: String): List<SymbolDef> =
        synchronized(lock) { documents[path]?.definitions.orEmpty() }

    data class IndexStats(val files: Int, val symbols: Int, val usages: Int)

    fun stats(): IndexStats = synchronized(lock) {
        val fileCount = documents.size
        val symbolCount = documents.values.sumOf { it.definitions.size }
        val usageCount = usagesIndex.values.sumOf { it.size }
        IndexStats(fileCount, symbolCount, usageCount)
    }

    fun workspaceSymbols(query: String, limit: Int = 64): List<Symbol> {
        if (query.isBlank()) return emptyList()
        val lower = query.lowercase(Locale.ROOT)
        val matches = mutableListOf<Symbol>()
        synchronized(lock) {
            workspaceIndex.forEach { (name, defs) ->
                if (matches.size >= limit) return@forEach
                if (name.contains(lower)) {
                    matches.addAll(defs.mapNotNull { it.toSymbol() })
                }
            }
        }
        return matches.take(limit)
    }

    override fun tokens(request: TokensRequest): List<Token> {
        val doc = synchronized(lock) { documents[request.filePath] }
        if (doc == null || doc.version != request.version) return emptyList()
        val tokens = mutableListOf<Token>()
        request.lines.forEachIndexed { idx, line ->
            val absoluteLine = request.startLine + idx
            val idents = doc.identifiersByLine[absoluteLine].orEmpty()
            idents.forEach { id ->
                val clampedStart = id.start.coerceIn(0, line.length)
                val clampedEnd = id.end.coerceIn(clampedStart, line.length)
                if (clampedEnd <= clampedStart) return@forEach
                val scope = if (id.declaration) DECL_SCOPE else USAGE_SCOPE
                val kind = resolveKind(id.name, request.filePath, doc)
                val scopes = mutableListOf(scope)
                when (kind) {
                    SymbolKind.METHOD -> scopes += "codeintel.method"
                    SymbolKind.FIELD -> scopes += "codeintel.field"
                    else -> {}
                }
                tokens += Token(
                    start = clampedStart,
                    end = clampedEnd,
                    scopes = scopes,
                    line = absoluteLine,
                    text = line.substring(clampedStart, clampedEnd),
                    fg = null
                )
            }
        }
        return tokens
    }

    fun clear() {
        synchronized(lock) {
            pendingJobs.values.forEach { it.cancel(false) }
            pendingJobs.clear()
            latestVersionByPath.clear()
            documents.clear()
            defsByPath.clear()
            workspaceIndex.clear()
        }
    }

    fun loadFromStore() {
        val loaded = store?.loadAll() ?: return
        synchronized(lock) {
            defsByPath.clear()
            workspaceIndex.clear()
            usagesIndex.clear()
            loaded.defs.groupBy { it.filePath }.forEach { (path, defs) ->
                defsByPath[path] = defs
                defs.forEach { def ->
                    val key = def.name.lowercase(Locale.ROOT)
                    val bucket = workspaceIndex.getOrPut(key) { mutableListOf() }
                    bucket.add(def)
                }
            }
            loaded.usages.forEach { tok ->
                val key = tok.name.lowercase(Locale.ROOT)
                val bucket = usagesIndex.getOrPut(key) { mutableListOf() }
                bucket.add(tok)
            }
        }
    }

    fun hasPersistentData(): Boolean = store?.hasData() ?: false

    fun waitForIdle(timeoutMs: Long = 1000L) {
        val jobs = synchronized(lock) { pendingJobs.values.toList() }
        jobs.forEach { future ->
            runCatching { future.get(timeoutMs, TimeUnit.MILLISECONDS) }
        }
    }

    fun shutdown() {
        executor.shutdownNow()
    }

    private fun performIndex(path: String, language: String?, text: String, version: Long) {
        if (language.isNullOrBlank()) return
        val latestVersion = synchronized(lock) { latestVersionByPath[path] }
        if (latestVersion != version) return
        val knownNames = synchronized(lock) { workspaceIndex.keys.toSet() }
        val extracted = extractor.extract(path, text, language)
        val defs = extracted.symbols.map {
            SymbolDef(
                name = it.name,
                kind = it.kind,
                filePath = path,
                range = it.startOffset until it.endOffset,
                container = it.container,
                line = it.line,
                startColumn = it.startColumn,
                language = language,
                tsLanguage = it.tsLanguage ?: language,
                tsParent = it.tsParent ?: it.container,
                tsKind = it.tsKind ?: it.kind.name.lowercase(Locale.ROOT),
                tsIsNamed = it.tsIsNamed ?: true,
                tsFieldNames = it.tsFieldNames
            )
        }
        val allowedNames = (knownNames + defs.map { it.name.lowercase(Locale.ROOT) }).toSet()
        val filteredIdents = extracted.identifiersByLine.mapValues { (_, list) ->
            list.filter { it.name.lowercase(Locale.ROOT) in allowedNames }.map { tok ->
                tok.copy(
                    tsLanguage = tok.tsLanguage ?: language,
                    tsParent = tok.tsParent ?: tok.container,
                    tsKind = tok.tsKind ?: "identifier",
                    tsIsNamed = tok.tsIsNamed ?: true,
                    tsFieldNames = tok.tsFieldNames
                )
            }
        }
        val docIndex = DocumentIndex(
            version = version,
            definitions = defs.sortedBy { it.range.first },
            identifiersByLine = filteredIdents
        )
        synchronized(lock) {
            pendingJobs.remove(path)
            documents[path] = docIndex
            rebuildWorkspaceIndex(path, defs)
            rebuildUsagesIndex(path, docIndex.identifiersByLine)
        }
        store?.storeFile(path, language, defs, docIndex.identifiersByLine)
    }

    private fun rebuildWorkspaceIndex(path: String, newDefs: List<SymbolDef>) {
        val previous = defsByPath[path].orEmpty()
        if (previous.isNotEmpty()) {
            previous.forEach { def ->
                val key = def.name.lowercase(Locale.ROOT)
                workspaceIndex[key]?.removeIf { it.filePath == path && it.range == def.range }
                if (workspaceIndex[key].isNullOrEmpty()) workspaceIndex.remove(key)
            }
        }
        defsByPath[path] = newDefs
        newDefs.forEach { def ->
            val key = def.name.lowercase(Locale.ROOT)
            val bucket = workspaceIndex.getOrPut(key) { mutableListOf() }
            bucket.add(def)
        }
    }

    private fun rebuildUsagesIndex(path: String, identifiersByLine: Map<Int, List<IdentifierToken>>) {
        // remove old entries for this path
        usagesIndex.forEach { (_, list) ->
            list.removeIf { it.filePath == path }
        }
        identifiersByLine.values.flatten().filter { !it.declaration }.forEach { tok ->
            val key = tok.name.lowercase(Locale.ROOT)
            val bucket = usagesIndex.getOrPut(key) { mutableListOf() }
            bucket.add(tok)
        }
    }

    private fun identifierContext(doc: DocumentIndex?, position: TextPosition): Pair<String?, SymbolKind?> {
        doc ?: return null to null
        val tokens = doc.identifiersByLine[position.line].orEmpty()
        tokens.firstOrNull { position.column in it.start until it.end }?.let { tok ->
            return tok.container to null
        }
        val def = definitionAt(doc, position)
        return def?.container to def?.kind
    }

    private fun definitionAt(doc: DocumentIndex?, position: TextPosition): SymbolDef? {
        doc ?: return null
        return doc.definitions.firstOrNull { def ->
            val line = def.line ?: return@firstOrNull false
            val startCol = def.startColumn ?: return@firstOrNull false
            val endCol = startCol + def.name.length
            position.line == line && position.column in startCol until endCol
        }
    }

    private fun SymbolDef.toSymbol(): Symbol? {
        val lineNum = line ?: return null
        val col = startColumn ?: 0
        return Symbol(
            name = name,
            kind = kind,
            container = container,
            filePath = filePath,
            range = TextRange(
                start = TextPosition(lineNum, col),
                end = TextPosition(lineNum, col + name.length)
            )
        )
    }

    private fun resolveKind(name: String, filePath: String?, doc: DocumentIndex): SymbolKind? {
        val lower = name.lowercase(Locale.ROOT)
        doc.definitions.firstOrNull { it.name.equals(name, ignoreCase = true) }?.let { return it.kind }
        synchronized(lock) {
            workspaceIndex[lower]?.firstOrNull { def ->
                filePath == null || def.filePath == filePath || def.name.equals(name, ignoreCase = true)
            }?.let { return it.kind }
        }
        return null
    }

    companion object {
        private const val DECL_SCOPE = "codeintel.declaration"
        private const val USAGE_SCOPE = "codeintel.usage"
    }
}

private class CompositeDefinitionExtractor(
    private val primary: DefinitionExtractor,
    private val fallback: DefinitionExtractor
) : DefinitionExtractor {
    override fun extract(path: String, text: String, language: String?): ExtractedSymbols {
        return runCatching { primary.extract(path, text, language) }.getOrNull()
            ?: fallback.extract(path, text, language)
    }
}

private class TreeSitterDefinitionExtractor(
    private val fallback: DefinitionExtractor = RegexDefinitionExtractor()
) : DefinitionExtractor {
    override fun extract(path: String, text: String, language: String?): ExtractedSymbols {
        val langKey = language?.lowercase(Locale.ROOT) ?: return fallback.extract(path, text, language)
        val tsLang = loadLanguage(langKey) ?: return fallback.extract(path, text, language)
        val parser = TSParser()
        val setOk = runCatching { parser.setLanguage(tsLang) }.getOrDefault(false)
        if (!setOk) return fallback.extract(path, text, language)
        val tree = runCatching { parser.parseString(null, text) }.getOrNull() ?: return fallback.extract(path, text, language)
        val root = tree.rootNode
        val base = fallback.extract(path, text, language)
        val tsLangName = runCatching { tsLang.name() }.getOrNull()
        val enrichedDefs = base.symbols.map { def ->
            val node = findNode(root, def.line, def.startColumn, def.name.length)
            if (node == null) {
                def.copy(tsLanguage = tsLangName)
            } else {
                def.copy(
                    tsLanguage = tsLangName,
                    tsParent = node.parent?.type,
                    tsKind = node.type,
                    tsIsNamed = node.isNamed,
                    tsFieldNames = collectFieldNames(node)
                )
            }
        }
        val enrichedIdents = base.identifiersByLine.mapValues { (_, list) ->
            list.map { tok ->
                val node = findNode(root, tok.line, tok.start, tok.end - tok.start)
                if (node == null) tok.copy(tsLanguage = tsLangName)
                else tok.copy(
                    tsLanguage = tsLangName,
                    tsParent = node.parent?.type,
                    tsKind = node.type,
                    tsIsNamed = node.isNamed,
                    tsFieldNames = collectFieldNames(node)
                )
            }
        }
        return ExtractedSymbols(enrichedDefs, enrichedIdents)
    }

    private fun loadLanguage(lang: String): TSLanguage? =
        when (lang) {
            "typescript", "ts", "tsx" -> runCatching { TreeSitterTypescript() }.getOrNull()
            "javascript", "js" -> runCatching { TreeSitterJavascript() }.getOrNull()
            "python", "py" -> runCatching { TreeSitterPython() }.getOrNull()
            "c" -> runCatching { TreeSitterC() }.getOrNull()
            "json" -> runCatching { TreeSitterJson() }.getOrNull()
            "kotlin", "kt" -> runCatching { TreeSitterKotlin() }.getOrNull()
            "sql" -> runCatching { TreeSitterSql() }.getOrNull()
            "php" -> runCatching { TreeSitterPhp() }.getOrNull()
            "css" -> runCatching { TreeSitterCss() }.getOrNull()
            "html", "htm" -> runCatching { TreeSitterHtml() }.getOrNull()
            "zig" -> runCatching { TreeSitterZig() }.getOrNull()
            "markdown", "md" -> runCatching { TreeSitterMarkdown() }.getOrNull()
            "swift" -> runCatching { TreeSitterSwift() }.getOrNull()
            "lua" -> runCatching { TreeSitterLua() }.getOrNull()
            "cpp", "c++", "cc", "cxx", "hpp", "h++", "hh", "hxx" -> runCatching { TreeSitterCpp() }.getOrNull()
            "svelte" -> runCatching { TreeSitterSvelte() }.getOrNull()
            "bash", "sh" -> runCatching { TreeSitterBash() }.getOrNull()
            "go", "golang" -> runCatching { TreeSitterGo() }.getOrNull()
            "perl", "pl" -> runCatching { TreeSitterPerl() }.getOrNull()
            "d" -> runCatching { TreeSitterD() }.getOrNull()
            "yaml", "yml" -> runCatching { TreeSitterYaml() }.getOrNull()
            "pascal", "pas" -> runCatching { TreeSitterPascal() }.getOrNull()
            "ruby", "rb" -> runCatching { TreeSitterRuby() }.getOrNull()
            "ocaml", "ml", "mli" -> runCatching { TreeSitterOcaml() }.getOrNull()
            "csharp", "cs" -> runCatching { TreeSitterCSharp() }.getOrNull()
            else -> null
        }

    private fun findNode(root: TSNode, line: Int?, startCol: Int?, length: Int): TSNode? {
        if (line == null || startCol == null) return null
        val start = TSPoint(line, startCol)
        val end = TSPoint(line, startCol + length)
        val node = root.getNamedDescendantForPointRange(start, end)
        return if (node.isNull) null else node
    }

    private fun collectFieldNames(node: TSNode): String? {
        val count = node.namedChildCount
        if (count <= 0) return null
        val fields = mutableListOf<String>()
        for (i in 0 until count) {
            val name = node.getFieldNameForNamedChild(i)
            if (name != null && name.isNotBlank()) fields += name
        }
        return if (fields.isEmpty()) null else fields.joinToString(",")
    }
}

private class RegexDefinitionExtractor(
    private val configs: List<LanguageDefinitionConfig> = loadDefaultConfigs()
) : DefinitionExtractor {

    private data class DefinitionPattern(val regex: Regex, val kind: SymbolKind, val groupIndex: Int = 1)
    private data class SanitizedLine(val text: String, val inBlockComment: Boolean)

    private val configByLanguage: Map<String, LanguageDefinitionConfig> =
        configs.associateBy { it.language.lowercase(Locale.ROOT) }
    private val aliasMap: Map<String, String> = configs
        .flatMap { cfg -> cfg.aliases.map { it.lowercase(Locale.ROOT) to cfg.language.lowercase(Locale.ROOT) } }
        .toMap()
    private val blockTrackers: MutableMap<String, BlockTracker> = mutableMapOf()

    private data class ScopeEntry(val name: String, val kind: SymbolKind, val depth: Int)

    override fun extract(path: String, text: String, language: String?): ExtractedSymbols {
        val langKey = language?.lowercase(Locale.ROOT)
        val cfg = configFor(langKey)
        val lines = text.split("\n")
        val sanitized = sanitizeLines(lines, cfg)
        val offsets = lineStartOffsets(lines)
        val bestSymbols = mutableMapOf<Pair<Int, Int>, LocalSymbol>()
        val identifiers = mutableMapOf<Int, MutableList<IdentifierToken>>()
        val patterns = patternsForLanguage(cfg)
        val declRanges = mutableMapOf<Int, MutableList<IntRange>>()
        val scopeStack = ArrayDeque<ScopeEntry>()

        val tracker = blockTrackers.getOrPut(cfg?.language ?: "__default") {
            val mode = cfg?.blockMode ?: BlockMode.BRACE
            BlockTracker(mode, cfg?.beginBlockRegex, cfg?.endBlockRegex)
        }.also { it.reset() }

        sanitized.forEachIndexed { idx, sanitizedLine ->
            if (sanitizedLine.text.isBlank()) return@forEachIndexed
            val depthBefore = tracker.currentDepth()
            tracker.update(sanitizedLine.text)
            val depthAfter = tracker.currentDepth()
            while (scopeStack.isNotEmpty() && scopeStack.last().depth > depthAfter) {
                scopeStack.removeLast()
            }
            patterns.forEach { pattern ->
                pattern.regex.findAll(sanitizedLine.text).forEach { match ->
                    val group = match.groups[pattern.groupIndex] ?: return@forEach
                    val name = group.value
                    if (name.isBlank()) return@forEach
                    val startCol = group.range.first
                    val endCol = group.range.last + 1
                    val startOffset = offsets[idx] + startCol
                    val endOffset = offsets[idx] + endCol
                    val container = scopeStack.lastOrNull()?.name
                    val symbol = LocalSymbol(
                        name = name,
                        kind = pattern.kind,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        line = idx,
                        startColumn = startCol,
                        endColumn = endCol,
                        filePath = path,
                        container = container
                    )
                    val key = startOffset to endOffset
                    val existing = bestSymbols[key]
                    if (existing == null || priorityOf(symbol.kind) > priorityOf(existing.kind)) {
                        bestSymbols[key] = symbol
                        if (opensScope(symbol.kind)) {
                            scopeStack.addLast(ScopeEntry(name, symbol.kind, depthAfter))
                        }
                    }
                }
            }
        }

        val symbols = bestSymbols.values.sortedBy { it.startOffset }
        symbols.forEach { symbol ->
            declRanges.getOrPut(symbol.line) { mutableListOf() }
                .add(symbol.startColumn until symbol.endColumn)
            identifiers.getOrPut(symbol.line) { mutableListOf() }.add(
                IdentifierToken(
                    line = symbol.line,
                    start = symbol.startColumn,
                    end = symbol.endColumn,
                    declaration = true,
                    name = symbol.name,
                    filePath = path,
                    container = symbol.container
                )
            )
        }

        val identPattern = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\b")
        tracker.reset()
        sanitized.forEachIndexed { idx, sanitizedLine ->
            if (sanitizedLine.text.isBlank()) return@forEachIndexed
            tracker.update(sanitizedLine.text)
            identPattern.findAll(sanitizedLine.text).forEach { match ->
                val name = match.groupValues[1]
                val start = match.range.first
                val end = match.range.last + 1
                if (isInsideDeclaration(idx, start, end, declRanges)) return@forEach
                if (isKeyword(langKey, name)) return@forEach
                val container = scopeStack.lastOrNull()?.name
                identifiers.getOrPut(idx) { mutableListOf() }.add(
                    IdentifierToken(
                        line = idx,
                        start = start,
                        end = end,
                        declaration = false,
                        name = name,
                        filePath = path,
                        container = container
                    )
                )
            }
        }

        val immutableIdentifiers = identifiers.mapValues { (_, v) -> v.toList() }
        return ExtractedSymbols(symbols, immutableIdentifiers)
    }

    private fun sanitizeLines(
        lines: List<String>,
        cfg: LanguageDefinitionConfig?
    ): List<SanitizedLine> {
        var inBlockComment = false
        val lineComments = cfg?.lineComments?.takeIf { it.isNotEmpty() } ?: DEFAULT_LINE_COMMENTS
        val blockStart = cfg?.blockCommentStart ?: "/*"
        val blockEnd = cfg?.blockCommentEnd ?: "*/"
        return lines.map { line ->
            val sanitized = sanitizeLine(line, inBlockComment, lineComments, blockStart, blockEnd)
            inBlockComment = sanitized.inBlockComment
            sanitized
        }
    }

    private fun sanitizeLine(
        line: String,
        inComment: Boolean,
        lineComments: List<String>,
        blockStart: String?,
        blockEnd: String?
    ): SanitizedLine {
        val out = StringBuilder(line.length)
        var idx = 0
        var inBlock = inComment
        while (idx < line.length) {
            if (inBlock && !blockEnd.isNullOrEmpty()) {
                val end = line.indexOf(blockEnd, idx)
                if (end == -1) {
                    repeat(line.length - idx) { out.append(' ') }
                    return SanitizedLine(out.toString(), true)
                }
                repeat(end + blockEnd.length - idx) { out.append(' ') }
                idx = end + blockEnd.length
                inBlock = false
                continue
            }
            if (!blockStart.isNullOrEmpty() && idx + blockStart.length <= line.length &&
                line.regionMatches(idx, blockStart, 0, blockStart.length)
            ) {
                inBlock = true
                repeat(blockStart.length) { out.append(' ') }
                idx += blockStart.length
                continue
            }
            val lineComment = lineComments.firstOrNull { comment ->
                idx + comment.length <= line.length && line.regionMatches(idx, comment, 0, comment.length)
            }
            if (lineComment != null) {
                repeat(line.length - idx) { out.append(' ') }
                break
            }
            val ch = line[idx]
            if (ch == '"' || ch == '\'') {
                val start = idx
                idx++
                var escaped = false
                while (idx < line.length) {
                    val c = line[idx]
                    if (!escaped && c == ch) {
                        idx++
                        break
                    }
                    escaped = !escaped && c == '\\'
                    idx++
                }
                repeat(idx - start) { out.append(' ') }
                continue
            }
            out.append(ch)
            idx++
        }
        return SanitizedLine(out.toString(), inBlock)
    }

    private fun isInsideDeclaration(
        line: Int,
        start: Int,
        end: Int,
        declRanges: Map<Int, List<IntRange>>
    ): Boolean {
        val ranges = declRanges[line].orEmpty()
        return ranges.any { start >= it.first && end <= it.last + 1 }
    }

    private fun lineStartOffsets(lines: List<String>): IntArray {
        val offsets = IntArray(lines.size)
        var running = 0
        lines.forEachIndexed { idx, line ->
            offsets[idx] = running
            running += line.length + 1
        }
        return offsets
    }

    private fun patternsForLanguage(cfg: LanguageDefinitionConfig?): List<DefinitionPattern> {
        val defs = cfg?.patterns?.mapNotNull { pattern ->
            val regex = runCatching { Regex(pattern.regex, setOf(RegexOption.IGNORE_CASE)) }.getOrNull()
                ?: return@mapNotNull null
            DefinitionPattern(regex, pattern.kind.toSymbolKind(), pattern.groupIndex)
        }
        if (!defs.isNullOrEmpty()) return defs
        return DEFAULT_PATTERNS
    }

    private fun configFor(langKey: String?): LanguageDefinitionConfig? {
        if (langKey == null) return null
        val primary = configByLanguage[langKey]
        if (primary != null) return primary
        val resolved = aliasMap[langKey]
        return resolved?.let { configByLanguage[it] }
    }

    private fun priorityOf(kind: SymbolKind): Int = when (kind) {
        SymbolKind.METHOD, SymbolKind.FIELD -> 3
        SymbolKind.FUNCTION -> 2
        SymbolKind.VARIABLE -> 1
        else -> 0
    }

    private fun isKeyword(language: String?, word: String): Boolean =
        KeywordSyntaxProvider.isKeyword(language, word)

    private fun opensScope(kind: SymbolKind): Boolean =
        when (kind) {
            SymbolKind.CLASS,
            SymbolKind.INTERFACE,
            SymbolKind.OBJECT,
            SymbolKind.ENUM,
            SymbolKind.MODULE,
            SymbolKind.PACKAGE,
            SymbolKind.FUNCTION,
            SymbolKind.METHOD -> true
            else -> false
        }

    companion object {
        private val DEFAULT_LINE_COMMENTS = listOf("//", "#", "--")
        private val DEFAULT_PATTERNS = listOf(
            DefinitionPattern(Regex("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
            DefinitionPattern(Regex("\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.INTERFACE),
            DefinitionPattern(Regex("\\bobject\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.OBJECT),
            DefinitionPattern(Regex("\\benum\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.ENUM),
            DefinitionPattern(Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION),
            DefinitionPattern(Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.VARIABLE)
        )
        private val json = Json { ignoreUnknownKeys = true }

        private fun loadDefaultConfigs(): List<LanguageDefinitionConfig> {
            val external = resolveExternalDefinitions()
            if (external != null) {
                val text = runCatching { Files.readString(external) }.getOrNull()
                if (!text.isNullOrBlank()) {
                    val parsed = runCatching {
                        json.decodeFromString(LanguageDefinitionBundle.serializer(), text)
                    }.getOrNull()
                    if (parsed != null && parsed.languages.isNotEmpty()) return parsed.languages
                }
            }
            val stream = RegexDefinitionExtractor::class.java.getResourceAsStream("/codeintel/definitions.json")
                ?: return emptyList()
            val content = stream.use { it.readBytes().toString(Charsets.UTF_8) }
            return runCatching { json.decodeFromString(LanguageDefinitionBundle.serializer(), content).languages }
                .getOrElse { emptyList() }
        }

        private fun resolveExternalDefinitions(): Path? {
            System.getProperty("kode.codeintel.path")?.let {
                val p = Paths.get(it)
                if (Files.exists(p)) return p
            }
            System.getenv("KODE_CODEINTEL_PATH")?.let {
                val p = Paths.get(it)
                if (Files.exists(p)) return p
            }
            val candidates = listOfNotNull(
                System.getProperty("kode.home")?.let { Paths.get(it).resolve("codeintel/definitions.json") },
                System.getenv("KODE_HOME")?.let { Paths.get(it).resolve("codeintel/definitions.json") },
                Paths.get("codeintel/definitions.json")
            )
            return candidates.firstOrNull { Files.exists(it) }
        }
    }
}

@Serializable
data class LanguageDefinitionBundle(val languages: List<LanguageDefinitionConfig> = emptyList())

@Serializable
data class LanguageDefinitionConfig(
    val language: String,
    val aliases: List<String> = emptyList(),
    val lineComments: List<String> = emptyList(),
    val blockCommentStart: String? = null,
    val blockCommentEnd: String? = null,
    val blockMode: BlockMode = BlockMode.BRACE,
    val beginBlockRegex: String? = null,
    val endBlockRegex: String? = null,
    val patterns: List<PatternConfig> = emptyList()
)

@Serializable
data class PatternConfig(
    val regex: String,
    val kind: String,
    val groupIndex: Int = 1
)

@Serializable
enum class BlockMode { BRACE, INDENT, PAREN, REGEX, SQL, NONE }

private fun String.toSymbolKind(): SymbolKind =
    runCatching { SymbolKind.valueOf(this.uppercase(Locale.ROOT)) }.getOrDefault(SymbolKind.VARIABLE)
