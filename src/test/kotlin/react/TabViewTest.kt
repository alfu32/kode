package react

import kotlin.test.Test
import react.renderer.CanvasRenderer
import react.renderer.NoopRenderer

class TabViewTest {

    @Test
    fun renderDoesNotCrashWhenTabsOverflowAvailableWidth() {
        val styleSheet = StyleSheet()
        val child = object : BaseComponent(styleSheet) {
            override fun render(canvas: CanvasRenderer) = Unit
            override fun dispatch(event: UIEvent): Boolean = false
        }
        val tabs = TabView(
            styleSheet = styleSheet,
            titles = listOf("Files", "Project", "Git"),
            tabComponents = listOf(child, child, child)
        )

        tabs.render(NoopRenderer(cols = 2, rows = 5))
    }
}
