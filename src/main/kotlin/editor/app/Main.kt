package editor.app

import react.BaseComponent
import react.Component
import react.StyleSheet
import react.UIEvent
import react.renderer.AnsiCanvasRenderer
import react.renderer.CanvasRenderer
import java.time.LocalDateTime.now

fun runApp(app: Component, renderer: CanvasRenderer = AnsiCanvasRenderer(), idleSleepMillis: Long = 8L) {
    fun redraw() {
        renderer.clear()
        app.render(renderer)
        renderer.flush()
    }

    try {
        renderer.enableMouseTracking()
        renderer.hideCursor()
        redraw()

        while (renderer.isRunning()) {
            val event = renderer.tryPollEvent()
            if (event == null) {
                Thread.sleep(idleSleepMillis)
                continue
            }

            val needsRender = app.dispatch(event) || event.kind == "resize"
            // if (needsRender) {
                redraw()
            // }
        }
    } finally {
        renderer.showCursor()
        renderer.disableMouseTracking()
        renderer.resetAttributes()
        renderer.shutdown()
    }
}

fun main() {
    val styleSheet = StyleSheet()
    val renderer = AnsiCanvasRenderer()

    val app = object : BaseComponent(styleSheet) {
        override fun render(canvas: CanvasRenderer) {
            canvas.drawText(0, 0, "kt-tui-editor running — press 'q' to quit ${now()}")
        }

        override fun dispatch(event: UIEvent): Boolean {
            if (event.kind == "key_down" && event.key?.lowercase() == "q") {
                renderer.requestExit()
            }
            return event.kind == "resize"
        }
    }

    runApp(app, renderer)
}
