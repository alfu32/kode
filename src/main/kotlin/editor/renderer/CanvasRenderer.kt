package editor.renderer

interface CanvasRenderer {
    fun cols(): Int
    fun rows(): Int
    fun clear()
    fun drawRect(x: Int, y: Int, width: Int, height: Int)
    fun drawText(x: Int, y: Int, text: String)
    fun setColor(r: Int, g: Int, b: Int)
    fun setBackgroundColor(r: Int, g: Int, b: Int)
    fun bold(enabled: Boolean)
    fun italic(enabled: Boolean)
    fun underline(enabled: Boolean)
    fun flush()
}
