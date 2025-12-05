package editor.ui.dom

import react.DOMNode
import react.ContentBox

/**
 * Applies absolute positions to a DOM tree. Parent is expected to pass the origin.
 */
fun applyAbsoluteLayout(node: DOMNode, originX: Int, originY: Int) {
    val box = node.style.boundingBox()
    val dx = originX + box.left
    val dy = originY + box.top
    node.style.offset(originX, originY)
    node.children.forEach { child ->
        applyAbsoluteLayout(child, dx, dy)
    }
}

/**
 * A simple horizontal layout: stack children left-to-right inside the given box.
 */
fun applyHorizontalLayout(node: DOMNode, container: ContentBox, spacing: Int = 1) {
    var cursorX = container.left
    node.children.forEach { child ->
        val box = child.style.boundingBox()
        val width = box.width()
        val height = box.height()
        child.style.left = cursorX
        child.style.top = container.top
        child.style.right = cursorX + width - 1
        child.style.bottom = container.top + height - 1
        cursorX += width + spacing
    }
}

/**
 * Vertical layout: stack children top-to-bottom inside the given box.
 */
fun applyVerticalLayout(node: DOMNode, container: ContentBox, spacing: Int = 1) {
    var cursorY = container.top
    node.children.forEach { child ->
        val box = child.style.boundingBox()
        val width = box.width()
        val height = box.height()
        child.style.left = container.left
        child.style.top = cursorY
        child.style.right = container.left + width - 1
        child.style.bottom = cursorY + height - 1
        cursorY += height + spacing
    }
}
