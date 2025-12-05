package editor.lib

import kotlin.text.iterator

/*  
===============================================================
  TEXT BUFFER IMPLEMENTATION
===============================================================
*/
class TextBuffer : ITextBuffer {

    /* -----------------------------------------------------------
       CLASS CONSTANTS
       (TAB_STOP moved here per instructions)
    ----------------------------------------------------------- */
    companion object {
        const val TAB_STOP = 4

        /*  
           Expands tabs into spaces using the defined TAB_STOP width.
        */
        fun expandTabs(line: String): String {
            val builder = StringBuilder(line.length + 8)
            var col = 0
            for (ch in line) {
                if (ch == '\t') {
                    val spaces = TAB_STOP - (col % TAB_STOP)
                    repeat(spaces) { builder.append(' ') }
                    col += spaces
                } else {
                    builder.append(ch)
                    col++
                }
            }
            return builder.toString()
        }

        /*  
           Computes the visual width of a substring up to a given column,
           accounting for tab expansion.
        */
        fun visualColumn(line: String, column: Int): Int {
            var v = 0
            var idx = 0
            for (ch in line) {
                if (idx >= column) break
                if (ch == '\t') {
                    val spaces = TAB_STOP - (v % TAB_STOP)
                    v += spaces
                } else v++
                idx++
            }
            return v
        }

        /*  
           Computes the visual width of an entire line.
        */
        fun visualLength(line: String): Int = visualColumn(line, line.length)

        /*  
           Maps a visual (tab-expanded) column back to the raw character index.
        */
        fun actualColumn(line: String, visual: Int): Int {
            var current = 0
            var idx = 0
            for (ch in line) {
                val w = if (ch == '\t') TAB_STOP - (current % TAB_STOP) else 1
                if (current + w > visual) return idx
                current += w
                idx++
                if (current == visual) return idx
            }
            return idx
        }

        /*  
           Word-character classification.
        */
        fun isWordChar(ch: Char): Boolean =
            (ch in '0'..'9') || (ch in 'a'..'z') || (ch in 'A'..'Z') || ch == '_' || ch == '$'
    }

    /* -----------------------------------------------------------
       INTERNAL STATE
    ----------------------------------------------------------- */
    private var lines: MutableList<String> = mutableListOf("")
    private var cursor = Position()
    private var anchor: Position? = null
    private var clipboard: String = ""
    private val notifications = mutableListOf<Notification>()
    private var bufferBom: String = ""
    private var bufferEncoding: String = "UTF-8"


    /*  
    ===============================================================
      BASIC STATE & TEXT ACCESS
    ===============================================================
    */

    override fun text(): String = lines.joinToString("\n")

    override fun cursorPosition(): Position = Position(cursor.line, cursor.column)

    override fun selectionText(): String {
        val r = selectionRange() ?: return ""
        return extractText(r)
    }

    override fun totalLines(): Int = lines.size

    override fun bom(): String = bufferBom

    override fun encoding(): String = bufferEncoding

    override fun clone(): ITextBuffer {
        val b = TextBuffer()
        b.lines = lines.toMutableList()
        b.cursor = Position(cursor.line, cursor.column)
        b.anchor = anchor?.let { Position(it.line, it.column) }
        b.clipboard = clipboard
        return b
    }

    override fun loadText(text: String) {
        val n = text.replace("\r\n", "\n")
        lines = if (n.isEmpty()) mutableListOf("") else n.split("\n").toMutableList()
        if (n.endsWith("\n")) lines.add("")
        cursor = Position(0, 0)
        anchor = null
    }


    /*  
    ===============================================================
      CURSOR MOVEMENT
    ===============================================================
    */

    private fun clampPosition(pos: Position): Position {
        val line = pos.line.coerceIn(0, lines.size - 1)
        val col = pos.column.coerceIn(0, lines[line].length)
        return Position(line, col)
    }

    override fun moveCursorTo(position: Position, expand: Boolean) {
        val np = clampPosition(position)
        if (!expand) anchor = null
        else if (anchor == null) anchor = Position(cursor.line, cursor.column)
        cursor = np
    }

    override fun moveLeft(expand: Boolean, word: Boolean) {
        var p = Position(cursor.line, cursor.column)
        if (word) p = wordBoundaryLeft()
        else if (p.column > 0) p.column--
        else if (p.line > 0) {
            p.line--
            p.column = lines[p.line].length
        }
        moveCursorTo(p, expand)
    }

    override fun moveRight(expand: Boolean, word: Boolean) {
        var p = Position(cursor.line, cursor.column)
        val line = lines[p.line]
        if (word) p = wordBoundaryRight()
        else if (p.column < line.length) p.column++
        else if (p.line < lines.size - 1) {
            p.line++
            p.column = 0
        }
        moveCursorTo(p, expand)
    }

    override fun moveUp(expand: Boolean) {
        if (cursor.line == 0) {
            moveCursorTo(Position(0, 0), expand)
            return
        }
        val p = Position(cursor.line - 1, cursor.column)
        moveCursorTo(p.copy(column = p.column.coerceAtMost(lines[p.line].length)), expand)
    }

