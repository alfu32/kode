package editor.codeintel

import editor.grammars.Token
import react.Color
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit

enum class SymbolKind { CLASS, FUNCTION, VARIABLE, INTERFACE, ENUM, OBJECT, MODULE }

data class SymbolDef(
    val name: String,
    val kind: SymbolKind,
    val filePath: String,
    val range: IntRange,
    val container: String? = null
)

data class IdentifierToken(
    val line: Int,
    val start: Int,
    val end: Int,
    val declaration: Boolean,
    val name: String
)

data class LocalSymbol(
    val name: String,
    val kind: SymbolKind,
    val startOffset: Int,
    val endOffset: Int,
    val line: Int,
    val startColumn: Int,
    val endColumn: Int
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
    fun extract(text: String, language: String?): ExtractedSymbols
}

class CodeIntelService(
    private val extractor: DefinitionExtractor = RegexDefinitionExtractor(),
    private val debounceMs: Long = 200L
) {

    private val executor = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread(runnable, "code-intel").apply { isDaemon = true }
    }
    private val latestVersionByPath = mutableMapOf<String, Long>()
    private val pendingJobs = mutableMapOf<String, ScheduledFuture<*>>()
    private val documents = mutableMapOf<String, DocumentIndex>()
    private val defsByPath = mutableMapOf<String, List<SymbolDef>>()
    private val workspaceIndex = mutableMapOf<String, MutableList<SymbolDef>>()
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

    fun definitionCandidates(name: String): List<SymbolDef> {
        if (name.isBlank()) return emptyList()
        val lower = name.lowercase(Locale.ROOT)
        return synchronized(lock) { workspaceIndex[lower]?.toList().orEmpty() }
    }

    fun tokensForLines(path: String, startLine: Int, lines: List<String>, currentVersion: Long): List<Token> {
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
        val extracted = extractor.extract(text, language)
        val defs = extracted.symbols.map {
            SymbolDef(
                name = it.name,
                kind = it.kind,
                filePath = path,
                range = it.startOffset until it.endOffset
            )
        }
        val docIndex = DocumentIndex(
            version = version,
            definitions = defs.sortedBy { it.range.first },
            identifiersByLine = extracted.identifiersByLine
        )
        synchronized(lock) {
            pendingJobs.remove(path)
            documents[path] = docIndex
            rebuildWorkspaceIndex(path, defs)
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

    companion object {
        private const val DECL_SCOPE = "codeintel.declaration"
        private const val USAGE_SCOPE = "codeintel.usage"
    }
}

private class RegexDefinitionExtractor : DefinitionExtractor {

    private data class DefinitionPattern(val regex: Regex, val kind: SymbolKind, val groupIndex: Int = 1)
    private data class SanitizedLine(val text: String, val inBlockComment: Boolean)

    override fun extract(text: String, language: String?): ExtractedSymbols {
        val lines = text.split("\n")
        val sanitized = sanitizeLines(lines)
        val offsets = lineStartOffsets(lines)
        val symbols = mutableListOf<LocalSymbol>()
        val identifiers = mutableMapOf<Int, MutableList<IdentifierToken>>()
        val patterns = patternsForLanguage(language)
        val declRanges = mutableMapOf<Int, MutableList<IntRange>>()

        sanitized.forEachIndexed { idx, sanitizedLine ->
            if (sanitizedLine.text.isBlank()) return@forEachIndexed
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
                        endColumn = endCol
                    )
                    symbols += symbol
                    declRanges.getOrPut(idx) { mutableListOf() }.add(startCol until endCol)
                    identifiers.getOrPut(idx) { mutableListOf() }.add(
                        IdentifierToken(
                            line = idx,
                            start = startCol,
                            end = endCol,
                            declaration = true,
                            name = name
                        )
                    )
                }
            }
        }

        val names = symbols.map { it.name }.toSet()
        if (names.isNotEmpty()) {
            val pattern = Regex("\\b(${names.joinToString("|") { Regex.escape(it) }})\\b")
            sanitized.forEachIndexed { idx, sanitizedLine ->
                if (sanitizedLine.text.isBlank()) return@forEachIndexed
                pattern.findAll(sanitizedLine.text).forEach { match ->
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
                            name = name
                        )
                    )
                }
            }
        }

        val immutableIdentifiers = identifiers.mapValues { (_, v) -> v.toList() }
        return ExtractedSymbols(symbols, immutableIdentifiers)
    }

    private fun sanitizeLines(lines: List<String>): List<SanitizedLine> {
        var inBlockComment = false
        return lines.map { line ->
            val sanitized = sanitizeLine(line, inBlockComment)
            inBlockComment = sanitized.inBlockComment
            sanitized
        }
    }

    private fun sanitizeLine(line: String, inComment: Boolean): SanitizedLine {
        val out = StringBuilder(line.length)
        var idx = 0
        var inBlock = inComment
        while (idx < line.length) {
            if (inBlock) {
                val end = line.indexOf("*/", idx)
                if (end == -1) {
                    repeat(line.length - idx) { out.append(' ') }
                    return SanitizedLine(out.toString(), true)
                }
                repeat(end + 2 - idx) { out.append(' ') }
                idx = end + 2
                inBlock = false
                continue
            }
            if (idx + 1 < line.length && line[idx] == '/' && line[idx + 1] == '*') {
                inBlock = true
                out.append(' ').append(' ')
                idx += 2
                continue
            }
            if (idx + 1 < line.length && line[idx] == '/' && line[idx + 1] == '/') {
                repeat(line.length - idx) { out.append(' ') }
                break
            }
            if (line[idx] == '#') {
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

    private fun patternsForLanguage(language: String?): List<DefinitionPattern> {
        val lang = language?.lowercase(Locale.ROOT) ?: ""
        val kotlinLike = listOf(
            DefinitionPattern(Regex("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
            DefinitionPattern(Regex("\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.INTERFACE),
            DefinitionPattern(Regex("\\bobject\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.OBJECT),
            DefinitionPattern(Regex("\\benum\\s+class\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.ENUM),
            DefinitionPattern(Regex("\\bfun\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION),
            DefinitionPattern(Regex("\\b(?:val|var)\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.VARIABLE)
        )
        val cStyle = listOf(
            DefinitionPattern(Regex("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
            DefinitionPattern(Regex("\\binterface\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.INTERFACE),
            DefinitionPattern(Regex("\\benum\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.ENUM),
            DefinitionPattern(Regex("\\bfunction\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION),
            DefinitionPattern(Regex("\\b(?:const|let|var)\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.VARIABLE)
        )
        val python = listOf(
            DefinitionPattern(Regex("\\bclass\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.CLASS),
            DefinitionPattern(Regex("\\bdef\\s+([A-Za-z_][A-Za-z0-9_]*)"), SymbolKind.FUNCTION)
        )

        return when {
            lang.contains("kotlin") -> kotlinLike
            lang.contains("java") || lang.contains("c#") -> kotlinLike
            lang.contains("js") || lang.contains("ts") || lang.contains("javascript") || lang.contains("typescript") -> cStyle
            lang.contains("python") || lang == "py" -> python
            else -> kotlinLike + cStyle + python
        }
    }
}
