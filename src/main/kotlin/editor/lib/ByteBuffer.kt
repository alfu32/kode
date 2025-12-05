package editor.lib

/**
 * Mutable byte buffer with cursor, selection, and clipboard support for the hex editor.
 */
class ByteBuffer : IByteBuffer {

    private val data: MutableList<Byte> = mutableListOf()
    private var cursor: Int = 0
    private var anchor: Int? = null
    private var clipboard: ByteArray = ByteArray(0)

    override fun totalBytes(): Int = data.size

    override fun bytes(): ByteArray = data.toByteArray()

    override fun clone(): IByteBuffer {
        val copy = ByteBuffer()
        copy.data.addAll(data)
        copy.cursor = cursor
        copy.anchor = anchor
        copy.clipboard = clipboard.copyOf()
        return copy
    }

    override fun cursorIndex(): Int = cursor

    override fun selectionBytes(): ByteArray {
        val range = selectionRange() ?: return ByteArray(0)
        return data.subList(range.first, range.last + 1).toByteArray()
    }

    override fun hasSelection(): Boolean = selectionRange() != null

    override fun loadBytes(bytes: ByteArray) {
        data.clear()
        data.addAll(bytes.toList())
        cursor = 0
        anchor = null
    }

    override fun moveCursorTo(index: Int, expand: Boolean) {
        val clamped = index.coerceIn(0, data.size.coerceAtLeast(0))
        if (!expand) {
            anchor = null
        } else if (anchor == null) {
            anchor = cursor
        }
        cursor = clamped
    }

    override fun moveLeft(expand: Boolean) = moveCursorTo(cursor - 1, expand)

    override fun moveRight(expand: Boolean) = moveCursorTo(cursor + 1, expand)

    override fun moveUp(bytesPerRow: Int, expand: Boolean) =
        moveCursorTo(cursor - bytesPerRow, expand)

    override fun moveDown(bytesPerRow: Int, expand: Boolean) =
        moveCursorTo(cursor + bytesPerRow, expand)

    override fun moveStart(expand: Boolean) = moveCursorTo(0, expand)

    override fun moveEnd(expand: Boolean) = moveCursorTo(data.size, expand)

    override fun selectAll() {
        anchor = 0
        cursor = data.size
    }

    override fun insertBytes(data: ByteArray) {
        if (data.isEmpty()) return
        if (hasSelection()) deleteSelection()
        val pos = cursor
        this.data.addAll(pos, data.toList())
        cursor = pos + data.size
        anchor = null
    }

    override fun deleteBackspace() {
        if (deleteSelection()) return
        if (cursor <= 0 || data.isEmpty()) return
        data.removeAt(cursor - 1)
        cursor = (cursor - 1).coerceAtLeast(0)
    }

    override fun deleteForward() {
        if (deleteSelection()) return
        if (cursor >= data.size || data.isEmpty()) return
        data.removeAt(cursor)
    }

    override fun copySelection(): Boolean {
        val range = selectionRange() ?: return false
        clipboard = data.subList(range.first, range.last + 1).toByteArray()
        return true
    }

    override fun cutSelection(): Boolean {
        val copied = copySelection()
        if (!copied) return false
        deleteSelection()
        return true
    }

    override fun pasteClipboard() {
        insertBytes(clipboard)
    }

    override fun startSelection(index: Int) {
        anchor = index.coerceIn(0, data.size.coerceAtLeast(0))
        cursor = anchor!!
    }

    override fun selectTo(index: Int) {
        if (anchor == null) anchor = cursor
        cursor = index.coerceIn(0, data.size.coerceAtLeast(0))
    }

    override fun clearSelection() {
        anchor = null
    }

    override fun viewportSlice(view: ByteViewport): ByteViewportSlice {
        val start = view.startIndex.coerceAtLeast(0)
        val bytesPerRow = view.bytesPerRow.coerceAtLeast(1)
        val rows = view.rows.coerceAtLeast(0)
        val selection = selectionRange()
        val list = mutableListOf<ByteViewportRow>()
        var offset = start
        repeat(rows) {
            if (offset >= data.size) return@repeat
            val endExclusive = (offset + bytesPerRow).coerceAtMost(data.size)
            val slice = data.subList(offset, endExclusive).toByteArray()
            val mask = BooleanArray(slice.size) { idx ->
                val global = offset + idx
                selection?.let { global in it } ?: false
            }
            list.add(ByteViewportRow(offset, slice, mask))
            offset += bytesPerRow
        }
        return ByteViewportSlice(
            rows = list,
            cursorIndex = cursor.coerceIn(0, data.size.coerceAtLeast(0)),
            selection = selection,
            totalBytes = data.size
        )
    }

    private fun selectionRange(): IntRange? {
        val a = anchor ?: return null
        if (a == cursor) return null
        val start = minOf(a, cursor)
        val end = maxOf(a, cursor) - 1
        if (start > end) return null
        return start..end
    }

    private fun deleteSelection(): Boolean {
        val range = selectionRange() ?: return false
        val removeCount = range.last - range.first + 1
        repeat(removeCount) {
            data.removeAt(range.first)
        }
        cursor = range.first
        anchor = null
        return true
    }
}
