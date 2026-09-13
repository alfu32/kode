package editor.grammars

import react.Color
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.regex.Pattern

/**
 * Keyword-only syntax provider. Patterns and colors are loaded from external text files
 * placed alongside the jar (dist/) or in the current working directory.
 */
object KeywordSyntaxProvider : SyntaxProvider {
    private val extensionIndex: Map<String, String> = emptyMap()

    private val markupLanguages = setOf("html", "html-derivative", "xml", "xsl", "jsx", "tsx", "vue-html")
    private val markupTagColor = Color.from("#E8BF6A")
    private val markupAttributeColor = Color.from("#A5C261")
    private val markupStringColor = Color.from("#6A8759")
    private val cssClassColor = Color.from("#9876AA")
    private val markupTagPattern = Pattern.compile("<\\s*/?\\s*([A-Za-z_][A-Za-z0-9_.:-]*)([^>]*)(?:>|$)")
    private val markupProcessingPattern = Pattern.compile("<\\?\\s*([A-Za-z_][A-Za-z0-9_.:-]*)([^>]*)\\?>")
    private val markupAttributePattern = Pattern.compile("\\b([A-Za-z_:][A-Za-z0-9_.:-]*)(?=\\s*=)")
    private val markupValuePattern = Pattern.compile("=\\s*(\\\"(?:[^\\\"\\\\]|\\\\.)*\\\"|'(?:[^'\\\\]|\\\\.)*')")
    private val cssClassPattern = Pattern.compile("(?<![A-Za-z0-9_-])\\.([A-Za-z_-][A-Za-z0-9_-]*)")

    private val colorOverrides: Map<String, Color> by lazy { loadColorOverrides() }
    private val defaultColor: Color = colorOverrides["default"] ?: Color.from("#CC7832")!!
    private data class KeywordEntry(val pattern: Pattern, val keywords: List<String>)

    private val keywordEntries: Map<String, KeywordEntry> by lazy { loadPatterns() }

    override fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token> =
        tokensForLines(lineNumber, listOf(lineText), language)

    override fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token> {
        val normalizedLanguage = language.lowercase(Locale.ROOT)
        if (normalizedLanguage in markupLanguages) {
            return markupTokens(startLine, lines, normalizedLanguage)
        }
        if (normalizedLanguage == "css" || normalizedLanguage == "scss" || normalizedLanguage == "less") {
            return cssTokens(startLine, lines, normalizedLanguage)
        }
        val entry = keywordEntries[normalizedLanguage] ?: return emptyList()
        val pattern = entry.pattern
        if (lines.isEmpty()) return emptyList()
        val tokens = mutableListOf<Token>()
        lines.forEachIndexed { idx, line ->
            val matcher = pattern.matcher(line)
            while (matcher.find()) {
                val s = matcher.start()
                val e = matcher.end()
                if (s < 0 || e < 0 || s >= line.length || e > line.length) continue
                tokens += Token(
                    start = s,
                    end = e,
                    scopes = listOf("keyword"),
                    line = startLine + idx,
                    text = line.substring(s, e),
                    fg = colorFor("keyword")
                )
            }
        }
        return tokens
    }

    private fun markupTokens(startLine: Int, lines: List<String>, language: String): List<Token> {
        val tokens = mutableListOf<Token>()
        lines.forEachIndexed { index, line ->
            val lineNumber = startLine + index
            val tagRanges = mutableListOf<IntRange>()
            fun addTag(match: java.util.regex.Matcher, nameGroup: Int, bodyGroup: Int? = null) {
                val nameStart = match.start(nameGroup)
                val nameEnd = match.end(nameGroup)
                if (nameStart >= 0 && nameEnd > nameStart) {
                    tokens += Token(nameStart, nameEnd, listOf("tag"), lineNumber,
                        line.substring(nameStart, nameEnd), markupTagColor)
                }
                val bodyStart = bodyGroup?.let(match::start) ?: return
                val bodyEnd = bodyGroup.let(match::end)
                if (bodyStart < 0 || bodyEnd <= bodyStart) return
                val body = line.substring(bodyStart, bodyEnd)
                markupAttributePattern.matcher(body).let { attributes ->
                    while (attributes.find()) {
                        val attrStart = bodyStart + attributes.start(1)
                        val attrEnd = bodyStart + attributes.end(1)
                        val attrName = attributes.group(1)
                        val scope = if (attrName.equals("class", true) || attrName.equals("className", true)) {
                            "css.class"
                        } else "attribute"
                        tokens += Token(attrStart, attrEnd, listOf(scope), lineNumber,
                            line.substring(attrStart, attrEnd), if (scope == "css.class") cssClassColor else markupAttributeColor)
                    }
                }
                markupValuePattern.matcher(body).let { values ->
                    while (values.find()) {
                        val valueStart = bodyStart + values.start(1)
                        val valueEnd = bodyStart + values.end(1)
                        tokens += Token(valueStart, valueEnd, listOf("string"), lineNumber,
                            line.substring(valueStart, valueEnd), markupStringColor)
                    }
                }
                tagRanges += match.start() until match.end()
            }
            markupProcessingPattern.matcher(line).let { processing ->
                while (processing.find()) addTag(processing, 1, 2)
            }
            markupTagPattern.matcher(line).let { tags ->
                while (tags.find()) addTag(tags, 1, 2)
            }
            // JSX/TSX still needs its language keywords in addition to markup.
            if (language == "jsx" || language == "tsx") {
                val entry = keywordEntries[language]
                if (entry != null) {
                    val keywords = entry.pattern.matcher(line)
                    while (keywords.find()) {
                        if (tagRanges.none { keywords.start() in it }) {
                            tokens += Token(keywords.start(), keywords.end(), listOf("keyword"), lineNumber,
                                line.substring(keywords.start(), keywords.end()), colorFor("keyword"))
                        }
                    }
                }
            }
        }
        return tokens.sortedWith(compareBy<Token> { it.line }.thenBy { it.start }.thenBy { it.end })
    }

