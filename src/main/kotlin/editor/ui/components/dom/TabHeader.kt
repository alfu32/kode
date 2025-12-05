package editor.ui.components.dom

import editor.state.LeftTab
import editor.ui.dom.Component
import editor.ui.dom.container
import react.DOMNode
import react.StyleSet

class TabHeader(private val active: LeftTab) : Component() {
    override fun render(): DOMNode {
        val children = listOf(
            tab("Project", active == LeftTab.PROJECT, "tab.project"),
            tab("Git", active == LeftTab.GIT, "tab.git"),
            tab("Settings", active == LeftTab.SETTINGS, "tab.settings"),
        )
        return container(tag = "tabs", style = StyleSet(top = 0, left = 0), children = children)
    }

    private fun tab(label: String, active: Boolean, id: String): DOMNode {
        val style = StyleSet().apply {
            fg = if (active) react.Color.from(0xFFFFFF) else react.Color.from(0xCCCCCC)
            bg = if (active) react.Color.from(0x333A44) else react.Color.from(0x252A32)
        }
        return container(tag = "tab", id = id, text = label, style = style)
    }
}
