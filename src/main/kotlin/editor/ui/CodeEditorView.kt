package editor.ui

import editor.lib.ITextBuffer
import editor.lib.Position
import editor.lib.SelectionRange
import editor.lib.TextBuffer
import editor.lib.handleKeyForBuffer
import editor.lib.handleMouseToBuffer
import editor.mime.MimeTypeResult
import editor.grammars.SyntaxProvider
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class CodeEditorView(
    styleSheet: StyleSheet,
    private val buffer: ITextBuffer = TextBuffer(),
    private val syntaxProvider: SyntaxProvider? = null
) : BaseComponent(styleSheet) {

    private var localStyleSheet: StyleSheet = styleSheet
    private var filePath: String = ""
    private var mime: String? = null
    private var language: String? = null
    private var grammarAvailable: Boolean = false
    private var grammarLanguage: String? = null
    private var scrollTop: Int = 0
    private var dragging = false
    private var lastCols: Int = 0
    private var lastRows: Int = 0

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
        val bodyStyle = baseBody

        // Header bar with file path and mime
        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[unknown]"
            val grammarInfo = grammarLabel()
            val langLabel = language?.let { "· $it$grammarInfo" } ?: grammarInfo
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel $langLabel"
                .take(cols)
            drawText(0, 0, label.padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return

        val visibleLines = buffer.text().split("\n").drop(scrollTop).take(bodyRows)
        val selection = buffer.selectionRange()
        val tokensByLine = if (grammarAvailable && syntaxProvider != null && grammarLanguage != null) {
            syntaxProvider.tokensForLines(scrollTop, visibleLines, grammarLanguage!!).groupBy { it.line }
        } else {
            emptyMap()
        }
        val gutterWidth = computeGutterWidth()
        canvas.applyStyle(bodyStyle) {
            drawRect(0, 1, cols, bodyRows)
            visibleLines.forEachIndexed { idx, textLine ->
                val lineNumber = scrollTop + idx
                // gutter
                canvas.applyStyle(gutterStyle) {
                    val g = (lineNumber + 1).toString().padStart(gutterWidth - 1, ' ') + " "
                    drawText(0, 1 + idx, g.take(gutterWidth))
                }
                val contentCols = (cols - gutterWidth).coerceAtLeast(0)
                if (contentCols <= 0) return@forEachIndexed
                val tokens = tokensByLine[lineNumber] ?: emptyList()
                val selectionCols = selectionRangeForLine(selection, lineNumber, textLine)
                renderLineWithTokens(
                    canvas = this,
                    text = textLine,
                    y = 1 + idx,
                    startX = gutterWidth,
                    maxCols = contentCols,
                    tokens = tokens,
                    baseStyle = bodyStyle,
                    selection = selectionCols,
                    selectionStyle = selectionStyle
                )
            }
        }

        val cursor = buffer.cursorPosition()
        val cx = (gutterWidth + cursor.column).coerceAtMost(cols - 1)
        val cy = 1 + (cursor.line - scrollTop)
        if (cy in 1 until rows) {
            val ch = visibleLines.getOrNull(cursor.line - scrollTop)?.getOrNull(cursor.column)?.toString() ?: " "
            canvas.applyStyle(cursorStyle) {
                drawText(cx, cy, ch)
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val rows = (event.rows ?: lastRows).coerceAtLeast(1)
        val bodyRows = (rows - 1).coerceAtLeast(0)

        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val maxOffset = (buffer.totalLines() - bodyRows).coerceAtLeast(0)
                val prev = scrollTop
                scrollTop = (scrollTop - delta).coerceIn(0, maxOffset)
                return scrollTop != prev
            }
            "mouse_down" -> {
                val y = event.y ?: return false
                if (y == 0) return false // header
                dragging = true
                return handleMouse(event, bodyRows, startSelection = true, extendSelection = false)
            }
            "mouse_up" -> {
                dragging = false
                return true
            }
            "mouse_move" -> {
                if (!dragging) return false
                return handleMouse(event, bodyRows, startSelection = false, extendSelection = true)
            }
            "key_down" -> {
                val key = event.key?.lowercase()
                if (key == "pageup" || key == "pagedown") {
                    val delta = if (key == "pageup") -bodyRows else bodyRows
                    val newLine = (buffer.cursorPosition().line + delta).coerceIn(0, buffer.totalLines().coerceAtLeast(1) - 1)
                    buffer.moveCursorTo(Position(newLine, buffer.cursorPosition().column), expand = event.shift)
                    ensureCursorVisible(rows)
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
                ensureCursorVisible(rows)
                return changed || moved || textChanged
            }
        }
        return true
    }

    private fun ensureCursorVisible(totalRows: Int) {
        val bodyRows = (totalRows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return
        val cursor = buffer.cursorPosition()
        if (cursor.line < scrollTop) {
            scrollTop = cursor.line
        } else if (cursor.line >= scrollTop + bodyRows) {
            scrollTop = cursor.line - bodyRows + 1
        }
    }

    private fun handleMouse(event: UIEvent, bodyRows: Int, startSelection: Boolean, extendSelection: Boolean): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ey <= 0) return false
        val gutterWidth = computeGutterWidth()
        val relX = (ex - gutterWidth).coerceAtLeast(0) + 1 // +1 because handler subtracts 1
        val relY = ey - 1
        val mapped = event.alterCopy(
            UIEvent(
                kind = event.kind,
                x = event.x,
                y = event.y,
                relX = relX,
                relY = relY,
                button = event.button,
                scrollDelta = event.scrollDelta,
                key = event.key,
                ctrl = event.ctrl,
                alt = event.alt,
                shift = event.shift,
                meta = event.meta,
                focusId = event.focusId,
                cols = event.cols ?: (gutterWidth + (event.cols ?: 0)),
                rows = event.rows,
                raw = event.raw
            )
        )
        handleMouseToBuffer(
            buffer = buffer,
            ev = mapped,
            singleLine = false,
            scrollOffset = scrollTop,
            startSelection = startSelection,
            extendSelection = extendSelection
        )
        ensureCursorVisible((event.rows ?: 0))
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
        selectionStyle: StyleSet
    ) {
        if (maxCols <= 0) return
        val baseSegments = buildSegments(text, tokens, baseStyle)
        val withSelection = applySelection(baseSegments, selection, selectionStyle)
        withSelection.forEach { seg ->
            if (seg.start >= maxCols) return
            val drawEnd = minOf(seg.end, maxCols, text.length)
            if (drawEnd <= seg.start) return@forEach
            val part = text.substring(seg.start, drawEnd)
            if (part.isNotEmpty()) {
                canvas.applyStyle(seg.style) {
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
}
