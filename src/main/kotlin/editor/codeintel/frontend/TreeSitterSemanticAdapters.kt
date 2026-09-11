package editor.codeintel.frontend

import java.util.Locale
import org.treesitter.TreeSitterC
import org.treesitter.TreeSitterCpp
import org.treesitter.TreeSitterCss
import org.treesitter.TreeSitterGo
import org.treesitter.TreeSitterHtml
import org.treesitter.TreeSitterJavascript
import org.treesitter.TreeSitterJson
import org.treesitter.TreeSitterPython
import org.treesitter.TreeSitterTypescript

/** Registry for the first non-Kotlin adapter batch. */
object TreeSitterSemanticAdapters {
    private val adapters: Map<String, LanguageSemanticAdapter> by lazy {
        mapOf(
            "c" to TreeSitterSemanticAdapter("c", TreeSitterC()),
            "cpp" to TreeSitterSemanticAdapter("cpp", TreeSitterCpp()),
            "c++" to TreeSitterSemanticAdapter("cpp", TreeSitterCpp()),
            "cc" to TreeSitterSemanticAdapter("cpp", TreeSitterCpp()),
            "cxx" to TreeSitterSemanticAdapter("cpp", TreeSitterCpp()),
            "hpp" to TreeSitterSemanticAdapter("cpp", TreeSitterCpp()),
            "go" to TreeSitterSemanticAdapter("go", TreeSitterGo()),
            "golang" to TreeSitterSemanticAdapter("go", TreeSitterGo()),
            "javascript" to TreeSitterSemanticAdapter("javascript", TreeSitterJavascript()),
            "js" to TreeSitterSemanticAdapter("javascript", TreeSitterJavascript()),
            "typescript" to TreeSitterSemanticAdapter("typescript", TreeSitterTypescript()),
            "ts" to TreeSitterSemanticAdapter("typescript", TreeSitterTypescript()),
            "jsx" to TreeSitterSemanticAdapter("typescript", TreeSitterTypescript()),
            "tsx" to TreeSitterSemanticAdapter("typescript", TreeSitterTypescript()),
            "html" to TreeSitterSemanticAdapter("html", TreeSitterHtml()),
            "htm" to TreeSitterSemanticAdapter("html", TreeSitterHtml()),
            "css" to TreeSitterSemanticAdapter("css", TreeSitterCss()),
            "json" to TreeSitterSemanticAdapter("json", TreeSitterJson()),
            "python" to TreeSitterSemanticAdapter("python", TreeSitterPython()),
            "py" to TreeSitterSemanticAdapter("python", TreeSitterPython())
        )
    }

    fun forLanguage(language: String?): LanguageSemanticAdapter? =
        language?.lowercase(Locale.ROOT)?.let(adapters::get)
}
