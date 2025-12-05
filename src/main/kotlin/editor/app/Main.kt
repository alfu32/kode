package editor.app

import editor.state.EditorState
import editor.state.Focus
import editor.state.LeftTab
import editor.state.buffer.TextBuffer
import editor.terminal.enterRawMode
import editor.terminal.restoreStty
import editor.ui.DomUiRenderer
import react.StyleSheet
import react.renderer.AnsiCanvasRenderer
import org.github.alfu32.ktx.lib.FileTree
import react.UIEvent

/**
 * Minimal bootstrap: initialize renderer/input, render once, and react to resize.
 * Extend this into a full event loop with key/mouse handling.
 */
fun main() {
    val buffer = TextBuffer.fromString(
        """
        Welcome to the TUI editor scaffold.
        - Left pane: tabs (Project, Git, Settings).
        - Right pane: layered code editor with gutter and syntax color.
        - Status bar: mode, file path, dirty flag, Git info.
        """.trimIndent()
    )

    val tree = FileTree.newFileTree(System.getProperty("user.dir"))
    var state = EditorState(buffer = buffer, filePath = "scratch.txt", fileTree = tree)
    val renderer = AnsiCanvasRenderer()
    val styleSheet = StyleSheet() // extend to load from files if available
    val uiRenderer = DomUiRenderer(renderer, styleSheet)

    val savedStty = enterRawMode()
    renderer.enterAlternateScreen()
    renderer.enableMouseTracking()
    renderer.hideCursor()

    try {
        uiRenderer.render(state)

        while (renderer.isRunning()) {
            val event = renderer.pollEvent()
            when (event.kind) {
                "resize" -> uiRenderer.render(state)
                "key_down" -> {
                    val layout = editor.ui.computeLayout(renderer.rows(), renderer.cols())
                    if (event.ctrl && event.key == "C") break
                    state = handleKey(state, event.key.orEmpty(), layout)
                    uiRenderer.render(state)
                }
                "mouse_down", "mouse_up", "mouse_move", "mouse_scroll" -> {
                    val layout = editor.ui.computeLayout(renderer.rows(), renderer.cols())
                    state = handleMouse(state, event, layout)
                    uiRenderer.render(state)
                }
                else -> {
                    // Mouse and other events will be handled later.
                }
            }
        }
    } finally {
        renderer.resetAttributes()
        renderer.disableMouseTracking()
        renderer.showCursor()
        renderer.leaveAlternateScreen()
        renderer.shutdown()
        restoreStty(savedStty)
    }
}

private fun handleKey(state: EditorState, key: String, layout: editor.ui.Layout): EditorState {
    return when (key) {
        "Tab" -> state.toggleFocus()
        "1" -> state.setLeftTab(LeftTab.PROJECT)
        "2" -> state.setLeftTab(LeftTab.GIT)
        "3" -> state.setLeftTab(LeftTab.SETTINGS)
        "Up" -> if (state.focus == Focus.LEFT) state.moveFileSelection(-1) else state.moveCursor(-1, 0, layout.right.height, layout.right.width)
        "Down" -> if (state.focus == Focus.LEFT) state.moveFileSelection(1) else state.moveCursor(1, 0, layout.right.height, layout.right.width)
        "Left" -> if (state.focus == Focus.LEFT) state else state.moveCursor(0, -1, layout.right.height, layout.right.width)
        "Right" -> if (state.focus == Focus.LEFT) state else state.moveCursor(0, 1, layout.right.height, layout.right.width)
        "Home" -> if (state.focus == Focus.LEFT) state else state.moveCursor(0, -state.cursor.col, layout.right.height, layout.right.width)
        "End" -> if (state.focus == Focus.LEFT) state else {
            val lineLen = state.buffer.line(state.cursor.line).length
            state.moveCursor(0, lineLen - state.cursor.col, layout.right.height, layout.right.width)
        }
        "Enter", " " -> if (state.focus == Focus.LEFT) state.toggleSelectedNode() else state
        else -> state
    }
}

private fun handleMouse(state: EditorState, event: UIEvent, layout: editor.ui.Layout): EditorState {
    val x = event.x ?: return state
    val y = event.y ?: return state
    var newState = state

    // Scroll handling
    event.scrollDelta?.let { delta ->
        return when {
            state.focus == Focus.LEFT && state.leftTab == LeftTab.PROJECT ->
                state.moveFileSelection(if (delta > 0) -1 else 1)
            state.focus == Focus.RIGHT -> state.moveCursor(if (delta > 0) -1 else 1, 0, layout.right.height, layout.right.width)
            else -> state
        }
    }

    // Click focus and actions
    if (x < layout.left.width) {
        // Left pane
        newState = newState.copy(focus = Focus.LEFT)
        // Click on tabs row
        if (y == layout.left.y) {
            return when {
                x < layout.left.width / 3 -> newState.setLeftTab(LeftTab.PROJECT)
                x < (2 * layout.left.width) / 3 -> newState.setLeftTab(LeftTab.GIT)
                else -> newState.setLeftTab(LeftTab.SETTINGS)
            }
        }
        val entries = newState.fileTreeEntries()
        val idx = (y - (layout.left.y + 1))
        if (idx in entries.indices) {
            newState = newState.copy(fileTreeSelection = idx)
            if (event.kind == "mouse_down") {
                val entry = entries[idx]
                if (entry.typ == "folder") {
                    newState.fileTree?.toggle(entry.fullPath)
                }
            }
        }
        return newState
    }

    // Right pane click -> focus right and set cursor
    newState = newState.copy(focus = Focus.RIGHT)
    val targetLine = newState.viewport.top + (y - layout.right.y)
    val targetCol = newState.viewport.left + (x - layout.right.x)
    newState = newState.setCursorPosition(targetLine, targetCol, layout.right.height, layout.right.width)
    return newState
}
