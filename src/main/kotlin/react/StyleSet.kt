package react

import kotlin.text.get

data class StyleSet(
    var top: Int? = null,
    var left: Int? = null,
    var bottom: Int? = null,
    var right: Int? = null,
    var bg: Color? = null,
    var fg: Color? = null,
    var textDecoration: String? = null,
    var borderSet: String? = null,
    var lineSet: String? = null
) {
    var extended = mutableMapOf<String,String>()
    var styleName=""
    fun mergeFrom(src: StyleSet) {
        if (src.top != null) top = src.top
        if (src.left != null) left = src.left
        if (src.bottom != null) bottom = src.bottom
        if (src.right != null) right = src.right
        if (src.bg != null) bg = src.bg
        if (src.fg != null) fg = src.fg
        if (src.textDecoration != null) textDecoration = src.textDecoration
        if (src.borderSet != null) borderSet = src.borderSet
        if (src.lineSet != null) lineSet = src.lineSet
        src.extended.forEach { (key,value) ->
            if(src[key] != null) extended[key] = value
        }
    }
    fun offset(x:Int, y:Int): StyleSet {
        if(this.top != null) {
            this.top = this.top!! + y
        }
        if(this.left != null) {
            this.left = this.left!! + x
        }
        if(this.bottom != null) {
            this.bottom = this.bottom!! + y
        }
        if(this.right != null) {
            this.right = this.right!! + x
        }
        return this
    }

    fun boundingBox() = ContentBox(top=top ?: 0, left=left ?: 0, bottom=bottom ?: 0, right=right ?: 0)

    fun merged(src: StyleSet): StyleSet =
        this.copy().also { it.mergeFrom(src) }
    operator fun plus(other: StyleSet): StyleSet {
        return this.merged(other)
    }
    operator fun get(key: String): String? {
        return this.extended[key]
    }
    companion object {
        fun parse(def: String): StyleSet {
            val style = StyleSet()
            val entries = def.split(';', '\n', '\r')
            for (raw in entries) {
                val line = raw.trim()
                if (line.isEmpty()) continue
                val parts = line.split(':', limit = 2)
                if (parts.size < 2) continue
                val key = parts[0].trim()
                val valueRaw = parts[1].trim()
                if (valueRaw.isEmpty() || valueRaw == "none") continue
                style.extended[key] = valueRaw
                when (key) {
                    "top"    -> style.top = valueRaw.toIntOrNull()
                    "left"   -> style.left = valueRaw.toIntOrNull()
                    "bottom" -> style.bottom = valueRaw.toIntOrNull()
                    "right"  -> style.right = valueRaw.toIntOrNull()
                    "bg"     -> style.bg = parseColor(valueRaw)
                    "fg"     -> style.fg = parseColor(valueRaw)
                    "text-decoration" -> style.textDecoration = valueRaw
                    "border-set"      -> style.borderSet = valueRaw
                    "line-set"        -> style.lineSet = valueRaw
                }
            }
            return style
        }

        private fun parseColor(raw: String): Color? {
            return Color.from(raw)
        }
    }
}

