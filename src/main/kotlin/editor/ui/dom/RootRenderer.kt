package editor.ui.dom

import react.DOMNode
import react.StyleSheet
import react.StyleSet
import react.renderer.CanvasRenderer

/**
 * Renders a DOM tree onto a CanvasRenderer.
 */
class RootRenderer(
    private val renderer: CanvasRenderer,
    private val styleSheet: StyleSheet
){
    fun render(root: DOMNode) {
        renderer.clear()
        drawNode(root)
        renderer.flush()
    }

    private fun drawNode(node: DOMNode) {
        if (!node.visible) return
        val resolved = resolveStyle(node)
        val box = resolved.boundingBox()

        // background fill
        resolved.bg?.let { bg ->
            renderer.setBackgroundColor(bg.r, bg.g, bg.b)
            renderer.drawRect(box.left, box.top, box.width(), box.height())
        }
        // text
        node.text?.let { text ->
            resolved.fg?.let { fg -> renderer.setColor(fg.r, fg.g, fg.b) }
            renderer.drawText(box.left, box.top, text)
        }

        node.children.forEach { drawNode(it) }
    }

    private fun resolveStyle(node: DOMNode): StyleSet {
        val base = node.style.copy()
        node.styleId?.let { id ->
            val fromSheet = styleSheet.getStyle(id)
            base.mergeFrom(fromSheet)
        }
        return base
    }
}
