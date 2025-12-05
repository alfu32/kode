package editor.renderer

interface CanvasRenderer {
    fun cols(): Int
    fun rows(): Int
    fun resize(cols: Int, rows: Int)
    fun clear()
    fun drawRect(x: Int, y: Int, width: Int, height: Int)
    fun drawText(x: Int, y: Int, text: String)
    fun setColor(r: Int, g: Int, b: Int)
    fun setBackgroundColor(r: Int, g: Int, b: Int)
    fun bold(enabled: Boolean)
    fun italic(enabled: Boolean)
    fun underline(enabled: Boolean)
    fun flush()
    fun resetAttributes()
    fun hideCursor()
    fun showCursor()
    fun enableMouseTracking()
    fun disableMouseTracking()
    fun enterAlternateScreen()
    fun leaveAlternateScreen()
    fun shutdown()
}