    override fun moveDown(expand: Boolean) {
        if (cursor.line == lines.size - 1) {
            moveCursorTo(Position(cursor.line, lines.last().length), expand)
            return
        }
        val p = Position(cursor.line + 1, cursor.column)
        moveCursorTo(p.copy(column = p.column.coerceAtMost(lines[p.line].length)), expand)
    }

    override fun moveStartOfLine(expand: Boolean) =
        moveCursorTo(Position(cursor.line, 0), expand)

    override fun moveEndOfLine(expand: Boolean) =
        moveCursorTo(Position(cursor.line, lines[cursor.line].length), expand)

    override fun selectAll() {
        anchor = Position(0, 0)
        cursor = Position(lines.size - 1, lines.last().length)
    }


    /*  
    ===============================================================
      INSERTION
    ===============================================================
    */

    override fun insertText(text: String) {
        if (text.isEmpty()) return
        deleteSelection()

        val norm = text.replace("\r\n", "\n")
        val parts = norm.split("\n")
        val cur = lines[cursor.line]

        val prefix = cur.substring(0, cursor.column)
        val suffix = cur.substring(cursor.column)

        if (parts.size == 1) {
            lines[cursor.line] = prefix + parts[0] + suffix
            cursor.column += parts[0].length
            return
        }

        lines[cursor.line] = prefix + parts.first()
        var insertIdx = cursor.line + 1

        for (i in 1 until parts.size - 1) {
            lines.add(insertIdx, parts[i])
            insertIdx++
        }
        lines.add(insertIdx, parts.last() + suffix)

        cursor.line = insertIdx
        cursor.column = parts.last().length
    }

    override fun insertNewline() = insertText("\n")


    /*  
    ===============================================================
      DELETION
    ===============================================================
    */

    override fun deleteBackspace() {
        if (deleteSelection()) return
        if (cursor.column > 0) {
            val line = lines[cursor.line]
            lines[cursor.line] =
                line.substring(0, cursor.column - 1) + line.substring(cursor.column)
            cursor.column--
            return
        }
        if (cursor.line == 0) return

        val above = lines[cursor.line - 1]
        val here = lines[cursor.line]

        lines[cursor.line - 1] = above + here
        lines.removeAt(cursor.line)
        cursor.line--
        cursor.column = above.length
    }

    override fun deleteForward() {
        if (deleteSelection()) return
        val line = lines[cursor.line]
        if (cursor.column < line.length) {
            lines[cursor.line] =
                line.substring(0, cursor.column) + line.substring(cursor.column + 1)
            return
        }
        if (cursor.line == lines.size - 1) return

        lines[cursor.line] = line + lines[cursor.line + 1]
        lines.removeAt(cursor.line + 1)
    }


    /*  
    ===============================================================
      CLIPBOARD OPERATIONS
    ===============================================================
    */

    override fun copySelection(): Boolean {
        val r = selectionRange() ?: return false
        clipboard = extractText(r)
        notifications.add(Notification(NotificationKind.COPY, clipboard))
        return true
    }

    override fun cutSelection(): Boolean {
        val r = selectionRange() ?: return false
        clipboard = extractText(r)
        deleteSelection()
        notifications.add(Notification(NotificationKind.CUT, clipboard))
        return true
    }

    override fun pasteClipboard() {
        if (clipboard.isNotEmpty()) insertText(clipboard)
    }

    override fun consumeNotifications(): List<Notification> {
        val out = notifications.toList()
        notifications.clear()
        return out
    }


    /*  
    ===============================================================
      SELECTION
    ===============================================================
    */

    override fun startSelection(pos: Position) {
        cursor = clampPosition(pos)
        anchor = Position(cursor.line, cursor.column)
    }

    override fun selectTo(pos: Position) {
        if (anchor == null) anchor = Position(cursor.line, cursor.column)
        cursor = clampPosition(pos)
    }

    override fun hasSelection(): Boolean {
        val a = anchor ?: return false
        return a.line != cursor.line || a.column != cursor.column
    }

    override fun clearSelection() {
        anchor = null
    }


    /*  
    ===============================================================
      VIEWPORT RENDERING
      (gutterWidth now passed as parameter)
    ===============================================================
    */

    override fun viewportSlice(view: EditorViewport, gutterWidth: Int): ViewportSlice {
        val height = if (view.height <= 0) 1 else view.height
        val out = mutableListOf<ViewLine>()

        for (row in 0 until height) {
            val idx = view.y + row
            if (idx >= lines.size) break
            val gutter = gutterText(idx, gutterWidth)
            val segs = buildSegments(idx, view.x, view.width)
            out.add(ViewLine(idx, gutter, segs))
        }

        val cursorView = computeCursorView(view)

        return ViewportSlice(out, lines.size, cursorView)
    }

