package editor.mime

/**
 * Immutable representation of a detected MIME type.
 *
 * @param mime MIME string such as "application/zip".
 * @param extension Associated extension (with leading dot when known).
 * @param source How the MIME was resolved (extension, signature, platform).
 */
data class MimeTypeResult(
    val mime: String,
    val extension: String = "",
    val source: DetectionSource = DetectionSource.UNKNOWN,
) {
    enum class DetectionSource {
        EXTENSION,
        SIGNATURE,
        PLATFORM,
        FALLBACK,
        UNKNOWN,
    }
}
