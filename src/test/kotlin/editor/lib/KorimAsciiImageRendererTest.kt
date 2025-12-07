package editor.lib

import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test

class KorimAsciiImageRendererTest {
    fun extractResource(name: String): String {
        val stream = this::class.java.classLoader.getResourceAsStream(name)
            ?: error("Resource not found on classpath: $name")

        val tmp = kotlin.io.path.createTempFile("img_", "_tmp.png")
        java.nio.file.Files.write(tmp, stream.readBytes())
        return tmp.toAbsolutePath().toString()
    }
    @BeforeEach
    fun setUp() {
        // TODO("Not yet implemented")
    }

    @AfterEach
    fun tearDown() {
        // TODO("Not yet implemented")
    }



    private val renderer: AsciiImageRenderer = KorimAsciiImageRenderer()

    @Test
    fun `renders ascii from image file`() {
    for( imgPathString in listOf(
        "sample-00-racoon.png",
        "sample-01-gradient.png",
        "sample-02-checkerboard.png",
        "sample-03-ansi-colors.png",
        "sample-04-smiley.png",
        "sample-05-TV-bw.png",
        "sample-06-TV-color.png",
        "sample-07-TV-color.png",
    )) {
        val imagePath = extractResource(imgPathString)
        println("Temp file = $imagePath")

        val ascii = renderer.imageToAscii(
            path = imagePath,
            outWidth = 80,
            outHeight = 40,
            grayThreshold = 0.15
        )

        // Basic sanity checks
        assertFalse(ascii.isBlank(), "ASCII output should not be blank")
        // assertTrue(
        //     ascii.contains("\u001B[38;2"),
        //     "ASCII output should contain ANSI 24-bit color codes"
        // )

        // Optional: print to see it in your VT terminal
        // println(location)
        println(ascii)
    }
    }

}