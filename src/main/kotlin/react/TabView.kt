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
                val label = " $title "
                val style = if (idx == selected) activeStyle else inactiveStyle
                withStyle(style) {
                    drawText(x.coerceAtLeast(0), 0, label.take(cols - x))
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
            "key_down" -> when (event.key?.lowercase()) {
                "left" -> {
                    if (titles.isNotEmpty()) {
                        selected = (selected - 1 + titles.size) % titles.size
                        onSelect?.invoke(selected)
                        return true
                    }
                }
                "right" -> {
                    if (titles.isNotEmpty()) {
                        selected = (selected + 1) % titles.size
                        onSelect?.invoke(selected)
                        return true
                    }
                }
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
                raw = event.raw
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

class ClippedCanvasRenderer(
    private val base: CanvasRenderer,
    private val offsetX: Int,
    private val offsetY: Int,
    private val width: Int,
    private val height: Int
) : CanvasRenderer {
    override fun cols(): Int = width
    override fun rows(): Int = height

    override fun clear() {
        base.clear()
    }

    override fun setColor(r: Int, g: Int, b: Int) = base.setColor(r, g, b)
    override fun setBackgroundColor(r: Int, g: Int, b: Int) = base.setBackgroundColor(r, g, b)
    override fun bold(enabled: Boolean) = base.bold(enabled)
    override fun italic(enabled: Boolean) = base.italic(enabled)
    override fun underline(enabled: Boolean) = base.underline(enabled)
    override fun blink(enabled: Boolean) = base.blink(enabled)

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        val x0 = (x + offsetX).coerceAtLeast(offsetX)
        val y0 = (y + offsetY).coerceAtLeast(offsetY)
        val x1 = (x + offsetX + width).coerceAtMost(offsetX + this.width)
        val y1 = (y + offsetY + height).coerceAtMost(offsetY + this.height)
        if (x1 <= x0 || y1 <= y0) return
        base.drawRect(x0, y0, x1 - x0, y1 - y0)
    }

    override fun drawText(x: Int, y: Int, text: String) {
        val targetY = y + offsetY
        if (targetY !in offsetY until (offsetY + height)) return
        var targetX = x + offsetX
        if (targetX >= offsetX + width) return
        var slice = text
        if (targetX < offsetX) {
            val skip = offsetX - targetX
            if (skip >= slice.length) return
            slice = slice.drop(skip)
            targetX = offsetX
        }
        val maxLen = (offsetX + width - targetX).coerceAtLeast(0)
        if (maxLen <= 0) return
        base.drawText(targetX, targetY, slice.take(maxLen))
    }

    override fun setCursorPosition(x: Int, y: Int) = base.setCursorPosition(x + offsetX, y + offsetY)
    override fun flush() = base.flush()

    override fun pollEvent(): UIEvent? = base.pollEvent()
    override fun tryPollEvent(): UIEvent? = base.tryPollEvent()

    override fun enableMouseTracking() = base.enableMouseTracking()
    override fun disableMouseTracking() = base.disableMouseTracking()
    override fun hideCursor() = base.hideCursor()
    override fun showCursor() = base.showCursor()
    override fun resetAttributes() = base.resetAttributes()
    override fun isRunning(): Boolean = base.isRunning()
    override fun requestExit() = base.requestExit()
    override fun shutdown() = base.shutdown()
}

private inline fun CanvasRenderer.withStyle(style: StyleSet, block: CanvasRenderer.() -> Unit) {
    style.bg?.let { setBackgroundColor(it.r, it.g, it.b) }
    style.fg?.let { setColor(it.r, it.g, it.b) }
    block()
    resetAttributes()
}
