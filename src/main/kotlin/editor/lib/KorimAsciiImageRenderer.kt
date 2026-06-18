package editor.lib

// KorimAsciiImageRenderer.kt
import kotlinx.coroutines.runBlocking

class KorimAsciiImageRenderer : AsciiImageRenderer {

    override fun imageToAscii(
        path: String,
        outWidth: Int,
        outHeight: Int,
        grayThreshold: Double,
        scatterThreshold: Double
    ): String = runBlocking {
        val bmp = readKodeBitmap(path)

        AsciiCore.pixelsToAscii(
            outWidth = outWidth,
            outHeight = outHeight,
            grayThreshold = grayThreshold,
            scatterThreshold = scatterThreshold,
            srcWidth = bmp.width,
            srcHeight = bmp.height
        ) { x, y ->
            // Korim Bitmap32.getRgba() usually returns 0xRRGGBBAA
            val rgba = bmp.getRgba(x, y)

            val r = rgba.r
            val g = rgba.g
            val b = rgba.b

            // pack into ARGB, alpha forced to 0xFF (we ignore real alpha)
            // rgba.value or (0xFF shl 24)
            (0xFF shl 24) or (r shl 16) or (g shl 8) or b
        }
    }
}
