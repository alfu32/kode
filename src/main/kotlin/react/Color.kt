package react

data class Color(val r: Int, val g: Int, val b: Int) {
    companion object {
        fun from(raw: String): Color? {
            var s = raw.trim()
            if (s.startsWith("#")) s = s.substring(1)
            if (s.length != 6) return null
            val r = s.substring(0, 2).toIntOrNull(16) ?: return null
            val g = s.substring(2, 4).toIntOrNull(16) ?: return null
            val b = s.substring(4, 6).toIntOrNull(16) ?: return null
            return Color(r, g, b)
        }
        fun from(raw: Int): Color {
            val r = raw shr 16 and 0xFF
            val g = raw shr 8 and 0xFF
            val b = raw shr 0 and 0xFF
            return Color(r, g, b)
        }
    }
}