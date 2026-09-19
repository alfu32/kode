package editor.database.ui

import editor.database.query.SqlExecutionResult
import editor.database.query.SqlResultSet
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class SqlResultPanel(
    styleSheet: StyleSheet
) : BaseComponent(styleSheet) {
    private var result: SqlExecutionResult? = null
    private var runningLabel: String? = null
    private var messageLines: List<String>? = null
    private var resultIndex = 0
    private var rowScroll = 0
    private var colScroll = 0

    fun showRunning(label: String) {
        runningLabel = label
        result = null
        messageLines = null
        resultIndex = 0
        rowScroll = 0
        colScroll = 0
    }

    fun showResult(next: SqlExecutionResult) {
        runningLabel = null
        result = next
        messageLines = null
        resultIndex = 0
        rowScroll = 0
        colScroll = 0
    }

    fun showMessage(lines: List<String>) {
        runningLabel = null
        result = null
        messageLines = lines
        resultIndex = 0
        rowScroll = 0
        colScroll = 0
    }

    fun clear() {
        runningLabel = null
        result = null
        messageLines = null
        resultIndex = 0
        rowScroll = 0
        colScroll = 0
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val base = styleSheet.getStyle("content")
        val header = styleSheet.getStyle("tab-strip")
        canvas.withStyle(base) {
            drawRect(0, 0, cols, rows)
        }
        val running = runningLabel
        if (running != null) {
            canvas.withStyle(header) {
                drawText(0, 0, "Running... $running".take(cols).padEnd(cols, ' '))
            }
            return
        }
        val messages = messageLines
        if (messages != null) {
            renderMessageLines(canvas, messages, cols, rows)
            return
        }
        val current = result
        if (current == null) {
            canvas.withStyle(header) {
                drawText(0, 0, "Results".take(cols).padEnd(cols, ' '))
            }
            return
        }
        if (current.error != null) {
            renderError(canvas, current, cols, rows)
            return
        }
        resultIndex = resultIndex.coerceIn(0, (current.resultSets.size - 1).coerceAtLeast(0))
        val activeSet = current.resultSets.getOrNull(resultIndex)
        if (activeSet != null) {
            renderResultSet(canvas, activeSet, current, cols, rows)
        } else {
            renderMessages(canvas, current, cols, rows)
        }
    }

    private fun renderMessageLines(canvas: CanvasRenderer, lines: List<String>, cols: Int, rows: Int) {
        val header = styleSheet.getStyle("tab-strip")
        val body = styleSheet.getStyle("content")
        canvas.withStyle(header) {
            drawText(0, 0, "Messages".take(cols).padEnd(cols, ' '))
        }
        canvas.withStyle(body) {
            lines.take(rows - 1).forEachIndexed { idx, line ->
                drawText(0, idx + 1, line.take(cols).padEnd(cols, ' '))
            }
        }
    }

    private fun renderError(canvas: CanvasRenderer, result: SqlExecutionResult, cols: Int, rows: Int) {
        val header = styleSheet.getStyle("tab-strip")
        val body = styleSheet.getStyle("content")
        val error = result.error ?: return
        canvas.withStyle(header) {
            drawText(0, 0, "Error | Messages".take(cols).padEnd(cols, ' '))
        }
        val lines = listOf(
            error.message,
            "SQLState: ${error.sqlState ?: ""}",
            "Vendor code: ${error.vendorCode ?: ""}",
            "Execution time: ${result.executionTimeMs} ms"
        )
        canvas.withStyle(body) {
            lines.take(rows - 1).forEachIndexed { idx, line ->
                drawText(0, idx + 1, line.take(cols).padEnd(cols, ' '))
            }
        }
    }

    private fun renderMessages(canvas: CanvasRenderer, result: SqlExecutionResult, cols: Int, rows: Int) {
        val header = styleSheet.getStyle("tab-strip")
        val body = styleSheet.getStyle("content")
        canvas.withStyle(header) {
            drawText(0, 0, "Messages".take(cols).padEnd(cols, ' '))
        }
        val lines = mutableListOf<String>()
        if (result.updateCounts.isNotEmpty()) {
            result.updateCounts.forEachIndexed { idx, count ->
                lines += "Update ${idx + 1}: $count rows affected"
            }
        } else {
            lines += "Statement executed successfully."
        }
        result.warnings.forEach { lines += "Warning: $it" }
        lines += "Execution time: ${result.executionTimeMs} ms"
        canvas.withStyle(body) {
            lines.take(rows - 1).forEachIndexed { idx, line ->
                drawText(0, idx + 1, line.take(cols).padEnd(cols, ' '))
            }
        }
    }

    private fun renderResultSet(canvas: CanvasRenderer, set: SqlResultSet, result: SqlExecutionResult, cols: Int, rows: Int) {
        val header = styleSheet.getStyle("tab-strip")
        val body = styleSheet.getStyle("content")
        val visibleRows = (rows - 3).coerceAtLeast(0)
        val maxRowScroll = (set.rows.size - visibleRows).coerceAtLeast(0)
        rowScroll = rowScroll.coerceIn(0, maxRowScroll)
        val status = buildString {
            append(set.label)
            if (result.resultSets.size > 1) append(" ${resultIndex + 1}/${result.resultSets.size}")
            append(" | ${set.rows.size}")
            if (set.limited) append("+")
            append(" rows | ${result.executionTimeMs} ms")
            if (result.resultSets.size > 1) append(" | Tab switches")
        }
        canvas.withStyle(header) {
            drawText(0, 0, status.take(cols).padEnd(cols, ' '))
        }
        val widths = set.columns.mapIndexed { idx, col ->
            val maxValue = set.rows.asSequence().take(100).map { row ->
                row.getOrNull(idx)?.let { if (it.nullValue) "<null>" else it.value.orEmpty() }.orEmpty().length
            }.maxOrNull() ?: 0
            (maxOf(col.name.length, maxValue, col.typeName.length) + 2).coerceIn(6, 32)
        }
        val totalWidth = widths.sum().coerceAtLeast(cols)
        colScroll = colScroll.coerceIn(0, (totalWidth - cols).coerceAtLeast(0))
        val headerLine = set.columns.mapIndexed { idx, col -> col.name.padEnd(widths[idx]) }.joinToString("")
        val separator = widths.joinToString("") { "-".repeat(it.coerceAtLeast(1)) }
        canvas.withStyle(body) {
            drawText(0, 1, slice(headerLine, colScroll, cols).padEnd(cols, ' '))
            drawText(0, 2, slice(separator, colScroll, cols).padEnd(cols, ' '))
            set.rows.drop(rowScroll).take(visibleRows).forEachIndexed { ridx, row ->
                val line = row.mapIndexed { idx, cell ->
                    val text = if (cell.nullValue) "<null>" else cell.value.orEmpty()
                    text.padEnd(widths[idx])
                }.joinToString("")
                drawText(0, ridx + 3, slice(line, colScroll, cols).padEnd(cols, ' '))
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind != "key_down" && event.kind != "mouse_scroll") return false
        val prevRows = rowScroll
        val prevCols = colScroll
        val prevIndex = resultIndex
        if (event.kind == "mouse_scroll") {
            rowScroll = (rowScroll - (event.scrollDelta ?: 0)).coerceAtLeast(0)
        } else {
            when (event.key?.lowercase()) {
                "tab" -> {
                    val count = result?.resultSets?.size ?: 0
                    if (count <= 1) return false
                    resultIndex = if (event.shift) {
                        (resultIndex - 1).floorMod(count)
                    } else {
                        (resultIndex + 1).floorMod(count)
                    }
                    rowScroll = 0
                    colScroll = 0
                }
                "up" -> rowScroll = (rowScroll - 1).coerceAtLeast(0)
                "down" -> rowScroll += 1
                "pageup" -> rowScroll = (rowScroll - 10).coerceAtLeast(0)
                "pagedown" -> rowScroll += 10
                "left" -> colScroll = (colScroll - 4).coerceAtLeast(0)
                "right" -> colScroll += 4
                "home" -> {
                    rowScroll = 0
                    colScroll = 0
                }
                else -> return false
            }
        }
        return prevRows != rowScroll || prevCols != colScroll || prevIndex != resultIndex
    }

    private fun Int.floorMod(modulus: Int): Int =
        ((this % modulus) + modulus) % modulus

    private fun slice(value: String, start: Int, width: Int): String {
        if (width <= 0 || start >= value.length) return ""
        return value.substring(start, (start + width).coerceAtMost(value.length))
    }
}
