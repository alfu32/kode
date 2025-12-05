package react.renderer

import react.UIEvent
import kotlin.text.iterator

/* =====================================================================
   StringSnapshotRenderer (for testing)

This renderer:

 - Performs no ANSI output
 - Maintains an internal 2D character buffer (grid)
 - Records all drawRect/drawText operations
 - Ignores colors/bold/italic/etc.
 - Does not generate events
 - Provides .snapshot() to retrieve textual output

Made for clean, deterministic tests.
   ===================================================================== */
class StringSnapshotRenderer(
    private val cols: Int = 120,
    private val rows: Int = 40
) : CanvasRenderer {

    private val buffer = Array(rows) { CharArray(cols) { ' ' } }

    override fun cols(): Int = cols
    override fun rows(): Int = rows

    override fun clear() {
        for (y in 0 until rows)
            for (x in 0 until cols)
                buffer[y][x] = ' '
    }

    override fun setColor(r: Int, g: Int, b: Int) {}
    override fun setBackgroundColor(r: Int, g: Int, b: Int) {}
    override fun bold(enabled: Boolean) {}
    override fun italic(enabled: Boolean) {}
    override fun underline(enabled: Boolean) {}
    override fun blink(enabled: Boolean) {}

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        for (yy in y until (y + height)) {
            if (yy !in 0 until this@StringSnapshotRenderer.rows) continue
            for (xx in x until (x + width)) {
                if (xx !in 0 until this@StringSnapshotRenderer.cols) continue
                buffer[yy][xx] = '#'
            }
        }
    }

    override fun drawText(x: Int, y: Int, text: String) {
        if (y !in 0 until rows) return
        var px = x
        for (c in text) {
            if (px in 0 until cols)
                buffer[y][px] = c
            px++
        }
    }

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

    override fun requestExit() {
        running = false
    }
    override fun shutdown() {}

    fun snapshot(): String =
        buffer.joinToString("\n") { String(it) }
}
