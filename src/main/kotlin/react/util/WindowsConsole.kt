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
    private const val ENABLE_ECHO_INPUT = 0x0004
    private const val ENABLE_LINE_INPUT = 0x0002
    private const val ENABLE_PROCESSED_INPUT = 0x0001

    private val isWindows: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    private val kernel32: Kernel32? = if (isWindows) {
        runCatching { Native.load("kernel32", Kernel32::class.java) as Kernel32 }.getOrNull()
    } else null
    @Volatile private var savedInputMode: Int? = null

    fun isWindows(): Boolean = isWindows

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
        mode = mode and (ENABLE_ECHO_INPUT or ENABLE_LINE_INPUT or ENABLE_PROCESSED_INPUT).inv()
        return k32.SetConsoleMode(handle, mode)
    }

    fun restoreInputMode() {
        if (!isWindows) return
        val k32 = kernel32 ?: return
        val saved = savedInputMode ?: return
        val handle = k32.GetStdHandle(STD_INPUT_HANDLE) ?: return
        k32.SetConsoleMode(handle, saved)
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

    private interface Kernel32 : Library {
        fun GetStdHandle(nStdHandle: Int): Pointer?
        fun GetConsoleMode(hConsoleHandle: Pointer?, lpMode: IntByReference): Boolean
        fun SetConsoleMode(hConsoleHandle: Pointer?, dwMode: Int): Boolean
        fun GetConsoleScreenBufferInfo(hConsoleHandle: Pointer?, lpConsoleScreenBufferInfo: CONSOLE_SCREEN_BUFFER_INFO): Boolean
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
}
