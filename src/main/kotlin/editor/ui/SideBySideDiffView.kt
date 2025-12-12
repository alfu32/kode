package editor.ui

import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.HistogramDiff
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import kotlin.math.max

class SideBySideDiffView(styleSheet: StyleSheet) : BaseComponent(styleSheet) {

    private var path: String = ""
    private var rows: List<DiffRow> = emptyList()
    private var scrollTop: Int = 0
    private var lastBodyHeight: Int = 0
    private var lastLayout: Layout? = null
    private var onRestoreChunk: ((Int) -> Unit)? = null
    private var showContext: Boolean = true
    private var headerWidth: Int = 0
    private var lastWrappedLines: List<WrappedLine> = emptyList()

    fun showDiff(path: String, oldContent: String, newContent: String) {
        this.path = path
        rows = buildRows(oldContent, newContent)
        scrollTop = 0
    }

    fun setOnRestoreChunk(callback: (Int) -> Unit) {
        onRestoreChunk = callback
    }

    fun setShowContext(show: Boolean) {
        if (showContext == show) return
        showContext = show
        scrollTop = scrollTop.coerceIn(0, (displayRows().size - 1).coerceAtLeast(0))
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rowsAvailable = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("diff-header")
        canvas.withStyle(headerStyle) {
            val toggle = if (showContext) "[squashed]" else "[unsquashed]"
            val label = "Diff: $path"
            val content = buildString {
                append(label)
                if (cols > toggle.length + 1) {
                    val pad = (cols - toggle.length - label.length - 1).coerceAtLeast(1)
                    append(" ".repeat(pad))
                    append(toggle)
                }
            }.take(cols).padEnd(cols, ' ')
            drawText(0, 0, content)
        }
        headerWidth = cols
        val bodyHeight = (rowsAvailable - 1).coerceAtLeast(0)
        lastBodyHeight = bodyHeight
        if (bodyHeight <= 0) return

        val gutterStyle = styleSheet.getStyle("diff-gutter").withDefaults()
        val addedStyle = styleSheet.getStyle("diff-added").withDefaults()
        val removedStyle = styleSheet.getStyle("diff-removed").withDefaults()
        val modifiedStyle = styleSheet.getStyle("diff-modified").withDefaults()
        val contextStyle = styleSheet.getStyle("diff-context").withDefaults()

        val gutterWidth = (maxLeftLineNumberLength() + 1).coerceAtLeast(4)
        val sepWidth = 3 // space + bar + space
        val sideWidth = ((cols - sepWidth) / 2).coerceAtLeast(1)
        lastLayout = Layout(gutterWidth, sepWidth, sideWidth, cols)
        val wrapped = wrapDisplayRows(displayRows(), gutterWidth, sepWidth, sideWidth, cols)
        lastWrappedLines = wrapped
        val visible = wrapped.drop(scrollTop).take(bodyHeight)
        visible.forEachIndexed { idx, line ->
            val y = idx + 1
            val leftStyle = styleForSide(line.kind, Side.LEFT, addedStyle, removedStyle, modifiedStyle, contextStyle)
            val rightStyle = styleForSide(line.kind, Side.RIGHT, addedStyle, removedStyle, modifiedStyle, contextStyle)
            canvas.withStyle(gutterStyle) {
                val num = if (line.showLeftNumber) {
                    line.leftNumber?.toString()?.padStart(gutterWidth - 1, ' ') ?: " ".repeat(gutterWidth)
                } else " ".repeat(gutterWidth)
                drawText(0, y, num.take(gutterWidth))
            }
            canvas.withStyle(leftStyle) {
                val textWidth = (sideWidth - gutterWidth).coerceAtLeast(0)
                val text = line.leftText.padEnd(textWidth, ' ')
                drawRect(gutterWidth, y, textWidth, 1)
                drawText(gutterWidth, y, text.take(textWidth))
            }
            val sepText = " ${line.marker} ".take(sepWidth)
            canvas.drawText(sideWidth, y, sepText)
            canvas.withStyle(gutterStyle) {
                val num = if (line.showRightNumber) {
                    line.rightNumber?.toString()?.padStart(gutterWidth - 1, ' ') ?: " ".repeat(gutterWidth)
                } else " ".repeat(gutterWidth)
                drawText(sideWidth + sepWidth, y, num.take(gutterWidth))
            }
            canvas.withStyle(rightStyle) {
                val startX = sideWidth + sepWidth + gutterWidth
                val space = (cols - startX).coerceAtLeast(0)
                val text = line.rightText.padEnd(space, ' ')
                drawRect(startX, y, space, 1)
                drawText(startX, y, text.take(space))
            }
        }
    }

