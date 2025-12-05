package editor.ui

import editor.lib.AsciiImageRenderer
import editor.lib.KorimAsciiImageRenderer
import editor.mime.MimeTypeResult
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import kotlin.math.max
import kotlin.math.min

/**
 * Image viewer using ASCII rendering via Korim.
 */
class ImageViewerView(
    styleSheet: StyleSheet,
    private val asciiRenderer: AsciiImageRenderer = KorimAsciiImageRenderer()
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null
    private var grayThreshold: Double = 0.2
    private var targetWidth: Int = 60
    private var ascii: List<String> = emptyList()
    private var needsRender: Boolean = false

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
        ascii = emptyList()
        needsRender = true
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("code-header").withDefaults()
        val bodyStyle = styleSheet.getStyle("code-body").withDefaults()

        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[image]"
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel"
            drawText(0, 0, label.take(cols).padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return

        canvas.applyStyle(bodyStyle) {
            drawRect(0, 1, cols, bodyRows)
            ensureAscii(cols, bodyRows)
            val sliderWidth = (cols - 2).coerceAtLeast(0)
            val sliderRows = 2
            drawText(1, 1, "Width: ${targetWidth}".padEnd(sliderWidth, ' '))
            drawText(1, 2, "Gray: ${"%.2f".format(grayThreshold)}".padEnd(sliderWidth, ' '))
            val availableRows = bodyRows - sliderRows
            if (availableRows <= 0) return
            ascii.take(availableRows).forEachIndexed { idx, line ->
                drawText(0, 1 + sliderRows + idx, line)
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        when (event.kind) {
            "key_down" -> {
                val key = event.key?.lowercase() ?: return false
                val prevWidth = targetWidth
                val prevGray = grayThreshold
                when (key) {
                    "left" -> targetWidth = (targetWidth - 4).coerceAtLeast(8)
                    "right" -> targetWidth = min(targetWidth + 4, (event.cols ?: targetWidth + 4))
                    "up" -> grayThreshold = (grayThreshold + 0.05).coerceAtMost(1.0)
                    "down" -> grayThreshold = (grayThreshold - 0.05).coerceAtLeast(0.0)
                    else -> return false
                }
                if (prevWidth != targetWidth || prevGray != grayThreshold) {
                    needsRender = true
                    return true
                }
            }
            "mouse_down" -> {
                val x = event.x ?: return false
                val y = event.y ?: return false
                if (y == 1 || y == 2) {
                    val isWidth = y == 1
                    val sliderRange = (event.cols ?: targetWidth + 2) - 2
                    val ratio = (x - 1).toDouble() / sliderRange.toDouble().coerceAtLeast(1.0)
                    if (isWidth) {
                        targetWidth = max(8, (ratio * (sliderRange)).toInt())
                    } else {
                        grayThreshold = ratio.coerceIn(0.0, 1.0)
                    }
                    needsRender = true
                    return true
                }
            }
            "resize" -> {
                needsRender = true
                return true
            }
        }
        return false
    }

    private fun ensureAscii(cols: Int, bodyRows: Int) {
        if (!needsRender || filePath.isEmpty()) return
        val sliderRows = 2
        val availableRows = bodyRows - sliderRows
        if (availableRows <= 0) return
        val width = targetWidth.coerceIn(8, cols.coerceAtLeast(8))
        val height = availableRows.coerceAtLeast(4)
        val rendered = runCatching {
            asciiRenderer.imageToAscii(
                path = filePath,
                outWidth = width,
                outHeight = height,
                grayThreshold = grayThreshold
            )
        }.getOrElse { "[image render failed: ${it.message}]" }
        ascii = rendered.split("\n")
        needsRender = false
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
