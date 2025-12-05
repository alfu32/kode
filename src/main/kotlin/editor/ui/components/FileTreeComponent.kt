package editor.ui.components

import editor.state.EditorState
import editor.state.Focus
import editor.ui.Region
import react.renderer.CanvasRenderer

class FileTreeComponent {
    fun render(renderer: CanvasRenderer, region: Region, state: EditorState) {
        val entries = state.fileTreeEntries()
        val startY = region.y + 1
        val height = region.height - 1
        for (i in 0 until height) {
            val entry = entries.getOrNull(i) ?: break
            val y = startY + i
            val icon = if (entry.typ == "folder") {
                if (entry.isOpen) "[-]" else "[+]"
            } else "[=]"
            val pad = "  ".repeat(entry.padding)
            val line = "$pad  $icon ${entry.name}"
            if (state.focus == Focus.LEFT && state.fileTreeSelection == i) {
                renderer.setBackgroundColor(50, 56, 66)
                renderer.setColor(230, 230, 230)
            } else {
                renderer.setBackgroundColor(25, 28, 34)
                renderer.setColor(180, 180, 180)
            }
            renderer.drawText(region.x + 1, y, line.take(region.width - 2))
        }
    }
}
