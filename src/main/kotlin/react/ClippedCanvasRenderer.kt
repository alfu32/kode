package react

import react.renderer.CanvasRenderer

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