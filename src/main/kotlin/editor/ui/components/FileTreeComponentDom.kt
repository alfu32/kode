package editor.ui.components

import editor.state.EditorState
import editor.state.Focus
import editor.ui.dom.Component
import editor.ui.dom.container
import react.DOMNode
import react.StyleSet

class FileTreeComponentDom(private val state: EditorState) : Component() {
    override fun render(): DOMNode {
        val entries = state.fileTreeEntries()
        val children = entries.mapIndexed { idx, entry ->
            val icon = if (entry.typ == "folder") {
                if (entry.isOpen) "[-]" else "[+]"
            } else "[=]"
            val pad = "  ".repeat(entry.padding)
            val line = "$pad  $icon ${entry.name}"
            val style = StyleSet(
                top = idx + 1,
                left = 0,
                fg = if (state.focus == Focus.LEFT && state.fileTreeSelection == idx) react.Color.from(0xE6E6E6) else react.Color.from(0xB4B4B4),
                bg = if (state.focus == Focus.LEFT && state.fileTreeSelection == idx) react.Color.from(0x323842) else react.Color.from(0x191C22)
            )
            container(
                tag = "file-entry",
                id = entry.fullPath,
                text = line,
                style = style
            )
        }
        return container(tag = "file-tree", styleId = "file-tree", style = StyleSet(), children = children)
    }
}
