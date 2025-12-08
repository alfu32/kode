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
    private val defaultColor: Color = colorOverrides["default"] ?: Color.from("#CC7832")
    private val keywordPatterns: Map<String, Pattern> by lazy { loadPatterns() }

    override fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token> =
        tokensForLines(lineNumber, listOf(lineText), language)

    override fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token> {
        val pattern = keywordPatterns[language] ?: return emptyList()
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

    override fun languages(): Set<String> = keywordPatterns.keys

    override fun languageForExtension(ext: String): String? =
        extensionIndex[ext.removePrefix(".").lowercase(Locale.ROOT)]

    private fun colorFor(qualifier: String): Color =
        colorOverrides[qualifier.lowercase(Locale.ROOT)] ?: defaultColor

    private fun loadPatterns(): Map<String, Pattern> {
        val source = resolveConfigFile("keyword-patterns.txt")
        try {
            if (source == null || !Files.isRegularFile(source)) {
                throw IllegalStateException("keyword-patterns.txt not found (looked in working directory and alongside the jar)")
            }
            val map = linkedMapOf<String, Pattern>()
            Files.readAllLines(source).forEachIndexed { idx, line ->
                val trimmed = line.trim()
                if (trimmed.isEmpty() || trimmed.startsWith("#")) return@forEachIndexed
                val parts = trimmed.split("=", limit = 2)
                if (parts.size != 2) {
                    throw IllegalArgumentException("Invalid line ${idx + 1} in $source: '$line'")
                }
                val lang = parts[0].trim()
                val regex = parts[1].trim()
                map[lang] = Pattern.compile(regex)
            }
            return map
        } catch (e: Exception) {
            System.err.println("Failed to load keyword-patterns.txt: ${e.message}")
            e.printStackTrace()
            throw e
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
