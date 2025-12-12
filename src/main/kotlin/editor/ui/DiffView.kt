package editor.ui

import editor.grammars.SyntaxProvider
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class DiffView(
    styleSheet: StyleSheet,
    private val syntaxProvider: SyntaxProvider? = null
) : BaseComponent(styleSheet) {

    private val editor = CodeEditorView(styleSheet, syntaxProvider = syntaxProvider).also {
        it.setReadOnly(true)
    }

    fun showDiff(path: String, content: String, language: String? = "diff") {
        editor.loadVirtualContent("[diff] $path", content, language)
    }

    override fun render(canvas: CanvasRenderer) {
        editor.render(canvas)
    }

    override fun dispatch(event: UIEvent): Boolean = editor.dispatch(event)
}
