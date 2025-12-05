package editor.mime

/**
 * Immutable representation of a detected MIME type.
 *
 * @param mime MIME string such as "application/zip".
 * @param extension Associated extension (with leading dot when known).
 * @param language Optional language name (e.g., "kotlin") when known.
 * @param category Optional coarse category for routing: "text", "image", or "binary".
 * @param source How the MIME was resolved (extension, signature, platform).
 */
data class MimeTypeResult(
    val mime: String,
    val extension: String = "",
    val language: String? = null,
    val category: Category = Category.UNKNOWN,
    val source: DetectionSource = DetectionSource.UNKNOWN,
) {
    enum class Category {
        TEXT,
        IMAGE,
        BINARY,
        UNKNOWN
    }
    enum class DetectionSource {
        EXTENSION,
        SIGNATURE,
        PLATFORM,
        FALLBACK,
        UNKNOWN,
    }
}
