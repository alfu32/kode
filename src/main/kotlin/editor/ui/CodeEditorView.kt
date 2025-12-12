package editor.ui

import editor.lib.FoundToken
import editor.lib.Position
import editor.lib.SelectionRange
import editor.lib.TextBuffer
import editor.lib.handleKeyForBuffer
import editor.lib.handleMouseToBuffer
import editor.mime.MimeTypeResult
import editor.grammars.SyntaxProvider
import editor.app.EditorSessionState
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File
import editor.ui.SearchReplaceBar.SearchCommand

class CodeEditorView(
    styleSheet: StyleSheet,
    private val buffer: TextBuffer = TextBuffer(),
    private val syntaxProvider: SyntaxProvider? = null
) : BaseComponent(styleSheet) {

    private var localStyleSheet: StyleSheet = styleSheet
    private var filePath: String = ""
    private var mime: String? = null
    private var language: String? = null
    private var grammarAvailable: Boolean = false
    private var grammarLanguage: String? = null
    private var scrollTop: Int = 0
    private var lastLayout: VisualLayout? = null
    private var dragging = false
    private var lastCols: Int = 0
    private var lastRows: Int = 0
    private var searchVisible = false
    private var searchHasFocus = false
    private var searchBar: SearchReplaceBar = SearchReplaceBar(styleSheet, this::handleSearchAction)

    fun textContent(): String = buffer.text()

    fun loadTextContent(text: String) {
        buffer.loadText(text)
        scrollTop = 0
    }

    fun captureState(lastModifiedMillis: Long? = null): EditorSessionState? {
        if (filePath.isEmpty()) return null
        return EditorSessionState(
            path = filePath,
            buffer = buffer.exportState(),
            scrollTop = scrollTop,
            mime = mime,
            language = language,
            grammarLanguage = grammarLanguage,
            grammarAvailable = grammarAvailable,
            lastModifiedMillis = lastModifiedMillis
        )
    }

    fun restoreState(state: EditorSessionState) {
        filePath = state.path
        mime = state.mime
        language = state.language
        grammarAvailable = state.grammarAvailable
        grammarLanguage = state.grammarLanguage ?: state.language
        buffer.restoreState(state.buffer)
        scrollTop = state.scrollTop.coerceAtLeast(0)
    }

    fun currentPath(): String = filePath

    fun openFile(
        path: String,
        detection: MimeTypeResult? = null,
        grammarAvailable: Boolean = false,
        grammarLanguage: String? = null
    ) {
        val content = try {
            File(path).readText()
        } catch (_: Exception) {
            ""
        }
        buffer.loadText(content)
        filePath = path
        this.mime = detection?.mime
        this.language = detection?.language
        this.grammarAvailable = grammarAvailable
        this.grammarLanguage = grammarLanguage ?: detection?.language
        scrollTop = 0
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        lastCols = cols
        lastRows = rows
        val headerStyle = localStyleSheet.getStyle("code-header").withDefaults()
        val baseBody = localStyleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = localStyleSheet.getStyle("code-gutter").withDefaults(baseBody.fg, baseBody.bg)
        val selectionStyle = localStyleSheet.getStyle("code-selection")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val cursorStyle = localStyleSheet.getStyle("code-cursor")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val searchMatchStyle = localStyleSheet.getStyle("code-search-match").withDefaults(baseBody.fg, baseBody.bg)
        val searchActiveMatchStyle =
            localStyleSheet.getStyle("code-search-active").withDefaults(searchMatchStyle.fg, searchMatchStyle.bg)
        val bodyStyle = baseBody
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyStartRow = 1 + searchHeight

        // Header bar with file path and mime
        canvas.withStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[unknown]"
            val grammarInfo = grammarLabel()
            val langLabel = language?.let { "· $it$grammarInfo" } ?: grammarInfo
            val saveMarker = if (buffer.isDirty()) "*" else " "
            val label = "$saveMarker${filePath.ifEmpty { "[no file]" }} $mimeLabel $langLabel"
                .take(cols)
            drawText(0, 0, label.padEnd(cols, ' '))
        }

        if (searchVisible && searchHeight > 0) {
            val clipped = ClippedCanvasRenderer(
                base = canvas,
                offsetX = 0,
                offsetY = 1,
                width = cols,
                height = searchHeight
            )
            val state = buffer.searchState()
            searchBar.updateMatchLabel(state.activeIndex, state.matchCount)
            searchBar.render(clipped)
        }

        val bodyRows = (rows - bodyStartRow).coerceAtLeast(0)
        if (bodyRows == 0) return

        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(0)
        val lines = buffer.text().split("\n")
        val layout = buildLayout(lines, contentCols)
        lastLayout = layout

        val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
        scrollTop = scrollTop.coerceIn(0, maxOffset)

        val visibleRows = layout.wrapped.drop(scrollTop).take(bodyRows)
        if (visibleRows.isEmpty()) return

        val selection = buffer.selectionRange()
        val searchTokensByLine = buffer.foundTokens().groupBy { it.line }
        val firstVisibleLine = visibleRows.first().lineIndex
        val lastVisibleLine = visibleRows.last().lineIndex
        val tokensByLine = if (grammarAvailable && syntaxProvider != null && grammarLanguage != null) {
            val sliceEnd = (lastVisibleLine + 1).coerceAtMost(lines.size)
            val lineSlice = lines.subList(firstVisibleLine, sliceEnd)
            syntaxProvider.tokensForLines(firstVisibleLine, lineSlice, grammarLanguage!!).groupBy { it.line }
        } else {
            emptyMap()
        }

        canvas.withStyle(bodyStyle) {
            drawRect(0, bodyStartRow, cols, bodyRows)
            visibleRows.forEachIndexed { idx, wrapped ->
                val lineNumber = wrapped.lineIndex
                val y = bodyStartRow + idx
                val lineText = lines.getOrElse(lineNumber) { "" }

                canvas.withStyle(gutterStyle) {
                    val g = if (wrapped.startColumn == 0) {
                        (lineNumber + 1).toString().padStart(gutterWidth - 1, ' ') + " "
                    } else {
                        " ".repeat(gutterWidth)
                    }
                    drawText(0, y, g.take(gutterWidth))
                }

                if (contentCols <= 0) return@forEachIndexed
                val chunkText = lineText.substring(wrapped.startColumn, wrapped.endColumn)
                val tokens = tokensByLine[lineNumber] ?: emptyList()
                val chunkTokens = sliceTokens(tokens, wrapped.startColumn, wrapped.endColumn)
                val selectionCols = selectionRangeForLine(selection, lineNumber, lineText)
                val chunkSelection = selectionCols?.let { trimSelectionToChunk(it, wrapped.startColumn, wrapped.endColumn) }
                val highlights = searchTokensByLine[lineNumber]?.mapNotNull {
                    trimHighlightToChunk(it, wrapped.startColumn, wrapped.endColumn)
                } ?: emptyList()
                renderLineWithTokens(
                    canvas = this,
                    text = chunkText,
                    y = y,
                    startX = gutterWidth,
                    maxCols = contentCols,
                    tokens = chunkTokens,
                    baseStyle = bodyStyle,
                    selection = chunkSelection,
                    selectionStyle = selectionStyle,
                    highlights = highlights,
                    highlightStyle = searchMatchStyle,
                    activeHighlightStyle = searchActiveMatchStyle
                )
            }
        }

        val cursor = buffer.cursorPosition()
        val cursorRow = visualRowForPosition(cursor, layout)
        val cursorChunk = chunkForPosition(cursor, layout)
        val cy = bodyStartRow + (cursorRow - scrollTop)
        if (cursorChunk != null && cy in bodyStartRow until rows) {
            val cursorCol = (cursor.column - cursorChunk.startColumn).coerceAtLeast(0)
            val cx = (gutterWidth + cursorCol).coerceAtMost(cols - 1)
            val lineText = lines.getOrElse(cursorChunk.lineIndex) { "" }
            val ch = lineText.getOrNull(cursor.column)?.toString() ?: " "
            canvas.withStyle(cursorStyle) {
                drawText(cx, cy, ch)
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val cols = (event.cols ?: lastCols).coerceAtLeast(1)
        val rows = (event.rows ?: lastRows).coerceAtLeast(1)
        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(0)
        val layout = buildLayout(buffer.text().split("\n"), contentCols).also { lastLayout = it }
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyRows = (rows - 1 - searchHeight).coerceAtLeast(0)
        val bodyStartRow = 1 + searchHeight

        if (event.kind == "key_down" && event.ctrl && event.key?.lowercase() == "f") {
            val selection = if (buffer.hasSelection()) buffer.selectionText() else ""
            openSearch(selection)
            return true
        }

        if (searchVisible && event.kind.startsWith("mouse")) {
            val y = event.y ?: 0
            if (y in 1 until bodyStartRow) {
                val forwarded = event.alterCopy(
                    UIEvent(
                        kind = event.kind,
                        x = event.x,
                        y = (event.y ?: 0) - 1,
                        relX = event.relX,
                        relY = event.relY,
                        button = event.button,
                        scrollDelta = event.scrollDelta,
                        key = event.key,
                        ctrl = event.ctrl,
                        alt = event.alt,
                        shift = event.shift,
                        meta = event.meta,
                        focusId = event.focusId,
                        cols = event.cols,
                        rows = searchHeight,
                        raw = event.raw
                    )
                )
                val handled = searchBar.dispatch(forwarded)
                if (handled) {
                    searchHasFocus = searchVisible
                    syncSearchUiFromBuffer()
                    ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                    return true
                }
            }
        }

        if (searchVisible && event.kind == "key_down" && searchHasFocus) {
            val handled = searchBar.dispatch(event)
            if (handled) {
                syncSearchUiFromBuffer()
                ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
            }
            return true
        }

        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
                val prev = scrollTop
                scrollTop = (scrollTop - delta).coerceIn(0, maxOffset)
                return scrollTop != prev
            }
            "mouse_down" -> {
                val y = event.y ?: return false
                if (y == 0) return false // header
                if (searchVisible && y in 1 until bodyStartRow) return true
                searchHasFocus = false
                dragging = true
                return handleMouse(
                    event = event,
                    bodyStartRow = bodyStartRow,
                    layout = layout,
                    gutterWidth = gutterWidth,
                    startSelection = true,
                    extendSelection = false
                )
            }
            "mouse_up" -> {
                dragging = false
                return true
            }
            "mouse_move" -> {
                if (!dragging) return false
                return handleMouse(
                    event = event,
                    bodyStartRow = bodyStartRow,
                    layout = layout,
                    gutterWidth = gutterWidth,
                    startSelection = false,
                    extendSelection = true
                )
            }
            "key_down" -> {
                val key = event.key?.lowercase()
                if (event.ctrl && key == "s") {
                    if (filePath.isNotEmpty()) buffer.saveToFile(filePath)
                    return true
                }
                if (key == "pageup" || key == "pagedown") {
                    val delta = if (key == "pageup") -bodyRows else bodyRows
                    val newLine = (buffer.cursorPosition().line + delta).coerceIn(0, buffer.totalLines().coerceAtLeast(1) - 1)
                    buffer.moveCursorTo(Position(newLine, buffer.cursorPosition().column), expand = event.shift)
                    ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                    return true
                }
                val beforeCursor = buffer.cursorPosition()
                val beforeSelection = if (buffer.hasSelection()) buffer.selectionText() else null
                val beforeText = buffer.text()
                val changed = handleKeyForBuffer(buffer, event, singleLine = false)
                val afterCursor = buffer.cursorPosition()
                val afterSelection = if (buffer.hasSelection()) buffer.selectionText() else null
                val moved = beforeCursor != afterCursor || beforeSelection != afterSelection
                val textChanged = beforeText != buffer.text()
                ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                return changed || moved || textChanged
            }
        }
        return true
    }

    private fun ensureCursorVisible(
        totalRows: Int,
        searchHeight: Int,
        layout: VisualLayout? = lastLayout,
        gutterWidth: Int = computeGutterWidth()
    ) {
        val bodyRows = (totalRows - 1 - searchHeight).coerceAtLeast(0)
        if (bodyRows == 0) return
        val contentCols = (lastCols - gutterWidth).coerceAtLeast(0)
        val activeLayout = layout ?: buildLayout(buffer.text().split("\n"), contentCols).also { lastLayout = it }
        if (activeLayout.wrapped.isEmpty()) return
        val cursorRow = visualRowForPosition(buffer.cursorPosition(), activeLayout)
        val maxOffset = (activeLayout.wrapped.size - bodyRows).coerceAtLeast(0)
        val newScroll = when {
            cursorRow < scrollTop -> cursorRow
            cursorRow >= scrollTop + bodyRows -> cursorRow - bodyRows + 1
            else -> scrollTop
        }.coerceIn(0, maxOffset)
        scrollTop = newScroll
    }

    private fun handleMouse(
        event: UIEvent,
        bodyStartRow: Int,
        layout: VisualLayout,
        gutterWidth: Int,
        startSelection: Boolean,
        extendSelection: Boolean
    ): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ey < bodyStartRow) return false
        if (layout.wrapped.isEmpty()) return false
        val relX = (ex - gutterWidth).coerceAtLeast(0)
        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: layout.wrapped.last()
        val lineText = layout.lines.getOrElse(wrapped.lineIndex) { "" }
        val targetCol = (wrapped.startColumn + relX).coerceAtMost(lineText.length)
        val pos = Position(wrapped.lineIndex, targetCol)
        if (startSelection) {
            buffer.startSelection(pos)
        } else if (extendSelection) {
            buffer.selectTo(pos)
        }
        buffer.moveCursorTo(pos, expand = extendSelection)
        ensureCursorVisible((event.rows ?: 0), bodyStartRow - 1, layout, gutterWidth)
        return true
    }

    private fun computeGutterWidth(): Int {
        val digits = buffer.totalLines().coerceAtLeast(1).toString().length
        return (digits + 2).coerceAtMost(12) // number + space; cap to avoid overrun
    }

    private fun grammarLabel(): String {
        val lang = language ?: return ""
        return if (grammarAvailable) " (grammar:${grammarLanguage ?: lang})" else " (grammar:none)"
    }

    private fun scopeToStyleId(scope: String): String =
        scope.replace(' ', '_').replace(":", "-").replace(",", "-")

    private fun styleForToken(token: editor.grammars.Token, base: StyleSet): StyleSet {
        token.fg?.let { color ->
            val copy = base.copy()
            copy.fg = color
            return copy
        }
        val scope = token.scopes.lastOrNull() ?: return base
        return cachedStyle(scope).withDefaults(base.fg, base.bg)
    }

    private val styleCache = mutableMapOf<String, StyleSet>()

    private fun cachedStyle(scope: String): StyleSet =
        styleCache.getOrPut(scope) { localStyleSheet.getStyle(scopeToStyleId(scope)) }

    fun updateStyleSheet(styleSheet: StyleSheet) {
        this.localStyleSheet = styleSheet
        styleCache.clear()
        val searchState = buffer.searchState()
        searchBar = SearchReplaceBar(styleSheet, this::handleSearchAction).also {
            it.updateFromSearchState(searchState)
            it.setFocusEnabled(true)
        }
    }

    fun setSelection(start: Position, end: Position, center: Boolean = false) {
        buffer.startSelection(start)
        buffer.selectTo(end)
        val currentRows = lastRows.coerceAtLeast(1)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(currentRows - 1) else 0
        val gutterWidth = computeGutterWidth()
        val layout = lastLayout ?: buildLayout(
            buffer.text().split("\n"),
            (lastCols - gutterWidth).coerceAtLeast(0)
        ).also { lastLayout = it }
        if (center) {
            val bodyRows = (currentRows - 1 - searchHeight).coerceAtLeast(1)
            val targetRow = visualRowForPosition(start, layout)
            val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
            scrollTop = (targetRow - bodyRows / 2).coerceIn(0, maxOffset)
        }
        ensureCursorVisible(currentRows, searchHeight, layout, gutterWidth)
    }

    fun isDirty(): Boolean = buffer.isDirty()

    fun currentSelectionText(): String? = if (buffer.hasSelection()) buffer.selectionText() else null

    private fun openSearch(selectionText: String? = null) {
        searchVisible = true
        searchHasFocus = true
        if (!selectionText.isNullOrEmpty()) {
            buffer.updateSearch(Regex.escape(selectionText))
        }
        searchBar.updateFromSearchState(buffer.searchState())
    }

    private fun handleSearchAction(action: SearchCommand) {
        when (action) {
            is SearchCommand.Change -> buffer.updateSearch(action.query, action.replacement)
            SearchCommand.FindNext -> buffer.findNext()
            SearchCommand.FindAll -> buffer.findAll()
            SearchCommand.ReplaceOne -> buffer.replaceCurrent()
            SearchCommand.ReplaceAll -> buffer.replaceAll()
            SearchCommand.Close -> {
                searchVisible = false
                searchHasFocus = false
                return
            }
        }
        syncSearchUiFromBuffer()
        val currentRows = lastRows.coerceAtLeast(1)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(currentRows - 1) else 0
        val gutterWidth = computeGutterWidth()
        val layout = lastLayout ?: buildLayout(
            buffer.text().split("\n"),
            (lastCols - gutterWidth).coerceAtLeast(0)
        ).also { lastLayout = it }
        ensureCursorVisible(currentRows, searchHeight, layout, gutterWidth)
    }

    private fun syncSearchUiFromBuffer() {
        val state = buffer.searchState()
        searchBar.updateFromSearchState(state)
    }

    private fun renderLineWithTokens(
        canvas: CanvasRenderer,
        text: String,
        y: Int,
        startX: Int,
        maxCols: Int,
        tokens: List<editor.grammars.Token>,
        baseStyle: StyleSet,
        selection: IntRange?,
        selectionStyle: StyleSet,
        highlights: List<FoundToken>,
        highlightStyle: StyleSet,
        activeHighlightStyle: StyleSet
    ) {
        if (maxCols <= 0) return
        val baseSegments = buildSegments(text, tokens, baseStyle)
        val withHighlights = applyHighlights(baseSegments, highlights, highlightStyle, activeHighlightStyle)
        val withSelection = applySelection(withHighlights, selection, selectionStyle)
        withSelection.forEach { seg ->
            if (seg.start >= maxCols) return
            val drawEnd = minOf(seg.end, maxCols, text.length)
            if (drawEnd <= seg.start) return@forEach
            val part = text.substring(seg.start, drawEnd)
            if (part.isNotEmpty()) {
                canvas.withStyle(seg.style) {
                    drawText(startX + seg.start, y, part)
                }
            }
        }
    }

    private fun selectionRangeForLine(selection: SelectionRange?, lineIdx: Int, lineText: String): IntRange? {
        selection ?: return null
        if (lineIdx < selection.start.line || lineIdx > selection.end.line) return null
        val lineLength = lineText.length
        val startCol = if (lineIdx == selection.start.line) selection.start.column else 0
        val endCol = if (lineIdx == selection.end.line) selection.end.column else lineLength
        val start = startCol.coerceIn(0, lineLength)
        val end = endCol.coerceIn(0, lineLength)
        if (start >= end) return null
        return start until end
    }

    private data class StyledSegment(val start: Int, val end: Int, val style: StyleSet)

    private fun buildSegments(
        text: String,
        tokens: List<editor.grammars.Token>,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        if (text.isEmpty()) return emptyList()
        if (tokens.isEmpty()) return listOf(StyledSegment(0, text.length, baseStyle))
        val segments = mutableListOf<StyledSegment>()
        var cursor = 0
        tokens.sortedBy { it.start }.forEach { tok ->
            val segStart = tok.start.coerceIn(0, text.length)
            val segEnd = tok.end.coerceIn(0, text.length)
            if (segStart > cursor) {
                segments.add(StyledSegment(cursor, segStart, baseStyle))
            }
            if (segEnd > segStart) {
                val style = styleForToken(tok, baseStyle)
                segments.add(StyledSegment(segStart, segEnd, style))
            }
            cursor = maxOf(cursor, segEnd)
            if (cursor >= text.length) return@forEach
        }
        if (cursor < text.length) {
            segments.add(StyledSegment(cursor, text.length, baseStyle))
        }
        return segments
    }

    private fun applySelection(
        segments: List<StyledSegment>,
        selection: IntRange?,
        selectionStyle: StyleSet
    ): List<StyledSegment> {
        selection ?: return segments
        if (segments.isEmpty()) return segments
        val selStart = selection.first
        val selEnd = selection.last + 1
        if (selStart >= selEnd) return segments

        val out = mutableListOf<StyledSegment>()
        segments.forEach { seg ->
            if (seg.end <= selStart || seg.start >= selEnd) {
                out.add(seg)
                return@forEach
            }
            if (seg.start < selStart) {
                out.add(StyledSegment(seg.start, selStart, seg.style))
            }
            val selectedStart = maxOf(seg.start, selStart)
            val selectedEnd = minOf(seg.end, selEnd)
            if (selectedStart < selectedEnd) {
                out.add(StyledSegment(selectedStart, selectedEnd, selectionStyle))
            }
            if (seg.end > selEnd) {
                out.add(StyledSegment(selEnd, seg.end, seg.style))
            }
        }
        return out
    }

    private fun applyHighlights(
        segments: List<StyledSegment>,
        highlights: List<FoundToken>,
        highlightStyle: StyleSet,
        activeHighlightStyle: StyleSet
    ): List<StyledSegment> {
        if (highlights.isEmpty()) return segments
        var current = segments
        highlights.sortedBy { it.startColumn }.forEach { token ->
            val style = if (token.active) activeHighlightStyle else highlightStyle
            current = overlayRange(current, token.startColumn, token.endColumn, style)
        }
        return current
    }

    private fun overlayRange(
        segments: List<StyledSegment>,
        start: Int,
        end: Int,
        style: StyleSet
    ): List<StyledSegment> {
        if (segments.isEmpty() || start >= end) return segments
        val out = mutableListOf<StyledSegment>()
        segments.forEach { seg ->
            if (seg.end <= start || seg.start >= end) {
                out.add(seg)
                return@forEach
            }
            if (seg.start < start) {
                out.add(StyledSegment(seg.start, start, seg.style))
            }
            val overlayStart = maxOf(seg.start, start)
            val overlayEnd = minOf(seg.end, end)
            if (overlayStart < overlayEnd) {
                out.add(StyledSegment(overlayStart, overlayEnd, style))
            }
            if (seg.end > end) {
                out.add(StyledSegment(end, seg.end, seg.style))
            }
        }
        return out
    }

    private fun trimSelectionToChunk(selection: IntRange, chunkStart: Int, chunkEnd: Int): IntRange? {
        val selStart = maxOf(selection.first, chunkStart)
        val selEndExclusive = minOf(selection.last + 1, chunkEnd)
        if (selStart >= selEndExclusive) return null
        return selStart - chunkStart until selEndExclusive - chunkStart
    }

    private fun sliceTokens(
        tokens: List<editor.grammars.Token>,
        chunkStart: Int,
        chunkEnd: Int
    ): List<editor.grammars.Token> =
        tokens.mapNotNull { token ->
            val start = maxOf(token.start, chunkStart)
            val end = minOf(token.end, chunkEnd)
            if (start >= end) return@mapNotNull null
            token.copy(start = start - chunkStart, end = end - chunkStart)
        }

    private fun trimHighlightToChunk(token: FoundToken, chunkStart: Int, chunkEnd: Int): FoundToken? {
        val start = maxOf(token.startColumn, chunkStart)
        val end = minOf(token.endColumn, chunkEnd)
        if (start >= end) return null
        return token.copy(startColumn = start - chunkStart, endColumn = end - chunkStart)
    }

    private fun buildLayout(lines: List<String>, contentCols: Int): VisualLayout {
        val width = contentCols.coerceAtLeast(1)
        val wrapped = mutableListOf<WrappedLine>()
        val lineOffsets = IntArray(lines.size)
        val wrapCounts = IntArray(lines.size)
        lines.forEachIndexed { idx, line ->
            lineOffsets[idx] = wrapped.size
            val len = line.length
            if (len == 0) {
                wrapped.add(WrappedLine(idx, 0, 0))
                wrapCounts[idx] = 1
            } else {
                var start = 0
                var count = 0
                while (start < len) {
                    val end = (start + width).coerceAtMost(len)
                    wrapped.add(WrappedLine(idx, start, end))
                    start = end
                    count++
                }
                wrapCounts[idx] = maxOf(1, count)
            }
        }
        if (lines.isEmpty()) {
            wrapped.add(WrappedLine(0, 0, 0))
        }
        return VisualLayout(lines, wrapped, lineOffsets, wrapCounts, width)
    }

    private fun visualRowForPosition(pos: Position, layout: VisualLayout): Int {
        if (layout.wrapped.isEmpty()) return 0
        val line = pos.line.coerceIn(0, layout.lines.lastIndex)
        val offset = layout.lineOffsets.getOrElse(line) { 0 }
        val width = layout.contentWidth.coerceAtLeast(1)
        val lineLength = layout.lines.getOrElse(line) { "" }.length
        val wraps = layout.wrapCounts.getOrElse(line) { 1 }.coerceAtLeast(1)
        val chunkIndex = (pos.column.coerceAtMost(lineLength) / width).coerceAtMost(wraps - 1)
        return offset + chunkIndex
    }

    private fun chunkForPosition(pos: Position, layout: VisualLayout): WrappedLine? {
        if (layout.wrapped.isEmpty()) return null
        val line = pos.line.coerceIn(0, layout.lines.lastIndex)
        val offset = layout.lineOffsets.getOrElse(line) { 0 }
        val width = layout.contentWidth.coerceAtLeast(1)
        val lineLength = layout.lines.getOrElse(line) { "" }.length
        val wraps = layout.wrapCounts.getOrElse(line) { 1 }.coerceAtLeast(1)
        val idxInLine = (pos.column.coerceAtMost(lineLength) / width).coerceAtMost(wraps - 1)
        val index = offset + idxInLine
        return layout.wrapped.getOrNull(index)
    }

    private data class WrappedLine(val lineIndex: Int, val startColumn: Int, val endColumn: Int)
    private data class VisualLayout(
        val lines: List<String>,
        val wrapped: List<WrappedLine>,
        val lineOffsets: IntArray,
        val wrapCounts: IntArray,
        val contentWidth: Int
    )
}
