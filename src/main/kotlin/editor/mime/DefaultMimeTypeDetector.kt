package editor.mime

import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.min

/**
 * Simple MIME type detector for Kotlin projects. It uses a small signature
 * table, extension lookups, and platform probing as fallbacks.
 */
class DefaultMimeTypeDetector(
    private var byteLimit: Int = DEFAULT_LIMIT,
) : MimeTypeDetector {

    private val extensionMap = ConcurrentHashMap<String, SampleMimeTable.Entry>().apply {
        // Hints derived from repository samples.
        putAll(SampleMimeTable.extensionToEntry)

        // Common binaries and archives not covered by samples.
        val misc = mapOf(
            "zip" to "application/zip",
            "gz" to "application/gzip",
            "tar" to "application/x-tar",
            "rar" to "application/vnd.rar",
            "7z" to "application/x-7z-compressed",
            "mp3" to "audio/mpeg",
            "wav" to "audio/wav",
            "ogg" to "audio/ogg",
            "mp4" to "video/mp4",
            "webm" to "video/webm",
            "mov" to "video/quicktime",
            "avi" to "video/x-msvideo",
            "jar" to "application/java-archive",
            "apk" to "application/vnd.android.package-archive",
            "epub" to "application/epub+zip",
            "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "ppt" to "application/vnd.ms-powerpoint",
            "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "wasm" to "application/wasm",
        )
        misc.forEach { (ext, mime) ->
            this[ext] = SampleMimeTable.Entry(language = "generic-$ext", extension = ".$ext", mime = mime)
        }
    }

    override fun detect(bytes: ByteArray): MimeTypeResult {
        val limited = if (byteLimit > 0) bytes.copyOfRange(0, min(bytes.size, byteLimit)) else bytes
        signatureDetectors.forEach { detector ->
            detector.match(limited)?.let { return it }
        }
        // Fallback to guessing text vs binary if nothing matched.
        return if (looksLikeText(limited)) {
            MimeTypeResult(
                mime = "text/plain",
                extension = ".txt",
                language = "plain-text",
                source = MimeTypeResult.DetectionSource.FALLBACK
            )
        } else {
            MimeTypeResult(
                mime = OCTET_STREAM,
                extension = "",
                language = null,
                source = MimeTypeResult.DetectionSource.FALLBACK
            )
        }
    }

    override fun detectFile(path: Path): MimeTypeResult {
        val nameResult = detectFilename(path.fileName.toString())
        if (nameResult.source != MimeTypeResult.DetectionSource.FALLBACK) {
            return nameResult
        }

        Files.newInputStream(path).use { input ->
            val buffer = readBytes(input, byteLimit)
            val signatureResult = detect(buffer)
            if (signatureResult.source != MimeTypeResult.DetectionSource.FALLBACK) {
                return signatureResult.copy(language = nameResult.language ?: signatureResult.language)
            }
        }

        // Platform-specific probing as a last resort.
        val platformMime = runCatching { Files.probeContentType(path) }.getOrNull()
        if (!platformMime.isNullOrBlank()) {
            return MimeTypeResult(
                mime = platformMime,
                extension = nameResult.extension,
                language = nameResult.language,
                source = MimeTypeResult.DetectionSource.PLATFORM,
            )
        }

        return nameResult.copy(source = MimeTypeResult.DetectionSource.FALLBACK)
    }

    override fun detectFilename(name: String): MimeTypeResult {
        val ext = name.substringAfterLast('.', missingDelimiterValue = "").let(::normalizeExtension)
        if (ext.isNotEmpty()) {
            extensionMap[ext]?.let { entry ->
                return MimeTypeResult(
                    mime = entry.mime,
                    extension = entry.extension,
                    language = entry.language,
                    source = MimeTypeResult.DetectionSource.EXTENSION
                )
            }
        }
        return MimeTypeResult(
            mime = OCTET_STREAM,
            extension = if (ext.isEmpty()) "" else ".$ext",
            language = null,
            source = MimeTypeResult.DetectionSource.FALLBACK
        )
    }

    override fun registerExtension(extension: String, mime: String) {
        val normalized = normalizeExtension(extension)
        if (normalized.isNotEmpty()) {
            extensionMap[normalized] = SampleMimeTable.Entry(language = "custom-$normalized", extension = ".$normalized", mime = mime)
        }
    }

    private fun normalizeExtension(ext: String): String =
        ext.removePrefix(".").lowercase(Locale.ROOT)

    private fun readBytes(input: InputStream, limit: Int): ByteArray {
        val max = if (limit > 0) limit else DEFAULT_READ_SIZE
        val buffer = ByteArray(max)
        val read = input.read(buffer)
        return if (read < 0) ByteArray(0) else buffer.copyOf(read)
    }

    private val signatureDetectors = listOf(
        SignatureDetector("image/png", ".png") { data ->
            data.startsWith(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        },
        SignatureDetector("image/jpeg", ".jpg") { data ->
            data.size >= 3 && data[0] == 0xFF.toByte() && data[1] == 0xD8.toByte() && data[2] == 0xFF.toByte()
        },
        SignatureDetector("image/gif", ".gif") { data ->
            data.startsWith("GIF87a".toByteArray()) || data.startsWith("GIF89a".toByteArray())
        },
        SignatureDetector("application/pdf", ".pdf") { data ->
            data.startsWith("%PDF-".toByteArray())
        },
        SignatureDetector("application/zip", ".zip") { data ->
            data.startsWith(byteArrayOf(0x50, 0x4B, 0x03, 0x04)) ||
                data.startsWith(byteArrayOf(0x50, 0x4B, 0x05, 0x06)) ||
                data.startsWith(byteArrayOf(0x50, 0x4B, 0x07, 0x08))
        },
        SignatureDetector("application/gzip", ".gz") { data ->
            data.startsWith(byteArrayOf(0x1F.toByte(), 0x8B.toByte(), 0x08))
        },
        SignatureDetector("video/mp4", ".mp4") { data ->
            // Look for ftyp box near start.
            data.size > 12 && data.copyOfRange(4, 8).contentEquals("ftyp".toByteArray())
        },
        SignatureDetector("audio/mpeg", ".mp3") { data ->
            data.startsWith("ID3".toByteArray()) ||
                (data.size >= 2 && data[0] == 0xFF.toByte() && data[1].toInt() and 0xE0 == 0xE0)
        },
        SignatureDetector("image/svg+xml", ".svg") { data ->
            val trimmed = data.toString(Charsets.UTF_8).trimStart()
            trimmed.startsWith("<svg") || trimmed.startsWith("<?xml")
        },
        SignatureDetector("text/plain", ".txt") { data ->
            looksLikeText(data)
        },
    )

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean {
        if (this.size < prefix.size) return false
        for (i in prefix.indices) {
            if (this[i] != prefix[i]) return false
        }
        return true
    }

    private fun looksLikeText(data: ByteArray): Boolean {
        if (data.isEmpty()) return true
        var printable = 0
        data.forEach { b ->
            val v = b.toInt() and 0xFF
            if (v in 9..13 || v in 32..126) printable++
        }
        return printable >= data.size * 0.9
    }

    private data class SignatureDetector(
        val mime: String,
        val extension: String,
        val detector: (ByteArray) -> Boolean,
    ) {
        fun match(data: ByteArray): MimeTypeResult? =
            if (detector(data)) MimeTypeResult(
                mime = mime,
                extension = extension,
                language = null,
                source = MimeTypeResult.DetectionSource.SIGNATURE
            ) else null
    }

    companion object {
        const val OCTET_STREAM: String = "application/octet-stream"
        const val DEFAULT_LIMIT: Int = 3072
        private const val DEFAULT_READ_SIZE: Int = 4096
    }
}
