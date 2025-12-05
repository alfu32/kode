package editor.ui.dom.layout

import react.DOMNode
import react.StyleSet

/**
 * Assigns basic regions to root children: top bar (1 row), status (1 row), left/right split.
 */
fun layoutRoot(root: DOMNode, ctx: LayoutContext): DOMNode {
    val topHeight = 1
    val statusHeight = 1
    val bodyHeight = (ctx.rows - topHeight - statusHeight).coerceAtLeast(0)
    val leftWidth = (ctx.cols * 0.32).toInt().coerceIn(20, (ctx.cols * 0.6).toInt())
    val rightWidth = (ctx.cols - leftWidth).coerceAtLeast(10)

    root.children.getOrNull(0)?.style?.apply {
        top = 0; left = 0; right = ctx.cols - 1; bottom = topHeight - 1
    }
    root.children.getOrNull(1)?.style?.apply {
        top = topHeight; left = 0; right = leftWidth - 1; bottom = topHeight + bodyHeight - 1
    }
    root.children.getOrNull(2)?.style?.apply {
        top = topHeight; left = leftWidth; right = leftWidth + rightWidth - 1; bottom = topHeight + bodyHeight - 1
    }
    root.children.getOrNull(3)?.style?.apply {
        top = topHeight + bodyHeight; left = 0; right = ctx.cols - 1; bottom = ctx.rows - 1
    }
    return root
}
