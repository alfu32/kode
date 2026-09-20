package editor.rest

import editor.lib.SystemClipboard
import editor.rest.http.RestResponse
import editor.rest.model.RestWorkspaceModel
import editor.rest.model.RestNodePath
import editor.rest.resolve.ResolvedRequest
import editor.rest.ui.RestEditorTab
import editor.rest.ui.RestEditorView
import editor.rest.ui.RestResponseTab
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

    @Test
    fun focusedHeadersEditorKeepsArrowKeysWhenTransactionResponseIsVisible() {
        val model = RestWorkspaceModel()
        val path = model.addRequest(RestNodePath(), "Request")!!
        val view = RestEditorView(
            styleSheet = StyleSheet(),
            model = model,
            workspaceRoot = createTempDirectory("rest-header-editor"),
            onSend = {},
            onChanged = {},
            onInvalidate = {}
        )
        view.open(path)
        view.showResponse(
            RestResponse(
                request = ResolvedRequest(path, "GET", "https://example.test", emptyList()),
                startedAt = 1L,
                durationMs = 1L,
                statusCode = 200,
                bodyBytes = "ok".toByteArray()
            )
        )
        setPrivate(view, "tab", RestEditorTab.HEADERS)
        setPrivate(view, "responseTab", RestResponseTab.TRANSACTION)
        val renderer = StringSnapshotRenderer(cols = 80, rows = 30)
        view.render(renderer)
        assertTrue(view.dispatch(UIEvent(kind = "mouse_down", x = 0, y = 9, cols = 80, rows = 30)))

        val before = privateInt(view, "transactionScroll")
        assertTrue(view.dispatch(UIEvent(kind = "key_down", key = "down", cols = 80, rows = 30)))
        assertEquals(before, privateInt(view, "transactionScroll"))
    }

    private fun setPrivate(target: Any, name: String, value: Any) {
        target.javaClass.getDeclaredField(name).apply {
            isAccessible = true
            set(target, value)
        }
    }

    private fun privateInt(target: Any, name: String): Int =
        target.javaClass.getDeclaredField(name).apply { isAccessible = true }.getInt(target)
}
