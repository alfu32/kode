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

    fun showDiff(path: String, oldContent: String, newContent: String) {
        this.path = path
        rows = buildRows(oldContent, newContent)
        scrollTop = 0
    }

    fun setOnRestoreChunk(callback: (Int) -> Unit) {
        onRestoreChunk = callback
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rowsAvailable = canvas.rows().coerceAtLeast(1)
        val headerStyle = styleSheet.getStyle("diff-header")
        canvas.withStyle(headerStyle) {
            val label = "Diff: $path".take(cols).padEnd(cols, ' ')
            drawText(0, 0, label)
        }
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
        val visible = rows.drop(scrollTop).take(bodyHeight)
        visible.forEachIndexed { idx, row ->
            val y = idx + 1
            val leftStyle = styleForSide(row.kind, Side.LEFT, addedStyle, removedStyle, modifiedStyle, contextStyle)
            val rightStyle = styleForSide(row.kind, Side.RIGHT, addedStyle, removedStyle, modifiedStyle, contextStyle)
            // Left side
            canvas.withStyle(gutterStyle) {
                val num = row.leftNumber?.toString()?.padStart(gutterWidth - 1, ' ') ?: " ".repeat(gutterWidth)
                drawText(0, y, num.take(gutterWidth))
            }
            canvas.withStyle(leftStyle) {
                val textWidth = (sideWidth - gutterWidth).coerceAtLeast(0)
                val text = row.leftText.take(textWidth).padEnd(textWidth, ' ')
                drawRect(gutterWidth, y, textWidth, 1)
                drawText(gutterWidth, y, text)
            }
            // Separator
            val marker = if (row.chunkId != null && row.kind != DiffKind.CONTEXT) ">>" else "|"
            val sepText = " $marker ".take(sepWidth)
            canvas.drawText(sideWidth, y, sepText)
            // Right side
            canvas.withStyle(gutterStyle) {
                val num = row.rightNumber?.toString()?.padStart(gutterWidth - 1, ' ') ?: " ".repeat(gutterWidth)
                drawText(sideWidth + sepWidth, y, num.take(gutterWidth))
            }
            canvas.withStyle(rightStyle) {
                val startX = sideWidth + sepWidth + gutterWidth
                val space = (cols - startX).coerceAtLeast(0)
                val text = row.rightText.take(space).padEnd(space, ' ')
                drawRect(startX, y, space, 1)
                drawText(startX, y, text)
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val prev = scrollTop
                val visible = (event.rows?.minus(1))?.coerceAtLeast(1) ?: (lastBodyHeight.coerceAtLeast(1))
                val maxScroll = (rows.size - visible).coerceAtLeast(0)
                scrollTop = (scrollTop - delta).coerceIn(0, maxScroll)
                return scrollTop != prev
            }
            "mouse_down" -> {
                val layout = lastLayout ?: return false
                val ex = event.x ?: return false
                val ey = event.y ?: return false
                if (ey <= 0) return false
                val rowIdx = scrollTop + ey - 1
                val row = rows.getOrNull(rowIdx) ?: return false
                val sepStart = layout.sideWidth
                if (ex in sepStart until (sepStart + layout.sepWidth) && row.chunkId != null && row.kind != DiffKind.CONTEXT) {
                    onRestoreChunk?.invoke(row.chunkId)
                    return true
                }
            }
            "key_down" -> {
                when (event.key?.lowercase()) {
                    "up" -> {
                        scrollTop = (scrollTop - 1).coerceAtLeast(0); return true
                    }
                    "down" -> {
                        val maxScroll = (rows.size - lastBodyHeight).coerceAtLeast(0)
                        scrollTop = (scrollTop + 1).coerceAtMost(maxScroll); return true
                    }
                    "pageup" -> {
                        val rowsVisible = (event.rows ?: 0) - 1
                        scrollTop = (scrollTop - rowsVisible).coerceAtLeast(0); return true
                    }
                    "pagedown" -> {
                        val rowsVisible = ((event.rows ?: lastBodyHeight) - 1).coerceAtLeast(1)
                        val maxScroll = (rows.size - rowsVisible).coerceAtLeast(0)
                        scrollTop = (scrollTop + rowsVisible).coerceAtMost(maxScroll); return true
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
            rows.maxOfOrNull { it.leftNumber ?: 0 } ?: 0,
            rows.maxOfOrNull { it.rightNumber ?: 0 } ?: 0
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
}
