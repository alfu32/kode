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

    private val isWindows: Boolean =
        System.getProperty("os.name")?.startsWith("Windows", ignoreCase = true) == true

    private val kernel32: Kernel32? = if (isWindows) {
        runCatching { Native.load("kernel32", Kernel32::class.java) as Kernel32 }.getOrNull()
    } else null

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

    private interface Kernel32 : Library {
        fun GetStdHandle(nStdHandle: Int): Pointer?
        fun GetConsoleMode(hConsoleHandle: Pointer?, lpMode: IntByReference): Boolean
        fun SetConsoleMode(hConsoleHandle: Pointer?, dwMode: Int): Boolean
    }
}
