package editor.grammars

import org.eclipse.tm4e.core.grammar.IGrammar
import org.eclipse.tm4e.core.grammar.IToken
import org.eclipse.tm4e.core.grammar.ITokenizeLineResult
import org.eclipse.tm4e.core.grammar.IStateStack
import org.eclipse.tm4e.core.registry.IGrammarSource
import org.eclipse.tm4e.core.registry.Registry
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

// ### Minimal Kotlin façade

data class Token(
    val start: Int,
    val end: Int,
    val scopes: List<String>,
    val line: Int
)

interface SyntaxProvider {
    fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token>
    fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token>
    fun languages(): Set<String>
}

// ### Loader and registry

class TmProvider(grammarDir: Path) : SyntaxProvider {
    private val registry = Registry()
    private val langById = mutableMapOf<String, IGrammar>() // language or scope -> grammar

    init {
        loadGrammars(grammarDir)
    }

    private fun loadGrammars(dir: Path) {
        if (!Files.isDirectory(dir)) return
        val loaded = mutableMapOf<Path, IGrammar>()

        // Load aliases from package.json if present.
        parsePackageJson(dir).forEach { entry ->
            val path = dir.resolve(entry.path).normalize()
            if (!Files.exists(path)) return@forEach
            val grammar = loaded.getOrPut(path) { registry.addGrammar(IGrammarSource.fromFile(path)) ?: return@forEach }
            langById[entry.language] = grammar
            langById.putIfAbsent(entry.scopeName, grammar)
        }

        // Fallback: load all top-level *.json grammars.
        Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".json") && it.fileName.toString() != "package.json" }
                .forEach { path ->
                    val grammar = loaded.getOrPut(path) {
                        registry.addGrammar(IGrammarSource.fromFile(path))
                    } ?: return@forEach
                    val langId = path.fileName.toString().removeSuffix(".json")
                    langById.putIfAbsent(langId, grammar)
                    langById.putIfAbsent(grammar.scopeName, grammar)
                }
        }
    }

    override fun languages(): Set<String> = langById.keys

    override fun tokensForLine(lineNumber: Int, lineText: String, language: String): List<Token> {
        val grammar = langById[language] ?: return emptyList()
        val result = grammar.tokenizeLine(lineText, nullState(), DEFAULT_TIMEOUT)
        return toTokens(result, lineNumber)
    }

    override fun tokensForLines(startLine: Int, lines: List<String>, language: String): List<Token> {
        val grammar = langById[language] ?: return emptyList()
        var state: IStateStack = nullState()
        val out = mutableListOf<Token>()
        lines.forEachIndexed { idx, line ->
            val res = grammar.tokenizeLine(line, state, DEFAULT_TIMEOUT)
            state = res.ruleStack
            out += toTokens(res, startLine + idx)
        }
        return out
    }

    private fun toTokens(result: ITokenizeLineResult<out Array<IToken>>, line: Int): List<Token> =
        result.tokens.map {
            Token(
                start = it.startIndex,
                end = it.endIndex,
                scopes = it.scopes,
                line = line
            )
        }

    private fun nullState(): IStateStack = org.eclipse.tm4e.core.internal.grammar.StateStack.NULL

    companion object {
        private val DEFAULT_TIMEOUT: Duration = Duration.ofMillis(50)
    }
}

private data class PkgLanguage(val language: String, val scopeName: String, val path: String)

private fun parsePackageJson(dir: Path): List<PkgLanguage> {
    val pkg = dir.resolve("package.json")
    if (!Files.exists(pkg)) return emptyList()
    val text = runCatching { Files.readString(pkg) }.getOrDefault("")
    if (text.isEmpty()) return emptyList()
    val regex = Regex(
        "\\{[^}]*\"language\"\\s*:\\s*\"([^\"]+)\"[^}]*\"scopeName\"\\s*:\\s*\"([^\"]+)\"[^}]*\"path\"\\s*:\\s*\"([^\"]+)\"[^}]*}",
        setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)
    )
    return regex.findAll(text).map { m ->
        PkgLanguage(
            language = m.groupValues[1],
            scopeName = m.groupValues[2],
            path = m.groupValues[3]
        )
    }.toList()
}
