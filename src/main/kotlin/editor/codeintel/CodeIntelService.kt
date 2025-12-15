package editor.codeintel

import editor.grammars.Token
import react.Color
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

enum class SymbolKind { CLASS, FUNCTION, VARIABLE, INTERFACE, ENUM, OBJECT, MODULE }

data class SymbolDef(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val range: IntRange,
    val container: String? = null,
    val line: Int? = null,
    val startColumn: Int? = null
)

data class IdentifierToken(
    val line: Int,
    val start: Int,
    val end: Int,
    val declaration: Boolean,
    val name: String,
    val filePath: String? = null
)

data class LocalSymbol(
    val name: String,
    val kind: SymbolKind,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val startColumn: Int,
    val endColumn: Int,
    val filePath: String
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
    private val extractor: DefinitionExtractor = RegexDefinitionExtractor(),
    private val debounceMs: Long = 200L
) : CodeIntelProvider {

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

    fun documentOutline(path: String): List<SymbolDef> =
        synchronized(lock) { documents[path]?.definitions.orEmpty() }

    fun workspaceSymbols(query: String, limit: Int = 64): List<SymbolDef> {
        if (query.isBlank()) return emptyList()
        val lower = query.lowercase(Locale.ROOT)
        val matches = mutableListOf<SymbolDef>()
        synchronized(lock) {
            workspaceIndex.forEach { (name, defs) ->
                if (matches.size >= limit) return@forEach
                if (name.contains(lower)) {
                    matches.addAll(defs)
                }
            }
        }
        return matches.take(limit)
    }

    override fun tokensForLines(path: String, startLine: Int, lines: List<String>, currentVersion: Long): List<Token> {
        val doc = synchronized(lock) { documents[path] }
        if (doc == null || doc.version != currentVersion) return emptyList()
        val accent = Color.from("#5da9ff")
        val tokens = mutableListOf<Token>()
        lines.forEachIndexed { idx, line ->
            val absoluteLine = startLine + idx
            val idents = doc.identifiersByLine[absoluteLine].orEmpty()
            idents.forEach { id ->
                val clampedStart = id.start.coerceIn(0, line.length)
                val clampedEnd = id.end.coerceIn(clampedStart, line.length)
                if (clampedEnd <= clampedStart) return@forEach
                val scope = if (id.declaration) DECL_SCOPE else USAGE_SCOPE
                tokens += Token(
                    start = clampedStart,
                    end = clampedEnd,
                    scopes = listOf(scope),
                    line = absoluteLine,
                    text = line.substring(clampedStart, clampedEnd),
                    fg = accent
                )
            }
        }
        return tokens
    }

    override fun definitions(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation> {
        if (name.isBlank()) return emptyList()
        val lower = name.lowercase(Locale.ROOT)
        val defs = synchronized(lock) { workspaceIndex[lower]?.toList().orEmpty() }
        return defs.mapNotNull { def ->
            val line = def.line ?: return@mapNotNull null
            val col = def.startColumn ?: 0
            CodeLocation(def.filePath, line, col)
        }
    }

    override fun references(path: String, language: String?, position: CodePosition, name: String): List<CodeLocation> {
        if (name.isBlank()) return emptyList()
        val lower = name.lowercase(Locale.ROOT)
        val results = mutableListOf<CodeLocation>()
        val defs = synchronized(lock) { workspaceIndex[lower]?.toList().orEmpty() }
        defs.forEach { def ->
            val line = def.line
            val col = def.startColumn
            if (line != null && col != null) {
                results.add(CodeLocation(def.filePath, line, col))
            }
        }
        val tokens = synchronized(lock) { usagesIndex[lower]?.toList().orEmpty() }
        tokens.forEach { tok ->
            val file = tok.filePath
            if (file != null) {
                results.add(CodeLocation(file, tok.line, tok.start))
            }
        }
        return results
    }

    override fun completions(path: String, language: String?, position: CodePosition, prefix: String): List<CompletionItem> {
        val trimmed = prefix.trim()
        val items = mutableListOf<CompletionItem>()
        synchronized(lock) {
            val source = if (trimmed.isEmpty()) {
                workspaceIndex.values.flatten()
            } else {
                val lower = trimmed.lowercase(Locale.ROOT)
                workspaceIndex.entries
                    .filter { (name, _) -> name.startsWith(lower) }
                    .flatMap { it.value }
            }
            source.forEach { def ->
                items.add(CompletionItem(def.name, java.io.File(def.filePath).name))
            }
        }
        return items.take(50)
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
                line = it.line,
                startColumn = it.startColumn
            )
        }
        val allowedNames = (knownNames + defs.map { it.name.lowercase(Locale.ROOT) }).toSet()
        val filteredIdents = extracted.identifiersByLine.mapValues { (_, list) ->
            list.filter { it.name.lowercase(Locale.ROOT) in allowedNames }
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

    companion object {
        private const val DECL_SCOPE = "codeintel.declaration"
        private const val USAGE_SCOPE = "codeintel.usage"
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

    override fun extract(path: String, text: String, language: String?): ExtractedSymbols {
        val langKey = language?.lowercase(Locale.ROOT)
        val cfg = configFor(langKey)
        val lines = text.split("\n")
        val sanitized = sanitizeLines(lines, cfg)
        val offsets = lineStartOffsets(lines)
        val symbols = mutableListOf<LocalSymbol>()
        val identifiers = mutableMapOf<Int, MutableList<IdentifierToken>>()
        val patterns = patternsForLanguage(cfg)
        val declRanges = mutableMapOf<Int, MutableList<IntRange>>()

        val tracker = blockTrackers.getOrPut(cfg?.language ?: "__default") {
            val mode = cfg?.blockMode ?: BlockMode.BRACE
            BlockTracker(mode, cfg?.beginBlockRegex, cfg?.endBlockRegex)
        }.also { it.reset() }

        sanitized.forEachIndexed { idx, sanitizedLine ->
            if (sanitizedLine.text.isBlank()) return@forEachIndexed
            tracker.update(sanitizedLine.text)
            patterns.forEach { pattern ->
                pattern.regex.findAll(sanitizedLine.text).forEach { match ->
                    val group = match.groups[pattern.groupIndex] ?: return@forEach
                    val name = group.value
                    if (name.isBlank()) return@forEach
                    val startCol = group.range.first
                    val endCol = group.range.last + 1
                    val startOffset = offsets[idx] + startCol
                    val endOffset = offsets[idx] + endCol
                    val symbol = LocalSymbol(
                        name = name,
                        kind = pattern.kind,
                        startOffset = startOffset,
                        endOffset = endOffset,
                        line = idx,
                        startColumn = startCol,
                        endColumn = endCol,
                        filePath = path
                    )
                    symbols += symbol
                    declRanges.getOrPut(idx) { mutableListOf() }.add(startCol until endCol)
                    identifiers.getOrPut(idx) { mutableListOf() }.add(
                        IdentifierToken(
                            line = idx,
                            start = startCol,
                            end = endCol,
                            declaration = true,
                            name = name,
                            filePath = path
                        )
                    )
                }
            }
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
                identifiers.getOrPut(idx) { mutableListOf() }.add(
                    IdentifierToken(
                        line = idx,
                        start = start,
                        end = end,
                        declaration = false,
                        name = name,
                        filePath = path
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

data class UsageLocation(
    val filePath: String,
    val line: Int,
    val startColumn: Int,
    val endColumn: Int
)

private fun String.toSymbolKind(): SymbolKind =
    runCatching { SymbolKind.valueOf(this.uppercase(Locale.ROOT)) }.getOrDefault(SymbolKind.VARIABLE)