    private fun wrapRow(text: String, width: Int): List<String> {
        if (width <= 0) return listOf("")
        if (text.length <= width) return listOf(text)
        val out = mutableListOf<String>()
        var idx = 0
        while (idx < text.length) {
            val end = (idx + width).coerceAtMost(text.length)
            out.add(text.substring(idx, end))
            idx = end
        }
        return out
    }

    override fun dispatch(event: UIEvent): Boolean {
        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val prev = scrollTop
                val visible = (event.rows?.minus(1))?.coerceAtLeast(1) ?: (lastBodyHeight.coerceAtLeast(1))
                val maxScroll = ((lastWrappedLines.takeIf { it.isNotEmpty() } ?: wrapDisplayRows(
                    displayRows(),
                    lastLayout?.gutterWidth ?: 0,
                    lastLayout?.sepWidth ?: 0,
                    lastLayout?.sideWidth ?: 0,
                    lastLayout?.totalCols ?: 0
                )).size - visible).coerceAtLeast(0)
                scrollTop = (scrollTop - delta).coerceIn(0, maxScroll)
                return scrollTop != prev
            }
            "mouse_down" -> {
                val layout = lastLayout ?: return false
                val ex = event.x ?: return false
                val ey = event.y ?: return false
                if (ey == 0 && headerWidth > 0) {
                    val toggleLabel = if (showContext) "[squashed]" else "[unsquashed]"
                    val start = (headerWidth - toggleLabel.length).coerceAtLeast(0)
                    if (ex in start until headerWidth) {
                        setShowContext(!showContext)
                        return true
                    }
                }
                if (ey <= 0) return false
                val rowIdx = scrollTop + ey - 1
                val line = lastWrappedLines.getOrNull(rowIdx) ?: return false
                val sepStart = layout.sideWidth
                if (ex in sepStart until (sepStart + layout.sepWidth) && line.chunkId != null && line.kind != DiffKind.CONTEXT && line.marker.isNotBlank()) {
                    onRestoreChunk?.invoke(line.chunkId)
                    return true
                }
            }
            "key_down" -> {
                when (event.key?.lowercase()) {
                    "up" -> {
                        scrollTop = (scrollTop - 1).coerceAtLeast(0); return true
                    }
                    "down" -> {
                        val maxScroll = (lastWrappedLines.size - lastBodyHeight).coerceAtLeast(0)
                        scrollTop = (scrollTop + 1).coerceAtMost(maxScroll); return true
                    }
                    "pageup" -> {
                        val rowsVisible = (event.rows ?: 0) - 1
                        scrollTop = (scrollTop - rowsVisible).coerceAtLeast(0); return true
                    }
                    "pagedown" -> {
                        val rowsVisible = ((event.rows ?: lastBodyHeight) - 1).coerceAtLeast(1)
                        val maxScroll = (lastWrappedLines.size - rowsVisible).coerceAtLeast(0)
                        scrollTop = (scrollTop + rowsVisible).coerceAtMost(maxScroll); return true
                    }
                    "c" -> {
                        setShowContext(!showContext); return true
                    }
                }
            }
        }
        return false
    }

    private fun buildRows(oldContent: String, newContent: String): List<DiffRow> {
        val leftLines = splitLines(oldContent)
        val rightLines = splitLines(newContent)
        val edits = HistogramDiff().diff(
            RawTextComparator.DEFAULT,
            RawText(oldContent.toByteArray()),
            RawText(newContent.toByteArray())
        )
        val out = mutableListOf<DiffRow>()
        var leftIdx = 0
        var rightIdx = 0
        var chunkCounter = 0
        edits.forEach { edit ->
            // context before edit
            val ctxLen = (edit.beginA - leftIdx).coerceAtLeast(0)
            for (i in 0 until ctxLen) {
                out.add(
                    DiffRow(
                        leftNumber = leftIdx + i + 1,
                        leftText = leftLines.getOrElse(leftIdx + i) { "" },
                        rightNumber = rightIdx + i + 1,
                        rightText = rightLines.getOrElse(rightIdx + i) { "" },
                        kind = DiffKind.CONTEXT
                    )
                )
            }
            leftIdx = edit.beginA
            rightIdx = edit.beginB
            when (edit.type) {
                Edit.Type.INSERT -> {
                    for (i in edit.beginB until edit.endB) {
                        out.add(
                            DiffRow(
                                leftNumber = null,
                                leftText = "",
                                rightNumber = i + 1,
                                rightText = rightLines.getOrElse(i) { "" },
                                kind = DiffKind.ADDED,
                                chunkId = chunkCounter
                            )
                        )
                    }
                    chunkCounter++
                    rightIdx = edit.endB
                }
                Edit.Type.DELETE -> {
                    for (i in edit.beginA until edit.endA) {
                        out.add(
                            DiffRow(
                                leftNumber = i + 1,
                                leftText = leftLines.getOrElse(i) { "" },
                                rightNumber = null,
                                rightText = "",
                                kind = DiffKind.REMOVED,
                                chunkId = chunkCounter
                            )
                        )
                    }
                    chunkCounter++
                    leftIdx = edit.endA
                }
                Edit.Type.REPLACE -> {
                    val lenA = edit.endA - edit.beginA
                    val lenB = edit.endB - edit.beginB
                    val maxLen = max(lenA, lenB)
                    val deleteOnly = lenB == 0 || rightLines.subList(edit.beginB, edit.endB).all { it.isBlank() }
                    for (i in 0 until maxLen) {
                        val lIdx = edit.beginA + i
                        val rIdx = edit.beginB + i
                        val effectiveKind = if (deleteOnly) DiffKind.REMOVED else DiffKind.MODIFIED
                        out.add(
                            DiffRow(
                                leftNumber = if (i < lenA) lIdx + 1 else null,
                                leftText = if (i < lenA) leftLines.getOrElse(lIdx) { "" } else "",
                                rightNumber = if (i < lenB) rIdx + 1 else null,
                                rightText = if (i < lenB) rightLines.getOrElse(rIdx) { "" } else "",
                                kind = effectiveKind,
                                chunkId = chunkCounter
                            )
                        )
                    }
                    chunkCounter++
                    leftIdx = edit.endA
                    rightIdx = edit.endB
                }
                Edit.Type.EMPTY -> {}
            }
        }
        // trailing context
        val trailing = max(leftLines.size - leftIdx, rightLines.size - rightIdx)
        for (i in 0 until trailing) {
            val lIdx = leftIdx + i
            val rIdx = rightIdx + i
            out.add(
                DiffRow(
                    leftNumber = if (lIdx < leftLines.size) lIdx + 1 else null,
                    leftText = leftLines.getOrElse(lIdx) { "" },
                    rightNumber = if (rIdx < rightLines.size) rIdx + 1 else null,
                    rightText = rightLines.getOrElse(rIdx) { "" },
                    kind = DiffKind.CONTEXT
                )
            )
        }
        return out
    }

    private fun splitLines(content: String): List<String> =
        content.split("\n", ignoreCase = false, limit = Int.MAX_VALUE)

    private fun maxLeftLineNumberLength(): Int =
        listOf(
            displayRows().maxOfOrNull { it.leftNumber ?: 0 } ?: 0,
            displayRows().maxOfOrNull { it.rightNumber ?: 0 } ?: 0
        ).maxOrNull()?.toString()?.length ?: 1

    private data class DiffRow(
        val leftNumber: Int?,
        val leftText: String,
        val rightNumber: Int?,
        val rightText: String,
        val kind: DiffKind,
        val chunkId: Int? = null
    )

    private enum class DiffKind { CONTEXT, ADDED, REMOVED, MODIFIED }

    private enum class Side { LEFT, RIGHT }

    private fun styleForSide(
        kind: DiffKind,
        side: Side,
        added: react.StyleSet,
        removed: react.StyleSet,
        modified: react.StyleSet,
        context: react.StyleSet
    ): react.StyleSet = when (kind) {
        DiffKind.CONTEXT -> context
        DiffKind.MODIFIED -> modified
        DiffKind.ADDED -> if (side == Side.LEFT) removed else added
        DiffKind.REMOVED -> if (side == Side.LEFT) removed else context
    }

    private data class Layout(
        val gutterWidth: Int,
        val sepWidth: Int,
        val sideWidth: Int,
        val totalCols: Int
    )

    private data class WrappedLine(
        val leftText: String,
        val rightText: String,
        val leftNumber: Int?,
        val rightNumber: Int?,
        val showLeftNumber: Boolean,
        val showRightNumber: Boolean,
        val marker: String,
        val chunkId: Int?,
        val kind: DiffKind
    )

    private fun wrapDisplayRows(
        rows: List<DiffRow>,
        gutterWidth: Int,
        sepWidth: Int,
        sideWidth: Int,
        totalCols: Int
    ): List<WrappedLine> {
        if (gutterWidth <= 0 || sideWidth <= 0 || totalCols <= 0) return emptyList()
        val rightWidth = totalCols - (sideWidth + sepWidth + gutterWidth)
        val out = mutableListOf<WrappedLine>()
        rows.forEachIndexed { idx, row ->
            val leftWrapped = wrapRow(row.leftText, sideWidth - gutterWidth)
            val rightWrapped = wrapRow(row.rightText, rightWidth)
            val linesToDraw = max(leftWrapped.size, rightWrapped.size).coerceAtLeast(1)
            val prevChunk = rows.getOrNull(idx - 1)?.chunkId
            val isFirstInChunk = row.chunkId != null && row.chunkId != prevChunk && row.kind != DiffKind.CONTEXT
            for (lineIdx in 0 until linesToDraw) {
                val marker = if (isFirstInChunk && lineIdx == 0) ">>" else " "
                out.add(
                    WrappedLine(
                        leftText = leftWrapped.getOrNull(lineIdx) ?: "",
                        rightText = rightWrapped.getOrNull(lineIdx) ?: "",
                        leftNumber = row.leftNumber,
                        rightNumber = row.rightNumber,
                        showLeftNumber = lineIdx == 0,
                        showRightNumber = lineIdx == 0,
                        marker = marker,
                        chunkId = row.chunkId,
                        kind = row.kind
                    )
                )
            }
        }
        return out
    }

    private fun displayRows(): List<DiffRow> {
        if (showContext) return rows
        val out = mutableListOf<DiffRow>()
        var prevChunk: Int? = null
        rows.filter { it.kind != DiffKind.CONTEXT }.forEach { row ->
            if (prevChunk != null && prevChunk != row.chunkId) {
                out.add(
                    DiffRow(
                        leftNumber = null,
                        leftText = "",
                        rightNumber = null,
                        rightText = "",
                        kind = DiffKind.CONTEXT,
                        chunkId = null
                    )
                )
            }
            out.add(row)
            prevChunk = row.chunkId
        }
        return out
    }
}
