package editor.lib

import korlibs.image.format.readBitmap
import korlibs.io.file.std.localVfs
import kotlinx.coroutines.runBlocking

class BrailleAsciiImageRenderer : AsciiImageRenderer {
    override fun imageToAscii(
        path: String,
        outWidth: Int,
        outHeight: Int,
        grayThreshold: Double
    ): String = runBlocking {
        val bmp = localVfs(path).readBitmap()
        AsciiCore.pixelsToBraille(
            outWidth = outWidth,
            outHeight = outHeight,
            grayThreshold = grayThreshold,
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
