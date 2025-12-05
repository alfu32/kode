package editor.ui

import editor.state.EditorState
import editor.state.Focus
import editor.state.LeftTab
import editor.ui.components.FileTreeComponent
import editor.ui.components.GitComponent
import editor.ui.components.SettingsComponent
import react.renderer.CanvasRenderer

/**
 * Draws the high-level layout: top bar, split panes, status bar.
 */
class UiRenderer(private val renderer: CanvasRenderer) {
    private val fileTreeComponent = FileTreeComponent()
    private val gitComponent = GitComponent()
    private val settingsComponent = SettingsComponent()

    fun render(state: EditorState) {
        val layout = computeLayout(renderer.rows(), renderer.cols())
        renderer.clear()

        drawTop(layout)
        drawLeftPane(layout)
        drawTabs(layout, state.leftTab)
        when (state.leftTab) {
            LeftTab.PROJECT -> fileTreeComponent.render(renderer, layout.left, state)
            LeftTab.GIT -> gitComponent.render(renderer, layout.left, state)
            LeftTab.SETTINGS -> settingsComponent.render(renderer, layout.left, state)
        }
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
    }

    private fun drawTabs(layout: Layout, active: LeftTab) {
        val tabLine = buildString {
            append(" ")
            append(if (active == LeftTab.PROJECT) "[Project]" else " Project ")
            append("  ")
            append(if (active == LeftTab.GIT) "[Git]" else " Git ")
            append("  ")
            append(if (active == LeftTab.SETTINGS) "[Settings]" else " Settings ")
        }
        renderer.setBackgroundColor(35, 40, 50)
        renderer.setColor(200, 210, 220)
        renderer.drawText(layout.left.x + 1, layout.left.y, tabLine.take(layout.left.width - 2))
    }

    private fun drawRightPane(layout: Layout, state: EditorState) {
        renderer.setBackgroundColor(18, 18, 18)
        renderer.setColor(220, 220, 220)
        renderer.drawRect(layout.right.x, layout.right.y, layout.right.width, layout.right.height)
        val visible = state.visibleLines(layout.right.height, layout.right.width)
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
