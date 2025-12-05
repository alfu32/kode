package editor.ui

import editor.mime.MimeTypeResult
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import editor.lib.ByteBuffer
import editor.lib.IByteBuffer
import editor.lib.ByteViewport
import java.io.File
import kotlin.math.max
import kotlin.math.min

/**
 * Hex editor backed by a byte buffer. Renders offsets, hex bytes, ASCII, cursor, and selection.
 */
class BinaryHexView(
    styleSheet: StyleSheet,
    private val bytesPerRow: Int = 16,
    private val buffer: IByteBuffer = ByteBuffer()
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null
    private var scrollRow: Int = 0
    private var dragging = false

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
        val data = runCatching { File(path).readBytes() }.getOrElse { ByteArray(0) }
        buffer.loadBytes(data)
        scrollRow = 0
        buffer.moveCursorTo(0, expand = false)
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("code-header").withDefaults()
        val bodyStyle = styleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = styleSheet.getStyle("code-gutter").withDefaults(bodyStyle.fg, bodyStyle.bg)
        val cursorStyle = styleSheet.getStyle("code-cursor")
            .withDefaults(fg = bodyStyle.bg ?: gutterStyle.bg, bg = bodyStyle.fg ?: gutterStyle.fg)
        val selectionStyle = styleSheet.getStyle("code-selection")
            .withDefaults(fg = bodyStyle.bg ?: gutterStyle.bg, bg = bodyStyle.fg ?: gutterStyle.fg)

        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[binary]"
            val sizeLabel = "${buffer.totalBytes()} bytes"
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel · $sizeLabel"
            drawText(0, 0, label.take(cols).padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return
        if (buffer.totalBytes() == 0) return

        val slice = buffer.viewportSlice(ByteViewport(scrollRow * bytesPerRow, bytesPerRow, bodyRows))
        val cursorIndex = slice.cursorIndex

        canvas.applyStyle(bodyStyle) {
            drawRect(0, 1, cols, bodyRows)
            slice.rows.forEachIndexed { rowIdx, row ->
                val offset = String.format("%08X", row.offset)
                canvas.applyStyle(gutterStyle) {
                    drawText(0, 1 + rowIdx, offset.take(cols).padEnd(10, ' '))
                }
                val hexStart = 10
                val asciiStart = hexStart + (bytesPerRow * 3) + 2

                row.bytes.forEachIndexed { idx, b ->
                    val hx = hexStart + (idx * 3)
                    val ax = asciiStart + idx
                    val hexPair = String.format("%02X", b.toInt() and 0xFF)
                    val asciiChar = asciiCharFor(b)
                    val selected = row.selection.getOrElse(idx) { false }
                    val hStyle = if (selected) selectionStyle else bodyStyle
                    val aStyle = if (selected) selectionStyle else bodyStyle
                    if (hx < cols) {
                        canvas.applyStyle(hStyle) {
                            drawText(hx, 1 + rowIdx, hexPair.take(max(0, cols - hx)))
                        }
                    }
                    if (ax < cols) {
                        canvas.applyStyle(aStyle) {
                            drawText(ax, 1 + rowIdx, asciiChar)
                        }
                    }
                }

                // Cursor overlay
                val rowRangeStart = row.offset
                val rowRangeEnd = row.offset + row.bytes.size
                if (cursorIndex in rowRangeStart until rowRangeEnd) {
                    val local = cursorIndex - rowRangeStart
                    val hx = hexStart + (local * 3)
                    val ax = asciiStart + local
                    if (hx < cols) {
                        val pair = String.format("%02X", row.bytes[local].toInt() and 0xFF)
                        canvas.applyStyle(cursorStyle) {
                            drawText(hx, 1 + rowIdx, pair.take(max(0, cols - hx)))
                        }
                    }
                    if (ax < cols) {
                        val ch = asciiCharFor(row.bytes[local])
                        canvas.applyStyle(cursorStyle) {
                            drawText(ax, 1 + rowIdx, ch)
                        }
                    }
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val totalRows = max(1, (buffer.totalBytes() + bytesPerRow - 1) / bytesPerRow)
        val maxRow = max(0, totalRows - 1)
        val bodyRows = max(1, (event.rows ?: 0) - 1)
        when (event.kind) {
            "mouse_down" -> {
                dragging = true
                return handleMouse(event, startSelection = true, extendSelection = event.shift)
            }
            "mouse_move" -> {
                if (!dragging) return false
                return handleMouse(event, startSelection = false, extendSelection = true)
            }
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val prev = scrollRow
                scrollRow = (scrollRow - delta).coerceIn(0, maxRow)
                return scrollRow != prev
            }
            "mouse_up" -> {
                dragging = false
                return true
            }
            "key_down" -> {
                val key = event.key?.lowercase() ?: return false
                val prevScroll = scrollRow
                val prevCursor = buffer.cursorIndex()
                when (key) {
                    "up" -> buffer.moveUp(bytesPerRow, expand = event.shift)
                    "down" -> buffer.moveDown(bytesPerRow, expand = event.shift)
                    "left" -> buffer.moveLeft(expand = event.shift)
                    "right" -> buffer.moveRight(expand = event.shift)
                    "home" -> moveRowStart(expand = event.shift)
                    "end" -> moveRowEnd(expand = event.shift)
                    "pageup" -> pageMove(-bodyRows, maxRow)
                    "pagedown" -> pageMove(bodyRows, maxRow)
                    "c" -> if (event.ctrl) buffer.copySelection()
                    "x" -> if (event.ctrl) buffer.cutSelection()
                    "v" -> if (event.ctrl) buffer.pasteClipboard()
                    "a" -> if (event.ctrl) buffer.selectAll()
                    "backspace" -> buffer.deleteBackspace()
                    "delete" -> buffer.deleteForward()
                    else -> return false
                }
                ensureCursorVisible(bodyRows, maxRow)
                return scrollRow != prevScroll || buffer.cursorIndex() != prevCursor
            }
        }
        return false
    }

    private fun ensureCursorVisible(bodyRows: Int, maxRow: Int) {
        if (bodyRows <= 0) return
        val cursorRow = buffer.cursorIndex().floorDiv(bytesPerRow)
        if (cursorRow < scrollRow) {
            scrollRow = cursorRow
        } else if (cursorRow >= scrollRow + bodyRows) {
            scrollRow = (cursorRow - bodyRows + 1).coerceAtLeast(0)
        }
        scrollRow = scrollRow.coerceIn(0, maxRow)
    }

    private fun pageMove(deltaRows: Int, maxRow: Int) {
        val newRow = (buffer.cursorIndex().floorDiv(bytesPerRow) + deltaRows).coerceAtLeast(0)
        buffer.moveCursorTo((newRow * bytesPerRow).coerceAtMost(buffer.totalBytes()), expand = false)
        scrollRow = (scrollRow + deltaRows).coerceIn(0, maxRow)
    }

    private fun moveRowStart(expand: Boolean) {
        val rowStart = (buffer.cursorIndex() / bytesPerRow) * bytesPerRow
        buffer.moveCursorTo(rowStart, expand = expand)
    }

    private fun moveRowEnd(expand: Boolean) {
        val rowStart = (buffer.cursorIndex() / bytesPerRow) * bytesPerRow
        val rowEnd = min(rowStart + bytesPerRow - 1, buffer.totalBytes().coerceAtLeast(1) - 1)
        buffer.moveCursorTo(rowEnd + 1, expand = expand) // place cursor after the byte
    }

    private fun handleMouse(event: UIEvent, startSelection: Boolean, extendSelection: Boolean): Boolean {
        val x = event.x ?: return false
        val y = event.y ?: return false
        if (y == 0) return false // header
        val row = scrollRow + (y - 1)
        if (row < 0) return false
        val index = row * bytesPerRow
        if (index >= buffer.totalBytes()) return false
        val sliceSize = min(bytesPerRow, buffer.totalBytes() - index)
        val hexStart = 10
        val asciiStart = hexStart + (bytesPerRow * 3) + 2

        val inHex = x >= hexStart && x < asciiStart
        val inAscii = x >= asciiStart
        val local = when {
            inHex -> ((x - hexStart) / 3).coerceIn(0, bytesPerRow - 1)
            inAscii -> (x - asciiStart).coerceIn(0, bytesPerRow - 1)
            else -> null
        } ?: return false

        val targetIndex = index + local
        if (targetIndex >= buffer.totalBytes() || local >= sliceSize) return false

        when {
            startSelection -> buffer.startSelection(targetIndex)
            extendSelection -> buffer.selectTo(targetIndex)
            else -> buffer.moveCursorTo(targetIndex, expand = false)
        }
        val totalRows = max(1, (buffer.totalBytes() + bytesPerRow - 1) / bytesPerRow)
        val maxRow = max(0, totalRows - 1)
        ensureCursorVisible((event.rows ?: 0) - 1, maxRow)
        return true
    }

    private fun asciiCharFor(b: Byte): String {
        val c = b.toInt() and 0xFF
        return if (c in 32..126) c.toChar().toString() else "."
    }
}
