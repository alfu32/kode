package react.renderer

import react.UIEvent
import react.util.runCommand
import react.util.WindowsConsole
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
    private val input: InputStream = System.`in`,
    private val output: Appendable = System.out,
    private val initialCols: Int = 200,
    private val initialRows: Int = 80
) : CanvasRenderer {

    @Volatile
    private var currentCols: Int = initialCols
    @Volatile
    private var currentRows: Int = initialRows
    @Volatile
    private var pendingResize: UIEvent? = null
    private var lastSizeCheckNanos: Long = 0L

    private val frame = StringBuilder()
    private val ansiReady: Boolean =
        !WindowsConsole.isWindows() || WindowsConsole.enableVirtualTerminalProcessing()
    private var warnedPlain = false
    private val useThreadedInput = WindowsConsole.isWindows() ||
        (System.getenv("KODE_FORCE_THREADED_INPUT") == "1")
    private val useWin32Input = WindowsConsole.isWindows() &&
        WindowsConsole.canUseWin32Input() &&
        (System.getenv("KODE_WIN32_INPUT") == "1" || WindowsConsole.isVtInputEnabled() == false)
    private val inputBuffer = InputBuffer(input, useThreadedInput)

    init {
        queryTerminalSize()?.let { (rows, cols) ->
            currentRows = rows
            currentCols = cols
        }
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
        if (!ansiReady) return
        esc("2J")      // clear
        esc("H")       // cursor home
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

    override fun blink(enabled: Boolean) {
        esc(if (enabled) "5m" else "25m")
    }

    override fun drawRect(x: Int, y: Int, width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        if (!ansiReady) return
        for (row in 0 until height) {
            esc("${y + row + 1};${x + 1}H")
            repeat(width) { frame.append(" ") }
        }
    }

    override fun drawText(x: Int, y: Int, text: String) {
        if (!ansiReady) {
            frame.append(text)
            frame.append("\n")
            return
        }
        esc("${y + 1};${x + 1}H")
        frame.append(text)
    }

    override fun setCursorPosition(x: Int, y: Int) {
        if (!ansiReady) return
        esc("${y + 1};${x + 1}H")
    }

    override fun flush() {
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
        if (useWin32Input) {
            val winEvent = WindowsConsole.pollConsoleEvent()
            if (winEvent != null) {
                return when (winEvent) {
                    is WindowsConsole.KeyEvent -> UIEvent(
                        kind = "key_down",
                        key = winEvent.key,
                        ctrl = winEvent.ctrl,
                        alt = winEvent.alt,
                        shift = winEvent.shift
                    )
                    is WindowsConsole.MouseEvent -> UIEvent(
                        kind = winEvent.kind,
                        x = winEvent.x,
                        y = winEvent.y,
                        button = winEvent.button,
                        scrollDelta = winEvent.scrollDelta,
                        ctrl = winEvent.ctrl,
                        alt = winEvent.alt,
                        shift = winEvent.shift
                    )
                    is WindowsConsole.ResizeEvent -> {
                        currentRows = winEvent.rows
                        currentCols = winEvent.cols
                        UIEvent("resize", cols = currentCols, rows = currentRows)
                    }
                }
            }
            return null
        }
        if (inputBuffer.available() <= 0) return null
        val b = inputBuffer.readNonBlocking() ?: return null
        if (b < 0) return null

        return parseAnsiInput(b)
    }

    private fun parseAnsiInput(firstByte: Int): UIEvent? {
        // Handle ESC sequences
        if (firstByte == 0x1b) {
            val next = inputBuffer.readBlocking() ?: return UIEvent(kind = "key_down", key = "Escape")
            if (next == '['.code) {
                return parseCsi()
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

    private fun parseCsi(): UIEvent? {
        val seq = StringBuilder()
        while (true) {
            val next = inputBuffer.readBlocking(2) ?: break
            val c = next.toChar()
            seq.append(c)
            if ((c in 'A'..'Z') || (c in 'a'..'z')) break
        }
        val s = seq.toString()
        val finalChar = s.lastOrNull() ?: return null
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
                val btnCode = parts[0].drop(1).toIntOrNull() ?: return null
                val x = parts[1].toIntOrNull()?.minus(1) ?: return null
                val y = parts[2].toIntOrNull()?.minus(1) ?: return null
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
                        )
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
                return UIEvent(kind, x = x, y = y, button = button, ctrl = ctrl, alt = alt, shift = shift)
            }
        }

        // Keys / navigation with optional modifiers (CSI 1;5A, etc.)
        val mods = if (params.size >= 2) {
            decodeMods(params.last().toIntOrNull() ?: 1)
        } else Mods(false, false, false, false)

        return when (finalChar) {
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
    }

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
