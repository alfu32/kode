package editor.ui

import editor.lib.AsciiImageRenderer
import editor.lib.BrailleAsciiImageRenderer
import editor.lib.KorimAsciiImageRenderer
import editor.mime.MimeTypeResult
import korlibs.image.format.readBitmap
import korlibs.io.file.std.localVfs
import kotlinx.coroutines.runBlocking
import react.BaseComponent
import react.Color
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
    private val asciiRenderer: AsciiImageRenderer = KorimAsciiImageRenderer(),
    private val brailleRenderer: AsciiImageRenderer = BrailleAsciiImageRenderer()
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null
    private var grayThreshold: Double = 0.2
    private var targetWidth: Int = 60
    private var ascii: List<String> = emptyList()
    private var needsRender: Boolean = false
    private var srcWidth: Int = 0
    private var srcHeight: Int = 0
    private var useBraille: Boolean = false

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
        ascii = emptyList()
        loadMetadata()
        needsRender = true
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("image-header").withDefaults()
        val bodyStyle = styleSheet.getStyle("image-body").withDefaults()
        val buttonStyle = styleSheet.getStyle("button").withDefaults()
        val defaultFg = bodyStyle.fg ?: Color(0, 0, 0)
        val defaultBg = bodyStyle.bg ?: Color(255, 255, 255)

        canvas.applyStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[image]"
            val modeLabel = if (useBraille) "[braille]" else "[blocks]"
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel $modeLabel"
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
            drawText(1, 2, "Gray: ${"%.2f".format(grayThreshold)}  Mode: ${if (useBraille) "Braille" else "Blocks"}".padEnd(sliderWidth, ' '))
            // Button on the right to toggle mode
            val buttonLabel = "[ Toggle mode ]"
            val btnX = (cols - buttonLabel.length - 1).coerceAtLeast(1)
            canvas.applyStyle(buttonStyle) {
                drawText(btnX, 1, buttonLabel.take(cols - btnX))
            }
            val availableRows = bodyRows - sliderRows
            if (availableRows <= 0) return
            ascii.take(availableRows).forEachIndexed { idx, line ->
                renderAnsiLine(this, 0, 1 + sliderRows + idx, line, cols, defaultFg, defaultBg)
            }
            resetAttributes()
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
                    "b" -> useBraille = !useBraille
                    else -> return false
                }
                if (prevWidth != targetWidth || prevGray != grayThreshold) {
                    needsRender = true
                    return true
                }
                if (event.key?.lowercase() == "b") {
                    needsRender = true
                    return true
                }
            }
            "mouse_down" -> {
                val x = event.x ?: return false
                val y = event.y ?: return false
                if (y == 1 || y == 2) {
                    // Check button hit
                    val buttonLabel = "[ Toggle mode ]"
                    val btnX = (event.cols ?: buttonLabel.length) - buttonLabel.length - 1
                    if (x >= btnX && x < btnX + buttonLabel.length && y == 1) {
                        useBraille = !useBraille
                        needsRender = true
                        return true
                    }
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
        val width = min(targetWidth, cols.coerceAtLeast(8))
        val height = computeHeight(width, availableRows)
        val rendered = runCatching {
            val renderer = if (useBraille) brailleRenderer else asciiRenderer
            renderer.imageToAscii(
                path = filePath,
                outWidth = width,
                outHeight = height,
                grayThreshold = grayThreshold
            )
        }.getOrElse { "[image render failed: ${it.message}]" }
        ascii = rendered.split("\n")
        needsRender = false
    }

    private fun renderAnsiLine(
        canvas: CanvasRenderer,
        startX: Int,
        y: Int,
        line: String,
        maxCols: Int,
        defaultFg: Color,
        defaultBg: Color = Color(255, 255, 255)
    ) {
        var fg: react.Color? = defaultFg
        var bg: react.Color? = defaultBg
        var col = 0
        val sb = StringBuilder()
        var idx = 0
        canvas.setBackgroundColor(defaultBg.r, defaultBg.g, defaultBg.b)
        fun flush() {
            if (sb.isNotEmpty()) {
                bg?.let { canvas.setBackgroundColor(it.r, it.g, it.b) }
                fg?.let { canvas.setColor(it.r, it.g, it.b) }
                canvas.drawText(startX + col - sb.length, y, sb.toString())
                sb.clear()
            }
        }
        while (idx < line.length && col < maxCols) {
            val ch = line[idx]
            if (ch == '\u001B' && idx + 1 < line.length && line[idx + 1] == '[') {
                val end = line.indexOf('m', idx + 2)
                if (end > idx) {
                    flush()
                    val codes = line.substring(idx + 2, end).split(';')
                    when {
                        codes.size >= 5 && codes[0] == "38" && codes[1] == "2" -> {
                            val r = codes.getOrNull(2)?.toIntOrNull() ?: defaultFg.r
                            val g = codes.getOrNull(3)?.toIntOrNull() ?: defaultFg.g
                            val b = codes.getOrNull(4)?.toIntOrNull() ?: defaultFg.b
                            fg = react.Color(r, g, b)
                        }
                        codes.size >= 5 && codes[0] == "48" && codes[1] == "2" -> {
                            val r = codes.getOrNull(2)?.toIntOrNull() ?: defaultBg.r
                            val g = codes.getOrNull(3)?.toIntOrNull() ?: defaultBg.g
                            val b = codes.getOrNull(4)?.toIntOrNull() ?: defaultBg.b
                            bg = react.Color(r, g, b)
                        }
                        codes.size == 1 && codes[0] == "0" -> {
                            fg = defaultFg
                            bg = defaultBg
                            canvas.setBackgroundColor(defaultBg.r, defaultBg.g, defaultBg.b)
                        }
                    }
                    idx = end + 1
                    continue
                }
            }
            sb.append(ch)
            col++
            if (sb.length >= 32) {
                flush()
            }
            idx++
        }
        flush()
        canvas.resetAttributes()
    }

    private fun computeHeight(width: Int, maxRows: Int): Int {
        if (srcWidth > 0 && srcHeight > 0) {
            // Adjust for terminal cell aspect (~2:1 height vs width).
            val ratioHeight = (srcHeight.toDouble() * width.toDouble() / srcWidth.toDouble() / 2.0)
            return ratioHeight.toInt().coerceIn(4, maxRows)
        }
        return maxRows.coerceAtLeast(4)
    }

    private fun loadMetadata() {
        if (filePath.isEmpty()) return
        runCatching {
            runBlocking {
                val bmp = localVfs(filePath).readBitmap()
                srcWidth = bmp.width
                srcHeight = bmp.height
            }
        }.onFailure {
            srcWidth = 0
            srcHeight = 0
        }
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
