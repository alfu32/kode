package editor.mime

import java.nio.file.Path

/**
 * Contract for MIME type detection. Implementations may use signatures, file
 * extensions, or platform utilities to resolve the final type.
 */
interface MimeTypeDetector {
    /**
     * Detect MIME type using an in-memory buffer. Implementations may inspect
     * up to a configured byte limit.
     */
    fun detect(bytes: ByteArray): MimeTypeResult

    /** Detect MIME type for a file on disk. */
    fun detectFile(path: Path): MimeTypeResult

    /**
     * Detect MIME type using only the filename (typically by extension).
     * Intended for quick checks where content is unavailable.
     */
    fun detectFilename(name: String): MimeTypeResult

    /**
     * Register or override an extension-to-MIME mapping (extension should
     * include or omit the leading dot; both are accepted).
     */
    fun registerExtension(extension: String, mime: String)
}
