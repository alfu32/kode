package react.renderer

import react.Color
import react.ContentBox
import react.StyleSet
import react.UIEvent

/* =====================================================================
   CANVAS RENDERER INTERFACE WITH EVENT POLLING
   ===================================================================== */
interface CanvasRenderer {
    fun cols(): Int
    fun rows(): Int
    fun clear()
    fun setColor(r: Int, g: Int, b: Int)
    fun setBackgroundColor(r: Int, g: Int, b: Int)
    fun bold(enabled: Boolean)
    fun italic(enabled: Boolean)
    fun underline(enabled: Boolean)
    fun blink(enabled: Boolean)
    fun drawRect(x: Int, y: Int, width: Int, height: Int)
    fun drawText(x: Int, y: Int, text: String)
    fun setCursorPosition(x: Int, y: Int)
    fun flush()

    // Event API
    fun pollEvent(): UIEvent?
    fun tryPollEvent(): UIEvent?

    /* ============================================================
   Lifecycle / Terminal Control
   ============================================================ */
    fun enableMouseTracking()
    fun disableMouseTracking()

    fun hideCursor()
    fun showCursor()
    fun resetAttributes()

    fun isRunning(): Boolean
    fun requestExit()
    fun shutdown()

    fun withStyle(style: StyleSet, block: CanvasRenderer.() -> Unit) {
        val decorations = style.textDecoration
            ?.lowercase()
            ?.split(Regex("\\s+"))
            ?.filter { it.isNotBlank() }
            .orEmpty()
        val wantBold = "bold" in decorations || "heavy" in decorations
        val wantItalic = "italic" in decorations
        val wantUnderline = "underline" in decorations

        // Reset first to avoid decoration bleed across nested calls, then apply in a fixed order.
        resetAttributes()
        bold(wantBold)
        italic(wantItalic)
        underline(wantUnderline)
        style.bg?.let { setBackgroundColor(it.r, it.g, it.b) }
        style.fg?.let { setColor(it.r, it.g, it.b) }
        block()
        resetAttributes()
    }
}

class Draw(val renderer: CanvasRenderer) {
    constructor(
        renderer: CanvasRenderer,
        build: Draw.() -> Unit
    ) : this(renderer) {
        this.build()
    }
    private val ssDefault=listOf(
        Color.from(0x333333),
        Color.from(0xAAAAAA),
    )

    fun rect(box: ContentBox, style: String="bg:#AAAAAA;fg:#333333") {
        val ss= StyleSet.parse(style)
        val fg=ss.fg!!
        val bg=ss.bg!!
        renderer.setColor(fg.r, fg.g, fg.b)
        renderer.setBackgroundColor(bg.r, bg.g, bg.b)
        renderer.drawRect(box.left, box.top, box.right - box.left + 1, box.bottom - box.top + 1)
        renderer.resetAttributes()
    }
    fun text(x:Int,y: Int, text: String, style: String="bg:#AAAAAA;fg:#333333") {
        val ss= StyleSet.parse(style)
        val fg=ss.fg!!
        val bg=ss.bg!!
        renderer.setColor(fg.r, fg.g, fg.b)
        renderer.setBackgroundColor(bg.r, bg.g, bg.b)
        renderer.drawText(x,y,text)
        renderer.resetAttributes()
    }
    fun meter(x:Int,y: Int, value:Int, text: String, style: String="bg:#AAAAAA;fg:#333333") {
        val ss= StyleSet.parse(style)
        val tLen = text.length.coerceAtLeast(10)
        val pos=value.times(tLen).div(100).coerceAtLeast(1).coerceAtMost(tLen-1)
        val text1=text.take(pos)
        val text2=text.drop(pos+1)
        val fg=ss.fg!!
        val bg=ss.bg!!
        renderer.setColor(fg.r, fg.g, fg.b)
        renderer.setBackgroundColor(bg.r, bg.g, bg.b)
        renderer.underline(true)
        renderer.bold(true)
        // renderer.setBackgroundColor(bg1.r, bg1.g, bg1.b)
        renderer.drawText(x,y,text1)
        renderer.underline(false)
        renderer.drawText(x+pos,y,text2)
        renderer.resetAttributes()
    }
}
