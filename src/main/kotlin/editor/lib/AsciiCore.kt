package editor.lib

// AsciiCore.kt
import kotlin.math.roundToInt

object AsciiCore {
    // Dark → light
    private val RAMP = charArrayOf(' ', '.', ':', '-', '=', '+', '*', '#', '%', '@')

    /**
     * Generic pixels → colored ASCII.
     *
     * @param outWidth  output width in characters
     * @param outHeight output height in rows
     * @param grayThreshold 0.0–1.0, pushes darker/lighter mapping
     * @param srcWidth  source image width in pixels
     * @param srcHeight source image height in pixels
     * @param pixelAt   function returning ARGB (0xAARRGGBB) for source coords
     */
    fun pixelsToAscii(
        outWidth: Int,
        outHeight: Int,
        grayThreshold: Double = 0.0,
        srcWidth: Int,
        srcHeight: Int,
        pixelAt: (x: Int, y: Int) -> Int
    ): String {
        require(outWidth > 0 && outHeight > 0) { "outWidth/outHeight must be > 0" }
        require(srcWidth > 0 && srcHeight > 0) { "srcWidth/srcHeight must be > 0" }

        val sb = StringBuilder()
        val scaleX = srcWidth.toDouble() / outWidth.toDouble()
        val scaleY = srcHeight.toDouble() / outHeight.toDouble()
        val t = grayThreshold.coerceIn(0.0, 1.0)

        var prevR = -1
        var prevG = -1
        var prevB = -1

        for (y in 0 until outHeight) {
            val sy = (y * scaleY).toInt().coerceIn(0, srcHeight - 1)
            for (x in 0 until outWidth) {
                val sx = (x * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val argb = pixelAt(sx, sy)

                val r = argb shr 16 and 0xFF
                val g = argb shr 8 and 0xFF
                val b = argb and 0xFF

                // relative luminance [0,1]
                val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255.0

                val adjusted = if (t > 0.0) {
                    ((lum - t) / (1.0 - t)).coerceIn(0.0, 1.0)
                } else {
                    lum
                }

                val idx = ((RAMP.size - 1) * adjusted)
                    .roundToInt()
                    .coerceIn(0, RAMP.size - 1)

                // only emit color when it changes
                if (r != prevR || g != prevG || b != prevB) {
                    sb.append("\u001B[38;2;$r;$g;${b}m")
                    prevR = r
                    prevG = g
                    prevB = b
                }

                sb.append(RAMP[idx])
            }

            sb.append("\u001B[0m\n")
            prevR = -1
            prevG = -1
            prevB = -1
        }

        sb.append("\u001B[0m")
        return sb.toString()
    }
}
