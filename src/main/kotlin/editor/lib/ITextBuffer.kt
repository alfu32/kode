package editor.lib

import react.UIEvent

/*
   ===============================================================
     TEXT BUFFER INTERFACE
   ===============================================================
     Describes the required API for a generic line-based text buffer
     abstraction, independent of UI toolkit or rendering layer.
   ===============================================================
   */
interface ITextBuffer {

    fun text(): String
    fun clone(): ITextBuffer

    fun cursorPosition(): Position
    fun selectionText(): String
    fun totalLines(): Int
    fun bom(): String
    fun encoding(): String
    fun isDirty(): Boolean
    fun version(): Long

    fun loadText(text: String)
    fun undo(): Boolean
    fun redo(): Boolean
    fun saveToFile(path: String): Boolean

    fun moveCursorTo(position: Position, expand: Boolean)
    fun moveLeft(expand: Boolean = false, word: Boolean = false)
    fun moveRight(expand: Boolean = false, word: Boolean = false)
    fun moveUp(expand: Boolean = false)
    fun moveDown(expand: Boolean = false)
    fun moveStartOfLine(expand: Boolean = false)
    fun moveEndOfLine(expand: Boolean = false)
    fun selectAll()

    fun insertText(text: String)
    fun insertNewline()

    fun deleteBackspace()
    fun deleteForward()

    fun copySelection(): Boolean
    fun cutSelection(): Boolean
    fun pasteClipboard()

    fun consumeNotifications(): List<Notification>

    fun startSelection(pos: Position)
    fun selectTo(pos: Position)
    fun hasSelection(): Boolean
    fun selectionRange(): SelectionRange?
    fun clearSelection()

    fun updateSearch(query: String, replacement: String? = null)
    fun searchState(): SearchState
    fun foundTokens(): List<FoundToken>
    fun findNext(): SelectionRange?
    fun findAll(): List<FoundToken>
    fun replaceCurrent(): Boolean
    fun replaceAll(): Int

    fun viewportSlice(view: EditorViewport, gutterWidth: Int): ViewportSlice
}

/*
===============================================================
  DATA TYPES
===============================================================
*/
data class Position(var line: Int = 0, var column: Int = 0)

data class SelectionRange(val start: Position, val end: Position)

data class EditorViewport(val x: Int, val y: Int, val width: Int, val height: Int)

data class ViewSegment(val text: String, val selected: Boolean)

data class ViewLine(val lineIndex: Int, val gutter: String, val segments: List<ViewSegment>)

data class CursorView(val line: Int, val column: Int, val char: String)

data class ViewportSlice(val lines: List<ViewLine>, val totalLines: Int, val cursor: CursorView?)
data class EditorState(
    val filePath: String,
    val language: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val selection: String,
    val totalLines: Int,
    val bom: String,
    val encoding: String
)

data class SearchState(
    val query: String,
    val replacement: String,
    val matchCount: Int,
    val activeIndex: Int,
    val patternError: String?
)

data class FoundToken(
    val line: Int,
    val startColumn: Int,
    val endColumn: Int,
    val active: Boolean
)

fun handleMouseToBuffer(
    buffer: ITextBuffer,
    ev: UIEvent,
    singleLine: Boolean = false,
    scrollOffset: Int = 0,
    startSelection: Boolean = false,
    extendSelection: Boolean = false
) {
    val line = (ev.relY ?: 0).coerceAtLeast(0) + scrollOffset
    val col = ((ev.relX ?: 0) - 1).coerceAtLeast(0)
    val targetLine = if (singleLine) 0 else line
    val pos = Position(targetLine, col)
    if (startSelection) {
        buffer.startSelection(pos)
    } else if (extendSelection) {
        buffer.selectTo(pos)
    }
    buffer.moveCursorTo(pos, expand = extendSelection)
}

/* =====================================================================
   END OF FILE
   ===================================================================== */
fun handleKeyForBuffer(buffer: ITextBuffer, ev: UIEvent, singleLine: Boolean = false): Boolean {
    val key = ev.key ?: return false
    val ctrl = ev.ctrl
    val shift = ev.shift
    val before = buffer.text()

    when (key) {
        "Backspace" -> {
            buffer.deleteBackspace()
        }
        "Delete" -> {
            buffer.deleteForward()
        }
        "Enter" -> if (!singleLine) buffer.insertNewline()
        "Left" -> buffer.moveLeft(expand = shift, word = ctrl)
        "Right" -> buffer.moveRight(expand = shift, word = ctrl)
        "Up" -> buffer.moveUp(expand = shift)
        "Down" -> buffer.moveDown(expand = shift)
        "Home" -> buffer.moveStartOfLine(expand = shift)
        "End" -> buffer.moveEndOfLine(expand = shift)
        else -> {
            if (ev.alt) {
                when (key.lowercase()) {
                    "c" -> buffer.copySelection()
                    "x" -> if (buffer.cutSelection()) {}
                    "v" -> buffer.pasteClipboard()
                    "u" -> buffer.undo()
                    "r" -> buffer.redo()
                }
            } else if (ctrl) {
                when (key.lowercase()) {
                    "a" -> buffer.selectAll()
                    "s" -> {} // placeholder for save hook
                }
            } else if (!ev.alt && key.length == 1) {
                buffer.insertText(key)
            }
        }
    }
    return buffer.text() != before
}

fun renderBuffer(buffer: ITextBuffer, width: Int, height: Int, startLine: Int = 0): String {
    val slice = buffer.viewportSlice(EditorViewport(0, startLine, width, height), gutterWidth = 0)
    return slice.lines.joinToString("\n") { line ->
        // Represent selection by inverted markup markers; rendering engine ignores them,
        // but we can use placeholders to hint selection (e.g., wrap in special chars).
        buildString {
            for (seg in line.segments) {
                if (seg.selected) {
                    append('\u001b').append("[7m") // inverse on
                    append(seg.text.ifEmpty { " " })
                    append('\u001b').append("[0m") // reset
                } else {
                    append(seg.text)
                }
            }
        }
    }
}
