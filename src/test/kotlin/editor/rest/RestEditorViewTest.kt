package editor.rest

import editor.lib.SystemClipboard
import editor.rest.model.RestWorkspaceModel
import editor.rest.ui.RestEditorView
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import react.StyleSheet
import react.UIEvent
import react.renderer.StringSnapshotRenderer

class RestEditorViewTest {
    @Test
    fun environmentEditorReceivesTypingAndClipboardEvents() {
        val model = RestWorkspaceModel()
        val environment = model.createEnvironment("Development")
        model.updateEnvironmentText(environment.id, "host=example.test")
        val view = RestEditorView(
            styleSheet = StyleSheet(),
            model = model,
            workspaceRoot = createTempDirectory("rest-environment-editor"),
            onSend = {},
            onChanged = {},
            onInvalidate = {}
        )

        view.openEnvironment(environment.id)
        val renderer = StringSnapshotRenderer(cols = 80, rows = 16)
        view.render(renderer)
        assertTrue(view.dispatch(UIEvent(kind = "mouse_down", x = 0, y = 6, cols = 80, rows = 16)))

        listOf("end", "enter", "n", "e", "w", "=", "1").forEach { key ->
            assertTrue(view.dispatch(UIEvent(kind = "key_down", key = key, cols = 80, rows = 16)))
            view.render(renderer)
        }
        assertEquals("1", model.environment(environment.id)?.values?.last()?.value)

        SystemClipboard.setText("pasted=ok")
        assertTrue(view.dispatch(UIEvent(kind = "key_down", key = "a", ctrl = true, cols = 80, rows = 16)))
        assertTrue(view.dispatch(UIEvent(kind = "key_down", key = "v", ctrl = true, cols = 80, rows = 16)))
        view.render(renderer)
        assertEquals("pasted", model.environment(environment.id)?.values?.single()?.key)
        assertEquals("ok", model.environment(environment.id)?.values?.single()?.value)
    }
}
