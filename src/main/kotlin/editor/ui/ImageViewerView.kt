package editor.ui

import editor.lib.AsciiImageRenderer
import editor.lib.BixelAsciiImageRenderer
import editor.lib.QuadxelAsciiImageRenderer
import editor.lib.BrailleAsciiImageRenderer
import editor.lib.readKodeBitmap
import editor.mime.MimeTypeResult
import kotlinx.coroutines.runBlocking
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import react.Color
import kotlin.math.min

/**
 * Image viewer using ASCII rendering via Korim.
 */
class ImageViewerView(
    styleSheet: StyleSheet
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null
    private var grayThreshold: Double = 0.2
    private var scatterThreshold: Double = 1600.0
    private var targetWidth: Int = 60
    private var ascii: List<String> = emptyList()
    private var needsRender: Boolean = false
    private var imageDirty: Boolean = false
    private var srcWidth: Int = 0
    private var srcHeight: Int = 0
    private var useQuartGlyph: Boolean = true
    private var useDemiGlyph: Boolean = false
    private var useBraille: Boolean = false
    private val sliders = listOf(
        SliderControl(
            styleSheet = styleSheet,
            label = "Width",
            minVal = 8.0,
            maxVal = 200.0,
            onChange = { needsRender = true },
            onRelease = { value ->
                targetWidth = value.toInt().coerceAtLeast(8)
                imageDirty = true
                needsRender = true
            }
        ),
        SliderControl(
            styleSheet = styleSheet,
            label = "Gray",
            minVal = 0.0,
            maxVal = 1.0,
            onChange = { needsRender = true },
            onRelease = { value ->
                grayThreshold = value.coerceIn(0.0, 1.0)
                imageDirty = true
                needsRender = true
            }
        ),
        SliderControl(
            styleSheet = styleSheet,
            label = "Contrast",
            minVal = 0.0,
            maxVal = 8000.0,
            onChange = { needsRender = true },
            onRelease = { value ->
                scatterThreshold = value
                imageDirty = true
                needsRender = true
            }
        )
    )

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
        ascii = emptyList()
        loadMetadata()
        needsRender = true
        imageDirty = true
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("image-header").withDefaults()
        val bodyStyle = styleSheet.getStyle("image-body").withDefaults()
        val buttonStyle = styleSheet.getStyle("button").withDefaults()
        val defaultFg = bodyStyle.fg ?: Color(0, 0, 0)
        val defaultBg = bodyStyle.bg ?: Color(255, 255, 255)

        canvas.withStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[image]"
            val modeLabel = when {
                useQuartGlyph -> "[quart-glyph]"
                useDemiGlyph -> "[demi-glyph]"
                useBraille -> "[braille]"
                else -> "[bixel]"
            }
            val label = "${filePath.ifEmpty { "[no file]" }} $mimeLabel $modeLabel"
            drawText(0, 0, label.take(cols).padEnd(cols, ' '))
        }

        val bodyRows = (rows - 1).coerceAtLeast(0)
        if (bodyRows == 0) return

        canvas.withStyle(bodyStyle) {
            drawRect(0, 1, cols, bodyRows)
            ensureAscii(cols, bodyRows)
            val visibleSliders = visibleSliders()
            val sliderRows = visibleSliders.size
            if (!visibleSliders[0].isDragging()) visibleSliders[0].setValueSilently(targetWidth.toDouble())
            visibleSliders.forEachIndexed { idx, slider ->
                slider.renderAt(canvas, 1 + idx, cols)
            }
            lastButtonRegion = null
            val toggleLabel = when {
                useQuartGlyph -> "[ demi ]"
                useDemiGlyph -> "[ braille ]"
                useBraille -> "[ bixel ]"
                else -> "[ quart ]"
            }
            lastButtonRegion = renderButton(canvas, sliderRows, cols, buttonStyle, toggleLabel)
            val availableRows = bodyRows - sliderRows
            if (availableRows <= 0) return@withStyle
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
                when (key) {
                    "left" -> targetWidth = (targetWidth - 4).coerceAtLeast(8)
                    "right" -> targetWidth = min(targetWidth + 4, (event.cols ?: targetWidth + 4))
                    else -> return false
                }
                needsRender = true
                return true
            }
            "mouse_down" -> {
                val x = event.x ?: return false
                val y = event.y ?: return false
                lastButtonRegion?.let {
                    if (it.contains(x, y)) {
                        when {
                            useQuartGlyph -> {
                                useQuartGlyph = false
                                useDemiGlyph = true
                            }
                            useDemiGlyph -> {
                                useDemiGlyph = false
                                useBraille = true
                            }
                            useBraille -> {
                                useBraille = false
                            }
                            else -> {
                                useQuartGlyph = true
                            }
                        }
                        imageDirty = true
                        needsRender = true
                        return true
                    }
                }
                visibleSliders().forEach { slider ->
                    if (slider.onMouseDown(x, y)) return true
                }
            }
            "mouse_move" -> {
                visibleSliders().forEach { slider ->
                    if (slider.onMouseMove(event.x, event.y)) {
                        needsRender = true
                        return true
                    }
                }
            }
            "mouse_up" -> {
                var released = false
                visibleSliders().forEach { slider ->
                    if (slider.onMouseUp()) {
                        released = true
                    }
                }
                if (released) {
                    needsRender = true
                    return true
                }
            }
        }
        return false
    }

    private data class ButtonRegion(val row: Int, val start: Int, val end: Int) {
        fun contains(x: Int, y: Int): Boolean = y == row && x in start..end
    }

    private var lastButtonRegion: ButtonRegion? = null

    private fun renderButton(canvas: CanvasRenderer, row: Int, cols: Int, style: StyleSet, label: String): ButtonRegion? {
        val btnX = (cols - label.length - 1).coerceAtLeast(1)
        canvas.withStyle(style) {
            drawText(btnX, row, label.take(cols - btnX))
        }
        return ButtonRegion(row, btnX, (btnX + label.length - 1).coerceAtMost(cols - 1))
    }

    private fun ensureAscii(cols: Int, bodyRows: Int) {
        if (!imageDirty || filePath.isEmpty()) return
        val sliderRows = visibleSliders().size
        val availableRows = bodyRows - sliderRows
        if (availableRows <= 0) return
        val width = min(targetWidth, cols.coerceAtLeast(8))
        val height = computeHeight(width)
        val rendered = runCatching {
            val renderer: AsciiImageRenderer = when {
                useQuartGlyph -> QuadxelAsciiImageRenderer()
                useDemiGlyph -> BixelAsciiImageRenderer()
                useBraille -> BrailleAsciiImageRenderer()
                else -> BixelAsciiImageRenderer()
            }
            renderer.imageToAscii(
                path = filePath,
                outWidth = width,
                outHeight = height,
                grayThreshold = grayThreshold,
                scatterThreshold = scatterThreshold
            )
        }.getOrElse { "[image render failed: ${it.message}]" }
        ascii = rendered.split("\n")
        imageDirty = false
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
                    var i = 0
                    while (i < codes.size) {
                        when (codes[i]) {
                            "38" -> if (codes.getOrNull(i + 1) == "2") {
                                val r = codes.getOrNull(i + 2)?.trimEnd('m')?.toIntOrNull() ?: defaultFg.r
                                val g = codes.getOrNull(i + 3)?.trimEnd('m')?.toIntOrNull() ?: defaultFg.g
                                val b = codes.getOrNull(i + 4)?.trimEnd('m')?.toIntOrNull() ?: defaultFg.b
                                fg = react.Color(r, g, b)
                                i += 5
                                continue
                            }
                            "48" -> if (codes.getOrNull(i + 1) == "2") {
                                val r = codes.getOrNull(i + 2)?.trimEnd('m')?.toIntOrNull() ?: defaultBg.r
                                val g = codes.getOrNull(i + 3)?.trimEnd('m')?.toIntOrNull() ?: defaultBg.g
                                val b = codes.getOrNull(i + 4)?.trimEnd('m')?.toIntOrNull() ?: defaultBg.b
                                bg = react.Color(r, g, b)
                                i += 5
                                continue
                            }
                            "0" -> {
                                fg = defaultFg
                                bg = defaultBg
                                canvas.setBackgroundColor(defaultBg.r, defaultBg.g, defaultBg.b)
                            }
                        }
                        i++
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

    private fun computeHeight(width: Int): Int {
        if (srcWidth > 0 && srcHeight > 0) {
            val ratioHeight = (srcHeight.toDouble() * width.toDouble() / srcWidth.toDouble() / 2.0)
            return ratioHeight.toInt().coerceAtLeast(4)
        }
        return 4
    }

    private fun visibleSliders(): List<SliderControl> = listOf(sliders[0])

    private fun loadMetadata() {
        if (filePath.isEmpty()) return
        runCatching {
            runBlocking {
                val bmp = readKodeBitmap(filePath)
                srcWidth = bmp.width
                srcHeight = bmp.height
            }
        }.onFailure {
            srcWidth = 0
            srcHeight = 0
        }
    }
}
