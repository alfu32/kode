package editor.grammars

import java.util.Locale
import java.util.regex.Pattern
import react.Color

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
                    text = matcher.group(group.name) ?: "",
                    fg = colorForQualifier(group.qualifier)
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

    private fun colorForQualifier(qualifier: String): Color? {
        val palette = mapOf(
            "comment" to Color.from("#808080"),
            "string" to Color.from("#6A8759"),
            "regex" to Color.from("#C6794C"),
            "number" to Color.from("#6897BB"),
            "constant" to Color.from("#9876AA"),
            "keyword" to Color.from("#CC7832"),
            "operator" to Color.from("#A9B7C6"),
            "punctuation" to Color.from("#A9B7C6"),
            "tag" to Color.from("#E8BF6A"),
            "attribute" to Color.from("#A5C261"),
            "property" to Color.from("#A5C261"),
            "type" to Color.from("#A9B7C6"),
            "class" to Color.from("#A9B7C6"),
            "interface" to Color.from("#A9B7C6"),
            "function" to Color.from("#FFC66D"),
            "method" to Color.from("#FFC66D"),
            "variable" to Color.from("#A9B7C6"),
            "parameter" to Color.from("#A9B7C6"),
            "namespace" to Color.from("#A9B7C6"),
            "module" to Color.from("#A9B7C6"),
            "annotation" to Color.from("#BBB529"),
            "decorator" to Color.from("#BBB529"),
            "boolean" to Color.from("#CC7832")
        )
        val lower = qualifier.lowercase()
        fun has(term: String) = lower.contains(term)
        val key = when {
            has("comment") -> "comment"
            has("string") -> "string"
            has("regex") -> "regex"
            has("number") || has("numeric") -> "number"
            has("keyword") -> "keyword"
            has("boolean") -> "boolean"
            has("constant") -> "constant"
            has("annotation") || has("decorator") -> "annotation"
            has("operator") -> "operator"
            has("punctuation") || has("delimiter") || has("brace") || has("bracket") -> "punctuation"
            has("function") || has("method") -> "function"
            has("parameter") -> "parameter"
            has("variable") || has("identifier") -> "variable"
            has("attribute") || has("property") -> "attribute"
            has("tag") || has("element") -> "tag"
            has("type") || has("class") || has("interface") || has("enum") -> "type"
            has("namespace") || has("module") || has("package") -> "namespace"
            else -> null
        }
        return key?.let { palette[it] }
    }
}
