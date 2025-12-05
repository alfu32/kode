package editor.ui

import editor.mime.MimeTypeResult
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File
import kotlin.math.max

/**
 * Simple hex viewer for binary files. Renders offsets, hex bytes, and ASCII.
 */
class BinaryHexView(
    styleSheet: StyleSheet,
    private val bytesPerRow: Int = 16,
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null
    private var bytes: ByteArray = ByteArray(0)
    private var scrollRow: Int = 0
    private var hexCursorIndex: Int = 0
    private var asciiCursorIndex: Int = 0

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
        bytes = runCatching { File(path).readBytes() }.getOrElse { ByteArray(0) }
        scrollRow = 0
        hexCursorIndex = 0
        asciiCursorIndex = 0
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("code-header").withDefaults()
        val bodyStyle = styleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = styleSheet.getStyle("code-gutter").withDefaults(bodyStyle.fg, bodyStyle.bg)
        val cursorStyle = styleSheet.getStyle("code-cursor")
            .withDefaults(fg = bodyStyle.bg ?: gutterStyle.bg, bg = bodyStyle.fg ?: gutterStyle.fg)

        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[binary]"
            val sizeLabel = "${bytes.size} bytes"
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel · $sizeLabel"
            drawText(0, 0, label.take(cols).padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return
        if (bytes.isEmpty()) return

        val maxIndex = (bytes.size - 1).coerceAtLeast(0)
        hexCursorIndex = hexCursorIndex.coerceIn(0, maxIndex)
        asciiCursorIndex = asciiCursorIndex.coerceIn(0, maxIndex)
        val viewStartIndex = scrollRow * bytesPerRow
        val viewEndIndex = viewStartIndex + (bodyRows * bytesPerRow)
        if (hexCursorIndex !in viewStartIndex until viewEndIndex) {
            hexCursorIndex = viewStartIndex.coerceAtMost(maxIndex)
        }
        if (asciiCursorIndex !in viewStartIndex until viewEndIndex) {
            asciiCursorIndex = viewStartIndex.coerceAtMost(maxIndex)
        }

        canvas.applyStyle(bodyStyle) {
            drawRect(0, 1, cols, bodyRows)
            for (row in 0 until bodyRows) {
                val index = (scrollRow + row) * bytesPerRow
                if (index >= bytes.size) break
                val slice = bytes.copyOfRange(index, minOf(index + bytesPerRow, bytes.size))
                val offset = String.format("%08X", index)
                canvas.applyStyle(gutterStyle) {
                    drawText(0, 1 + row, offset.take(cols).padEnd(10, ' '))
                }
                val hexText = slice.joinToString(" ") { String.format("%02X", it) }
                val asciiText = buildString {
                    slice.forEach { b ->
                        val c = b.toInt() and 0xFF
                        append(if (c in 32..126) c.toChar() else '.')
                    }
                }
                val hexStart = 10
                val asciiStart = hexStart + (bytesPerRow * 3) + 2
                if (hexStart < cols) {
                    drawText(hexStart, 1 + row, hexText.take(max(0, cols - hexStart)))
                }
                if (asciiStart < cols) {
                    drawText(asciiStart, 1 + row, asciiText.take(max(0, cols - asciiStart)))
                }

                // Overlay cursors for hex and ASCII regions
                val rowStartIndex = index
                if (hexCursorIndex in rowStartIndex until rowStartIndex + slice.size) {
                    val local = hexCursorIndex - rowStartIndex
                    val hx = hexStart + (local * 3)
                    val pair = String.format("%02X", slice[local].toInt() and 0xFF)
                    if (hx < cols) {
                        canvas.applyStyle(cursorStyle) {
                            drawText(hx, 1 + row, pair.take(max(0, cols - hx)))
                        }
                    }
                }
                if (asciiCursorIndex in rowStartIndex until rowStartIndex + slice.size) {
                    val local = asciiCursorIndex - rowStartIndex
                    val ax = asciiStart + local
                    if (ax < cols) {
                        val ch = asciiText.getOrNull(local)?.toString() ?: " "
                        canvas.applyStyle(cursorStyle) {
                            drawText(ax, 1 + row, ch)
                        }
                    }
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val totalRows = max(1, (bytes.size + bytesPerRow - 1) / bytesPerRow)
        val maxRow = max(0, totalRows - 1)
        val bodyRows = max(1, (event.rows ?: 0) - 1)
        when (event.kind) {
            "mouse_down" -> {
                return handleClick(event)
            }
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val prev = scrollRow
                scrollRow = (scrollRow - delta).coerceIn(0, maxRow)
                return scrollRow != prev
            }
            "key_down" -> {
                val key = event.key?.lowercase() ?: return false
                val prev = scrollRow
                when (key) {
                    "up" -> scrollRow = (scrollRow - 1).coerceAtLeast(0)
                    "down" -> scrollRow = (scrollRow + 1).coerceAtMost(maxRow)
                    "pageup" -> scrollRow = (scrollRow - bodyRows).coerceAtLeast(0)
                    "pagedown" -> scrollRow = (scrollRow + bodyRows).coerceAtMost(maxRow)
                    "home" -> scrollRow = 0
                    "end" -> scrollRow = maxRow
                    else -> return false
                }
                return scrollRow != prev
            }
        }
        return false
    }

    private fun handleClick(event: UIEvent): Boolean {
        val x = event.x ?: return false
        val y = event.y ?: return false
        if (y == 0) return false // header
        val row = scrollRow + (y - 1)
        if (row < 0) return false
        val index = row * bytesPerRow
        if (index >= bytes.size) return false
        val sliceSize = minOf(bytesPerRow, bytes.size - index)
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
        if (targetIndex >= bytes.size || local >= sliceSize) return false

        hexCursorIndex = targetIndex
        asciiCursorIndex = targetIndex
        return true
    }
}

private inline fun CanvasRenderer.applyStyle(style: StyleSet, block: CanvasRenderer.() -> Unit) {
    style.bg?.let { setBackgroundColor(it.r, it.g, it.b) }
    style.fg?.let { setColor(it.r, it.g, it.b) }
    block()
    resetAttributes()
}

private fun StyleSet.withDefaults(fg: react.Color? = this.fg, bg: react.Color? = this.bg): StyleSet {
    val copy = this.copy()
    if (copy.fg == null) copy.fg = fg
    if (copy.bg == null) copy.bg = bg
    return copy
}
