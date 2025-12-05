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

        for (y in 0 until outHeight) {
            for (x in 0 until outWidth) {
                val sxStart = (x * scaleX).toInt().coerceIn(0, srcWidth - 1)
                val sxEnd = ((x + 1) * scaleX).toInt().coerceIn(sxStart, srcWidth - 1)
                val syStart = (y * scaleY).toInt().coerceIn(0, srcHeight - 1)
                val syEnd = ((y + 1) * scaleY).toInt().coerceIn(syStart, srcHeight - 1)

                var rSum = 0
                var gSum = 0
                var bSum = 0
                var lumSum = 0.0
                var lumSqSum = 0.0
                var samples = 0

                for (yy in syStart..syEnd) {
                    for (xx in sxStart..sxEnd) {
                        val argb = pixelAt(xx, yy)
                        val r = argb shr 16 and 0xFF
                        val g = argb shr 8 and 0xFF
                        val b = argb and 0xFF
                        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b)
                        rSum += r
                        gSum += g
                        bSum += b
                        lumSum += lum
                        lumSqSum += lum * lum
                        samples++
                    }
                }

                val count = samples.coerceAtLeast(1)
                val avgR = rSum / count
                val avgG = gSum / count
                val avgB = bSum / count
                val meanLum = lumSum / count
                val variance = (lumSqSum / count) - (meanLum * meanLum)
                val adjustedLum = if (t > 0.0) {
                    ((meanLum / 255.0 - t) / (1.0 - t)).coerceIn(0.0, 1.0)
                } else {
                    (meanLum / 255.0).coerceIn(0.0, 1.0)
                }
                val idx = ((RAMP.size - 1) * adjustedLum)
                    .roundToInt()
                    .coerceIn(0, RAMP.size - 1)

                // Contrast only if the region is visually scattered.
                val scatterThreshold = 1600.0 // roughly variance of luminance > (40^2)
                val fgColor = if (variance > scatterThreshold) {
                    val factor = if (meanLum > 128) 0.6 else 1.4
                    val fr = (avgR * factor).roundToInt().coerceIn(0, 255)
                    val fg = (avgG * factor).roundToInt().coerceIn(0, 255)
                    val fb = (avgB * factor).roundToInt().coerceIn(0, 255)
                    Triple(fr, fg, fb)
                } else {
                    Triple(avgR, avgG, avgB)
                }

                sb.append("\u001B[48;2;$avgR;$avgG;$avgB;38;2;${fgColor.first};${fgColor.second};${fgColor.third}m")
                sb.append(RAMP[idx])
            }

            sb.append("\u001B[0m\n")
        }

        sb.append("\u001B[0m")
        return sb.toString()
    }

    /**
     * Render to Unicode Braille characters (2x4 dots per cell).
     * Each cell represents a 2x4 block of pixels; color is averaged per cell.
     */
    fun pixelsToBraille(
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
        val targetPixelW = outWidth * 2
        val targetPixelH = outHeight * 4
        val scaleX = srcWidth.toDouble() / targetPixelW.toDouble()
        val scaleY = srcHeight.toDouble() / targetPixelH.toDouble()
        val t = grayThreshold.coerceIn(0.0, 1.0)

        fun bitFor(dx: Int, dy: Int): Int {
            return when (dy) {
                0 -> if (dx == 0) 0x01 else 0x08   // dots 1,4
                1 -> if (dx == 0) 0x02 else 0x10   // dots 2,5
                2 -> if (dx == 0) 0x04 else 0x20   // dots 3,6
                else -> if (dx == 0) 0x40 else 0x80 // dots 7,8
            }
        }

        for (cy in 0 until outHeight) {
            val baseY = cy * 4
            for (cx in 0 until outWidth) {
                val baseX = cx * 2

                var mask = 0
                var rSum = 0
                var gSum = 0
                var bSum = 0
                var samples = 0
                var lumSum = 0.0
                var lumSqSum = 0.0

                for (dy in 0 until 4) {
                    val sy = ( (baseY + dy) * scaleY ).toInt().coerceIn(0, srcHeight - 1)
                    for (dx in 0 until 2) {
                        val sx = ( (baseX + dx) * scaleX ).toInt().coerceIn(0, srcWidth - 1)
                        val argb = pixelAt(sx, sy)
                        val r = argb shr 16 and 0xFF
                        val g = argb shr 8 and 0xFF
                        val b = argb and 0xFF
                        val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b)
                        val adjusted = if (t > 0.0) ((lum / 255.0 - t) / (1.0 - t)).coerceIn(0.0, 1.0) else (lum / 255.0)
                        if (adjusted > 0.5) {
                            mask = mask or bitFor(dx, dy)
                        }
                        rSum += r
                        gSum += g
                        bSum += b
                        lumSum += lum
                        lumSqSum += lum * lum
                        samples++
                    }
                }

                val avgR = if (samples > 0) rSum / samples else 255
                val avgG = if (samples > 0) gSum / samples else 255
                val avgB = if (samples > 0) bSum / samples else 255
                val meanLum = lumSum / samples.coerceAtLeast(1)
                val variance = (lumSqSum / samples.coerceAtLeast(1)) - (meanLum * meanLum)

                val scatterThreshold = 1600.0
                val fgColor = if (variance > scatterThreshold) {
                    val factor = if (meanLum > 128) 0.6 else 1.4
                    val fr = (avgR * factor).roundToInt().coerceIn(0, 255)
                    val fg = (avgG * factor).roundToInt().coerceIn(0, 255)
                    val fb = (avgB * factor).roundToInt().coerceIn(0, 255)
                    Triple(fr, fg, fb)
                } else {
                    Triple(avgR, avgG, avgB)
                }

                // Emit bg (average) and fg (contrast) per cell.
                sb.append("\u001B[48;2;$avgR;$avgG;$avgB;38;2;${fgColor.first};${fgColor.second};${fgColor.third}m")
                sb.append((0x2800 + mask).toChar())
            }
            sb.append("\u001B[0m\n")
        }

        sb.append("\u001B[0m")
        return sb.toString()
    }
}
