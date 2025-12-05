package editor.terminal

import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Enter raw mode using stty; returns the previous stty settings for restoration.
 */
fun enterRawMode(): String? {
    val saved = runShell("stty -g < /dev/tty")?.trim().takeUnless { it.isNullOrBlank() }
    runShell("stty raw -echo -icanon min 1 time 0 < /dev/tty")
    return saved
}

fun restoreStty(saved: String?) {
    if (saved.isNullOrBlank()) return
    runShell("stty $saved < /dev/tty")
}

private fun runShell(command: String): String? {
    return try {
        val proc = ProcessBuilder("sh", "-c", command)
            .redirectErrorStream(true)
            .start()
        proc.inputStream.bufferedReader().use(BufferedReader::readText).also {
            proc.waitFor()
        }
    } catch (_: Exception) {
        null
    }
}
