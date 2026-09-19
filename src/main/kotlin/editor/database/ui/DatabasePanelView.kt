package editor.database.ui

import editor.database.DatabaseService
import editor.database.console.SqlConsole
import editor.database.driver.JdbcDriverRegistry
import editor.database.model.ConnectionState
import editor.database.model.DataSourceDefinition
import editor.database.model.DatabaseObject
import editor.database.model.DatabaseObjectType
import editor.database.model.MetadataRequest
import editor.database.model.MetadataRequestKind
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

/** Mouse-first database explorer. Keyboard navigation remains available, but no action depends on it. */
class DatabasePanelView(
    styleSheet: StyleSheet,
    private val service: DatabaseService,
    private val driverRegistry: JdbcDriverRegistry,
    private val onCreateDataSource: () -> Unit,
    private val onOpenConsole: (DataSourceDefinition) -> Unit,
    private val onOpenSession: (SqlConsole) -> Unit,
    private val sessionsProvider: (String) -> List<SqlConsole>,
    private val onCreateSession: (DataSourceDefinition) -> Unit,
    private val onRemoveSession: (SqlConsole) -> Unit,
    private val onInsertSql: (DataSourceDefinition, String) -> Unit,
    private val onInspectObject: (DataSourceDefinition, DatabaseObject) -> Unit,
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private data class TreeLine(
        val key: String,
        val depth: Int,
        val label: String,
        val expandable: Boolean,
        val ref: NodeRef
    )

    private data class Hit(val range: IntRange, val action: Action)

    private sealed interface Action {
        data object NewDataSource : Action
        data object Refresh : Action
        data class Toggle(val key: String) : Action
        data class NewSession(val dataSourceId: String) : Action
        data class RemoveSession(val id: String) : Action
    }

    private sealed interface NodeRef {
        data class DataSource(val id: String) : NodeRef
        data class Metadata(val dataSourceId: String, val objectInfo: DatabaseObject) : NodeRef
        data class SessionFolder(val dataSourceId: String) : NodeRef
        data class Session(val id: String, val dataSourceId: String) : NodeRef
        data object Message : NodeRef
    }

    private var dataSources: List<DataSourceDefinition> = service.dataSources()
    private val expanded = mutableSetOf<String>()
    private val metadataChildren = mutableMapOf<String, List<DatabaseObject>>()
    private val loading = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private var lines: List<TreeLine> = emptyList()
    private var selected = 0
    private var scroll = 0
    private var message = ""
    private var hitsByRow: Map<Int, List<Hit>> = emptyMap()
    private var objectMenu: DatabaseObjectMenu? = null

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val base = styleSheet.getStyle("content").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(base.fg, base.bg)
        val selectedStyle = styleSheet.getStyle("file-entry:selected").withDefaults(base.fg, base.bg)
        canvas.withStyle(base) { drawRect(0, 0, cols, rows) }

        val toolbarHits = mutableListOf<Hit>()
        renderToolbar(canvas, cols, base, button, toolbarHits)
        canvas.withStyle(base) {
            drawText(0, 0, "Databases".take(cols).padEnd(cols, ' '))
            drawText(0, 2, message.take(cols).padEnd(cols, ' '))
        }

        lines = buildLines()
        selected = selected.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val listTop = 3
        val visibleRows = (rows - listTop).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, (lines.size - visibleRows).coerceAtLeast(0))
        val rowHits = mutableMapOf<Int, MutableList<Hit>>()
        lines.drop(scroll).take(visibleRows).forEachIndexed { visibleIndex, line ->
            val row = listTop + visibleIndex
            val absoluteIndex = scroll + visibleIndex
            val marker = if (line.expandable) if (line.key in expanded) "[-]" else "[+]" else "   "
            val prefix = " ".repeat(line.depth * 2)
            val labelStart = prefix.length + marker.length + 1
            val style = if (absoluteIndex == selected) selectedStyle else base
            canvas.withStyle(style) {
                drawText(0, row, (prefix + marker + " " + line.label).take(cols).padEnd(cols, ' '))
            }
            if (line.expandable) {
                rowHits.getOrPut(row) { mutableListOf() } += Hit(
                    prefix.length until (prefix.length + marker.length),
                    Action.Toggle(line.key)
                )
            }
            when (val ref = line.ref) {
                is NodeRef.SessionFolder -> {
                    rowHits.getOrPut(row) { mutableListOf() } += Hit(
                        (cols - 5).coerceAtLeast(labelStart) until cols,
                        Action.NewSession(ref.dataSourceId)
                    )
                    canvas.withStyle(button) { drawText((cols - 5).coerceAtLeast(0), row, " [+]") }
                }
                is NodeRef.Session -> {
                    rowHits.getOrPut(row) { mutableListOf() } += Hit(
                        (cols - 5).coerceAtLeast(labelStart) until cols,
                        Action.RemoveSession(ref.id)
                    )
                    canvas.withStyle(button) { drawText((cols - 5).coerceAtLeast(0), row, " [-]") }
                }
                else -> Unit
            }
        }
        hitsByRow = (mapOf(1 to toolbarHits) + rowHits).mapValues { it.value.toList() }
        objectMenu?.render(canvas)
    }

    private fun renderToolbar(canvas: CanvasRenderer, cols: Int, base: StyleSet, button: StyleSet, hits: MutableList<Hit>) {
        val labels = listOf(" [+ New database] " to Action.NewDataSource, " [refresh] " to Action.Refresh)
        var cursor = 0
        labels.forEach { (label, action) ->
            if (cursor + label.length > cols) return@forEach
            canvas.withStyle(button) { drawText(cursor, 1, label) }
            hits += Hit(cursor until cursor + label.length, action)
            cursor += label.length + 1
        }
        if (cursor < cols) canvas.withStyle(base) { drawText(cursor, 1, " ".repeat(cols - cursor)) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        objectMenu?.let {
            val handled = it.dispatch(event)
            if (event.kind == "key_down" && event.key?.lowercase() in setOf("escape", "esc")) objectMenu = null
            return handled
        }
        if (event.kind == "animation_frame") return false
        if (event.kind == "mouse_scroll") {
            val previous = scroll
            scroll = (scroll - (event.scrollDelta ?: 0)).coerceAtLeast(0)
            return previous != scroll
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return false
            val y = event.y ?: return false
            hitsByRow[y]?.firstOrNull { x in it.range }?.let { hit ->
                handle(hit.action)
                return true
            }
            val index = scroll + y - 3
            if (index !in lines.indices) return false
            selected = index
            val line = lines[index]
            if (isSecondary(event) && line.ref is NodeRef.Metadata) {
                openObjectMenu(line.ref as NodeRef.Metadata)
                return true
            }
            if (line.ref is NodeRef.Metadata && isEntity(line.ref as NodeRef.Metadata) &&
                x >= line.depth * 2 + 3
            ) {
                openObjectMenu(line.ref as NodeRef.Metadata)
                return true
            }
            return activate(line)
        }
        if (event.kind != "key_down") return false
        return when (event.key?.lowercase()) {
            "up" -> moveSelection(-1)
            "down" -> moveSelection(1)
            "pageup" -> moveSelection(-10)
            "pagedown" -> moveSelection(10)
            "home" -> { selected = 0; true }
            "end" -> { selected = (lines.size - 1).coerceAtLeast(0); true }
            "enter", "right" -> lines.getOrNull(selected)?.let(::activate) ?: false
            "left" -> collapseSelected()
            "n" -> { onCreateDataSource(); true }
            "r" -> { refreshSelected(); true }
            "s" -> selectedDataSource()?.let { onOpenConsole(it); true } ?: false
            "c" -> selectedDataSource()?.let { toggleConnection(it); true } ?: false
            "delete" -> selectedDataSource()?.let {
                service.removeDataSource(it.id)
                reload()
                message = "Removed ${it.name}"
                onInvalidate()
                true
            } ?: false
            else -> false
        }
    }

    fun reload() {
        dataSources = service.dataSources()
        selected = selected.coerceAtMost((lines.size - 1).coerceAtLeast(0))
        onInvalidate()
    }

    fun resetTree() {
        expanded.clear()
        metadataChildren.clear()
        loading.clear()
        errors.clear()
        selected = 0
        scroll = 0
        reload()
    }

    private fun buildLines(): List<TreeLine> {
        val out = mutableListOf<TreeLine>()
        dataSources.forEach { ds ->
            val state = service.status(ds.id).state
            val stateLabel = when (state) {
                ConnectionState.CONNECTED -> "[connected]"
                ConnectionState.CONNECTING -> "[connecting]"
                ConnectionState.ERROR -> "[error]"
                ConnectionState.DISCONNECTED -> "[offline]"
            }
            val key = dsKey(ds.id)
            out += TreeLine(key, 0, "${ds.name} $stateLabel", true, NodeRef.DataSource(ds.id))
            if (key !in expanded) return@forEach
            val sessionKey = sessionsKey(ds.id)
            val sessions = sessionsProvider(ds.id)
            out += TreeLine(sessionKey, 1, "Sessions (${sessions.size})", true, NodeRef.SessionFolder(ds.id))
            if (sessionKey in expanded) {
                sessions.forEach { session ->
                    out += TreeLine("session:${session.id}", 2, session.title, false, NodeRef.Session(session.id, ds.id))
                }
            }
            when {
                key in loading -> out += TreeLine("$key:loading", 1, "Loading metadata...", false, NodeRef.Message)
                errors[key] != null -> out += TreeLine("$key:error", 1, errors[key].orEmpty(), false, NodeRef.Message)
                else -> metadataChildren[key].orEmpty().forEach { obj -> appendObject(out, ds.id, obj, 1) }
            }
        }
        return out
    }

    private fun appendObject(out: MutableList<TreeLine>, dataSourceId: String, obj: DatabaseObject, depth: Int) {
        val key = objKey(dataSourceId, obj)
        out += TreeLine(key, depth, obj.name, obj.hasChildren, NodeRef.Metadata(dataSourceId, obj))
        if (key !in expanded) return
        when {
            key in loading -> out += TreeLine("$key:loading", depth + 1, "Loading...", false, NodeRef.Message)
            errors[key] != null -> out += TreeLine("$key:error", depth + 1, errors[key].orEmpty(), false, NodeRef.Message)
            else -> metadataChildren[key].orEmpty().forEach { child -> appendObject(out, dataSourceId, child, depth + 1) }
        }
    }

    private fun handle(action: Action) {
        when (action) {
            Action.NewDataSource -> onCreateDataSource()
            Action.Refresh -> refreshSelected()
            is Action.Toggle -> toggleKey(action.key)
            is Action.NewSession -> dataSources.firstOrNull { it.id == action.dataSourceId }?.let(onCreateSession)
            is Action.RemoveSession -> findSession(action.id)?.let(onRemoveSession)
        }
    }

    private fun activate(line: TreeLine): Boolean {
        when (val ref = line.ref) {
            is NodeRef.DataSource -> {
                val ds = dataSources.firstOrNull { it.id == ref.id } ?: return false
                if (service.status(ds.id).state != ConnectionState.CONNECTED) toggleConnection(ds) else toggleKey(line.key)
            }
            is NodeRef.SessionFolder -> toggleKey(line.key)
            is NodeRef.Session -> findSession(ref.id)?.let(onOpenSession)
            is NodeRef.Metadata -> if (line.expandable) toggleKey(line.key) else openObjectMenu(ref)
            NodeRef.Message -> return false
        }
        return true
    }

    private fun openObjectMenu(ref: NodeRef.Metadata) {
        val ds = dataSources.firstOrNull { it.id == ref.dataSourceId } ?: return
        objectMenu = DatabaseObjectMenu(
            styleSheet,
            ds,
            ref.objectInfo,
            onAction = { sql -> onInsertSql(ds, sql) },
            onDismiss = { objectMenu = null }
        )
    }

    private fun toggleKey(key: String) {
        if (key in expanded) {
            expanded.remove(key)
            onInvalidate()
            return
        }
        expanded += key
        val line = lines.firstOrNull { it.key == key } ?: return
        when (val ref = line.ref) {
            is NodeRef.DataSource -> if (!metadataChildren.containsKey(key)) loadChildren(key, ref.id, MetadataRequest(MetadataRequestKind.ROOT), false)
            is NodeRef.Metadata -> if (!metadataChildren.containsKey(key)) loadChildren(key, ref.dataSourceId, requestFor(ref.objectInfo), false)
            is NodeRef.SessionFolder, is NodeRef.Session, NodeRef.Message -> Unit
        }
        onInvalidate()
    }

    private fun loadChildren(key: String, dataSourceId: String, request: MetadataRequest, refresh: Boolean) {
        loading += key
        errors.remove(key)
        onInvalidate()
        Thread({
            runCatching { service.introspect(dataSourceId, request, refresh).objects }
                .onSuccess { objects ->
                    metadataChildren[key] = objects
                    message = "Loaded ${objects.size} metadata items"
                }
                .onFailure { ex -> errors[key] = ex.message ?: ex::class.simpleName ?: "metadata error" }
            loading.remove(key)
            onInvalidate()
        }, "database-metadata").apply { isDaemon = true }.start()
    }

    private fun toggleConnection(ds: DataSourceDefinition) {
        if (service.status(ds.id).state == ConnectionState.CONNECTED) {
            service.disconnect(ds.id)
            message = "Disconnected ${ds.name}"
            onInvalidate()
            return
        }
        message = "Connecting ${ds.name}..."
        onInvalidate()
        Thread({
            val status = service.connect(ds.id)
            message = status.message ?: status.state.name.lowercase()
            if (status.state == ConnectionState.CONNECTED) {
                expanded += dsKey(ds.id)
                loadChildren(dsKey(ds.id), ds.id, MetadataRequest(MetadataRequestKind.ROOT), true)
            }
            onInvalidate()
        }, "database-connect").apply { isDaemon = true }.start()
    }

    private fun refreshSelected() {
        val line = lines.getOrNull(selected) ?: return
        when (val ref = line.ref) {
            is NodeRef.DataSource -> {
                service.refreshAll(ref.id)
                metadataChildren.remove(line.key)
                if (service.status(ref.id).state == ConnectionState.CONNECTED) loadChildren(line.key, ref.id, MetadataRequest(MetadataRequestKind.ROOT), true)
            }
            is NodeRef.Metadata -> {
                metadataChildren.remove(line.key)
                loadChildren(line.key, ref.dataSourceId, requestFor(ref.objectInfo), true)
            }
            else -> Unit
        }
    }

    private fun collapseSelected(): Boolean {
        val key = lines.getOrNull(selected)?.key ?: return false
        if (key in expanded) {
            expanded.remove(key)
            onInvalidate()
            return true
        }
        return false
    }

    private fun moveSelection(delta: Int): Boolean {
        val next = (selected + delta).coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        if (next == selected) return false
        selected = next
        return true
    }

    private fun selectedDataSource(): DataSourceDefinition? {
        val ref = lines.getOrNull(selected)?.ref ?: return null
        val id = when (ref) {
            is NodeRef.DataSource -> ref.id
            is NodeRef.Metadata -> ref.dataSourceId
            is NodeRef.SessionFolder -> ref.dataSourceId
            is NodeRef.Session -> ref.dataSourceId
            NodeRef.Message -> return null
        }
        return dataSources.firstOrNull { it.id == id }
    }

    private fun requestFor(obj: DatabaseObject): MetadataRequest = when (obj.objectType) {
        DatabaseObjectType.CATALOG -> MetadataRequest(MetadataRequestKind.CATALOG, catalog = obj.catalog ?: obj.name)
        DatabaseObjectType.SCHEMA -> MetadataRequest(MetadataRequestKind.SCHEMA, catalog = obj.catalog, schema = obj.schema ?: obj.name)
        DatabaseObjectType.CATEGORY -> MetadataRequest(
            MetadataRequestKind.CATEGORY,
            catalog = obj.catalog,
            schema = obj.schema,
            category = obj.attributes["categoryType"]?.let { DatabaseObjectType.valueOf(it) }
        )
        else -> MetadataRequest(
            MetadataRequestKind.OBJECT,
            catalog = obj.catalog,
            schema = obj.schema,
            objectName = obj.name,
            objectType = obj.objectType
        )
    }

    private fun findSession(id: String): SqlConsole? = dataSources.asSequence()
        .map { ds -> sessionsProvider(ds.id) }
        .flatten()
        .firstOrNull { it.id == id }

    private fun isSecondary(event: UIEvent): Boolean = event.button == 1 || event.button == 2

    private fun isEntity(ref: NodeRef.Metadata): Boolean = ref.objectInfo.objectType in setOf(
        DatabaseObjectType.TABLE,
        DatabaseObjectType.VIEW,
        DatabaseObjectType.PROCEDURE,
        DatabaseObjectType.FUNCTION,
        DatabaseObjectType.PACKAGE,
        DatabaseObjectType.TYPE
    )

    private fun dsKey(id: String) = "ds:$id"
    private fun sessionsKey(id: String) = "sessions:$id"
    private fun objKey(dataSourceId: String, obj: DatabaseObject) = "$dataSourceId:${obj.id}"
}
