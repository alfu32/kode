package editor.lib

import java.nio.charset.Charset
import kotlin.math.max
import kotlin.math.min

/**
 * Extremely small VT-style emulator good enough for ANSI TUIs:
 * - Printable chars, CR/LF/BS
 * - CSI cursor moves (A/B/C/D), abs position (H/f), erase screen (J=2),
 *   erase line (K), column 1 (G), home (H), ignore colors (m).
 */
class TerminalEmulator(
    private var cols: Int,
    private var rows: Int,
    private val charset: Charset = Charsets.UTF_8
): AutoCloseable {
    private var buffer: Array<CharArray> = Array(rows) { CharArray(cols) { ' ' } }
    private var cursorX = 0
    private var cursorY = 0
    private val lock = Any()
    private var closed = false
    private var pendingEscape: StringBuilder? = null

    fun resize(newCols: Int, newRows: Int) {
        synchronized(lock) {
            if (closed) return
            if (newCols == cols && newRows == rows) return
            val newBuf = Array(newRows) { CharArray(newCols) { ' ' } }
            val copyRows = min(rows, newRows)
            val copyCols = min(cols, newCols)
            for (y in 0 until copyRows) {
                System.arraycopy(buffer[y], 0, newBuf[y], 0, copyCols)
            }
            buffer = newBuf
            cols = newCols
            rows = newRows
            cursorX = cursorX.coerceIn(0, max(0, cols - 1))
            cursorY = cursorY.coerceIn(0, max(0, rows - 1))
        }
    }

    fun appendBytes(bytes: ByteArray) {
        if (bytes.isEmpty()) return
        appendText(bytes.toString(charset))
    }

    fun appendText(text: String) {
        synchronized(lock) {
            if (closed) return
            val combined = pendingEscape?.append(text)?.toString() ?: text
            pendingEscape = null
            var idx = 0
            while (idx < combined.length) {
                val ch = combined[idx]
                if (ch == '\u001b') {
                    val (consumedTo, truncated) = parseEscape(combined, idx + 1)
                    if (truncated) {
                        pendingEscape = StringBuilder().append(combined.substring(idx))
                        break
                    } else {
                        idx = consumedTo
                        idx++
                        continue
                    }
                }
                when (ch) {
                    '\r' -> cursorX = 0
                    '\n' -> {
                        cursorX = 0
                        cursorY++
                        if (cursorY >= rows) scrollUp()
                    }
                    '\b' -> cursorX = max(0, cursorX - 1)
                    '\t' -> repeat(4) { putChar(' ') }
                    else -> {
                        if (ch >= ' ') {
                            putChar(ch)
                        }
                    }
                }
                idx++
            }
        }
    }

    private fun parseEscape(text: String, startIdx: Int): Pair<Int, Boolean> {
        if (startIdx >= text.length) return startIdx to true
        val next = text[startIdx]
        return when (next) {
            '[' -> {
                var idx = startIdx + 1
                val params = StringBuilder()
                while (idx < text.length) {
                    val c = text[idx]
                    if (c in '0'..'9' || c == ';' || c == '?') {
                        params.append(c)
                        idx++
                        continue
                    }
                    handleCsi(params.toString(), c)
                    return idx to false
                }
                text.length to true
            }
            ']' -> { // OSC ... BEL or ST
                var idx = startIdx + 1
                while (idx < text.length) {
                    val c = text[idx]
                    if (c == '\u0007') return idx to false
                    if (c == '\u001b' && idx + 1 < text.length && text[idx + 1] == '\\') {
                        return (idx + 1) to false
                    }
                    idx++
                }
                text.length to true
            }
            'c' -> { // RIS - reset
                clear(); startIdx to false
            }
            '(', ')', '*', '+' -> {
                // Charset designators: skip next char
                (startIdx + 1).coerceAtMost(text.length) to false
            }
            '7', '8', '=' -> (startIdx + 1) to false
            else -> (startIdx + 1).coerceAtMost(text.length) to false
        }
    }

    private fun handleCsi(params: String, final: Char) {
        val parts = params.split(';').filter { it.isNotEmpty() }
        fun p(index: Int, default: Int) = parts.getOrNull(index)?.toIntOrNull() ?: default
        when (final) {
            'H', 'f' -> { // cursor position (1-based)
                val row = p(0, 1) - 1
                val col = p(1, 1) - 1
                cursorY = row.coerceIn(0, rows - 1)
                cursorX = col.coerceIn(0, cols - 1)
            }
            'A' -> cursorY = max(0, cursorY - p(0, 1))
            'B' -> cursorY = min(rows - 1, cursorY + p(0, 1))
            'C' -> cursorX = min(cols - 1, cursorX + p(0, 1))
            'D' -> cursorX = max(0, cursorX - p(0, 1))
            'G' -> cursorX = (p(0, 1) - 1).coerceIn(0, cols - 1)
            'J' -> {
                if (p(0, 0) == 2) clear()
            }
            'K' -> clearLine(cursorY)
            'm' -> { /* ignore styling */ }
        }
    }

    private fun clear() {
        for (y in 0 until rows) buffer[y].fill(' ')
        cursorX = 0
        cursorY = 0
    }

    private fun clearLine(row: Int) {
        if (row !in 0 until rows) return
        buffer[row].fill(' ')
    }

    private fun scrollUp() {
        for (y in 1 until rows) {
            buffer[y - 1] = buffer[y]
        }
        buffer[rows - 1] = CharArray(cols) { ' ' }
        cursorY = rows - 1
        cursorX = cursorX.coerceIn(0, cols - 1)
    }

    private fun putChar(ch: Char) {
        if (cursorY !in 0 until rows || cursorX !in 0 until cols) return
        buffer[cursorY][cursorX] = ch
        cursorX++
        if (cursorX >= cols) {
            cursorX = 0
            cursorY++
            if (cursorY >= rows) scrollUp()
        }
    }

    fun snapshotLines(maxRows: Int): Pair<List<String>, Pair<Int, Int>> {
        synchronized(lock) {
            if (closed) return emptyList<String>() to (0 to 0)
            val rowsToTake = min(rows, maxRows)
            val lines = (0 until rowsToTake).map { y ->
                buffer[y].concatToString()
            }
            val cx = cursorX.coerceIn(0, cols - 1)
            val cy = cursorY.coerceIn(0, rows - 1)
            return lines to (cx to cy)
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
        }
    }
}
