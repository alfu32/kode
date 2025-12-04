package editor.state.buffer

/**
 * Minimal gap-buffer-like wrapper for line access; not optimized yet.
 */
class TextBuffer private constructor(private val lines: MutableList<String>) {

    fun lineCount(): Int = lines.size

    fun line(index: Int): String = lines.getOrElse(index) { "" }

    fun insertLine(index: Int, content: String) {
        if (index >= lines.size) {
            lines.add(content)
        } else {
            lines.add(index, content)
        }
    }

    fun updateLine(index: Int, content: String) {
        if (index in lines.indices) {
            lines[index] = content
        }
    }

    companion object {
        fun fromString(text: String): TextBuffer =
            TextBuffer(text.split("\n").toMutableList().ifEmpty { mutableListOf("") })
    }
}
