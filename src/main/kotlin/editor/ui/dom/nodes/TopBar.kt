package editor.ui.dom.nodes

import editor.ui.dom.Component
import editor.ui.dom.container
import react.DOMNode
import react.StyleSet

class TopBar : Component() {
    override fun render(): DOMNode =
        container(
            tag = "topbar",
            styleId = "topbar",
            style = StyleSet(top = 0, left = 0),
            text = "TUI Editor"
        )
}
