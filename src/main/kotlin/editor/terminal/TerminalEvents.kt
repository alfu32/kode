package editor.terminal

/**
 * Normalized terminal events for keyboard, mouse, and resize.
 */
sealed class TerminalEvent {
    data class Key(
        val key: String,
        val alt: Boolean = false,
        val ctrl: Boolean = false,
        val shift: Boolean = false
    ) : TerminalEvent()

    data class Mouse(
        val x: Int,
        val y: Int,
        val button: Int,
        val kind: Kind
    ) : TerminalEvent() {
        enum class Kind { Down, Up, Drag, ScrollUp, ScrollDown }
    }

    data class Resize(val cols: Int, val rows: Int) : TerminalEvent()
}

/**
 * Terminal input source abstraction.
 */
interface TerminalInput {
    fun poll(): TerminalEvent
    fun tryPoll(): TerminalEvent?
    fun currentSize(): Pair<Int, Int>
}
