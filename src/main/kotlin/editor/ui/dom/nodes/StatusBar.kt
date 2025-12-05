package editor.ui.dom.nodes

import editor.state.EditorState
import editor.ui.dom.Component
import editor.ui.dom.container
import react.DOMNode
import react.StyleSet

class StatusBar(private val state: EditorState) : Component() {
    override fun render(): DOMNode {
        val dirtyFlag = if (state.dirty) "*" else ""
        val branch = state.gitBranch?.let { " | $it" } ?: ""
        val status = state.gitStatus?.let { " [$it]" } ?: ""
        val mode = state.mode.name.lowercase().replaceFirstChar { it.titlecase() }
        val text = " $mode ${state.filePath ?: "untitled"}$dirtyFlag$branch$status"
        return container(
            tag = "status",
            styleId = "status",
            style = StyleSet(),
            text = text
        )
    }
}
