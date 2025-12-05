package editor.ui

import editor.mime.MimeTypeResult
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

/**
 * Placeholder image viewer. Hook up actual rendering when available.
 */
class ImageViewerView(
    styleSheet: StyleSheet
) : BaseComponent(styleSheet) {

    private var filePath: String = ""
    private var mime: String? = null

    fun openFile(path: String, detection: MimeTypeResult? = null) {
        filePath = path
        mime = detection?.mime
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
            if (bodyRows > 0) {
                val msg = "Image viewer placeholder"
                drawText(1, 1, msg.take(cols - 2))
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean = false
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
