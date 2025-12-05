package react.renderer

import react.UIEvent

/* =====================================================================
   Minimal no-op renderer to make the main loop runnable without a real VT backend.
   ===================================================================== */
class NoopRenderer(
    private val cols: Int = 120,
    private val rows: Int = 40
) : CanvasRenderer {
    override fun cols(): Int = cols
    override fun rows(): Int = rows
    override fun clear() {}
    override fun setColor(r: Int, g: Int, b: Int) {}
    override fun setBackgroundColor(r: Int, g: Int, b: Int) {}
    override fun bold(enabled: Boolean) {}
    override fun italic(enabled: Boolean) {}
    override fun underline(enabled: Boolean) {}
    override fun blink(enabled: Boolean) {}
    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {}
    override fun drawText(x: Int, y: Int, text: String) {}
    override fun setCursorPosition(x: Int, y: Int) {}
    override fun flush() {}
    override fun pollEvent(): UIEvent? = null
    override fun tryPollEvent(): UIEvent? = null

    /* ============================================================
   Lifecycle / Terminal Control (no-op)
   ============================================================ */

    override fun enableMouseTracking() {}
    override fun disableMouseTracking() {}
    override fun hideCursor() {}
    override fun showCursor() {}
    override fun resetAttributes() {}
    @Volatile
    private var running = true

    override fun isRunning(): Boolean = running
    override fun requestExit() { running = false }

    override fun shutdown() {}
}
