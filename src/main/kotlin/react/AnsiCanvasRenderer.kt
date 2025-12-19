package react.renderer

import react.UIEvent
import react.util.WindowsConsole
import react.util.runCommand
import java.io.Flushable
import java.io.InputStream
import java.util.ArrayDeque

/* =====================================================================
   ANSI Terminal Renderer

This renderer:

 - Uses ANSI escape sequences
 - Assumes raw mode is enabled (you’ll handle this outside—Termux/Linux)
 - Reads stdin for key and mouse events
 - Supports SGR text formatting
 - Supports RGB foreground/background
 - Draws rectangles and text
 - Maintains no back buffer (your framework controls redraw)

Note: Terminal mouse reporting requires enabling Mouse Tracking Mode.
You’ll need to enable it once, outside this class:

```
    print("\u001b[?1000h") // Mouse tracking on (press/release)
    print("\u001b[?1003h") // Mouse motion tracking
```

    And raw mode for stdin.
   ===================================================================== */
class AnsiCanvasRenderer(
    private val input: InputStream = WindowsConsole.openInputStream(),
    private val output: Appendable = System.out,
    private val initialCols: Int = 200,
    private val initialRows: Int = 80
) : CanvasRenderer {
    companion object {
        private const val STYLE_BOLD = 1
        private const val STYLE_ITALIC = 1 shl 1
        private const val STYLE_UNDERLINE = 1 shl 2
        private const val STYLE_BLINK = 1 shl 3
    }

    @Volatile
    private var currentCols: Int = initialCols
    @Volatile
    private var currentRows: Int = initialRows
    @Volatile
    private var pendingResize: UIEvent? = null
    private var lastSizeCheckNanos: Long = 0L

    private val frame = StringBuilder()
    private val useDiffBuffer = true
    private val ansiReady: Boolean =
        !WindowsConsole.isWindows() || WindowsConsole.enableVirtualTerminalProcessing()
    private var warnedPlain = false
    private val useThreadedInput = WindowsConsole.isWindows() ||
        (System.getenv("KODE_FORCE_THREADED_INPUT") == "1")
    private val inputBuffer = InputBuffer(input, useThreadedInput)
    private var currentFg: Int = -1
    private var currentBg: Int = -1
    private var currentStyle: Int = 0
    private var cursorX: Int = 0
    private var cursorY: Int = 0
    private var buffer: Array<Cell> = emptyArray()
    private var lastBuffer: Array<Cell> = emptyArray()

    init {
        queryTerminalSize()?.let { (rows, cols) ->
            currentRows = rows
            currentCols = cols
        }
        ensureBuffers()
    }

    private fun esc(code: String) {
        if (!ansiReady) {
            if (!warnedPlain) {
                frame.append("ANSI disabled; VT not available on this console.\n")
                warnedPlain = true
            }
            return
        }
        frame.append("\u001b[$code")
    }

    override fun cols(): Int = currentCols
    override fun rows(): Int = currentRows

    /* ============================================================
       Drawing API
       ============================================================ */

    override fun clear() {
        if (useDiffBuffer) {
            fillBuffer(' ')
            return
        }
        if (!ansiReady) return
        esc("2J")      // clear
        esc("H")       // cursor home
    }

    override fun setColor(r: Int, g: Int, b: Int) {
        if (useDiffBuffer) {
            currentFg = (r shl 16) or (g shl 8) or b
            return
        }
        esc("38;2;$r;$g;${b}m")
    }

    override fun setBackgroundColor(r: Int, g: Int, b: Int) {
        if (useDiffBuffer) {
            currentBg = (r shl 16) or (g shl 8) or b
            return
        }
        esc("48;2;$r;$g;${b}m")
    }

    override fun bold(enabled: Boolean) {
        if (useDiffBuffer) {
            currentStyle = if (enabled) (currentStyle or STYLE_BOLD) else (currentStyle and STYLE_BOLD.inv())
            return
        }
        esc(if (enabled) "1m" else "22m")
    }

    override fun italic(enabled: Boolean) {
        if (useDiffBuffer) {
            currentStyle = if (enabled) (currentStyle or STYLE_ITALIC) else (currentStyle and STYLE_ITALIC.inv())
            return
        }
        esc(if (enabled) "3m" else "23m")
    }

    override fun underline(enabled: Boolean) {
        if (useDiffBuffer) {
            currentStyle = if (enabled) (currentStyle or STYLE_UNDERLINE) else (currentStyle and STYLE_UNDERLINE.inv())
            return
        }
        esc(if (enabled) "4m" else "24m")
    }

    override fun blink(enabled: Boolean) {
        if (useDiffBuffer) {
            currentStyle = if (enabled) (currentStyle or STYLE_BLINK) else (currentStyle and STYLE_BLINK.inv())
            return
        }
        esc(if (enabled) "5m" else "25m")
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (useDiffBuffer) {
            val maxX = (x + width).coerceAtMost(currentCols)
            val maxY = (y + height).coerceAtMost(currentRows)
            for (row in y until maxY) {
                val rowStart = row * currentCols
                for (col in x until maxX) {
                    setCell(rowStart + col, ' ')
                }
            }
            return
        }
        if (!ansiReady) return
        for (row in 0 until height) {
            esc("${y + row + 1};${x + 1}H")
            repeat(width) { frame.append(" ") }
        }
    }

    override fun drawText(x: Int, y: Int, text: String) {
        if (useDiffBuffer) {
            if (y < 0 || y >= currentRows) return
            var col = x
            val rowStart = y * currentCols
            for (ch in text) {
                if (col >= 0 && col < currentCols) {
                    setCell(rowStart + col, ch)
                }
                col++
                if (col >= currentCols) break
            }
            return
        }
        if (!ansiReady) {
            frame.append(text)
            frame.append("\n")
            return
        }
        esc("${y + 1};${x + 1}H")
        frame.append(text)
    }

    override fun setCursorPosition(x: Int, y: Int) {
        if (useDiffBuffer) {
            cursorX = x
            cursorY = y
            return
        }
        if (!ansiReady) return
        esc("${y + 1};${x + 1}H")
    }

    override fun flush() {
        if (useDiffBuffer) {
            flushDiff()
            return
        }
        output.append(frame.toString())
        if (output is Flushable) {
            (output as Flushable).flush()
        }
        frame.setLength(0)
    }

    /* ============================================================
       Event Parsing
       ============================================================ */

    private fun queryTerminalSize(): Pair<Int, Int>? {
        if (WindowsConsole.isWindows()) {
            return WindowsConsole.getConsoleSize()
        }
        val output = runCommand("sh", "-c", "stty size < /dev/tty")?.trim() ?: return null
        val parts = output.split(Regex("\\s+"))
        if (parts.size != 2) return null
        val rows = parts[0].toIntOrNull() ?: return null
        val cols = parts[1].toIntOrNull() ?: return null
        return rows to cols
    }

    private fun checkForResizeEvent() {
        val now = System.nanoTime()
        if (now - lastSizeCheckNanos < 200_000_000L) return // throttle checks (~5/sec)
        lastSizeCheckNanos = now

        val (rows, cols) = queryTerminalSize() ?: return
        if (rows != currentRows || cols != currentCols) {
            currentRows = rows
            currentCols = cols
            ensureBuffers()
            pendingResize = UIEvent("resize", cols = currentCols, rows = currentRows)
        }
    }

    override fun pollEvent(): UIEvent {
        while (true) {
            val e = tryPollEvent()
            if (e != null) return e
            Thread.sleep(5)
        }
    }

    override fun tryPollEvent(): UIEvent? {
        checkForResizeEvent()
        pendingResize?.let {
            pendingResize = null
            return it
        }
        if (inputBuffer.available() <= 0) return null
        val b = inputBuffer.readNonBlocking() ?: return null
        if (b < 0) return null

        return parseAnsiInput(b)
    }

    private fun parseAnsiInput(firstByte: Int): UIEvent? {
        // Handle ESC sequences
        if (firstByte == 0x1b) {
            val next = inputBuffer.readBlocking()
                ?: return UIEvent(kind = "unknown", raw = bytesToHex(byteArrayOf(0x1b)))
            if (next == '['.code) {
                val (event, raw) = parseCsiWithRaw()
                return event ?: UIEvent(kind = "unknown", raw = raw)
            }
            // Alt-modified char: ESC + char
            val ch = next.toChar()
            return UIEvent(kind = "key_down", key = "$ch", alt = true)
        }

        // Control keys
        when (firstByte) {
            0x7F, 0x08 -> return UIEvent(kind = "key_down", key = "Backspace")
            0x0D, 0x0A -> return UIEvent(kind = "key_down", key = "Enter")
        }

        // Simple printable/control chars (use ctrl flag for ASCII control range)
        val ch = firstByte.toChar()
        val isCtrl = firstByte in 1..26
        val keyName = if (isCtrl) ch.plus(64).toChar().toString() else "$ch"
        return UIEvent(kind = "key_down", key = keyName, ctrl = isCtrl)
    }

    private fun parseCsiWithRaw(): Pair<UIEvent?, String> {
        val seq = StringBuilder()
        val bytes = ArrayList<Byte>(16)
        bytes.add(0x1b.toByte())
        bytes.add('['.code.toByte())
        while (true) {
            val next = inputBuffer.readBlocking(2) ?: break
            bytes.add(next.toByte())
            val c = next.toChar()
            seq.append(c)
            if ((c in 'A'..'Z') || (c in 'a'..'z')) break
        }
        val s = seq.toString()
        val finalChar = s.lastOrNull() ?: return null to bytesToHex(bytes.toByteArray())
        val body = s.dropLast(1)
        val params = if (body.isEmpty()) emptyList() else body.split(';')

        data class Mods(val shift: Boolean, val alt: Boolean, val ctrl: Boolean, val meta: Boolean)
        fun decodeMods(modParam: Int): Mods {
            // xterm modifier encoding: mod = 1 + (shift?1) + (alt?2) + (ctrl?4) + (meta?8)
            val bits = (modParam - 1).coerceAtLeast(0)
            val shift = (bits and 1) != 0
            val alt = (bits and 2) != 0
            val ctrl = (bits and 4) != 0
            val meta = (bits and 8) != 0
            return Mods(shift, alt, ctrl, meta)
        }

        // Mouse SGR: <btn;x;yM or <btn;x;ym
        if ((s.endsWith("M") || s.endsWith("m")) && s.startsWith("<")) {
            val parts = s.dropLast(1).split(';')
            if (parts.size >= 3) {
                val btnCode = parts[0].drop(1).toIntOrNull() ?: return null to bytesToHex(bytes.toByteArray())
                val x = parts[1].toIntOrNull()?.minus(1) ?: return null to bytesToHex(bytes.toByteArray())
                val y = parts[2].toIntOrNull()?.minus(1) ?: return null to bytesToHex(bytes.toByteArray())
                val press = s.endsWith("M")
                val motion = (btnCode and 32) != 0
                val baseBtn = btnCode and 0b11
                val isScroll = (btnCode and 0b1000000) != 0

                val shift = (btnCode and 4) != 0
                val alt = (btnCode and 8) != 0
                val ctrl = (btnCode and 16) != 0

                // Scroll wheel
                if (isScroll) {
                    val delta = when (baseBtn) {
                        0 -> 1   // wheel up
                        1 -> -1  // wheel down
                        else -> 0
                    }
                    if (delta != 0) {
                        return UIEvent(
                            "mouse_scroll",
                            x = x,
                            y = y,
                            scrollDelta = delta,
                            ctrl = ctrl,
                            alt = alt,
                            shift = shift
                        ) to bytesToHex(bytes.toByteArray())
                    }
                }

                val button = when (baseBtn) {
                    0 -> 0
                    1 -> 1
                    2 -> 2
                    else -> null
                }

                val kind = when {
                    motion -> "mouse_move"      // treat any motion as move
                    press -> "mouse_down"
                    else -> "mouse_up"
                }
                return UIEvent(kind, x = x, y = y, button = button, ctrl = ctrl, alt = alt, shift = shift) to bytesToHex(bytes.toByteArray())
            }
        }

        // Keys / navigation with optional modifiers (CSI 1;5A, etc.)
        val mods = if (params.size >= 2) {
            decodeMods(params.last().toIntOrNull() ?: 1)
        } else Mods(false, false, false, false)

        val event = when (finalChar) {
            'A' -> UIEvent(
                "key_down",
                key = "Up",
                shift = mods.shift,
                alt = mods.alt,
                ctrl = mods.ctrl,
                meta = mods.meta
            )
            'B' -> UIEvent(
                "key_down",
                key = "Down",
                shift = mods.shift,
                alt = mods.alt,
                ctrl = mods.ctrl,
                meta = mods.meta
            )
            'C' -> UIEvent(
                "key_down",
                key = "Right",
                shift = mods.shift,
                alt = mods.alt,
                ctrl = mods.ctrl,
                meta = mods.meta
            )
            'D' -> UIEvent(
                "key_down",
                key = "Left",
                shift = mods.shift,
                alt = mods.alt,
                ctrl = mods.ctrl,
                meta = mods.meta
            )
            'H' -> UIEvent(
                "key_down",
                key = "Home",
                shift = mods.shift,
                alt = mods.alt,
                ctrl = mods.ctrl,
                meta = mods.meta
            )
            'F' -> UIEvent(
                "key_down",
                key = "End",
                shift = mods.shift,
                alt = mods.alt,
                ctrl = mods.ctrl,
                meta = mods.meta
            )
            '~' -> {
                val code = params.firstOrNull()?.toIntOrNull()
                when (code) {
                    1, 7 -> UIEvent(
                        "key_down",
                        key = "Home",
                        shift = mods.shift,
                        alt = mods.alt,
                        ctrl = mods.ctrl,
                        meta = mods.meta
                    )
                    4, 8 -> UIEvent(
                        "key_down",
                        key = "End",
                        shift = mods.shift,
                        alt = mods.alt,
                        ctrl = mods.ctrl,
                        meta = mods.meta
                    )
                    2 -> UIEvent(
                        "key_down",
                        key = "Insert",
                        shift = mods.shift,
                        alt = mods.alt,
                        ctrl = mods.ctrl,
                        meta = mods.meta
                    )
                    3 -> UIEvent(
                        "key_down",
                        key = "Delete",
                        shift = mods.shift,
                        alt = mods.alt,
                        ctrl = mods.ctrl,
                        meta = mods.meta
                    )
                    5 -> UIEvent(
                        "key_down",
                        key = "PageUp",
                        shift = mods.shift,
                        alt = mods.alt,
                        ctrl = mods.ctrl,
                        meta = mods.meta
                    )
                    6 -> UIEvent(
                        "key_down",
                        key = "PageDown",
                        shift = mods.shift,
                        alt = mods.alt,
                        ctrl = mods.ctrl,
                        meta = mods.meta
                    )
                    else -> null
                }
            }
            else -> null
        }
        return event to bytesToHex(bytes.toByteArray())
    }

    private fun bytesToHex(bytes: ByteArray): String {
        val sb = StringBuilder(bytes.size * 2)
        for (b in bytes) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun ensureBuffers() {
        if (!useDiffBuffer) return
        val size = (currentCols * currentRows).coerceAtLeast(0)
        if (buffer.size != size) {
            buffer = Array(size) { Cell() }
            lastBuffer = Array(size) { Cell() }
            fillBuffer(' ')
            copyBuffer()
        }
    }

    private fun fillBuffer(ch: Char) {
        if (!useDiffBuffer) return
        for (i in buffer.indices) {
            val cell = buffer[i]
            cell.ch = ch
            cell.fg = currentFg
            cell.bg = currentBg
            cell.style = currentStyle
        }
    }

    private fun copyBuffer() {
        for (i in buffer.indices) {
            val src = buffer[i]
            val dst = lastBuffer[i]
            dst.ch = src.ch
            dst.fg = src.fg
            dst.bg = src.bg
            dst.style = src.style
        }
    }

    private fun setCell(index: Int, ch: Char) {
        if (index < 0 || index >= buffer.size) return
        val cell = buffer[index]
        cell.ch = ch
        cell.fg = currentFg
        cell.bg = currentBg
        cell.style = currentStyle
    }

    private fun flushDiff() {
        if (!ansiReady) return
        ensureBuffers()
        var lastFg = Int.MIN_VALUE
        var lastBg = Int.MIN_VALUE
        var lastStyle = Int.MIN_VALUE

        for (row in 0 until currentRows) {
            val rowStart = row * currentCols
            for (col in 0 until currentCols) {
                val idx = rowStart + col
                val cell = buffer[idx]
                val prev = lastBuffer[idx]
                if (cell != prev) {
                    esc("${row + 1};${col + 1}H")
                    if (cell.fg != lastFg || cell.bg != lastBg || cell.style != lastStyle) {
                        esc("0m")
                        emitStyle(cell)
                        lastFg = cell.fg
                        lastBg = cell.bg
                        lastStyle = cell.style
                    }
                    frame.append(cell.ch)
                    prev.ch = cell.ch
                    prev.fg = cell.fg
                    prev.bg = cell.bg
                    prev.style = cell.style
                }
            }
        }
        esc("${cursorY + 1};${cursorX + 1}H")
        output.append(frame.toString())
        if (output is Flushable) {
            (output as Flushable).flush()
        }
        frame.setLength(0)
    }

    private fun emitStyle(cell: Cell) {
        if ((cell.style and STYLE_BOLD) != 0) esc("1m")
        if ((cell.style and STYLE_ITALIC) != 0) esc("3m")
        if ((cell.style and STYLE_UNDERLINE) != 0) esc("4m")
        if ((cell.style and STYLE_BLINK) != 0) esc("5m")
        if (cell.bg != -1) {
            val r = (cell.bg shr 16) and 0xFF
            val g = (cell.bg shr 8) and 0xFF
            val b = cell.bg and 0xFF
            esc("48;2;$r;$g;${b}m")
        }
        if (cell.fg != -1) {
            val r = (cell.fg shr 16) and 0xFF
            val g = (cell.fg shr 8) and 0xFF
            val b = cell.fg and 0xFF
            esc("38;2;$r;$g;${b}m")
        }
    }

    private data class Cell(
        var ch: Char = ' ',
        var fg: Int = -1,
        var bg: Int = -1,
        var style: Int = 0
    )

    /* ============================================================
   Lifecycle / Terminal Control
   ============================================================ */

    // Enable terminal mouse tracking modes
    override fun enableMouseTracking() {
        // Basic click, drag & motion, SGR (extended coords)
        output.append("\u001b[?1000h") // mouse click
        output.append("\u001b[?1002h") // mouse drag
        output.append("\u001b[?1003h") // mouse motion
        output.append("\u001b[?1006h") // SGR extended
    }

    // Disable all mouse modes
    override fun disableMouseTracking() {
        output.append("\u001b[?1000l")
        output.append("\u001b[?1002l")
        output.append("\u001b[?1003l")
        output.append("\u001b[?1006l")
    }

    // Cursor visibility: hide/show
    override fun hideCursor() {
        output.append("\u001b[?25l")
    }

    override fun showCursor() {
        output.append("\u001b[?25h")
    }

    // Reset SGR attributes
    override fun resetAttributes() {
        if (useDiffBuffer) {
            currentFg = -1
            currentBg = -1
            currentStyle = 0
            return
        }
        output.append("\u001b[0m")
    }


    @Volatile
    private var running = true

    override fun isRunning(): Boolean = running

    override fun requestExit() {
        running = false
    }
    // Shutdown the renderer and cleanup terminal state
    override fun shutdown() {
        disableMouseTracking()
        resetAttributes()
        showCursor()
        leaveAlternateScreen()
    }

    fun enterAlternateScreen() {
        output.append("\u001b[?1049h")
        output.append("\u001b[H")
    }

    fun leaveAlternateScreen() {
        output.append("\u001b[?1049l")
    }

    private class InputBuffer(
        private val input: InputStream,
        private val threaded: Boolean
    ) {
        private val queue = ArrayDeque<Int>()
        private val lock = Any()

        init {
            if (threaded) {
                val thread = Thread {
                    while (true) {
                        val b = try {
                            input.read()
                        } catch (_: Exception) {
                            -1
                        }
                        if (b < 0) break
                        synchronized(lock) {
                            queue.addLast(b)
                        }
                    }
                }
                thread.isDaemon = true
                thread.name = "kode-ansi-input"
                thread.start()
            }
        }

        fun available(): Int {
            return if (threaded) {
                synchronized(lock) { queue.size }
            } else {
                input.available()
            }
        }

        fun readNonBlocking(): Int? {
            return if (threaded) {
                synchronized(lock) {
                    if (queue.isEmpty()) null else queue.removeFirst()
                }
            } else {
                if (input.available() <= 0) null else input.read()
            }
        }

        fun readBlocking(timeoutMs: Int = 10): Int? {
            if (!threaded) {
                return input.read()
            }
            val deadline = System.nanoTime() + timeoutMs * 1_000_000L
            while (System.nanoTime() < deadline) {
                synchronized(lock) {
                    if (queue.isNotEmpty()) return queue.removeFirst()
                }
                Thread.sleep(1)
            }
            return null
        }
    }
}
