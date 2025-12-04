package editor.app

import editor.renderer.AnsiCanvasRenderer
import editor.state.EditorState
import editor.state.buffer.TextBuffer
import editor.terminal.JLineTerminalInput
import editor.terminal.TerminalEvent
import editor.ui.UiRenderer

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

    var state = EditorState(buffer = buffer, filePath = "scratch.txt")
    val renderer = AnsiCanvasRenderer()
    val uiRenderer = UiRenderer(renderer)
    val input = JLineTerminalInput()

    uiRenderer.render(state)

    while (true) {
        when (val event = input.poll()) {
            is TerminalEvent.Resize -> {
                uiRenderer.render(state)
            }
            is TerminalEvent.Key -> {
                if (event.key == "Ctrl-C" || event.key == "\u0003") break
            }
            else -> {
                // Mouse and other events will be handled later.
            }
        }
    }
}
