package editor.lib

import kotlinx.coroutines.runBlocking

/**
 * Renders images using half-block glyphs: bg = top pixel, fg = bottom pixel (per cell).
 * Provides a more square aspect ratio compared to single-pixel glyphs.
 */
class BixelAsciiImageRenderer : AsciiImageRenderer {
    override fun imageToAscii(
        path: String,
        outWidth: Int,
        outHeight: Int,
        grayThreshold: Double,
        scatterThreshold: Double
    ): String = runBlocking {
        val bmp = readKodeBitmap(path)
        AsciiCore.pixelsToBixels(
            outWidth = outWidth,
            outHeight = outHeight,
            srcWidth = bmp.width,
            srcHeight = bmp.height
        ) { x, y ->
            val rgba = bmp.getRgba(x, y)
            val r = rgba.r
            val g = rgba.g
            val b = rgba.b
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
