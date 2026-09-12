package editor.codeintel.frontend

import java.util.Locale
import org.treesitter.TreeSitterCpp
import org.treesitter.TreeSitterCss
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterHtml
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterJson
import org.treesitter.TreeSitterPhp
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterRuby
import org.treesitter.TreeSitterSwift
import org.treesitter.TreeSitterTypescript
import org.treesitter.TreeSitterBash

/** Registry for the Tree-sitter-backed non-Kotlin adapter batches. */
object TreeSitterSemanticAdapters {
    private val factories: Map<String, () -> LanguageSemanticAdapter?> = mapOf(
        "c" to { CSemanticAdapter() },
        "cpp" to { create("cpp") { TreeSitterCpp() } },
        "c++" to { create("cpp") { TreeSitterCpp() } },
        "cc" to { create("cpp") { TreeSitterCpp() } },
        "cxx" to { create("cpp") { TreeSitterCpp() } },
        "hpp" to { create("cpp") { TreeSitterCpp() } },
        "go" to { create("go") { TreeSitterGo() } },
        "golang" to { create("go") { TreeSitterGo() } },
        "javascript" to { create("javascript") { TreeSitterJavascript() } },
        "js" to { create("javascript") { TreeSitterJavascript() } },
        "typescript" to { create("typescript") { TreeSitterTypescript() } },
        "ts" to { create("typescript") { TreeSitterTypescript() } },
        "jsx" to { create("typescript") { TreeSitterTypescript() } },
        "tsx" to { create("typescript") { TreeSitterTypescript() } },
        "html" to { create("html") { TreeSitterHtml() } },
        "htm" to { create("html") { TreeSitterHtml() } },
        "css" to { create("css") { TreeSitterCss() } },
        "json" to { create("json") { TreeSitterJson() } },
        "python" to { create("python") { TreeSitterPython() } },
        "py" to { create("python") { TreeSitterPython() } },
        "php" to { create("php") { TreeSitterPhp() } },
        "php3" to { create("php") { TreeSitterPhp() } },
        "php4" to { create("php") { TreeSitterPhp() } },
        "php5" to { create("php") { TreeSitterPhp() } },
        "ruby" to { create("ruby") { TreeSitterRuby() } },
        "rb" to { create("ruby") { TreeSitterRuby() } },
        "bash" to { create("bash") { TreeSitterBash() } },
        "sh" to { create("bash") { TreeSitterBash() } },
        "shellscript" to { create("bash") { TreeSitterBash() } },
        "swift" to { create("swift") { TreeSitterSwift() } }
    )

    private val adapters = mutableMapOf<String, LanguageSemanticAdapter>()

    private fun <T : org.treesitter.TSLanguage> create(
        languageId: String,
        factory: () -> T
    ): LanguageSemanticAdapter? = runCatching {
        TreeSitterSemanticAdapter(languageId, factory())
    }.getOrNull()

    fun forLanguage(language: String?): LanguageSemanticAdapter? {
        val key = language?.lowercase(Locale.ROOT) ?: return null
        synchronized(adapters) {
            adapters[key]?.let { return it }
            val adapter = runCatching { factories[key]?.invoke() }.getOrNull() ?: return null
            adapters[key] = adapter
            return adapter
        }
    }
}
