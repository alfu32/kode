package editor.grammars

import java.util.Locale
import java.util.regex.Pattern

open class RegexSyntaxProvider(
    definitions: List<RegexGrammarDefinition> = emptyList()
) : SyntaxProvider {

    private data class GroupDef(val name: String, val qualifier: String)
    private data class CompiledGrammar(val pattern: Pattern, val groups: List<GroupDef>)

    private val grammars = mutableMapOf<String, RegexGrammarDefinition>()
    private val compiled = mutableMapOf<String, CompiledGrammar?>()
    private val extensionIndex = mutableMapOf<String, String>()

    init {
        addDefinitions(definitions)
    }

    fun addDefinitions(defs: List<RegexGrammarDefinition>) {
        defs.forEach { addDefinition(it) }
    }

    fun addDefinition(def: RegexGrammarDefinition) {
        grammars[def.language] = def
        compiled.remove(def.language)
        def.extensions.forEach { ext ->
            extensionIndex[ext.removePrefix(".").lowercase(Locale.ROOT)] = def.language
        }
    }

    override fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token> =
        tokensForLines(lineNumber, listOf(lineText), language)

    override fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token> {
        val grammar = compiled.getOrPut(language) { compile(language) } ?: return emptyList()
        if (grammar.groups.isEmpty()) return emptyList()
        val out = mutableListOf<Token>()
        lines.forEachIndexed { idx, line ->
            val matcher = grammar.pattern.matcher(line)
            while (matcher.find()) {
                val group = grammar.groups.firstOrNull { matcher.group(it.name) != null } ?: continue
                val start = matcher.start(group.name)
                val end = matcher.end(group.name)
                if (start < 0 || end < 0) continue
                out += Token(
                    start = start,
                    end = end,
                    scopes = listOf(group.qualifier),
                    line = startLine + idx,
                    text = matcher.group(group.name) ?: ""
                )
            }
        }
        return out
    }

    override fun languages(): Set<String> = grammars.keys

    override fun languageForExtension(ext: String): String? =
        extensionIndex[ext.removePrefix(".").lowercase(Locale.ROOT)]

    private fun compile(language: String): CompiledGrammar? {
        val def = grammars[language] ?: return null
        if (def.tokens.isEmpty()) return null
        val groupDefs = mutableListOf<GroupDef>()
        val usedNames = mutableSetOf<String>()
        val patternParts = def.tokens.entries.mapNotNull { (qualifier, regex) ->
            if (regex.isBlank()) return@mapNotNull null
            val groupName = uniqueGroupName(sanitizeGroup(qualifier), usedNames)
            groupDefs += GroupDef(groupName, qualifier)
            val bounded = enforceWordBoundaries(regex)
            "(?<$groupName>${bounded})"
        }
        if (patternParts.isEmpty()) return null
        val combined = patternParts.joinToString(separator = "|", prefix = "(", postfix = ")")
        val compiledPattern = runCatching { Pattern.compile(combined) }.getOrNull() ?: return null
        return CompiledGrammar(pattern = compiledPattern, groups = groupDefs)
    }

    private fun sanitizeGroup(raw: String): String {
        val cleaned = raw.asSequence()
            .mapNotNull { ch -> if (ch.isLetterOrDigit()) ch else null }
            .joinToString("")
            .ifEmpty { "g" }
        return if (cleaned.first().isLetter()) cleaned else "g$cleaned"
    }

    private fun uniqueGroupName(base: String, used: MutableSet<String>): String {
        var candidate = base
        var counter = 1
        while (!used.add(candidate)) {
            candidate = "${base}_$counter"
            counter++
        }
        return candidate
    }

    private fun enforceWordBoundaries(regex: String): String {
        val trimmed = regex.trim()
        val needsBoundary = trimmed.all { it.isLetterOrDigit() || it == '_' }
        return if (needsBoundary) "\\b(?:$trimmed)\\b" else trimmed
    }
}
