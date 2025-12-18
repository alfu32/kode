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

    private val colorOverrides: Map<String, Color> by lazy { loadColorOverrides() }
    private val defaultColor: Color = colorOverrides["default"] ?: Color.from("#CC7832")!!
    private data class KeywordEntry(val pattern: Pattern, val keywords: List<String>)

    private val keywordEntries: Map<String, KeywordEntry> by lazy { loadPatterns() }

    override fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token> =
        tokensForLines(lineNumber, listOf(lineText), language)

    override fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token> {
        val entry = keywordEntries[language] ?: return emptyList()
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
        // Heuristic: take first (...) group and split on '|'
        val group = Regex("\\(([^()]+)\\)").find(regex)?.groupValues?.get(1) ?: return emptyList()
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