    private fun cssTokens(startLine: Int, lines: List<String>, language: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val entry = keywordEntries[language]
        lines.forEachIndexed { index, line ->
            val lineNumber = startLine + index
            cssClassPattern.matcher(line).let { classes ->
                while (classes.find()) {
                    tokens += Token(classes.start(), classes.end(), listOf("css.class"), lineNumber,
                        line.substring(classes.start(), classes.end()), cssClassColor)
                }
            }
            if (entry != null) {
                val keywords = entry.pattern.matcher(line)
                while (keywords.find()) {
                    tokens += Token(keywords.start(), keywords.end(), listOf("keyword"), lineNumber,
                        line.substring(keywords.start(), keywords.end()), colorFor("keyword"))
                }
            }
        }
        return tokens.sortedWith(compareBy<Token> { it.line }.thenBy { it.start }.thenBy { it.end })
    }

    override fun languages(): Set<String> = keywordEntries.keys

    override fun languageForExtension(ext: String): String? =
        extensionIndex[ext.removePrefix(".").lowercase(Locale.ROOT)]

    fun isKeyword(language: String?, word: String): Boolean {
        if (language.isNullOrBlank() || word.isBlank()) return false
        val pattern = keywordEntries[language.lowercase(Locale.ROOT)]?.pattern ?: return false
        val m = pattern.matcher(word)
        return m.find()
    }

    fun keywords(language: String?): List<String> {
        language ?: return emptyList()
        return keywordEntries[language.lowercase(Locale.ROOT)]?.keywords.orEmpty()
    }

    private fun colorFor(qualifier: String): Color =
        colorOverrides[qualifier.lowercase(Locale.ROOT)] ?: defaultColor

    private fun loadPatterns(): Map<String, KeywordEntry> {
        val source = resolveConfigFile("keyword-patterns.txt")
        try {
            if (source == null || !Files.isRegularFile(source)) {
                throw IllegalStateException("keyword-patterns.txt not found (looked in working directory and alongside the jar)")
            }
            val map = linkedMapOf<String, KeywordEntry>()
            Files.readAllLines(source).forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
                val parts = trimmed.split("=", limit = 2)
                if (parts.size != 2) {
                    val msg = "Invalid line ${idx + 1} in $source: '$line'"
                    editor.app.Logger.logRegexError("keyword-patterns", msg, line)
                    return@forEachIndexed
                }
                val lang = parts[0].trim()
                val regex = parts[1].trim()
                try {
                    val pattern = Pattern.compile(regex)
                    val keywords = extractKeywords(regex)
                    map[lang.lowercase(Locale.ROOT)] = KeywordEntry(pattern, keywords)
                } catch (e: Exception) {
                    val msg = "Invalid regex for language '$lang': '$regex' (${e.message})"
                    editor.app.Logger.logRegexError("keyword-patterns", msg, regex)
                }
            }
            return map
        } catch (e: Exception) {
            val msg = "Failed to load keyword-patterns.txt: ${e.message}"
            System.err.println(msg)
            editor.app.Logger.logRegexError("keyword-patterns", msg, source?.toString() ?: "unknown")
            return emptyMap()
        }
    }

    private fun loadColorOverrides(): Map<String, Color> {
        val source = resolveConfigFile("token-colors.txt") ?: return emptyMap()
        val map = linkedMapOf<String, Color>()
        Files.readAllLines(source).forEach { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEach
            val parts = trimmed.split("=", limit = 2)
            if (parts.size != 2) return@forEach
            val key = parts[0].trim().lowercase(Locale.ROOT)
            val value = parts[1].trim()
            runCatching { Color.from(value) }.getOrNull()?.let { color ->
                map[key] = color
            }
        }
        return map
    }

    private fun extractKeywords(regex: String): List<String> {
        // Prefer the alternatives group over the anchoring group. Most grammar
        // patterns begin with `(^|\\b)` and the old first-group heuristic made
        // keyword completion return only `^` and `\\b`.
        val groups = Regex("\\(([^()]*)\\)").findAll(regex).map { it.groupValues[1] }.toList()
        val group = groups.firstOrNull { body ->
            body.contains('|') && body.split('|').any { part ->
                val candidate = part.replace("\\b", "").replace("\\", "").trim()
                candidate.isNotEmpty() && candidate !in setOf("^", "$")
            }
        } ?: groups.firstOrNull() ?: return emptyList()
        return group.split('|')
            .map { it.replace("\\b", "").replace("\\", "").trim() }
            .filter { it.isNotEmpty() }
    }

    private fun resolveConfigFile(name: String): Path? {
        val jarDir = try {
            javaClass.protectionDomain.codeSource?.location?.toURI()?.let { Path.of(it).parent }
        } catch (_: Exception) {
            null
        }
        val candidates = listOfNotNull(
            jarDir?.resolve(name),
            Path.of(name)
        )
        return candidates.firstOrNull { Files.isRegularFile(it) }
    }
}
