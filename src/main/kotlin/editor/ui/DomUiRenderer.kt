package editor.ui

import editor.state.EditorState
import editor.ui.dom.Component
import editor.ui.dom.RootRenderer
import editor.ui.dom.applyAbsoluteLayout
import editor.ui.dom.container
import editor.ui.dom.layout.LayoutContext
import editor.ui.dom.layout.layoutRoot
import editor.ui.dom.nodes.LeftPane
import editor.ui.dom.nodes.RightPane
import editor.ui.dom.nodes.StatusBar
import editor.ui.dom.nodes.TopBar
import react.DOMNode
import react.StyleSheet
import react.renderer.CanvasRenderer

/**
 * DOM-based renderer: builds a tree of components, applies layout, draws.
 */
class DomUiRenderer(
    private val renderer: CanvasRenderer,
    private val styleSheet: StyleSheet
) {
    private val rootRenderer = RootRenderer(renderer, styleSheet)
    private var linearized: List<DOMNode> = emptyList()
    private var rootNode: DOMNode? = null

    fun render(state: EditorState) {
        val root = buildTree(state)
        rootNode = root
        val layout = layoutRoot(root, LayoutContext(renderer.cols(), renderer.rows()))
        applyAbsoluteLayout(root, 0, 0)
        linearized = collectVisible(root)
        rootRenderer.render(root)
    }

    fun hitTest(x: Int, y: Int): DOMNode? =
        linearized.lastOrNull { node ->
            node.style.boundingBox().contains(x, y)
        }

    fun dispatchToNode(node: DOMNode?, event: react.UIEvent) {
        node ?: return
        when (event.kind) {
            "mouse_down" -> node.onMouseDown?.invoke(event)
            "mouse_up" -> node.onMouseUp?.invoke(event)
            "mouse_move" -> node.onMouseMove?.invoke(event)
            "mouse_scroll" -> node.onMouseScroll?.invoke(event)
            "key_down" -> node.onKeyDown?.invoke(event)
            "key_up" -> node.onKeyUp?.invoke(event)
            "resize" -> node.onResize?.invoke(event)
        }
    }

    fun dispatchKey(event: react.UIEvent) {
        dispatchToNode(rootNode, event)
    }

    private fun buildTree(state: EditorState): DOMNode {
        val top = TopBar()
        val left = LeftPane(state)
        val right = RightPane(state)
        val status = StatusBar(state)
        return container(
            tag = "root",
            styleId = "root",
            children = listOf(top.render(), left.render(), right.render(), status.render())
        )
    }

    private fun collectVisible(root: DOMNode): List<DOMNode> {
        val list = mutableListOf<DOMNode>()
        root.eachPre { node, _, _ ->
            if (node.visible) list += node
        }
        return list
    }
}
