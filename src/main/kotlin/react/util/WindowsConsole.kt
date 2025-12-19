package react.util

import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.IntByReference

/**
 * Minimal helper to enable ANSI/VT output on Windows consoles (ConHost).
 * No-op on non-Windows platforms.
 */
object WindowsConsole {
    private const val ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004
    private const val DISABLE_NEWLINE_AUTO_RETURN = 0x0008
    private const val STD_OUTPUT_HANDLE = -11
    private const val STD_INPUT_HANDLE = -10
    private const val ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200
    private const val ENABLE_MOUSE_INPUT = 0x0010
    private const val ENABLE_EXTENDED_FLAGS = 0x0080
    private const val ENABLE_QUICK_EDIT_MODE = 0x0040
    private const val ENABLE_ECHO_INPUT = 0x0004
    private const val ENABLE_LINE_INPUT = 0x0002
    private const val KEY_EVENT = 0x0001
    private const val MOUSE_EVENT = 0x0002
    private const val WINDOW_BUFFER_SIZE_EVENT = 0x0004
    private const val MOUSE_MOVED = 0x0001
    private const val MOUSE_WHEELED = 0x0004
    private const val FROM_LEFT_1ST_BUTTON_PRESSED = 0x0001
    private const val RIGHTMOST_BUTTON_PRESSED = 0x0002
    private const val FROM_LEFT_2ND_BUTTON_PRESSED = 0x0004
    private const val SHIFT_PRESSED = 0x0010
    private const val LEFT_ALT_PRESSED = 0x0002
    private const val RIGHT_ALT_PRESSED = 0x0001
    private const val LEFT_CTRL_PRESSED = 0x0008
    private const val RIGHT_CTRL_PRESSED = 0x0004

    private val isWindows: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    private val kernel32: Kernel32? = if (isWindows) {
        runCatching { Native.load("kernel32", Kernel32::class.java) as Kernel32 }.getOrNull()
    } else null
    @Volatile private var savedInputMode: Int? = null
    @Volatile private var vtInputEnabled: Boolean? = null
    @Volatile private var lastButtonState: Int = 0

    fun isWindows(): Boolean = isWindows
    fun isVtInputEnabled(): Boolean? = vtInputEnabled

    /**
     * Try to enable virtual terminal processing for stdout. Returns true on success.
     */
    fun enableVirtualTerminalProcessing(): Boolean {
        if (!isWindows) return true
        val k32 = kernel32 ?: return false
        val handle = k32.GetStdHandle(STD_OUTPUT_HANDLE) ?: return false
        val modeRef = IntByReference()
        if (!k32.GetConsoleMode(handle, modeRef)) return false
        val newMode = modeRef.value or ENABLE_VIRTUAL_TERMINAL_PROCESSING or DISABLE_NEWLINE_AUTO_RETURN
        if (!k32.SetConsoleMode(handle, newMode)) return false
        return true
    }

    /**
     * Try to enable virtual terminal input and raw-ish mode for stdin. Returns true on success.
     */
    fun enableVirtualTerminalInput(): Boolean {
        if (!isWindows) return true
        val k32 = kernel32 ?: return false
        val handle = k32.GetStdHandle(STD_INPUT_HANDLE) ?: return false
        val modeRef = IntByReference()
        if (!k32.GetConsoleMode(handle, modeRef)) return false
        if (savedInputMode == null) {
            savedInputMode = modeRef.value
        }
        var mode = modeRef.value
        mode = mode or ENABLE_VIRTUAL_TERMINAL_INPUT or ENABLE_EXTENDED_FLAGS or ENABLE_MOUSE_INPUT
        mode = mode and (ENABLE_ECHO_INPUT or ENABLE_LINE_INPUT).inv()
        mode = mode and ENABLE_QUICK_EDIT_MODE.inv()
        val ok = k32.SetConsoleMode(handle, mode)
        vtInputEnabled = ok
        return ok
    }

    fun restoreInputMode() {
        if (!isWindows) return
        val k32 = kernel32 ?: return
        val saved = savedInputMode ?: return
        val handle = k32.GetStdHandle(STD_INPUT_HANDLE) ?: return
        k32.SetConsoleMode(handle, saved)
    }

    fun canUseWin32Input(): Boolean {
        if (!isWindows) return false
        val k32 = kernel32 ?: return false
        val handle = k32.GetStdHandle(STD_INPUT_HANDLE) ?: return false
        val modeRef = IntByReference()
        return k32.GetConsoleMode(handle, modeRef)
    }

    fun getConsoleSize(): Pair<Int, Int>? {
        if (!isWindows) return null
        val k32 = kernel32 ?: return null
        val handle = k32.GetStdHandle(STD_OUTPUT_HANDLE) ?: return null
        val info = CONSOLE_SCREEN_BUFFER_INFO()
        if (!k32.GetConsoleScreenBufferInfo(handle, info)) return null
        val cols = info.srWindow.Right - info.srWindow.Left + 1
        val rows = info.srWindow.Bottom - info.srWindow.Top + 1
        return rows.toInt() to cols.toInt()
    }

