package react

import react.renderer.CanvasRenderer

class TabView(
    styleSheet: StyleSheet,
    private val titles: List<String>,
    tabComponents: List<Component>,
    initialIndex: Int = 0,
    private val onSelect: ((Int) -> Unit)? = null
) : BaseComponent(styleSheet, tabComponents) {
    private var selected = initialIndex.coerceIn(titles.indices)
    private val headerHeight = 1
    private var lastContentWidth = 0
    private var lastContentHeight = 0

    init {
        require(titles.size == tabComponents.size) { "Titles and components must match in size" }
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(headerHeight)
        val tabStyle = styleSheet.getStyle("tab-strip")
        val activeStyle = styleSheet.getStyle("tab-button:focus")
        val inactiveStyle = styleSheet.getStyle("tab-button")
        val contentStyle = styleSheet.getStyle("content")

        // Header
        canvas.withStyle(tabStyle) {
            drawRect(0, 0, cols, headerHeight)
            var x = 0
            titles.forEachIndexed { idx, title ->
                if (x >= cols) return@forEachIndexed
                val label = " $title "
                val style = if (idx == selected) activeStyle else inactiveStyle
                withStyle(style) {
                    val visibleWidth = (cols - x).coerceAtLeast(0)
                    if (visibleWidth > 0) {
                        drawText(x.coerceAtLeast(0), 0, label.take(visibleWidth))
                    }
                }
                x += label.length
            }
        }

        val contentTop = headerHeight
        val contentHeight = (rows - headerHeight).coerceAtLeast(0)
        lastContentWidth = cols
        lastContentHeight = contentHeight
        if (contentHeight == 0 || children.isEmpty()) return

        // Content background
        canvas.withStyle(contentStyle) {
            drawRect(0, contentTop, cols, contentHeight)
        }

        val child = children.getOrNull(selected) ?: return
        val clipped = ClippedCanvasRenderer(
            base = canvas,
            offsetX = 0,
            offsetY = contentTop,
            width = cols,
            height = contentHeight
        )
        child.render(clipped)
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") {
            var handled = false
            children.forEach { child ->
                handled = child.dispatch(event) || handled
            }
            return handled
        }
        when (event.kind) {
            "mouse_down" -> {
                val ex = event.x ?: return false
                val ey = event.y ?: return false
                if (ey == 0) {
                    val newIdx = tabIndexAtX(ex)
                    if (newIdx != null && newIdx != selected) {
                        selected = newIdx
                        onSelect?.invoke(selected)
                        return true
                    }
                    return false
                }
            }
            "key_down" -> {
                val key = event.key?.lowercase()
                // Only switch tabs on alt+left/right to avoid stealing arrows from editors.
                if (event.alt && (key == "left" || key == "right")) {
                    if (titles.isNotEmpty()) {
                        selected = if (key == "left") {
                            (selected - 1 + titles.size) % titles.size
                        } else {
                            (selected + 1) % titles.size
                        }
                        onSelect?.invoke(selected)
                        return true
                    }
                }
                val child = children.getOrNull(selected) ?: return false
                return child.dispatch(event)
            }
        }

        // Forward to active child with coordinate adjustment if inside content
        val child = children.getOrNull(selected) ?: return false
        val ex = event.x
        val ey = event.y
        val contentTop = headerHeight
        if (ex != null && ey != null && ey >= contentTop) {
        val forwarded = event.alterCopy(
            UIEvent(
                kind = event.kind,
                x = ex,
                y = ey - contentTop,
                relX = event.relX,
                relY = event.relY,
                button = event.button,
                scrollDelta = event.scrollDelta,
                key = event.key,
                ctrl = event.ctrl,
                alt = event.alt,
                shift = event.shift,
                meta = event.meta,
                focusId = event.focusId,
                cols = lastContentWidth,
                rows = lastContentHeight,
                raw = event.raw,
                timeMs = event.timeMs
            )
        )
        return child.dispatch(forwarded)
    }

        return false
    }

    private fun tabIndexAtX(x: Int): Int? {
        var cursor = 0
        titles.forEachIndexed { idx, title ->
            val label = " $title "
            val next = cursor + label.length
            if (x in cursor until next) return idx
            cursor = next
        }
        return null
    }
}
