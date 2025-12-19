package react.util

import react.util.WindowsConsole

fun runCommand(vararg cmd: String): String? = try {
    ProcessBuilder(*cmd)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().use { it.readText() }
} catch (_: Exception) { null }



fun enterRawMode(): String? {
    if (WindowsConsole.isWindows()) {
        WindowsConsole.enableVirtualTerminalInput()
        return null
    }
    val state = runCommand("sh", "-c", "stty -g < /dev/tty")?.trim()
    runCommand("sh", "-c", "stty raw -echo < /dev/tty")
    return state
}

fun restoreStty(state: String?) {
    if (WindowsConsole.isWindows()) {
        WindowsConsole.restoreInputMode()
        return
    }
    val cmd = if (state != null) {
        "stty $state < /dev/tty"
    } else {
        // If we failed to capture the previous state, at least return to a sane, echoed mode.
        "stty sane -echo echo icanon isig < /dev/tty"
    }
    runCommand("sh", "-c", cmd)
}