    fun pollConsoleEvent(): ConsoleEvent? {
        if (!isWindows) return null
        val k32 = kernel32 ?: return null
        val handle = k32.GetStdHandle(STD_INPUT_HANDLE) ?: return null
        val countRef = IntByReference()
        if (!k32.GetNumberOfConsoleInputEvents(handle, countRef)) return null
        if (countRef.value <= 0) return null
        val record = INPUT_RECORD()
        val readRef = IntByReference()
        if (!k32.ReadConsoleInputW(handle, record, 1, readRef)) return null
        if (readRef.value <= 0) return null
        record.read()
        return when (record.EventType.toInt()) {
            KEY_EVENT -> {
                record.Event.setType(KEY_EVENT_RECORD::class.java)
                record.Event.read()
                val keyEvent = record.Event.KeyEvent
                if (!keyEvent.bKeyDown) return null
                val control = keyEvent.dwControlKeyState
                val shift = (control and SHIFT_PRESSED) != 0
                val alt = (control and (LEFT_ALT_PRESSED or RIGHT_ALT_PRESSED)) != 0
                val ctrl = (control and (LEFT_CTRL_PRESSED or RIGHT_CTRL_PRESSED)) != 0
                val key = decodeKey(keyEvent) ?: return null
                KeyEvent(key = key, shift = shift, alt = alt, ctrl = ctrl)
            }
            MOUSE_EVENT -> {
                record.Event.setType(MOUSE_EVENT_RECORD::class.java)
                record.Event.read()
                decodeMouse(record.Event.MouseEvent)
            }
            WINDOW_BUFFER_SIZE_EVENT -> {
                record.Event.setType(WINDOW_BUFFER_SIZE_RECORD::class.java)
                record.Event.read()
                val cols = record.Event.WindowBufferSizeEvent.dwSize.X.toInt()
                val rows = record.Event.WindowBufferSizeEvent.dwSize.Y.toInt()
                ResizeEvent(cols = cols, rows = rows)
            }
            else -> null
        }
    }

    private fun decodeKey(keyEvent: KEY_EVENT_RECORD): String? {
        val ch = keyEvent.uChar
        if (ch.code != 0) {
            return ch.toString()
        }
        val vk = keyEvent.wVirtualKeyCode.toInt()
        return when (vk) {
            0x08 -> "Backspace"
            0x09 -> "Tab"
            0x0D -> "Enter"
            0x1B -> "Escape"
            0x21 -> "PageUp"
            0x22 -> "PageDown"
            0x23 -> "End"
            0x24 -> "Home"
            0x25 -> "Left"
            0x26 -> "Up"
            0x27 -> "Right"
            0x28 -> "Down"
            0x2E -> "Delete"
            else -> when (vk) {
                in 0x30..0x39 -> (vk - 0x30).toString()
                in 0x41..0x5A -> (vk.toChar().toString())
                else -> null
            }
        }
    }

    private fun decodeMouse(mouse: MOUSE_EVENT_RECORD): ConsoleEvent? {
        val x = mouse.dwMousePosition.X.toInt()
        val y = mouse.dwMousePosition.Y.toInt()
        val control = mouse.dwControlKeyState
        val shift = (control and SHIFT_PRESSED) != 0
        val alt = (control and (LEFT_ALT_PRESSED or RIGHT_ALT_PRESSED)) != 0
        val ctrl = (control and (LEFT_CTRL_PRESSED or RIGHT_CTRL_PRESSED)) != 0
        val flags = mouse.dwEventFlags
        val buttonState = mouse.dwButtonState

        if ((flags and MOUSE_WHEELED) != 0) {
            val delta = (buttonState shr 16).toShort().toInt()
            val step = if (delta > 0) 1 else if (delta < 0) -1 else 0
            if (step != 0) {
                return MouseEvent(
                    kind = "mouse_scroll",
                    x = x,
                    y = y,
                    scrollDelta = step,
                    ctrl = ctrl,
                    alt = alt,
                    shift = shift
                )
            }
        }

        if ((flags and MOUSE_MOVED) != 0) {
            return MouseEvent(kind = "mouse_move", x = x, y = y, ctrl = ctrl, alt = alt, shift = shift)
        }

        if (flags == 0) {
            val changed = lastButtonState xor buttonState
            if (changed != 0) {
                val (mask, button) = when {
                    (changed and FROM_LEFT_1ST_BUTTON_PRESSED) != 0 -> FROM_LEFT_1ST_BUTTON_PRESSED to 0
                    (changed and RIGHTMOST_BUTTON_PRESSED) != 0 -> RIGHTMOST_BUTTON_PRESSED to 1
                    (changed and FROM_LEFT_2ND_BUTTON_PRESSED) != 0 -> FROM_LEFT_2ND_BUTTON_PRESSED to 2
                    else -> 0 to null
                }
                if (mask != 0 && button != null) {
                    val down = (buttonState and mask) != 0
                    lastButtonState = buttonState
                    return MouseEvent(
                        kind = if (down) "mouse_down" else "mouse_up",
                        x = x,
                        y = y,
                        button = button,
                        ctrl = ctrl,
                        alt = alt,
                        shift = shift
                    )
                }
            }
            lastButtonState = buttonState
        }
        return null
    }

