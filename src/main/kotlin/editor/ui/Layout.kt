package editor.ui

data class Region(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

data class Layout(
    val top: Region,
    val left: Region,
    val right: Region,
    val status: Region
)

fun computeLayout(rows: Int, cols: Int, leftWidth: Int = (cols * 0.32).toInt()): Layout {
    val topHeight = 1
    val statusHeight = 1
    val bodyHeight = (rows - topHeight - statusHeight).coerceAtLeast(0)
    val leftPaneWidth = leftWidth.coerceIn(20, (cols * 0.6).toInt())
    val rightPaneWidth = (cols - leftPaneWidth).coerceAtLeast(10)
    return Layout(
        top = Region(0, 0, cols, topHeight),
        left = Region(0, topHeight, leftPaneWidth, bodyHeight),
        right = Region(leftPaneWidth, topHeight, rightPaneWidth, bodyHeight),
        status = Region(0, topHeight + bodyHeight, cols, statusHeight)
    )
}
