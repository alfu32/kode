package react

/* =====================================================================
   UNIFIED EVENT STRUCTURE
   ===================================================================== */
data class UIEvent(
    val kind: String,           // e.g. "mouse_down", "key_down", "resize"
    val x: Int? = null,         // mouse coordinates
    val y: Int? = null,
    val relX: Int? = null,         // mouse coordinates
    val relY: Int? = null,
    val button: Int? = null,    // mouse button
    val scrollDelta: Int? = null,
    val key: String? = null,    // keyboard key
    val ctrl: Boolean = false,
    val alt: Boolean = false,
    val shift: Boolean = false,
    val meta: Boolean = false,
    val focusId: String? = null,
    val cols: Int? = null,      // resize cols
    val rows: Int? = null,       // resize rows
    val raw: String = "",
    val timeMs: Long? = null       // optional timestamp for synthetic events (e.g. animation_frame)
){
    fun alterCopy(conf:UIEvent)= UIEvent(
            kind= this.kind,
            x= conf.x ?: this.x,
            y= conf.y ?: this.y,
            relX= conf.relX ?: this.relX,
            relY= conf.relY ?: this.relY,
            button= conf.button ?: this.button,
            scrollDelta= conf.scrollDelta ?: this.scrollDelta,
            key= conf.key ?: this.key,
            ctrl = conf.ctrl || this.ctrl,
            alt = conf.alt || this.alt,
            shift = conf.shift || this.shift,
            meta = conf.meta || this.meta,
            focusId= conf.focusId ?: this.focusId,
            cols= conf.cols ?: this.cols,
            rows= conf.rows ?: this.rows,
            raw = if (conf.raw.isNotEmpty()) conf.raw else this.raw,
            timeMs = conf.timeMs ?: this.timeMs
        )
}
/* =====================================================================
   DOM MODEL WITH EXPLICIT EVENT CALLBACKS
   ===================================================================== */
typealias UIEventHandler = (UIEvent) -> Unit