    private interface Kernel32 : Library {
        fun GetStdHandle(nStdHandle: Int): Pointer?
        fun GetConsoleMode(hConsoleHandle: Pointer?, lpMode: IntByReference): Boolean
        fun SetConsoleMode(hConsoleHandle: Pointer?, dwMode: Int): Boolean
        fun GetConsoleScreenBufferInfo(hConsoleHandle: Pointer?, lpConsoleScreenBufferInfo: CONSOLE_SCREEN_BUFFER_INFO): Boolean
        fun GetNumberOfConsoleInputEvents(hConsoleHandle: Pointer?, lpcNumberOfEvents: IntByReference): Boolean
        fun ReadConsoleInputW(
            hConsoleInput: Pointer?,
            lpBuffer: INPUT_RECORD,
            nLength: Int,
            lpNumberOfEventsRead: IntByReference
        ): Boolean
    }

    @Suppress("unused")
    class COORD : com.sun.jna.Structure() {
        @JvmField var X: Short = 0
        @JvmField var Y: Short = 0
        override fun getFieldOrder(): List<String> = listOf("X", "Y")
    }

    @Suppress("unused")
    class SMALL_RECT : com.sun.jna.Structure() {
        @JvmField var Left: Short = 0
        @JvmField var Top: Short = 0
        @JvmField var Right: Short = 0
        @JvmField var Bottom: Short = 0
        override fun getFieldOrder(): List<String> = listOf("Left", "Top", "Right", "Bottom")
    }

    @Suppress("unused")
    class CONSOLE_SCREEN_BUFFER_INFO : com.sun.jna.Structure() {
        @JvmField var dwSize: COORD = COORD()
        @JvmField var dwCursorPosition: COORD = COORD()
        @JvmField var wAttributes: Short = 0
        @JvmField var srWindow: SMALL_RECT = SMALL_RECT()
        @JvmField var dwMaximumWindowSize: COORD = COORD()
        override fun getFieldOrder(): List<String> =
            listOf("dwSize", "dwCursorPosition", "wAttributes", "srWindow", "dwMaximumWindowSize")
    }

    @Suppress("unused")
    class KEY_EVENT_RECORD : com.sun.jna.Structure() {
        @JvmField var bKeyDown: Boolean = false
        @JvmField var wRepeatCount: Short = 0
        @JvmField var wVirtualKeyCode: Short = 0
        @JvmField var wVirtualScanCode: Short = 0
        @JvmField var uChar: Char = 0.toChar()
        @JvmField var dwControlKeyState: Int = 0
        override fun getFieldOrder(): List<String> =
            listOf("bKeyDown", "wRepeatCount", "wVirtualKeyCode", "wVirtualScanCode", "uChar", "dwControlKeyState")
    }

    @Suppress("unused")
    class MOUSE_EVENT_RECORD : com.sun.jna.Structure() {
        @JvmField var dwMousePosition: COORD = COORD()
        @JvmField var dwButtonState: Int = 0
        @JvmField var dwControlKeyState: Int = 0
        @JvmField var dwEventFlags: Int = 0
        override fun getFieldOrder(): List<String> =
            listOf("dwMousePosition", "dwButtonState", "dwControlKeyState", "dwEventFlags")
    }

    @Suppress("unused")
    class WINDOW_BUFFER_SIZE_RECORD : com.sun.jna.Structure() {
        @JvmField var dwSize: COORD = COORD()
        override fun getFieldOrder(): List<String> = listOf("dwSize")
    }

    @Suppress("unused")
    class INPUT_RECORD : com.sun.jna.Structure() {
        @JvmField var EventType: Short = 0
        @JvmField var Event: EventUnion = EventUnion()
        override fun getFieldOrder(): List<String> = listOf("EventType", "Event")
    }

    @Suppress("unused")
    class EventUnion : com.sun.jna.Union() {
        @JvmField var KeyEvent: KEY_EVENT_RECORD = KEY_EVENT_RECORD()
        @JvmField var MouseEvent: MOUSE_EVENT_RECORD = MOUSE_EVENT_RECORD()
        @JvmField var WindowBufferSizeEvent: WINDOW_BUFFER_SIZE_RECORD = WINDOW_BUFFER_SIZE_RECORD()
    }

    sealed interface ConsoleEvent
    data class KeyEvent(
        val key: String,
        val shift: Boolean,
        val alt: Boolean,
        val ctrl: Boolean
    ) : ConsoleEvent
    data class MouseEvent(
        val kind: String,
        val x: Int,
        val y: Int,
        val button: Int? = null,
        val scrollDelta: Int? = null,
        val ctrl: Boolean,
        val alt: Boolean,
        val shift: Boolean
    ) : ConsoleEvent
    data class ResizeEvent(val cols: Int, val rows: Int) : ConsoleEvent
}
