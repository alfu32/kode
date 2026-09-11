package editor.mime

enum class MimeTypeCategory {
    TEXT,
    IMAGE,
    BINARY,
    UNKNOWN
}
enum class MimeTypeDetectionSource {
    EXTENSION,
    SIGNATURE,
    SHEBANG,
    PLATFORM,
    FALLBACK,
    UNKNOWN,
}
/**
 * Immutable representation of a detected MIME type.
 *
 * @param mime MIME string such as "application/zip".
 * @param extension Associated extension (with leading dot when known).
 * @param language Optional language name (e.g., "kotlin") when known.
 * @param mimeTypeCategory Optional coarse category for routing: "text", "image", or "binary".
 * @param source How the MIME was resolved (extension, signature, platform).
 */
data class MimeTypeResult(
    val mime: String,
    val extension: String = "",
    val language: String? = null,
    val mimeTypeCategory: MimeTypeCategory = MimeTypeCategory.UNKNOWN,
    val source: MimeTypeDetectionSource = MimeTypeDetectionSource.UNKNOWN,
) {
}
