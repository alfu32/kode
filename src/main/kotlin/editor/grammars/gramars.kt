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
        Files.list(dir).use { files ->
            files.filter { it.toString().endsWith(".json") }.forEach { path ->
                runCatching {
                    val source = IGrammarSource.fromFile(path)
                    val grammar = registry.addGrammar(source)
                    grammar?.let {
                        val langId = path.fileName.toString().removeSuffix(".json")
                        langById[langId] = it
                        langById.putIfAbsent(it.scopeName, it)
                    }
                }
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
