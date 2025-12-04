package editor.ui

import editor.renderer.CanvasRenderer
import editor.state.EditorState

/**
 * Draws the high-level layout: top bar, split panes, status bar.
 */
class UiRenderer(private val renderer: CanvasRenderer) {

    fun render(state: EditorState) {
        val layout = computeLayout(renderer.rows(), renderer.cols())
        renderer.clear()

        drawTop(layout)
        drawLeftPane(layout)
        drawRightPane(layout, state)
        drawStatus(layout, state)

        renderer.flush()
    }

    private fun drawTop(layout: Layout) {
        renderer.setBackgroundColor(35, 40, 50)
        renderer.setColor(200, 210, 220)
        renderer.drawRect(layout.top.x, layout.top.y, layout.top.width, layout.top.height)
        renderer.drawText(layout.top.x + 1, layout.top.y, "TUI Editor")
    }

    private fun drawLeftPane(layout: Layout) {
        renderer.setBackgroundColor(25, 28, 34)
        renderer.setColor(180, 180, 180)
        renderer.drawRect(layout.left.x, layout.left.y, layout.left.width, layout.left.height)
        renderer.drawText(layout.left.x + 1, layout.left.y, "[Tabs] Project | Git | Settings")
    }

    private fun drawRightPane(layout: Layout, state: EditorState) {
        renderer.setBackgroundColor(18, 18, 18)
        renderer.setColor(220, 220, 220)
        renderer.drawRect(layout.right.x, layout.right.y, layout.right.width, layout.right.height)
        val visible = state.visibleLines(layout.right.height)
        visible.forEachIndexed { idx, line ->
            val y = layout.right.y + idx
            renderer.drawText(layout.right.x, y, line.take(layout.right.width))
        }
    }

    private fun drawStatus(layout: Layout, state: EditorState) {
        renderer.setBackgroundColor(50, 56, 66)
        renderer.setColor(198, 120, 221)
        renderer.drawRect(layout.status.x, layout.status.y, layout.status.width, layout.status.height)
        val dirtyFlag = if (state.dirty) "*" else ""
        val branch = state.gitBranch?.let { " | $it" } ?: ""
        val status = state.gitStatus?.let { " [$it]" } ?: ""
        val mode = state.mode.name.lowercase().replaceFirstChar { it.titlecase() }
        val text = " $mode ${state.filePath ?: "untitled"}$dirtyFlag$branch$status"
        renderer.drawText(layout.status.x + 1, layout.status.y, text.take(layout.status.width - 2))
    }
}
