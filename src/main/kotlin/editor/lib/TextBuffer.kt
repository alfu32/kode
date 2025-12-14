package editor.lib

import kotlin.text.iterator
import editor.lib.BufferPersistState
import editor.lib.BufferSnapshotState
import editor.lib.PositionState

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
    private var dirty: Boolean = false
    private var searchQuery: String = ""
    private var replacementText: String = ""
    private var matches: List<SelectionRange> = emptyList()
    private var activeMatchIndex: Int = -1
    private var patternError: String? = null
    private var compiledRegex: Regex? = null
    private val undoStack: ArrayDeque<BufferSnapshot> = ArrayDeque()
    private val redoStack: ArrayDeque<BufferSnapshot> = ArrayDeque()
    private var capturingUndo: Boolean = false
    private val maxHistory: Int = 200
    private val persistHistoryLimit: Int = 30
    private var docVersion: Long = 0


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
    override fun version(): Long = docVersion
    override fun isDirty(): Boolean = dirty

    override fun clone(): ITextBuffer {
        val b = TextBuffer()
        b.lines = lines.toMutableList()
        b.cursor = Position(cursor.line, cursor.column)
        b.anchor = anchor?.let { Position(it.line, it.column) }
        b.clipboard = clipboard
        b.dirty = dirty
        b.searchQuery = searchQuery
        b.replacementText = replacementText
        b.matches = matches.map { SelectionRange(Position(it.start.line, it.start.column), Position(it.end.line, it.end.column)) }
        b.activeMatchIndex = activeMatchIndex
        b.patternError = patternError
        b.compiledRegex = compiledRegex
        return b
    }

    override fun loadText(text: String) {
        val n = text.replace("\r\n", "\n")
        lines = if (n.isEmpty()) mutableListOf("") else n.split("\n").toMutableList()
        if (n.endsWith("\n")) lines.add("")
        cursor = Position(0, 0)
        anchor = null
        bumpVersion()
        dirty = false
        clearHistory()
        refreshSearchAfterChange()
    }

    private fun applyFullText(newText: String) {
        val n = newText.replace("\r\n", "\n")
        lines = if (n.isEmpty()) mutableListOf("") else n.split("\n").toMutableList()
        if (n.endsWith("\n")) lines.add("")
        cursor = clampPosition(Position(cursor.line.coerceAtMost(lines.lastIndex), cursor.column))
        anchor = null
        bumpVersion()
    }

    override fun saveToFile(path: String): Boolean {
        return try {
            java.io.File(path).writeText(text())
            dirty = false
            true
        } catch (_: Exception) {
            false
        }
    }

    override fun undo(): Boolean {
        val snapshot = undoStack.removeLastOrNull() ?: return false
        redoStack.addLast(takeSnapshot())
        applySnapshot(snapshot)
        return true
    }

    override fun redo(): Boolean {
        val snapshot = redoStack.removeLastOrNull() ?: return false
        undoStack.addLast(takeSnapshot())
        applySnapshot(snapshot)
        return true
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
        mutate {
            if (text.isEmpty()) return@mutate
            deleteSelection()

            val norm = text.replace("\r\n", "\n")
            val parts = norm.split("\n")
            val cur = lines[cursor.line]

            val prefix = cur.substring(0, cursor.column)
            val suffix = cur.substring(cursor.column)

            if (parts.size == 1) {
                lines[cursor.line] = prefix + parts[0] + suffix
                cursor.column += parts[0].length
                markDirty()
                refreshSearchAfterChange()
                return@mutate
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
            markDirty()
            refreshSearchAfterChange()
        }
    }

    override fun insertNewline() = insertText("\n")


    /*  
    ===============================================================
      DELETION
    ===============================================================
    */

    override fun deleteBackspace() {
        mutate {
            if (deleteSelection()) return@mutate
            if (cursor.column > 0) {
                val line = lines[cursor.line]
                lines[cursor.line] =
                    line.substring(0, cursor.column - 1) + line.substring(cursor.column)
                cursor.column--
                markDirty()
                refreshSearchAfterChange()
                return@mutate
            }
            if (cursor.line == 0) return@mutate

            val above = lines[cursor.line - 1]
            val here = lines[cursor.line]

            lines[cursor.line - 1] = above + here
            lines.removeAt(cursor.line)
            cursor.line--
            cursor.column = above.length
            markDirty()
            refreshSearchAfterChange()
        }
    }

    override fun deleteForward() {
        mutate {
            if (deleteSelection()) return@mutate
            val line = lines[cursor.line]
            if (cursor.column < line.length) {
                lines[cursor.line] =
                    line.substring(0, cursor.column) + line.substring(cursor.column + 1)
                markDirty()
                refreshSearchAfterChange()
                return@mutate
            }
            if (cursor.line == lines.size - 1) return@mutate

            lines[cursor.line] = line + lines[cursor.line + 1]
            lines.removeAt(cursor.line + 1)
            markDirty()
            refreshSearchAfterChange()
        }
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
        mutate {
            clipboard = extractText(r)
            deleteSelection()
            notifications.add(Notification(NotificationKind.CUT, clipboard))
        }
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

    override fun selectionRange(): SelectionRange? {
        normalizePositions()
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
        markDirty()
        refreshSearchAfterChange()
        return true
    }


    /*  
    ===============================================================
      SEARCH & REPLACE
    ===============================================================
    */

    override fun updateSearch(query: String, replacement: String?) {
        val newReplacement = replacement ?: replacementText
        val changed = (query != searchQuery) || (newReplacement != replacementText)
        searchQuery = query
        replacementText = newReplacement
        if (changed) activeMatchIndex = -1
        if (changed) rebuildSearchResults()
    }

    override fun searchState(): SearchState =
        SearchState(searchQuery, replacementText, matches.size, activeMatchIndex, patternError)

    override fun foundTokens(): List<FoundToken> {
        if (searchQuery.isEmpty() || matches.isEmpty()) return emptyList()
        val tokens = mutableListOf<FoundToken>()
        matches.forEachIndexed { idx, range ->
            val isActive = idx == activeMatchIndex
            if (range.start.line == range.end.line) {
                tokens.add(
                    FoundToken(
                        line = range.start.line,
                        startColumn = range.start.column,
                        endColumn = range.end.column,
                        active = isActive
                    )
                )
            } else {
                tokens.add(
                    FoundToken(
                        line = range.start.line,
                        startColumn = range.start.column,
                        endColumn = lines[range.start.line].length,
                        active = isActive
                    )
                )
                for (line in (range.start.line + 1) until range.end.line) {
                    tokens.add(
                        FoundToken(
                            line = line,
                            startColumn = 0,
                            endColumn = lines[line].length,
                            active = isActive
                        )
                    )
                }
                tokens.add(
                    FoundToken(
                        line = range.end.line,
                        startColumn = 0,
                        endColumn = range.end.column,
                        active = isActive
                    )
                )
            }
        }
        return tokens
    }

    override fun findAll(): List<FoundToken> {
        rebuildSearchResults()
        return foundTokens()
    }

    override fun findNext(): SelectionRange? {
        if (searchQuery.isEmpty()) return null
        if (matches.isEmpty()) rebuildSearchResults()
        if (matches.isEmpty()) return null

        activeMatchIndex = if (activeMatchIndex in matches.indices) {
            (activeMatchIndex + 1) % matches.size
        } else 0

        val target = matches[activeMatchIndex]
        anchor = Position(target.start.line, target.start.column)
        cursor = Position(target.end.line, target.end.column)
        return target
    }

    override fun replaceCurrent(): Boolean {
        if (searchQuery.isEmpty()) return false
        if (matches.isEmpty()) rebuildSearchResults()
        if (matches.isEmpty()) return false
        val regex = compiledRegex ?: return false
        if (activeMatchIndex !in matches.indices) activeMatchIndex = 0
        val text = text()
        var idx = -1
        var replaced = false
        val newText = buildString {
            var lastEnd = 0
            regex.findAll(text).forEach { mr ->
                idx++
                if (idx == activeMatchIndex && !replaced) {
                    append(text.substring(lastEnd, mr.range.first))
                    append(expandReplacement(replacementText, mr))
                    lastEnd = mr.range.last + 1
                    replaced = true
                }
            }
            append(text.substring(lastEnd))
        }
        if (!replaced) return false
        var changed = false
        mutate {
            applyFullText(newText)
            markDirty()
            rebuildSearchResults()
            activeMatchIndex = if (matches.isEmpty()) -1 else activeMatchIndex.coerceIn(0, matches.lastIndex)
            changed = true
        }
        return changed
    }

    override fun replaceAll(): Int {
        if (searchQuery.isEmpty()) return 0
        if (matches.isEmpty()) rebuildSearchResults()
        val regex = compiledRegex ?: return 0
        val count = matches.size
        if (count == 0) return 0

        val newText = regex.replace(text()) { mr -> expandReplacement(replacementText, mr) }
        mutate {
            applyFullText(newText)
            markDirty()
            rebuildSearchResults()
        }
        return count
    }

    private fun replaceRangeFlat(startIndex: Int, endIndex: Int, replacement: String): Int {
        val content = text()
        if (startIndex >= content.length) return content.length
        val safeStart = startIndex.coerceIn(0, content.length)
        val safeEnd = endIndex.coerceIn(safeStart, content.length)
        val newText = content.replaceRange(safeStart, safeEnd, replacement)
        val newCursorIndex = safeStart + replacement.length
        mutate {
            applyFullText(newText)
            val pos = indexToPosition(newCursorIndex)
            cursor = pos
            anchor = null
            markDirty()
            refreshSearchAfterChange()
        }
        return newCursorIndex
    }

    private fun rebuildSearchResults() {
        if (searchQuery.isEmpty()) {
            matches = emptyList()
            activeMatchIndex = -1
            compiledRegex = null
            patternError = null
            return
        }
        val regex = compileRegex() ?: run {
            matches = emptyList()
            activeMatchIndex = -1
            return
        }
        val content = text()
        if (content.isEmpty()) {
            matches = emptyList()
            activeMatchIndex = -1
            return
        }
        val found = mutableListOf<SelectionRange>()
        regex.findAll(content).forEach { mr ->
            val startIdx = mr.range.first
            val endIdx = mr.range.last + 1
            if (endIdx <= startIdx) return@forEach
            val startPos = indexToPosition(startIdx)
            val endPos = indexToPosition(endIdx)
            found.add(SelectionRange(startPos, endPos))
        }
        matches = found
        if (matches.isEmpty() || activeMatchIndex !in matches.indices) {
            activeMatchIndex = -1
        }
    }

    private fun refreshSearchAfterChange() {
        if (searchQuery.isEmpty()) {
            matches = emptyList()
            activeMatchIndex = -1
            return
        }
        rebuildSearchResults()
    }

    private inline fun mutate(block: () -> Unit) {
        if (capturingUndo) {
            block()
            return
        }
        normalizePositions()
        capturingUndo = true
        try {
            recordUndoSnapshot()
            redoStack.clear()
            block()
        } finally {
            capturingUndo = false
        }
    }

    private fun recordUndoSnapshot() {
        undoStack.addLast(takeSnapshot())
        if (undoStack.size > maxHistory) {
            undoStack.removeFirst()
        }
    }

    private fun takeSnapshot(): BufferSnapshot =
        BufferSnapshot(
            lines = lines.toList(),
            cursor = Position(cursor.line, cursor.column),
            anchor = anchor?.let { Position(it.line, it.column) },
            dirty = dirty
        )

    private fun applySnapshot(snapshot: BufferSnapshot) {
        lines = snapshot.lines.toMutableList()
        cursor = Position(snapshot.cursor.line, snapshot.cursor.column)
        anchor = snapshot.anchor?.let { Position(it.line, it.column) }
        dirty = snapshot.dirty
        bumpVersion()
        normalizePositions()
        refreshSearchAfterChange()
    }

    private fun clearHistory() {
        undoStack.clear()
        redoStack.clear()
    }

    private fun normalizePositions() {
        cursor = clampPosition(cursor)
        anchor = anchor?.let { clampPosition(it) }
    }

    fun exportState(): BufferPersistState {
        normalizePositions()
        return BufferPersistState(
            lines = lines.toList(),
            cursor = PositionState(cursor.line, cursor.column),
            anchor = anchor?.let { PositionState(it.line, it.column) },
            undo = undoStack.takeLast(persistHistoryLimit).map { it.toState() },
            redo = redoStack.takeLast(persistHistoryLimit).map { it.toState() },
            dirty = dirty
        )
    }

    fun restoreState(state: BufferPersistState) {
        lines = state.lines.toMutableList().ifEmpty { mutableListOf("") }
        cursor = Position(state.cursor.line, state.cursor.column)
        anchor = state.anchor?.let { Position(it.line, it.column) }
        undoStack.clear()
        redoStack.clear()
        undoStack.addAll(state.undo.map { it.toSnapshot() })
        redoStack.addAll(state.redo.map { it.toSnapshot() })
        dirty = state.dirty
        bumpVersion()
        normalizePositions()
        refreshSearchAfterChange()
    }

    private fun markDirty() {
        bumpVersion()
        dirty = true
    }

    private fun bumpVersion() {
        docVersion++
    }

    private fun compileRegex(): Regex? {
        return try {
            val regex = Regex(searchQuery)
            compiledRegex = regex
            patternError = null
            regex
        } catch (e: Exception) {
            compiledRegex = null
            patternError = e.message
            null
        }
    }

    private fun expandReplacement(replacement: String, match: MatchResult): String {
        val out = StringBuilder()
        var i = 0
        while (i < replacement.length) {
            val ch = replacement[i]
            if (ch == '$' && i + 1 < replacement.length && replacement[i + 1].isDigit()) {
                var j = i + 1
                var num = ""
                while (j < replacement.length && replacement[j].isDigit()) {
                    num += replacement[j]
                    j++
                }
                val idx = num.toIntOrNull()
                if (idx != null && idx in match.groupValues.indices) {
                    out.append(match.groupValues[idx])
                }
                i = j
            } else {
                out.append(ch)
                i++
            }
        }
        return out.toString()
    }

    private fun positionToIndex(pos: Position): Int {
        var idx = 0
        val targetLine = pos.line.coerceIn(0, lines.size.coerceAtLeast(1) - 1)
        for (i in 0 until targetLine) {
            idx += lines[i].length + 1 // include newline
        }
        val col = pos.column.coerceIn(0, lines[targetLine].length)
        return idx + col
    }

    private fun indexToPosition(index: Int): Position {
        if (lines.isEmpty()) return Position(0, 0)
        var remaining = index
        lines.forEachIndexed { lineIdx, line ->
            val span = line.length
            if (remaining <= span) {
                return Position(lineIdx, remaining)
            }
            remaining -= (span + 1)
        }
        return Position(lines.size - 1, lines.last().length.coerceAtLeast(0))
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

private data class BufferSnapshot(
    val lines: List<String>,
    val cursor: Position,
    val anchor: Position?,
    val dirty: Boolean
) {
    fun toState(): BufferSnapshotState =
        BufferSnapshotState(
            lines = lines,
            cursor = PositionState(cursor.line, cursor.column),
            anchor = anchor?.let { PositionState(it.line, it.column) },
            dirty = dirty
        )
}

private fun BufferSnapshotState.toSnapshot(): BufferSnapshot =
    BufferSnapshot(
        lines = lines.toList(),
        cursor = Position(cursor.line, cursor.column),
        anchor = anchor?.let { Position(it.line, it.column) },
        dirty = dirty
    )

data class Notification(val kind: NotificationKind, val text: String)
enum class NotificationKind { COPY, CUT }
