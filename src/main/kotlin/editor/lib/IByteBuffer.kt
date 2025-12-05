package editor.lib

/**
 * Byte-oriented editable buffer for the hex editor.
 * Mirrors the text buffer API where useful, but exposes viewport slices
 * tailored for hex/ASCII rendering.
 */
interface IByteBuffer {
    fun totalBytes(): Int
    fun bytes(): ByteArray
    fun clone(): IByteBuffer

    fun cursorIndex(): Int
    fun selectionBytes(): ByteArray
    fun hasSelection(): Boolean

    fun loadBytes(bytes: ByteArray)

    fun moveCursorTo(index: Int, expand: Boolean = false)
    fun moveLeft(expand: Boolean = false)
    fun moveRight(expand: Boolean = false)
    fun moveUp(bytesPerRow: Int, expand: Boolean = false)
    fun moveDown(bytesPerRow: Int, expand: Boolean = false)
    fun moveStart(expand: Boolean = false)
    fun moveEnd(expand: Boolean = false)
    fun selectAll()

    fun insertBytes(data: ByteArray)
    fun deleteBackspace()
    fun deleteForward()

    fun copySelection(): Boolean
    fun cutSelection(): Boolean
    fun pasteClipboard()

    fun startSelection(index: Int)
    fun selectTo(index: Int)
    fun clearSelection()

    fun viewportSlice(view: ByteViewport): ByteViewportSlice
}

data class ByteViewport(val startIndex: Int, val bytesPerRow: Int, val rows: Int)

data class ByteViewportRow(val offset: Int, val bytes: ByteArray, val selection: BooleanArray)

data class ByteViewportSlice(
    val rows: List<ByteViewportRow>,
    val cursorIndex: Int,
    val selection: IntRange?,
    val totalBytes: Int
)
