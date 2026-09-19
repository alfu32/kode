package editor.rest.ui

import editor.rest.http.RestResponse
import editor.rest.model.RestNodePath
import editor.rest.model.RestWorkspaceModel
import editor.rest.model.array
import editor.rest.model.items
import editor.rest.model.isRequestNode
import editor.rest.model.jsonObject
import editor.rest.model.string
import editor.rest.model.withField
import editor.rest.model.withoutField
import editor.rest.model.withOptionalField
import editor.rest.resolve.RestRequestResolver
import editor.ui.FormFieldRenderer
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

enum class RestEditorTab { OVERVIEW, AUTHORIZATION, VARIABLES, PARAMS, HEADERS, BODY, SETTINGS, RESPONSE }
enum class RestResponseTab { BODY, HEADERS, COOKIES, RAW }
data class RestRunSummary(val path: RestNodePath, val text: String)

class RestEditorView(
    styleSheet: StyleSheet,
    private val model: RestWorkspaceModel,
    private val workspaceRoot: Path,
    private val onSend: (RestNodePath) -> Unit,
    private val onClearCookies: () -> Unit = {},
    private val onChanged: () -> Unit,
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private sealed interface Action {
        data object Send : Action
        data class Tab(val tab: RestEditorTab) : Action
        data class ResponseTab(val tab: RestResponseTab) : Action
        data object FocusName : Action
        data object FocusDescription : Action
        data object FocusMethod : Action
        data object FocusUrl : Action
        data object FocusBody : Action
        data object AddVariable : Action
        data object AddHeader : Action
        data object AddQuery : Action
        data object AddBodyEntry : Action
        data class EditVariable(val index: Int) : Action
        data class EditHeader(val index: Int) : Action
        data class EditQuery(val index: Int) : Action
        data class EditPath(val index: Int) : Action
        data class EditBodyEntry(val index: Int) : Action
        data class ToggleEntry(val kind: String, val index: Int) : Action
        data class Auth(val type: String?) : Action
        data class BodyMode(val mode: String) : Action
        data object ToggleRedirects : Action
        data object ToggleSsl : Action
        data object ClearCookies : Action
        data object RevealSecrets : Action
        data object FocusTimeout : Action
        data class OpenRunResult(val path: RestNodePath) : Action
        data class EditAuth(val index: Int) : Action
        data object FocusGraphqlVariables : Action
    }

    private data class Hit(val row: Int, val range: IntRange, val action: Action)

    private class Field(initial: String = "") {
        var value = initial
        var cursor = initial.length

        fun set(next: String) {
            value = next
            cursor = value.length
        }

        fun edit(event: UIEvent): Boolean {
            val key = event.key ?: return false
            when (key.lowercase()) {
                "left" -> if (cursor > 0) cursor-- else return false
                "right" -> if (cursor < value.length) cursor++ else return false
                "home" -> cursor = 0
                "end" -> cursor = value.length
                "backspace" -> if (cursor > 0) {
                    value = value.removeRange(cursor - 1, cursor)
                    cursor--
                } else return false
                "delete" -> if (cursor < value.length) value = value.removeRange(cursor, cursor + 1) else return false
                else -> if (!event.ctrl && !event.alt && !event.meta && key.length == 1) {
                    value = value.substring(0, cursor) + key + value.substring(cursor)
                    cursor++
                } else return false
            }
            return true
        }
    }

    private var currentPath = RestNodePath()
    private var tab = RestEditorTab.OVERVIEW
    private var responseTab = RestResponseTab.BODY
    private var response: RestResponse? = null
    private var runSummary: List<RestRunSummary>? = null
    private val runResponses = mutableMapOf<RestNodePath, RestResponse>()
    private var running = false
    private var focused: Field? = null
    private val method = Field("GET")
    private val url = Field()
    private val name = Field()
    private val description = Field()
    private val rawBody = Field()
    private val graphqlVariables = Field("{}")
    private val timeout = Field("30000")
    private val hits = mutableListOf<Hit>()
    private var entryDialog: RestKeyValueDialog? = null
    private var entryKind: String = ""
    private var entryIndex: Int = -1
    private var draggingResponse = false
    private var revealSecrets = false

    fun open(path: RestNodePath) {
        currentPath = path
        model.select(path)
        response = runResponses[path]
        runSummary = null
        running = false
        focused = null
        syncFields()
        tab = if (path.isRoot || model.node(path)?.isRequestNode() != true) RestEditorTab.OVERVIEW else RestEditorTab.PARAMS
        onInvalidate()
    }

    fun markRunning() {
        running = true
        response = null
        runSummary = null
        onInvalidate()
    }

    fun showResponse(result: RestResponse) {
        running = false
        response = result
        runResponses[currentPath] = result
        runSummary = null
        tab = RestEditorTab.RESPONSE
        onInvalidate()
    }

    fun clearRunHistory() {
        runResponses.clear()
        response = null
    }

    fun showRunSummary(summary: List<RestRunSummary>) {
        running = false
        response = null
        runSummary = summary
        tab = RestEditorTab.RESPONSE
        onInvalidate()
    }

    fun currentPath(): RestNodePath = currentPath

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val base = styleSheet.getStyle("content").withDefaults()
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(base.fg, base.bg)
        val button = styleSheet.getStyle("lsp-button").withDefaults(base.fg, base.bg)
        canvas.withStyle(base) { drawRect(0, 0, cols, rows) }
        hits.clear()
        val node = model.node(currentPath)
        if (currentPath.isRoot || node?.isRequestNode() != true) renderNodeEditor(canvas, cols, base, active, node)
        else renderRequestEditor(canvas, cols, rows, base, active, button, node)
        entryDialog?.render(canvas)
    }

    private fun renderNodeEditor(canvas: CanvasRenderer, cols: Int, base: StyleSet, active: StyleSet, node: JsonObject?) {
        val title = if (currentPath.isRoot) model.collection.jsonObject("info")?.string("name") ?: "REST API" else node?.string("name") ?: "Folder"
        val kind = if (currentPath.isRoot) "COLLECTION" else "FOLDER"
        drawLine(canvas, base, 0, kind + " | " + title, cols)
        drawTabs(canvas, cols, base, active, listOf(RestEditorTab.OVERVIEW, RestEditorTab.AUTHORIZATION, RestEditorTab.VARIABLES), 2)
        when (tab) {
            RestEditorTab.OVERVIEW -> {
                renderLabeledField(canvas, base, 5, "Name", name, cols, Action.FocusName)
                renderLabeledField(canvas, base, 6, "Description", description, cols, Action.FocusDescription)
                drawLine(canvas, base, 8, if (currentPath.isRoot) "Postman Collection Format v2.1.0" else "Folder items: " + (node?.items()?.size ?: 0), cols)
            }
            RestEditorTab.AUTHORIZATION -> renderAuth(canvas, cols, base, currentPath)
            RestEditorTab.VARIABLES -> renderVariables(canvas, cols, base, currentPath)
            else -> Unit
        }
        runSummary?.let { summary ->
            drawLine(canvas, base, 11, "Collection Run", cols)
            summary.take(10).forEachIndexed { index, entry ->
                drawLine(canvas, base, 12 + index, entry.text, cols)
                hits += Hit(12 + index, 0 until cols, Action.OpenRunResult(entry.path))
            }
        }
    }

    private fun renderRequestEditor(canvas: CanvasRenderer, cols: Int, rows: Int, base: StyleSet, active: StyleSet, button: StyleSet, node: JsonObject) {
        val request = node.jsonObject("request") ?: return
        val requestMethod = request.string("method") ?: "GET"
        drawLine(canvas, base, 0, requestMethod + " | " + (node.string("name") ?: "Request"), cols)
        val send = if (running) "[ cancel ]" else "[ Send ]"
        val sendX = (cols - send.length).coerceAtLeast(0)
        val methodWidth = 10.coerceAtMost((sendX - 2).coerceAtLeast(3))
        val urlX = methodWidth + 2
        val urlWidth = (sendX - urlX - 1).coerceAtLeast(3)
        hits += Hit(1, FormFieldRenderer.draw(canvas, styleSheet, 0, 1, methodWidth, method.value, method.cursor, focused === method), Action.FocusMethod)
        hits += Hit(1, FormFieldRenderer.draw(canvas, styleSheet, urlX, 1, urlWidth, url.value, url.cursor, focused === url), Action.FocusUrl)
        canvas.withStyle(button) { drawText(sendX, 1, send) }
        hits += Hit(1, sendX until cols, Action.Send)
        val tabs = listOf(RestEditorTab.PARAMS, RestEditorTab.AUTHORIZATION, RestEditorTab.HEADERS, RestEditorTab.BODY, RestEditorTab.VARIABLES, RestEditorTab.SETTINGS, RestEditorTab.RESPONSE)
        drawTabs(canvas, cols, base, active, tabs, 3)
        val responseHeight = if (response != null || running || runSummary != null) model.ui.responsePanelHeight.coerceAtMost((rows - 8).coerceAtLeast(5)) else 0
        val editorRows = (rows - responseHeight - if (responseHeight > 0) 1 else 0).coerceAtLeast(6)
        when (tab) {
            RestEditorTab.PARAMS -> renderParams(canvas, cols, base)
            RestEditorTab.AUTHORIZATION -> renderAuth(canvas, cols, base, currentPath)
            RestEditorTab.HEADERS -> renderHeaders(canvas, cols, base, button)
            RestEditorTab.BODY -> renderBody(canvas, cols, base)
            RestEditorTab.VARIABLES -> renderVariables(canvas, cols, base, currentPath)
            RestEditorTab.SETTINGS -> renderSettings(canvas, cols, base)
            RestEditorTab.RESPONSE -> drawLine(canvas, base, 5, "Response is displayed below.", cols)
            else -> Unit
        }
        if (responseHeight > 0) {
            canvas.withStyle(styleSheet.getStyle("splitter").withDefaults(base.fg, base.bg)) { drawText(0, editorRows, "-".repeat(cols).take(cols)) }
            renderResponse(canvas, cols, editorRows + 1, responseHeight, base, active)
        }
    }

    private fun drawTabs(canvas: CanvasRenderer, cols: Int, base: StyleSet, active: StyleSet, tabs: List<RestEditorTab>, row: Int) {
        var x = 0
        tabs.forEach { candidate ->
            val label = " " + candidate.name.lowercase() + " "
            if (x < cols) {
                canvas.withStyle(if (candidate == tab) active else base) { drawText(x, row, label.take(cols - x)) }
                hits += Hit(row, x until (x + label.length).coerceAtMost(cols), Action.Tab(candidate))
            }
            x += label.length + 1
        }
    }

    private fun renderAuth(canvas: CanvasRenderer, cols: Int, base: StyleSet, path: RestNodePath) {
        val node = model.node(path) ?: model.collection
        val auth = if (path.isRoot) node.jsonObject("auth") else if (node.isRequestNode()) node.jsonObject("request")?.jsonObject("auth") else node.jsonObject("auth")
        val effective = if (!path.isRoot && auth == null) RestRequestResolver(model.collection, path, workspaceRoot).effectiveAuth() else null
        drawLine(canvas, base, 5, "Auth: " + (auth?.string("type") ?: "inherit auth from parent"), cols)
        drawLine(canvas, base, 6, "[inherit] [noauth] [bearer] [basic] [apikey]", cols)
        val choices = listOf(null, "noauth", "bearer", "basic", "apikey")
        var x = 0
        choices.forEach { type ->
            val label = "[" + (type ?: "inherit") + "]"
            hits += Hit(6, x until x + label.length, Action.Auth(type))
            x += label.length + 1
        }
        val authValues = auth?.let { definition ->
            definition.string("type")?.let { type -> definition.array(type).orEmpty().mapNotNull { it as? JsonObject } }
        }.orEmpty()
        authValues.forEachIndexed { index, entry ->
            val key = entry.string("key").orEmpty()
            val value = entry.string("value").orEmpty()
            val hidden = key in setOf("token", "password", "value") || key.contains("secret", true)
            drawLine(canvas, base, 8 + index, key + ": " + if (hidden && !revealSecrets) "********" else value, cols)
            hits += Hit(8 + index, 0 until cols, Action.EditAuth(index))
        }
        val reveal = "[reveal secrets]"
        drawLine(canvas, base, 12, reveal, cols)
        hits += Hit(12, 0 until reveal.length, Action.RevealSecrets)
        drawLine(canvas, base, 13, if (path.isRoot) "Collection auth is the root default." else "Omitting auth inherits from the nearest ancestor.", cols)
        effective?.let { drawLine(canvas, base, 14, "Effective: " + it.type + " from " + it.source, cols) }
    }

    private fun renderVariables(canvas: CanvasRenderer, cols: Int, base: StyleSet, path: RestNodePath) {
        drawLine(canvas, base, 5, "[+ variable] [reveal secrets] Enabled Variable Value Type Source", cols)
        hits += Hit(5, 0 until 13, Action.AddVariable)
        hits += Hit(5, 14 until 31, Action.RevealSecrets)
        val local = model.node(path)?.array("variable").orEmpty().mapNotNull { it as? JsonObject }
        local.forEachIndexed { index, variable ->
            val key = variable.string("key").orEmpty()
            val value = variable.string("value").orEmpty()
            val enabled = if (variable.booleanValue("disabled")) "[ ]" else "[x]"
            drawLine(canvas, base, 7 + index, enabled + " " + key + " = " + mask(key, value) + " (" + (variable.string("type") ?: "string") + ") local", cols)
            hits += Hit(7 + index, 0 until 4, Action.ToggleEntry("variable", index))
            hits += Hit(7 + index, 4 until cols, Action.EditVariable(index))
        }
        val effective = RestRequestResolver(model.collection, path, workspaceRoot).effectiveVariables()
        var row = 8 + local.size
        effective.filter { value -> local.none { it.string("key") == value.key } }.forEach { value ->
            drawLine(canvas, base, row++, "    " + value.key + " = " + mask(value.key, value.value) + " (" + value.type + ") " + value.source, cols)
        }
    }

    private fun renderParams(canvas: CanvasRenderer, cols: Int, base: StyleSet) {
        val request = model.node(currentPath)?.jsonObject("request") ?: return
        val urlObject = request["url"] as? JsonObject
        drawLine(canvas, base, 5, "Query Parameters", cols)
        val query = urlObject?.array("query").orEmpty().mapNotNull { it as? JsonObject }
        query.forEachIndexed { index, value ->
            val enabled = if (value.booleanValue("disabled")) "[ ]" else "[x]"
            drawLine(canvas, base, 6 + index, enabled + " " + value.string("key").orEmpty() + " = " + value.string("value").orEmpty(), cols)
            hits += Hit(6 + index, 0 until 4, Action.ToggleEntry("query", index))
            hits += Hit(6 + index, 4 until cols, Action.EditQuery(index))
        }
        val add = "[+ query]"
        drawLine(canvas, base, 7 + query.size, add, cols)
        hits += Hit(7 + query.size, 0 until add.length, Action.AddQuery)
        drawLine(canvas, base, 9 + query.size, "Path Variables", cols)
        urlObject?.array("variable").orEmpty().mapNotNull { it as? JsonObject }.forEachIndexed { index, value ->
            val row = 10 + query.size + index
            drawLine(canvas, base, row, ":" + value.string("key").orEmpty() + " = " + value.string("value").orEmpty(), cols)
            hits += Hit(row, 0 until cols, Action.EditPath(index))
        }
    }

    private fun renderHeaders(canvas: CanvasRenderer, cols: Int, base: StyleSet, button: StyleSet) {
        val headers = model.node(currentPath)?.jsonObject("request")?.array("header").orEmpty().mapNotNull { it as? JsonObject }
        drawLine(canvas, base, 5, "Enabled Header Value Description", cols)
        headers.forEachIndexed { index, header ->
            val enabled = if (header.booleanValue("disabled")) "[ ]" else "[x]"
            drawLine(canvas, base, 6 + index, enabled + " " + header.string("key").orEmpty() + ": " + header.string("value").orEmpty(), cols)
            hits += Hit(6 + index, 0 until 4, Action.ToggleEntry("header", index))
            hits += Hit(6 + index, 4 until cols, Action.EditHeader(index))
        }
        val y = 7 + headers.size
        canvas.withStyle(button) { drawText(0, y, "[+ header]") }
        hits += Hit(y, 0 until 10, Action.AddHeader)
    }

    private fun renderBody(canvas: CanvasRenderer, cols: Int, base: StyleSet) {
        val body = model.node(currentPath)?.jsonObject("request")?.jsonObject("body")
        val mode = body?.string("mode") ?: "none"
        drawLine(canvas, base, 5, "Body mode: " + mode, cols)
        val modes = listOf("none", "raw", "urlencoded", "formdata", "file", "graphql")
        var modeX = 0
        modes.forEach { mode ->
            val label = "[" + mode + "]"
            canvas.withStyle(styleSheet.getStyle("lsp-button")) { drawText(modeX, 6, label) }
            hits += Hit(6, modeX until modeX + label.length, Action.BodyMode(mode))
            modeX += label.length + 1
        }
        when (mode) {
            "urlencoded", "formdata" -> {
                val fieldName = if (mode == "formdata") "formdata" else "urlencoded"
                val values = body?.array(fieldName).orEmpty().mapNotNull { it as? JsonObject }
                values.forEachIndexed { index, value ->
                    val row = 8 + index
                    val type = if (mode == "formdata") " [" + (value.string("type") ?: "text") + "]" else ""
                    drawLine(canvas, base, row, (if (value.booleanValue("disabled")) "[ ]" else "[x]") + " " + value.string("key").orEmpty() + " = " + value.string("value").orEmpty() + type, cols)
                    hits += Hit(row, 0 until cols, Action.EditBodyEntry(index))
                }
                val addRow = 8 + values.size
                drawLine(canvas, base, addRow, if (mode == "formdata") "[+ form field]" else "[+ parameter]", cols)
                hits += Hit(addRow, 0 until cols, Action.AddBodyEntry)
            }
            "graphql" -> {
                renderLabeledField(canvas, base, 8, "Query", rawBody, cols, Action.FocusBody)
                renderLabeledField(canvas, base, 9, "Variables", graphqlVariables, cols, Action.FocusGraphqlVariables)
            }
            "file" -> {
                renderLabeledField(canvas, base, 8, "File", rawBody, cols, Action.FocusBody)
            }
            else -> {
                renderLabeledField(canvas, base, 8, "Raw", rawBody, cols, Action.FocusBody)
            }
        }
    }

    private fun renderSettings(canvas: CanvasRenderer, cols: Int, base: StyleSet) {
        val settings = model.node(currentPath)?.jsonObject("protocolProfileBehavior") ?: JsonObject(emptyMap())
        drawLine(canvas, base, 5, "Follow redirects: " + if (settings.booleanValue("disableRedirects")) "off" else "on", cols)
        hits += Hit(5, 0 until cols, Action.ToggleRedirects)
        renderLabeledField(canvas, base, 6, "Request timeout", timeout, cols, Action.FocusTimeout, suffix = " ms")
        drawLine(canvas, base, 7, "SSL validation: " + if (settings.booleanValue("disableSslVerification")) "off" else "on", cols)
        hits += Hit(7, 0 until cols, Action.ToggleSsl)
        drawLine(canvas, base, 9, "[clear cookies]  Cookie jar is runtime state and is not persisted.", cols)
        hits += Hit(9, 0 until 15, Action.ClearCookies)
    }

    private fun renderResponse(canvas: CanvasRenderer, cols: Int, start: Int, height: Int, base: StyleSet, active: StyleSet) {
        val result = response
        runSummary?.let { summary ->
            drawLine(canvas, base, start, "Collection Run", cols)
            summary.take((height - 2).coerceAtLeast(0)).forEachIndexed { index, entry ->
                drawLine(canvas, base, start + 2 + index, entry.text, cols)
                hits += Hit(start + 2 + index, 0 until cols, Action.OpenRunResult(entry.path))
            }
            return
        }
        val status = when {
            running -> "Response | running..."
            result == null -> "Response"
            result.error != null -> "Response | error: " + result.error
            else -> "Response | " + result.statusCode + " " + result.statusText + " | " + result.durationMs + " ms | " + result.receivedBytes + " bytes"
        }
        drawLine(canvas, base, start, status, cols)
        val tabs = listOf(RestResponseTab.BODY, RestResponseTab.HEADERS, RestResponseTab.COOKIES, RestResponseTab.RAW)
        var x = 0
        tabs.forEach { candidate ->
            val label = " " + candidate.name.lowercase() + " "
            canvas.withStyle(if (candidate == responseTab) active else base) { drawText(x, start + 1, label.take(cols - x)) }
            hits += Hit(start + 1, x until (x + label.length).coerceAtMost(cols), Action.ResponseTab(candidate))
            x += label.length + 1
        }
        if (result == null) return
        val body = when (responseTab) {
            RestResponseTab.BODY -> responseBody(result)
            RestResponseTab.HEADERS -> result.headers.joinToString("\n") { it.name + ": " + it.value }
            RestResponseTab.COOKIES -> result.cookies.joinToString("\n")
            RestResponseTab.RAW -> "HTTP " + (result.statusCode ?: "ERR") + " " + result.statusText + "\n" + result.headers.joinToString("\n") { it.name + ": " + it.value } + "\n\n" + result.bodyText
        }
        body.lines().take((height - 3).coerceAtLeast(0)).forEachIndexed { index, line -> drawLine(canvas, base, start + 3 + index, line, cols) }
    }

    private fun responseBody(result: RestResponse): String {
        if (result.truncated) return "Response body truncated for display. Received: " + result.receivedBytes + " bytes\n\n" + result.bodyText
        if (result.contentType.orEmpty().contains("json", true)) return runCatching { kotlinx.serialization.json.Json { prettyPrint = true }.parseToJsonElement(result.bodyText).toString() }.getOrDefault(result.bodyText)
        if (result.contentType.orEmpty().startsWith("image/") || result.contentType.orEmpty().contains("octet-stream")) return "Binary response\nContent-Type: " + result.contentType + "\nSize: " + result.receivedBytes + " bytes"
        return result.bodyText
    }

    private fun drawLine(canvas: CanvasRenderer, base: StyleSet, row: Int, text: String, cols: Int) {
        canvas.withStyle(base) { drawText(0, row, text.take(cols).padEnd(cols, ' ')) }
    }

    private fun renderLabeledField(
        canvas: CanvasRenderer,
        base: StyleSet,
        row: Int,
        label: String,
        field: Field,
        cols: Int,
        action: Action,
        suffix: String = ""
    ) {
        val labelText = "$label: "
        canvas.withStyle(base) { drawText(0, row, labelText.take(cols)) }
        val x = labelText.length
        val suffixWidth = suffix.length + 1
        val width = (cols - x - suffixWidth).coerceAtLeast(3)
        val range = FormFieldRenderer.draw(canvas, styleSheet, x, row, width, field.value, field.cursor, focused === field)
        hits += Hit(row, range, action)
        if (suffix.isNotEmpty()) canvas.withStyle(base) { drawText(x + width + 1, row, suffix.take((cols - x - width - 1).coerceAtLeast(0))) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        entryDialog?.let { active ->
            val handled = active.dispatch(event)
            if (active.finished) {
                active.result?.let { applyEntry(it) }
                entryDialog = null
            }
            onInvalidate()
            return handled
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            if (response != null || running || runSummary != null) {
                val responseHeight = model.ui.responsePanelHeight.coerceAtMost(((event.rows ?: 1) - 8).coerceAtLeast(5))
                val responseStart = ((event.rows ?: 1) - responseHeight).coerceAtLeast(0)
                if (y == responseStart || y == responseStart - 1) {
                    draggingResponse = true
                    return true
                }
            }
            hits.firstOrNull { y == it.row && x in it.range }?.let { hit ->
                activate(hit.action)
                placeCursor(hit.action, x, hit.range)
            }
            return true
        }
        if (event.kind == "mouse_move" && draggingResponse) {
            val rows = event.rows ?: return true
            val y = event.y ?: return true
            model.setResponsePanelHeight((rows - y).coerceIn(5, 60))
            onChanged()
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_up") {
            draggingResponse = false
            return true
        }
        if (event.kind != "key_down") return false
        val key = event.key?.lowercase() ?: return true
        if (event.ctrl && key == "enter") {
            onSend(currentPath)
            return true
        }
        if (key == "tab") {
            tab = nextTab()
            onInvalidate()
            return true
        }
        focused?.let {
            if (it.edit(event)) {
                applyField(it)
                onChanged()
                onInvalidate()
                return true
            }
        }
        if (key == "escape") focused = null
        return false
    }

    private fun activate(action: Action) {
        when (action) {
            Action.Send -> if (!running) onSend(currentPath)
            is Action.Tab -> { tab = action.tab; focused = null }
            is Action.ResponseTab -> responseTab = action.tab
            Action.FocusName -> focused = name
            Action.FocusDescription -> focused = description
            Action.FocusMethod -> focused = method
            Action.FocusUrl -> focused = url
            Action.FocusBody -> focused = rawBody
            Action.FocusGraphqlVariables -> focused = graphqlVariables
            Action.AddVariable -> addVariable()
            Action.AddHeader -> addHeader()
            Action.AddQuery -> addQuery()
            Action.AddBodyEntry -> addBodyEntry()
            is Action.EditVariable -> openEntryDialog("Edit variable", "variable", action.index)
            is Action.EditHeader -> openEntryDialog("Edit header", "header", action.index)
            is Action.EditQuery -> openEntryDialog("Edit query parameter", "query", action.index)
            is Action.EditPath -> openEntryDialog("Edit path variable", "path", action.index)
            is Action.EditBodyEntry -> openEntryDialog("Edit body field", if (bodyMode() == "formdata") "formdata" else "urlencoded", action.index)
            is Action.EditAuth -> openEntryDialog("Edit auth value", "auth", action.index)
            is Action.ToggleEntry -> toggleEntry(action.kind, action.index)
            is Action.Auth -> setAuth(action.type)
            is Action.BodyMode -> setBodyMode(action.mode)
            Action.ToggleRedirects -> toggleRedirects()
            Action.ToggleSsl -> toggleSsl()
            Action.ClearCookies -> onClearCookies()
            Action.RevealSecrets -> revealSecrets = !revealSecrets
            Action.FocusTimeout -> focused = timeout
            is Action.OpenRunResult -> {
                currentPath = action.path
                model.select(action.path)
                syncFields()
                response = runResponses[action.path]
                runSummary = null
                tab = RestEditorTab.RESPONSE
                focused = null
            }
        }
        onInvalidate()
    }

    private fun placeCursor(action: Action, x: Int, range: IntRange) {
        val field = when (action) {
            Action.FocusName -> name
            Action.FocusDescription -> description
            Action.FocusMethod -> method
            Action.FocusUrl -> url
            Action.FocusBody -> rawBody
            Action.FocusGraphqlVariables -> graphqlVariables
            Action.FocusTimeout -> timeout
            else -> null
        } ?: return
        val innerWidth = range.count().coerceAtLeast(1)
        val windowStart = (field.cursor - innerWidth + 1).coerceAtLeast(0)
        field.cursor = (windowStart + (x - range.first)).coerceIn(0, field.value.length)
    }

    private fun nextTab(): RestEditorTab {
        val options = if (model.node(currentPath)?.isRequestNode() == true) listOf(RestEditorTab.PARAMS, RestEditorTab.AUTHORIZATION, RestEditorTab.HEADERS, RestEditorTab.BODY, RestEditorTab.VARIABLES, RestEditorTab.SETTINGS, RestEditorTab.RESPONSE) else listOf(RestEditorTab.OVERVIEW, RestEditorTab.AUTHORIZATION, RestEditorTab.VARIABLES)
        return options[(options.indexOf(tab) + 1) % options.size]
    }

    private fun syncFields() {
        val node = model.node(currentPath) ?: model.collection
        name.set(node.string("name") ?: node.jsonObject("info")?.string("name") ?: "REST API")
        description.set(node.string("description") ?: node.jsonObject("info")?.string("description").orEmpty())
        val request = node.jsonObject("request")
        method.set(request?.string("method") ?: "GET")
        val urlElement = request?.get("url")
        url.set((urlElement as? JsonPrimitive)?.content ?: (urlElement as? JsonObject)?.string("raw").orEmpty())
        val body = request?.jsonObject("body")
        rawBody.set(
            body?.string("raw")
                ?: body?.jsonObject("file")?.string("src")
                ?: body?.jsonObject("graphql")?.string("query")
                ?: ""
        )
        graphqlVariables.set(body?.jsonObject("graphql")?.get("variables")?.toString() ?: "{}")
        timeout.set(model.node(currentPath)?.jsonObject("protocolProfileBehavior")?.string("requestTimeout") ?: "30000")
    }

    private fun applyField(field: Field) {
        val node = model.node(currentPath) ?: return
        when (field) {
            name -> if (currentPath.isRoot) model.updateCollection { it.withField("info", (it.jsonObject("info") ?: JsonObject(emptyMap())).withField("name", JsonPrimitive(name.value))) } else model.updateNode(currentPath) { it.withField("name", JsonPrimitive(name.value)) }
            description -> if (currentPath.isRoot) model.updateCollection { it.withField("info", (it.jsonObject("info") ?: JsonObject(emptyMap())).withField("description", JsonPrimitive(description.value))) } else model.updateNode(currentPath) { it.withField("description", JsonPrimitive(description.value)) }
            method, url, rawBody, graphqlVariables, timeout -> model.updateNode(currentPath) { item ->
                val request = item.jsonObject("request") ?: return@updateNode item
                val updated = when (field) {
                    method -> request.withField("method", JsonPrimitive(method.value.ifBlank { "GET" }))
                    url -> {
                        val currentUrl = request["url"] as? JsonObject
                        val rawUrl = url.value
                        val parsedQuery = parseRawQuery(rawUrl)
                        val updatedUrl = (currentUrl ?: buildJsonObject {})
                            .withField("raw", JsonPrimitive(rawUrl))
                            .withOptionalField("query", parsedQuery?.let(::JsonArray))
                        request.withField("url", updatedUrl)
                    }
                    rawBody -> {
                        val body = request.jsonObject("body") ?: buildJsonObject { put("mode", "raw") }
                        val mode = body.string("mode") ?: "raw"
                        val updatedBody = when (mode) {
                            "file" -> body.withField("file", buildJsonObject { put("src", rawBody.value) })
                            "graphql" -> body.withField("graphql", buildJsonObject { put("query", rawBody.value); put("variables", body.jsonObject("graphql")?.get("variables") ?: buildJsonObject {}) })
                            else -> body.withField("raw", JsonPrimitive(rawBody.value))
                        }
                        request.withField("body", updatedBody)
                    }
                    graphqlVariables -> {
                        val body = request.jsonObject("body") ?: buildJsonObject { put("mode", "graphql") }
                        val graphql = body.jsonObject("graphql") ?: buildJsonObject { put("query", rawBody.value) }
                        val variables = runCatching { Json.parseToJsonElement(graphqlVariables.value) }
                            .getOrElse { JsonPrimitive(graphqlVariables.value) }
                        request.withField("body", body.withField("graphql", graphql.withField("variables", variables)))
                    }
                    else -> {
                        val behavior = item.jsonObject("protocolProfileBehavior") ?: JsonObject(emptyMap())
                        item.withField("protocolProfileBehavior", behavior.withField("requestTimeout", JsonPrimitive(timeout.value.filter(Char::isDigit).ifBlank { "30000" })))
                    }
                }
                if (field == timeout) updated else item.withField("request", updated)
            }
        }
    }

    private fun addVariable() {
        val node = model.node(currentPath) ?: model.collection
        val values = node.array("variable").orEmpty().toMutableList()
        values += buildJsonObject { put("key", "newVariable"); put("value", ""); put("type", "string") }
        if (currentPath.isRoot) model.updateCollection { it.withField("variable", JsonArray(values)) } else model.updateNode(currentPath) { it.withField("variable", JsonArray(values)) }
        onChanged()
    }

    private fun addHeader() {
        val item = model.node(currentPath) ?: model.collection
        val request = item.jsonObject("request") ?: return
        val values = request.array("header").orEmpty().toMutableList()
        values += buildJsonObject { put("key", "X-Header"); put("value", "") }
        model.updateNode(currentPath) { it.withField("request", request.withField("header", JsonArray(values))) }
        onChanged()
    }

    private fun addQuery() {
        val item = model.node(currentPath) ?: return
        val request = item.jsonObject("request") ?: return
        val urlObject = request["url"] as? JsonObject ?: buildJsonObject { put("raw", url.value) }
        val values = urlObject.array("query").orEmpty().toMutableList()
        values += buildJsonObject { put("key", "parameter"); put("value", "") }
        val updatedUrl = urlObject.withField("query", JsonArray(values)).withField("raw", JsonPrimitive(rawWithQuery(urlObject.string("raw").orEmpty(), values)))
        model.updateNode(currentPath) { it.withField("request", request.withField("url", updatedUrl)) }
        onChanged()
    }

    private fun addBodyEntry() {
        val item = model.node(currentPath) ?: return
        val request = item.jsonObject("request") ?: return
        val mode = bodyMode()
        val field = if (mode == "formdata") {
            buildJsonObject { put("key", "field"); put("value", ""); put("type", "text") }
        } else {
            buildJsonObject { put("key", "parameter"); put("value", "") }
        }
        val body = request.jsonObject("body") ?: buildJsonObject { put("mode", mode) }
        val fields = body.array(mode).orEmpty().toMutableList()
        fields += field
        model.updateNode(currentPath) { it.withField("request", request.withField("body", body.withField(mode, JsonArray(fields)))) }
        onChanged()
    }

    private fun openEntryDialog(title: String, kind: String, index: Int) {
        val item = model.node(currentPath) ?: model.collection
        val array = when (kind) {
            "variable" -> item.array("variable")
            "header" -> item.jsonObject("request")?.array("header")
            "path" -> (item.jsonObject("request")?.get("url") as? JsonObject)?.array("variable")
            "formdata", "urlencoded" -> item.jsonObject("request")?.jsonObject("body")?.array(kind)
            "auth" -> authFor(item)?.let { auth -> auth.array(auth.string("type").orEmpty()) }
            else -> (item.jsonObject("request")?.get("url") as? JsonObject)?.array("query")
        } ?: return
        val entry = array.getOrNull(index) as? JsonObject ?: return
        entryKind = kind
        entryIndex = index
        entryDialog = RestKeyValueDialog(
            styleSheet,
            title,
            entry.string("key").orEmpty(),
            entry.string("value").orEmpty(),
            entry.string("description").orEmpty(),
            onInvalidate,
            thirdLabel = if (kind == "formdata") "Type (text/file)" else "Description",
            thirdValue = if (kind == "formdata") entry.string("type") ?: "text" else entry.string("description").orEmpty(),
            variableMode = kind == "variable",
            variableType = entry.string("type") ?: "string",
            variableEnabled = !entry.booleanValue("disabled")
        )
    }

    private fun applyEntry(value: RestEntryValue) {
        val item = model.node(currentPath) ?: model.collection
        when (entryKind) {
            "variable" -> {
                val array = item.array("variable").orEmpty().toMutableList()
                val old = array.getOrNull(entryIndex) as? JsonObject ?: return
                array[entryIndex] = old.withField("key", JsonPrimitive(value.key)).withField("value", JsonPrimitive(value.value)).withField("type", JsonPrimitive(value.type)).withField("disabled", JsonPrimitive(!value.enabled)).withOptionalField("description", value.description.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                if (currentPath.isRoot) model.updateCollection { it.withField("variable", JsonArray(array)) }
                else model.updateNode(currentPath) { it.withField("variable", JsonArray(array)) }
            }
            "auth" -> {
                val auth = authFor(item) ?: return
                val type = auth.string("type") ?: return
                val values = auth.array(type).orEmpty().toMutableList()
                val old = values.getOrNull(entryIndex) as? JsonObject ?: return
                values[entryIndex] = old.withField("value", JsonPrimitive(value.value)).withOptionalField("description", value.description.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                updateAuth(auth.withField(type, JsonArray(values)))
            }
            "header" -> {
                val request = item.jsonObject("request") ?: return
                val array = request.array("header").orEmpty().toMutableList()
                val old = array.getOrNull(entryIndex) as? JsonObject ?: return
                array[entryIndex] = old.withField("key", JsonPrimitive(value.key)).withField("value", JsonPrimitive(value.value)).withOptionalField("description", value.description.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                model.updateNode(currentPath) { it.withField("request", request.withField("header", JsonArray(array))) }
            }
            "query" -> {
                val request = item.jsonObject("request") ?: return
                val urlObject = request["url"] as? JsonObject ?: return
                val array = urlObject.array("query").orEmpty().toMutableList()
                val old = array.getOrNull(entryIndex) as? JsonObject ?: return
                array[entryIndex] = old.withField("key", JsonPrimitive(value.key)).withField("value", JsonPrimitive(value.value)).withOptionalField("description", value.description.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                val updatedUrl = urlObject.withField("query", JsonArray(array)).withField("raw", JsonPrimitive(rawWithQuery(urlObject.string("raw").orEmpty(), array)))
                model.updateNode(currentPath) { it.withField("request", request.withField("url", updatedUrl)) }
            }
            "path" -> {
                val request = item.jsonObject("request") ?: return
                val urlObject = request["url"] as? JsonObject ?: return
                val array = urlObject.array("variable").orEmpty().toMutableList()
                val old = array.getOrNull(entryIndex) as? JsonObject ?: return
                array[entryIndex] = old.withField("key", JsonPrimitive(value.key)).withField("value", JsonPrimitive(value.value)).withOptionalField("description", value.description.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                model.updateNode(currentPath) { it.withField("request", request.withField("url", urlObject.withField("variable", JsonArray(array)))) }
            }
            "formdata", "urlencoded" -> {
                val request = item.jsonObject("request") ?: return
                val body = request.jsonObject("body") ?: return
                val array = body.array(entryKind).orEmpty().toMutableList()
                val old = array.getOrNull(entryIndex) as? JsonObject ?: return
                val updated = old.withField("key", JsonPrimitive(value.key)).withField("value", JsonPrimitive(value.value))
                    .withOptionalField(if (entryKind == "formdata") "type" else "description", value.description.takeIf { it.isNotBlank() }?.let(::JsonPrimitive))
                array[entryIndex] = updated
                model.updateNode(currentPath) { it.withField("request", request.withField("body", body.withField(entryKind, JsonArray(array)))) }
            }
        }
        onChanged()
    }

    private fun setAuth(type: String?) {
        val auth = type?.let(::buildAuth)
        if (currentPath.isRoot) model.updateCollection { if (auth == null) it.withoutField("auth") else it.withField("auth", auth) }
        else model.updateNode(currentPath) { item ->
            if (item.isRequestNode()) {
                val request = item.jsonObject("request") ?: return@updateNode item
                item.withField("request", if (auth == null) request.withoutField("auth") else request.withField("auth", auth))
            } else if (auth == null) item.withoutField("auth") else item.withField("auth", auth)
        }
        onChanged()
    }

    private fun buildAuth(type: String): JsonObject = when (type) {
        "noauth" -> buildJsonObject { put("type", "noauth") }
        "bearer" -> buildJsonObject { put("type", "bearer"); put("bearer", JsonArray(listOf(buildJsonObject { put("key", "token"); put("value", "{{bearerToken}}"); put("type", "string") }))) }
        "basic" -> buildJsonObject { put("type", "basic"); put("basic", JsonArray(listOf(buildJsonObject { put("key", "username"); put("value", "{{username}}"); put("type", "string") }, buildJsonObject { put("key", "password"); put("value", "{{password}}"); put("type", "string") }))) }
        "apikey" -> buildJsonObject { put("type", "apikey"); put("apikey", JsonArray(listOf(buildJsonObject { put("key", "key"); put("value", "X-API-Key"); put("type", "string") }, buildJsonObject { put("key", "value"); put("value", "{{apiKey}}"); put("type", "string") }, buildJsonObject { put("key", "in"); put("value", "header"); put("type", "string") }))) }
        else -> buildJsonObject { put("type", type) }
    }

    private fun toggleRedirects() {
        model.updateNode(currentPath) { item ->
            val behavior = item.jsonObject("protocolProfileBehavior") ?: JsonObject(emptyMap())
            item.withField("protocolProfileBehavior", behavior.withField("disableRedirects", JsonPrimitive(!behavior.booleanValue("disableRedirects"))))
        }
        onChanged()
    }

    private fun toggleSsl() {
        model.updateNode(currentPath) { item ->
            val behavior = item.jsonObject("protocolProfileBehavior") ?: JsonObject(emptyMap())
            item.withField("protocolProfileBehavior", behavior.withField("disableSslVerification", JsonPrimitive(!behavior.booleanValue("disableSslVerification"))))
        }
        onChanged()
    }

    private fun toggleEntry(kind: String, index: Int) {
        val item = model.node(currentPath) ?: model.collection
        when (kind) {
            "variable" -> if (currentPath.isRoot) model.updateCollection { it.withField("variable", toggleArrayEntry(it.array("variable"), index)) }
            else model.updateNode(currentPath) { it.withField("variable", toggleArrayEntry(it.array("variable"), index)) }
            "header" -> {
                val request = item.jsonObject("request") ?: return
                model.updateNode(currentPath) { it.withField("request", request.withField("header", toggleArrayEntry(request.array("header"), index))) }
            }
            "query" -> {
                val request = item.jsonObject("request") ?: return
                val urlObject = request["url"] as? JsonObject ?: return
                val entries = toggleArrayEntry(urlObject.array("query"), index)
                val updatedUrl = urlObject.withField("query", entries).withField("raw", JsonPrimitive(rawWithQuery(urlObject.string("raw").orEmpty(), entries)))
                model.updateNode(currentPath) { it.withField("request", request.withField("url", updatedUrl)) }
            }
        }
        onChanged()
    }

    private fun setBodyMode(mode: String) {
        model.updateNode(currentPath) { item ->
            val request = item.jsonObject("request") ?: return@updateNode item
            val existing = request.jsonObject("body") ?: buildJsonObject {}
            val body = existing
                .withField("mode", JsonPrimitive(mode))
                .withOptionalField("raw", rawBody.value.takeIf { mode == "raw" || mode == "graphql" }?.let(::JsonPrimitive))
            item.withField("request", request.withField("body", body))
        }
        onChanged()
    }

    private fun mask(key: String, value: String): String = if (!revealSecrets && (key.contains("token", true) || key.contains("password", true) || key.contains("secret", true))) "********" else value

    private fun authFor(item: JsonObject): JsonObject? = if (currentPath.isRoot) {
        item.jsonObject("auth")
    } else if (item.isRequestNode()) {
        item.jsonObject("request")?.jsonObject("auth")
    } else {
        item.jsonObject("auth")
    }

    private fun updateAuth(auth: JsonObject) {
        if (currentPath.isRoot) {
            model.updateCollection { it.withField("auth", auth) }
        } else {
            model.updateNode(currentPath) { item ->
                if (item.isRequestNode()) {
                    val request = item.jsonObject("request") ?: return@updateNode item
                    item.withField("request", request.withField("auth", auth))
                } else item.withField("auth", auth)
            }
        }
    }

    private fun bodyMode(): String = model.node(currentPath)?.jsonObject("request")?.jsonObject("body")?.string("mode") ?: "urlencoded"

    private fun toggleArrayEntry(array: JsonArray?, index: Int): JsonArray {
        val values = array.orEmpty().toMutableList()
        val entry = values.getOrNull(index) as? JsonObject ?: return JsonArray(values)
        values[index] = entry.withField("disabled", JsonPrimitive(!entry.booleanValue("disabled")))
        return JsonArray(values)
    }

    private fun rawWithQuery(raw: String, entries: List<JsonElement>): String {
        val base = raw.substringBefore('?').substringBefore('#')
        val fragment = raw.substringAfter('#', "").takeIf { raw.contains('#') }?.let { "#$it" }.orEmpty()
        val query = entries.mapNotNull { it as? JsonObject }
            .joinToString("&") { entry -> entry.string("key").orEmpty() + "=" + entry.string("value").orEmpty() }
        return base + (if (query.isBlank()) "" else "?$query") + fragment
    }

    private fun parseRawQuery(raw: String): List<JsonElement>? {
        val query = raw.substringAfter('?', "").substringBefore('#')
        if (!raw.contains('?')) return null
        if (query.isBlank()) return emptyList()
        return query.split('&').map { part ->
            val separator = part.indexOf('=')
            val key = if (separator >= 0) part.substring(0, separator) else part
            val value = if (separator >= 0) part.substring(separator + 1) else ""
            buildJsonObject { put("key", key); put("value", value) }
        }
    }
}

private fun JsonObject.booleanValue(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false
