package editor.state

import editor.state.buffer.TextBuffer
import org.github.alfu32.ktx.FileTreeEntry
import org.github.alfu32.ktx.IFileTree

/**
 * Editor state holds buffer, viewport, cursor, and UI mode flags.
 */
data class EditorState(
    val buffer: TextBuffer,
    val cursor: Cursor = Cursor(0, 0),
    val viewport: Viewport = Viewport(0, 0),
    val mode: Mode = Mode.INSERT,
    val focus: Focus = Focus.RIGHT,
    val leftTab: LeftTab = LeftTab.PROJECT,
    val filePath: String? = null,
    val dirty: Boolean = false,
    val gitBranch: String? = null,
    val gitStatus: String? = null,
    val fileTree: IFileTree? = null,
    val fileTreeSelection: Int = 0
) {
    fun visibleLines(height: Int, width: Int): List<String> {
        val lines = mutableListOf<String>()
        val start = viewport.top
        val end = (viewport.top + height).coerceAtMost(buffer.lineCount())
        for (i in start until end) {
            val raw = buffer.line(i)
            lines.add(raw.drop(viewport.left).take(width))
        }
        return lines
    }

    fun moveCursor(dLine: Int, dCol: Int, viewportHeight: Int, viewportWidth: Int): EditorState {
        val maxLineIndex = (buffer.lineCount() - 1).coerceAtLeast(0)
        val newLine = (cursor.line + dLine).coerceIn(0, maxLineIndex)
        val lineText = buffer.line(newLine)
        val newCol = (cursor.col + dCol).coerceIn(0, lineText.length.coerceAtLeast(0))

        var newTop = viewport.top
        if (newLine < newTop) newTop = newLine
        if (newLine >= newTop + viewportHeight) newTop = (newLine - viewportHeight + 1).coerceAtLeast(0)

        var newLeft = viewport.left
        if (newCol < newLeft) newLeft = newCol
        if (newCol >= newLeft + viewportWidth) newLeft = (newCol - viewportWidth + 1).coerceAtLeast(0)

        return copy(cursor = Cursor(newLine, newCol), viewport = Viewport(newTop, newLeft))
    }

    fun toggleFocus(): EditorState = copy(focus = if (focus == Focus.RIGHT) Focus.LEFT else Focus.RIGHT)

    fun setLeftTab(tab: LeftTab): EditorState = copy(leftTab = tab, fileTreeSelection = 0)

    fun moveFileSelection(delta: Int): EditorState {
        if (leftTab != LeftTab.PROJECT) return this
        val entries = fileTree?.flattened().orEmpty()
        if (entries.isEmpty()) return this
        val newIndex = (fileTreeSelection + delta).coerceIn(0, entries.lastIndex)
        return copy(fileTreeSelection = newIndex)
    }

    fun toggleSelectedNode(): EditorState {
        if (leftTab != LeftTab.PROJECT) return this
        val entries = fileTree?.flattened().orEmpty()
        if (entries.isEmpty()) return this
        val entry = entries.getOrNull(fileTreeSelection) ?: return this
        if (entry.typ == "folder") {
            fileTree?.toggle(entry.fullPath)
        }
        return this
    }

    fun fileTreeEntries(): List<FileTreeEntry> = if (leftTab == LeftTab.PROJECT) fileTree?.flattened().orEmpty() else emptyList()

    fun setCursorPosition(line: Int, col: Int, viewportHeight: Int, viewportWidth: Int): EditorState {
        val maxLineIndex = (buffer.lineCount() - 1).coerceAtLeast(0)
        val newLine = line.coerceIn(0, maxLineIndex)
        val lineText = buffer.line(newLine)
        val newCol = col.coerceIn(0, lineText.length.coerceAtLeast(0))

        var newTop = viewport.top
        if (newLine < newTop) newTop = newLine
        if (newLine >= newTop + viewportHeight) newTop = (newLine - viewportHeight + 1).coerceAtLeast(0)

        var newLeft = viewport.left
        if (newCol < newLeft) newLeft = newCol
        if (newCol >= newLeft + viewportWidth) newLeft = (newCol - viewportWidth + 1).coerceAtLeast(0)

        return copy(cursor = Cursor(newLine, newCol), viewport = Viewport(newTop, newLeft))
    }
}

data class Cursor(val line: Int, val col: Int)

data class Viewport(val top: Int, val left: Int)

enum class Mode { INSERT, NORMAL, VISUAL }
enum class Focus { LEFT, RIGHT }
enum class LeftTab { PROJECT, GIT, SETTINGS }
