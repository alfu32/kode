package editor.lib

import korlibs.image.bitmap.Bitmap
import korlibs.image.format.BMP
import korlibs.image.format.DDS
import korlibs.image.format.GIF
import korlibs.image.format.ImageFormats
import korlibs.image.format.KRA
import korlibs.image.format.PNG
import korlibs.image.format.PSD
import korlibs.image.format.SVG
import korlibs.image.format.TGA
import korlibs.image.format.readBitmap
import korlibs.io.file.std.localVfs

private val kodeImageFormats = ImageFormats(
    PNG,
    BMP,
    GIF,
    TGA,
    DDS,
    PSD,
    KRA,
    SVG
)

suspend fun readKodeBitmap(path: String): Bitmap =
    localVfs(path).readBitmap(kodeImageFormats)
