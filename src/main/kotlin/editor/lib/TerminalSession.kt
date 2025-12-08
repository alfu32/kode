package editor.lib

import com.pty4j.PtyProcess
import com.pty4j.WinSize
import java.io.File
import java.util.concurrent.Executors

class TerminalSession(
    private val workingDir: File = File(".")
) : AutoCloseable {
    private val emulator = TerminalEmulator(80, 24)
    private val process: PtyProcess
    private val readerExecutor = Executors.newSingleThreadExecutor { r -> Thread(r, "terminal-pty-reader").apply { isDaemon = true } }

    init {
        val shell = defaultShell()
        val env = mutableMapOf<String, String>().apply {
            putAll(System.getenv())
            this["TERM"] = "xterm-256color"
        }
        process = PtyProcess.exec(arrayOf(shell), env, workingDir.absolutePath)
        readerExecutor.submit {
            process.inputStream.buffered().use { input ->
                val buf = ByteArray(8192)
                while (true) {
                    val read = input.read(buf)
                    if (read <= 0) break
                    emulator.appendBytes(buf.copyOf(read))
                }
            }
        }
    }

    fun resize(cols: Int, rows: Int) {
        emulator.resize(cols, rows)
        runCatching { process.winSize = WinSize(cols, rows) }
    }

    fun handleKey(eventKey: String, ctrl: Boolean, alt: Boolean, shift: Boolean) {
        val seq = when (eventKey.lowercase()) {
            "enter" -> "\r"
            "tab" -> "\t"
            "backspace" -> "\u0008"
            "escape" -> "\u001b"
            "up" -> "\u001b[A"
            "down" -> "\u001b[B"
            "right" -> "\u001b[C"
            "left" -> "\u001b[D"
            "home" -> "\u001b[H"
            "end" -> "\u001b[F"
            "pageup" -> "\u001b[5~"
            "pagedown" -> "\u001b[6~"
            else -> {
                val ch = eventKey.firstOrNull() ?: return
                if (ctrl && ch.uppercaseChar() in 'A'..'Z') {
                    (ch.uppercaseChar() - 'A' + 1).toChar().toString()
                } else {
                    val base = if (shift) ch.uppercaseChar() else ch
                    if (alt) "\u001b$base" else "$base"
                }
            }
        }
        write(seq.toByteArray())
    }

    private fun write(data: ByteArray) {
        runCatching {
            process.outputStream.write(data)
            process.outputStream.flush()
        }
    }

    fun snapshot(maxRows: Int) = emulator.snapshotLines(maxRows)

    private fun defaultShell(): String {
        val os = System.getProperty("os.name").lowercase()
        return when {
            os.contains("win") -> System.getenv("COMSPEC") ?: "cmd"
            else -> System.getenv("SHELL") ?: "/bin/sh"
        }
    }

    override fun close() {
        emulator.close()
        runCatching { process.destroy() }
        readerExecutor.shutdownNow()
    }
}
