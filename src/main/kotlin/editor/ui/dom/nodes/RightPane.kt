package editor.ui.dom.nodes

import editor.state.EditorState
import editor.ui.dom.Component
import editor.ui.dom.container
import react.DOMNode
import react.StyleSet

class RightPane(private val state: EditorState) : Component() {
    override fun render(): DOMNode {
        val lines = state.visibleLines(1000, 1000) // actual clipping handled by layout bounding box
        val children = lines.mapIndexed { idx, line ->
            container(
                tag = "code-line",
                id = "line-$idx",
                styleId = "code.line",
                style = StyleSet(top = idx, left = 0),
                text = line
            )
        }
        return container(
            tag = "right",
            styleId = "right",
            style = StyleSet(),
            children = children
        )
    }
}