    private fun computeCursorView(view: EditorViewport): CursorView? {
        if (cursor.line < view.y || cursor.line >= view.y + view.height) return null

        val line = lines[cursor.line]
        val vcol = visualColumn(line, cursor.column)
        val rel = vcol - view.x
        if (rel < 0 || rel >= view.width) return null

        val expanded = expandTabs(line)
        val ch = expanded.getOrNull(vcol)?.toString() ?: " "

        return CursorView(cursor.line - view.y, rel, ch)
    }

    private fun gutterText(lineIdx: Int, gutterWidth: Int): String {
        val n = (lineIdx + 1).toString()
        val pad = gutterWidth - n.length
        val g = if (pad > 0) " ".repeat(pad) + n else n.takeLast(gutterWidth)
        return "$g "
    }

    private fun buildSegments(lineIdx: Int, viewX: Int, viewWidth: Int): List<ViewSegment> {
        val width = if (viewWidth <= 0) 1 else viewWidth
        val expanded = expandTabs(lines[lineIdx])
        val chars = expanded.toCharArray().map { it.toString() }

        val sel = selectionColumns(lineIdx)
        val segs = mutableListOf<ViewSegment>()

        var buf = StringBuilder()
        var currentSel = false
        var started = false

        for (i in 0 until width) {
            val col = viewX + i
            val ch = if (col < chars.size) chars[col] else " "
            val selHere = sel.intersects(col)

            if (!started) {
                started = true
                currentSel = selHere
                buf.append(ch)
                continue
            }

            if (selHere != currentSel) {
                segs.add(ViewSegment(buf.toString(), currentSel))
                buf = StringBuilder(ch)
                currentSel = selHere
            } else buf.append(ch)
        }

        if (started && buf.isNotEmpty()) segs.add(ViewSegment(buf.toString(), currentSel))
        return segs
    }

    private data class SelectionColumns(val start: Int = 0, val end: Int = 0, val active: Boolean = false) {
        fun intersects(col: Int): Boolean = active && col >= start && col < end
    }

    private fun selectionColumns(lineIdx: Int): SelectionColumns {
        val r = selectionRange() ?: return SelectionColumns(active = false)
        if (lineIdx < r.start.line || lineIdx > r.end.line) return SelectionColumns(active = false)

        val text = lines[lineIdx]
        val start = if (lineIdx == r.start.line) visualColumn(text, r.start.column) else 0
        val end = if (lineIdx == r.end.line) visualColumn(text, r.end.column) else visualLength(text)

        return SelectionColumns(start, end, active = true)
    }


    /*  
    ===============================================================
      SELECTION EXTRACTION / REMOVAL
    ===============================================================
    */

    private fun selectionRange(): SelectionRange? {
        val a = anchor ?: return null
        if (a.line == cursor.line && a.column == cursor.column) return null

        return if (a.line > cursor.line || (a.line == cursor.line && a.column > cursor.column))
            SelectionRange(cursor, a)
        else
            SelectionRange(a, cursor)
    }

    private fun extractText(s: SelectionRange): String {
        if (s.start.line == s.end.line)
            return lines[s.start.line].substring(s.start.column, s.end.column)

        val out = mutableListOf<String>()
        out.add(lines[s.start.line].substring(s.start.column))

        for (i in s.start.line + 1 until s.end.line) out.add(lines[i])
        out.add(lines[s.end.line].substring(0, s.end.column))

        return out.joinToString("\n")
    }

    private fun deleteSelection(): Boolean {
        val s = selectionRange() ?: return false

        if (s.start.line == s.end.line) {
            val line = lines[s.start.line]
            lines[s.start.line] =
                line.substring(0, s.start.column) + line.substring(s.end.column)
        } else {
            val first = lines[s.start.line]
            val last = lines[s.end.line]
            lines[s.start.line] =
                first.substring(0, s.start.column) + last.substring(s.end.column)

            for (i in s.start.line + 1..s.end.line) {
                lines.removeAt(s.start.line + 1)
            }
        }

        cursor = Position(s.start.line, s.start.column)
        anchor = null
        return true
    }


    /*  
    ===============================================================
      WORD BOUNDARIES
    ===============================================================
    */

    private fun wordBoundaryLeft(): Position {
        var line = cursor.line
        var col = cursor.column

        if (col == 0 && line == 0) return Position(0, 0)
        if (col == 0) {
            line--
            col = lines[line].length
        }

        val chars = lines[line].toCharArray()
        var i = col - 1

        while (i > 0 && !isWordChar(chars[i])) i--
        while (i > 0 && isWordChar(chars[i - 1])) i--

        return Position(line, i)
    }

    private fun wordBoundaryRight(): Position {
        val line = cursor.line
        val chars = lines[line].toCharArray()
        var i = cursor.column

        if (i >= chars.size) {
            if (line < lines.size - 1) return Position(line + 1, 0)
            return Position(line, chars.size)
        }

        while (i < chars.size && !isWordChar(chars[i])) i++
        while (i < chars.size && isWordChar(chars[i])) i++

        return Position(line, i)
    }
}

data class Notification(val kind: NotificationKind, val text: String)
enum class NotificationKind { COPY, CUT }