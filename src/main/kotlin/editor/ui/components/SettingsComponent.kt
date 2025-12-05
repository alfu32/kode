package editor.ui.components

import editor.state.EditorState
import editor.ui.Region
import react.renderer.CanvasRenderer

class SettingsComponent {
    fun render(renderer: CanvasRenderer, region: Region, state: EditorState) {
        renderer.setBackgroundColor(25, 28, 34)
        renderer.setColor(180, 180, 180)
        renderer.drawText(region.x + 1, region.y + 1, "Settings: theme selection".take(region.width - 2))
    }
}
