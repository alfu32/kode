package editor.ui.dom.nodes

import editor.state.EditorState
import editor.ui.components.dom.TabHeader
import editor.ui.dom.Component
import editor.ui.dom.container
import react.DOMNode
import react.StyleSet

class LeftPane(private val state: EditorState) : Component() {
    override fun render(): DOMNode {
        val tabs = TabHeader(state.leftTab).render()
        val tree = editor.ui.components.FileTreeComponentDom(state).render()
        return container(
            tag = "left",
            styleId = "left",
            style = StyleSet(),
            children = listOf(tabs, tree)
        )
    }
}
