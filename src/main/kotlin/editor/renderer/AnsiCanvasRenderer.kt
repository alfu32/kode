package editor.renderer

import java.io.Flushable
import java.io.InputStream

/**
 * Minimal ANSI renderer with a simple back buffer for diffed output.
 */
class AnsiCanvasRenderer(
    private val input: InputStream = System.`in`,
    private val output: Appendable = System.out,
    initialCols: Int = 120,
    initialRows: Int = 40
) : CanvasRenderer {

    @Volatile private var currentCols: Int = initialCols
    @Volatile private var currentRows: Int = initialRows
    private val frame = StringBuilder()
    private val backBuffer = StringBuilder()

    private fun esc(code: String) {
        frame.append("\u001b[$code")
    }

    override fun cols(): Int = currentCols
    override fun rows(): Int = currentRows

    override fun clear() {
        esc("2J")
        esc("H")
        backBuffer.setLength(0)
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        for (row in 0 until height) {
            esc("${y + row + 1};${x + 1}H")
            repeat(width) { frame.append(" ") }
        }
    }

    override fun drawText(x: Int, y: Int, text: String) {
        esc("${y + 1};${x + 1}H")
        frame.append(text)
    }

    override fun setColor(r: Int, g: Int, b: Int) {
        esc("38;2;$r;$g;${b}m")
    }

    override fun setBackgroundColor(r: Int, g: Int, b: Int) {
        esc("48;2;$r;$g;${b}m")
    }

    override fun bold(enabled: Boolean) {
        esc(if (enabled) "1m" else "22m")
    }

    override fun italic(enabled: Boolean) {
        esc(if (enabled) "3m" else "23m")
    }

    override fun underline(enabled: Boolean) {
        esc(if (enabled) "4m" else "24m")
    }

    override fun flush() {
        val content = frame.toString()
        if (content != backBuffer.toString()) {
            output.append(content)
            if (output is Flushable) {
                (output as Flushable).flush()
            }
            backBuffer.clear()
            backBuffer.append(content)
        }
        frame.setLength(0)
    }
}
