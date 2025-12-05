package editor.ui.dom

import react.DOMNode
import react.StyleSet
import react.UIEvent

/**
 * Base UI component. Each component is responsible for:
 *  - Laying out its children and setting bounding boxes in their StyleSet.
 *  - Rendering into a DOMNode tree (with resolved style ids/inline styles).
 *  - Handling events dispatched to it.
 */
abstract class Component {
    /**
     * Called to build the component's DOM subtree. Caller is responsible for applying layout
     * constraints to the returned node and its children.
     */
    abstract fun render(): DOMNode

    /**
     * Dispatch an event to this component. Implementations decide routing to children.
     */
    open fun handle(event: UIEvent) {}
}

/** Simple composition helper. */
fun container(
    tag: String,
    id: String? = null,
    styleId: String? = null,
    style: StyleSet = StyleSet(),
    text: String? = null,
    children: List<DOMNode> = emptyList()
): DOMNode =
    DOMNode(tag = tag, id = id, styleId = styleId, style = style, text = text, children = children)
