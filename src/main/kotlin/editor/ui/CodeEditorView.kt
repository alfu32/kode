package editor.ui

import editor.lib.EditorViewport
import editor.lib.ITextBuffer
import editor.lib.Position
import editor.lib.TextBuffer
import editor.lib.handleKeyForBuffer
import editor.lib.handleMouseToBuffer
import editor.lib.renderBuffer
import editor.mime.MimeTypeResult
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import react.Color
import java.io.File

class CodeEditorView(
    styleSheet: StyleSheet,
    private val buffer: ITextBuffer = TextBuffer()
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null
    private var language: String? = null
    private var scrollTop: Int = 0
    private var dragging = false
    private var lastCols: Int = 0
    private var lastRows: Int = 0

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        val content = try {
            File(path).readText()
        } catch (_: Exception) {
            ""
        }
        buffer.loadText(content)
        filePath = path
        this.mime = detection?.mime
        this.language = detection?.language
        scrollTop = 0
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        lastCols = cols
        lastRows = rows
        val headerStyle = styleSheet.getStyle("code-header").withDefaults()
        val baseBody = styleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = styleSheet.getStyle("code-gutter").withDefaults(baseBody.fg, baseBody.bg)
        val selectionStyle = styleSheet.getStyle("code-selection")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val cursorStyle = styleSheet.getStyle("code-cursor")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val bodyStyle = baseBody

        // Header bar with file path and mime
        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[unknown]"
            val langLabel = language?.let { "· $it" } ?: ""
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel $langLabel"
                .take(cols)
            drawText(0, 0, label.padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return

        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(1)
        val viewport = EditorViewport(0, scrollTop, contentCols, bodyRows)
        val slice = buffer.viewportSlice(viewport, gutterWidth = gutterWidth)

        canvas.applyStyle(bodyStyle) {
            // clear body area
            drawRect(0, 1, cols, bodyRows)
            slice.lines.take(bodyRows).forEachIndexed { idx, line ->
                // gutter
                canvas.applyStyle(gutterStyle) {
                    val g = line.gutter.padEnd(gutterWidth, ' ').take(gutterWidth)
                    drawText(0, 1 + idx, g)
                }
                // body segments
                var x = gutterWidth
                line.segments.forEach { seg ->
                    val segText = seg.text.take((cols - x).coerceAtLeast(0))
                    if (segText.isEmpty()) return@forEach
                    val style = if (seg.selected) selectionStyle else bodyStyle
                    canvas.applyStyle(style) {
                        drawText(x, 1 + idx, segText)
                    }
                    x += segText.length
                    if (x >= cols) return@forEach
                }
            }
        }

        slice.cursor?.let { cursor ->
            val cx = (gutterWidth + cursor.column).coerceAtMost(cols - 1)
            val cy = 1 + cursor.line
            if (cy in 1 until rows) {
                canvas.applyStyle(cursorStyle) {
                    drawText(cx, cy, cursor.char)
                }
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
}
