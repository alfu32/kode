package editor.mime

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

class DefaultMimeTypeDetectorTest {

    private val detector = DefaultMimeTypeDetector()

    @Test
    fun `detect filename by extension`() {
        val result = detector.detectFilename("example.json")
        println(result)
        assertEquals("text/json", result.mime)
        assertEquals(".json", result.extension)
        assertEquals(MimeTypeDetectionSource.EXTENSION, result.source)
    }

    @Test
    fun `detect bytes by signature`() {
        val pngHeader = byteArrayOf(
            0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, // PNG signature
            0x00, 0x00, 0x00, 0x0D, 0x49, 0x48, 0x44, 0x52, // IHDR chunk start (partial)
        )
        val result = detector.detect(pngHeader)
        assertEquals("image/png", result.mime)
        assertEquals(".png", result.extension)
        assertEquals(MimeTypeDetectionSource.SIGNATURE, result.source)
    }

    @Test
    fun `detect file uses signature when extension missing`() {
        val temp: Path = Files.createTempFile("mimetype-", ".bin")
        Files.write(temp, "%PDF-1.7\n".toByteArray())
        try {
            val result = detector.detectFile(temp)
            println(result)
            assertEquals("application/pdf", result.mime)
            assertEquals(".pdf", result.extension)
            // assertEquals(".bin", result.extension)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Test
    fun `register new extension`() {
        detector.registerExtension(".foo", "application/x-foo")
        val result = detector.detectFilename("sample.foo")
        assertEquals("application/x-foo", result.mime)
        assertEquals(".foo", result.extension)
        assertEquals(MimeTypeDetectionSource.EXTENSION, result.source)
    }

    @Test
    fun `fallback identifies likely text`() {
        val text = "hello world\nthis is text\n".toByteArray()
        val result = detector.detect(text)
        assertEquals("text/plain", result.mime)
        assertEquals(".txt", result.extension)
        assertEquals(MimeTypeDetectionSource.FALLBACK, result.source)
        assertTrue(result.mime.contains("text"))
    }

    @Test
    fun `detect file prefers content based fallback for unknown extensions`() {
        val temp: Path = Files.createTempFile("mimetype-", ".weirdtxt")
        Files.write(temp, "plain text body\nwith multiple lines\n".toByteArray())
        try {
            val result = detector.detectFile(temp)
            assertEquals("text/plain", result.mime)
            assertEquals(MimeTypeCategory.TEXT, result.mimeTypeCategory)
            assertEquals(MimeTypeDetectionSource.FALLBACK, result.source)
        } finally {
            Files.deleteIfExists(temp)
        }
    }

    @Test
    fun `detects shell script from a bash shebang without an extension`() {
        val result = detector.detect("#!/usr/bin/env bash -e\necho hello\n".toByteArray())
        assertEquals("text/shellscript", result.mime)
        assertEquals("shellscript", result.language)
        assertEquals(MimeTypeDetectionSource.SHEBANG, result.source)
        assertEquals(MimeTypeCategory.TEXT, result.mimeTypeCategory)
    }

    @Test
    fun `detects shell script from a direct sh shebang in a file`() {
        val temp = Files.createTempFile("script-", "")
        Files.writeString(temp, "#!/bin/sh\nprintf '%s\\n' ok\n")
        try {
            val result = detector.detectFile(temp)
            assertEquals("shellscript", result.language)
            assertEquals(MimeTypeDetectionSource.SHEBANG, result.source)
        } finally {
            Files.deleteIfExists(temp)
        }
    }
}
