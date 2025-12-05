package editor.lib

// AsciiImageRenderer.kt
interface AsciiImageRenderer {
    /**
     * @param path          image file path (png/jpeg/gif/webp/...)
     * @param outWidth      target width in characters
     * @param outHeight     target height in rows
     * @param grayThreshold 0.0–1.0, simple contrast/threshold tweak
     */
    fun imageToAscii(
        path: String,
        outWidth: Int,
        outHeight: Int,
        grayThreshold: Double = 0.0
    ): String
}
