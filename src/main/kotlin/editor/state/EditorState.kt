package editor.state

import editor.state.buffer.TextBuffer

/**
 * Editor state holds buffer, viewport, cursor, and UI mode flags.
 */
data class EditorState(
    val buffer: TextBuffer,
    val cursor: Cursor = Cursor(0, 0),
    val viewport: Viewport = Viewport(0, 0),
    val mode: Mode = Mode.INSERT,
    val filePath: String? = null,
    val dirty: Boolean = false,
    val gitBranch: String? = null,
    val gitStatus: String? = null
) {
    fun visibleLines(height: Int): List<String> {
        val lines = mutableListOf<String>()
        val start = viewport.top
        val end = (viewport.top + height).coerceAtMost(buffer.lineCount())
        for (i in start until end) {
            lines.add(buffer.line(i))
        }
        return lines
    }
}

data class Cursor(val line: Int, val col: Int)

data class Viewport(val top: Int, val left: Int)

enum class Mode { INSERT, NORMAL, VISUAL }
