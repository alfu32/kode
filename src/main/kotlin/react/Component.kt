package react

import react.renderer.CanvasRenderer

interface Component {
    val styleSheet: StyleSheet
    val children: List<Component>

    fun render(canvas: CanvasRenderer)
    fun dispatch(event: UIEvent): Boolean
}

abstract class BaseComponent(
    override val styleSheet: StyleSheet,
    children: List<Component> = emptyList()
) : Component {
    private val childList = children.toMutableList()
    override val children: List<Component> get() = childList

    fun addChild(child: Component) {
        childList.add(child)
    }

    fun setChildren(newChildren: List<Component>) {
        childList.clear()
        childList.addAll(newChildren)
    }
}
