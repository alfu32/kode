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

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
        bytes = runCatching { File(path).readBytes() }.getOrElse { ByteArray(0) }
        scrollRow = 0
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("code-header").withDefaults()
        val bodyStyle = styleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = styleSheet.getStyle("code-gutter").withDefaults(bodyStyle.fg, bodyStyle.bg)

        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[binary]"
            val sizeLabel = "${bytes.size} bytes"
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel · $sizeLabel"
            drawText(0, 0, label.take(cols).padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return

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
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val totalRows = max(1, (bytes.size + bytesPerRow - 1) / bytesPerRow)
        val maxRow = max(0, totalRows - 1)
        val bodyRows = max(1, (event.rows ?: 0) - 1)
        when (event.kind) {
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
