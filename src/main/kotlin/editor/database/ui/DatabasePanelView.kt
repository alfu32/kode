package editor.database.ui

import editor.database.DatabaseService
import editor.database.driver.JdbcDriverRegistry
import editor.database.model.ConnectionState
import editor.database.model.DataSourceDefinition
import editor.database.model.DatabaseObject
import editor.database.model.DatabaseObjectType
import editor.database.model.DriverSpec
import editor.database.model.MetadataRequest
import editor.database.model.MetadataRequestKind
import java.util.UUID
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class DatabasePanelView(
    styleSheet: StyleSheet,
    private val service: DatabaseService,
    private val driverRegistry: JdbcDriverRegistry,
    private val onOpenConsole: (DataSourceDefinition) -> Unit,
    private val onInspectObject: (DataSourceDefinition, DatabaseObject) -> Unit,
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private var dataSources: List<DataSourceDefinition> = service.dataSources()
    private val expanded = mutableSetOf<String>()
    private val metadataChildren = mutableMapOf<String, List<DatabaseObject>>()
    private val loading = mutableSetOf<String>()
    private val errors = mutableMapOf<String, String>()
    private var lines: List<TreeLine> = emptyList()
    private var selected = 0
    private var scroll = 0
    private var message: String = "n:new  enter:connect/expand  s:sql  r:refresh"
    private var form: DataSourceForm? = null

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val base = styleSheet.getStyle("content")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        canvas.withStyle(base) {
            drawRect(0, 0, cols, rows)
            drawText(0, 0, "Database".padEnd(cols).take(cols))
            if (rows > 1) drawText(0, 1, message.take(cols).padEnd(cols, ' '))
        }
        if (form != null) {
            renderForm(canvas, cols, rows)
            return
        }
        lines = buildLines()
        if (dataSources.isEmpty()) {
            canvas.withStyle(base) {
                if (rows > 2) drawText(0, 2, "No data sources.".take(cols).padEnd(cols, ' '))
                if (rows > 3) drawText(0, 3, "Press n to create an H2 datasource.".take(cols).padEnd(cols, ' '))
            }
            return
        }
        selected = selected.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val visibleRows = (rows - 2).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, (lines.size - visibleRows).coerceAtLeast(0))
        canvas.withStyle(base) {
            lines.drop(scroll).take(visibleRows).forEachIndexed { idx, line ->
                val y = idx + 2
                val marker = if (line.expandable) {
                    if (line.key in expanded) "v" else ">"
                } else " "
                val prefix = " ".repeat(line.depth * 2) + marker + " "
                val label = (prefix + line.label).take(cols).padEnd(cols, ' ')
                if (idx + scroll == selected) {
                    canvas.withStyle(selectedStyle) { drawText(0, y, label) }
                } else {
                    drawText(0, y, label)
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return false
        form?.let { return handleForm(event, it) }
        if (event.kind == "mouse_scroll") {
            val prev = scroll
            scroll = (scroll - (event.scrollDelta ?: 0)).coerceAtLeast(0)
            return prev != scroll
        }
        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            val idx = scroll + y - 2
            if (idx in lines.indices) {
                selected = idx
                return activateSelected()
            }
            return false
        }
        if (event.kind != "key_down") return false
        return when (event.key?.lowercase()) {
            "n" -> {
                form = DataSourceForm.fromDriver(driverRegistry.byId("h2")!!)
                true
            }
            "up" -> {
                selected = (selected - 1).coerceAtLeast(0)
                true
            }
            "down" -> {
                selected = (selected + 1).coerceAtMost((lines.size - 1).coerceAtLeast(0))
                true
            }
            "enter", "right" -> activateSelected()
            "left" -> collapseSelected()
            "s" -> selectedDataSource()?.let {
                onOpenConsole(it)
                true
            } ?: false
            "c" -> selectedDataSource()?.let {
                toggleConnection(it)
                true
            } ?: false
            "r" -> refreshSelected()
            "delete" -> selectedDataSource()?.let {
                service.removeDataSource(it.id)
                dataSources = service.dataSources()
                message = "Removed ${it.name}"
                true
            } ?: false
            else -> false
        }
    }

    fun reload() {
        dataSources = service.dataSources()
    }

    private fun buildLines(): List<TreeLine> {
        val out = mutableListOf<TreeLine>()
        dataSources.forEach { ds ->
            val state = service.status(ds.id).state
            val stateLabel = when (state) {
                ConnectionState.CONNECTED -> "[up]"
                ConnectionState.CONNECTING -> "[..]"
                ConnectionState.ERROR -> "[err]"
                ConnectionState.DISCONNECTED -> "[down]"
            }
            val key = dsKey(ds.id)
            out += TreeLine(key, 0, "${ds.name} $stateLabel", true, NodeRef.DataSource(ds.id))
            if (key in expanded) {
                when {
                    key in loading -> out += TreeLine("$key:loading", 1, "Loading...", false, NodeRef.Message)
                    errors[key] != null -> out += TreeLine("$key:error", 1, errors[key].orEmpty(), false, NodeRef.Message)
                    else -> metadataChildren[key].orEmpty().forEach { obj -> appendObject(out, ds.id, obj, 1) }
                }
            }
        }
        return out
    }

    private fun appendObject(out: MutableList<TreeLine>, dataSourceId: String, obj: DatabaseObject, depth: Int) {
        val key = objKey(dataSourceId, obj)
        out += TreeLine(key, depth, obj.name, obj.hasChildren, NodeRef.Metadata(dataSourceId, obj))
        if (key in expanded) {
            when {
                key in loading -> out += TreeLine("$key:loading", depth + 1, "Loading...", false, NodeRef.Message)
                errors[key] != null -> out += TreeLine("$key:error", depth + 1, errors[key].orEmpty(), false, NodeRef.Message)
                else -> metadataChildren[key].orEmpty().forEach { child -> appendObject(out, dataSourceId, child, depth + 1) }
            }
        }
    }

    private fun activateSelected(): Boolean {
        val line = lines.getOrNull(selected) ?: return false
        return when (val ref = line.ref) {
            is NodeRef.DataSource -> {
                val ds = dataSources.firstOrNull { it.id == ref.id } ?: return false
                if (service.status(ds.id).state != ConnectionState.CONNECTED) {
                    toggleConnection(ds)
                } else {
                    toggleExpand(line.key) { MetadataRequest(MetadataRequestKind.ROOT) }
                }
                true
            }
            is NodeRef.Metadata -> {
                val ds = dataSources.firstOrNull { it.id == ref.dataSourceId } ?: return false
                if (!ref.obj.hasChildren) {
                    onInspectObject(ds, ref.obj)
                    return true
                }
                toggleExpand(line.key) { requestFor(ref.obj) }
                true
            }
            NodeRef.Message -> false
        }
    }

    private fun collapseSelected(): Boolean {
        val key = lines.getOrNull(selected)?.key ?: return false
        if (key in expanded) {
            expanded.remove(key)
            return true
        }
        return false
    }

    private fun refreshSelected(): Boolean {
        val line = lines.getOrNull(selected) ?: return false
        when (val ref = line.ref) {
            is NodeRef.DataSource -> {
                val ds = dataSources.firstOrNull { it.id == ref.id } ?: return false
                service.refreshAll(ds.id)
                if (service.status(ds.id).state == ConnectionState.CONNECTED) loadChildren(line.key, ds.id, MetadataRequest(MetadataRequestKind.ROOT), refresh = true)
            }
            is NodeRef.Metadata -> loadChildren(line.key, ref.dataSourceId, requestFor(ref.obj), refresh = true)
            NodeRef.Message -> return false
        }
        return true
    }

    private fun toggleExpand(key: String, request: () -> MetadataRequest) {
        if (key in expanded) {
            expanded.remove(key)
            return
        }
        expanded += key
        val ref = lines.firstOrNull { it.key == key }?.ref
        val dataSourceId = when (ref) {
            is NodeRef.DataSource -> ref.id
            is NodeRef.Metadata -> ref.dataSourceId
            NodeRef.Message, null -> return
        }
        if (!metadataChildren.containsKey(key)) loadChildren(key, dataSourceId, request(), refresh = false)
    }

    private fun loadChildren(key: String, dataSourceId: String, request: MetadataRequest, refresh: Boolean) {
        loading += key
        errors.remove(key)
        onInvalidate()
        Thread({
            runCatching {
                service.introspect(dataSourceId, request, refresh).objects
            }.onSuccess { objects ->
                metadataChildren[key] = objects
                message = "${objects.size} metadata nodes"
            }.onFailure { ex ->
                errors[key] = ex.message ?: ex::class.simpleName ?: "metadata error"
            }
            loading.remove(key)
            onInvalidate()
        }, "database-metadata").apply { isDaemon = true }.start()
    }

    private fun toggleConnection(ds: DataSourceDefinition) {
        if (service.status(ds.id).state == ConnectionState.CONNECTED) {
            service.disconnect(ds.id)
            message = "Disconnected ${ds.name}"
            return
        }
        message = "Connecting ${ds.name}..."
        onInvalidate()
        Thread({
            val status = service.connect(ds.id)
            message = status.message ?: status.state.name.lowercase()
            if (status.state == ConnectionState.CONNECTED) {
                expanded += dsKey(ds.id)
                loadChildren(dsKey(ds.id), ds.id, MetadataRequest(MetadataRequestKind.ROOT), refresh = true)
            }
            onInvalidate()
        }, "database-connect").apply { isDaemon = true }.start()
    }

    private fun selectedDataSource(): DataSourceDefinition? {
        val line = lines.getOrNull(selected) ?: return null
        val id = when (val ref = line.ref) {
            is NodeRef.DataSource -> ref.id
            is NodeRef.Metadata -> ref.dataSourceId
            NodeRef.Message -> return null
        }
        return dataSources.firstOrNull { it.id == id }
    }

    private fun requestFor(obj: DatabaseObject): MetadataRequest =
        when (obj.objectType) {
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
                objectName = obj.name
            )
        }

    private fun renderForm(canvas: CanvasRenderer, cols: Int, rows: Int) {
        val current = form ?: return
        val base = styleSheet.getStyle("content")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        val labels = current.lines()
        canvas.withStyle(base) {
            drawRect(0, 0, cols, rows)
            drawText(0, 0, "New Data Source".take(cols).padEnd(cols, ' '))
            labels.take(rows - 2).forEachIndexed { idx, line ->
                val y = idx + 1
                if (idx == current.activeIndex) {
                    canvas.withStyle(selectedStyle) { drawText(0, y, line.take(cols).padEnd(cols, ' ')) }
                } else {
                    drawText(0, y, line.take(cols).padEnd(cols, ' '))
                }
            }
            if (rows > 1) drawText(0, rows - 1, "Tab next  Alt+T test  Alt+D driver  Ctrl+S save  Esc cancel".take(cols).padEnd(cols, ' '))
        }
    }

    private fun handleForm(event: UIEvent, current: DataSourceForm): Boolean {
        if (event.kind != "key_down") return false
        val key = event.key ?: return false
        when {
            key.equals("Esc", ignoreCase = true) -> form = null
            event.alt && key.equals("t", ignoreCase = true) -> testForm(current)
            event.alt && key.equals("d", ignoreCase = true) -> downloadFormDriver(current)
            event.ctrl && key.equals("s", ignoreCase = true) -> saveForm(current)
            key.equals("Tab", ignoreCase = true) || key.equals("Enter", ignoreCase = true) -> current.next()
            key.equals("Backspace", ignoreCase = true) -> current.backspace()
            key.length == 1 && !event.ctrl && !event.alt -> current.append(key)
            key.equals("Up", ignoreCase = true) -> current.previous()
            key.equals("Down", ignoreCase = true) -> current.next()
            else -> return false
        }
        return true
    }

    private fun saveForm(current: DataSourceForm) {
        val definition = current.toDefinition()
        service.saveDataSource(definition, current.password.takeIf { it.isNotEmpty() })
        dataSources = service.dataSources()
        selected = dataSources.indexOfFirst { it.id == definition.id }.coerceAtLeast(0)
        form = null
        message = "Saved ${definition.name}"
    }

    private fun testForm(current: DataSourceForm) {
        val definition = current.toDefinition()
        message = "Testing ${definition.name}..."
        onInvalidate()
        Thread({
            val result = service.testConnection(definition, current.password.takeIf { it.isNotEmpty() })
            message = if (result.success) {
                val database = listOfNotNull(result.databaseProduct, result.databaseVersion).joinToString(" ")
                val driver = listOfNotNull(result.driverName, result.driverVersion).joinToString(" ")
                "Connection ok: ${database.ifBlank { driver.ifBlank { definition.name } }} (${result.latencyMs} ms)"
            } else {
                "Connection failed: ${result.message ?: result.sqlState ?: "unknown error"}"
            }
            onInvalidate()
        }, "database-test-connection").apply { isDaemon = true }.start()
    }

    private fun downloadFormDriver(current: DataSourceForm) {
        val definition = current.toDefinition()
        message = "Installing driver ${definition.driver.coordinates ?: definition.driver.id}..."
        onInvalidate()
        Thread({
            runCatching {
                service.downloadDriver(definition)
            }.onSuccess { path ->
                message = "Driver installed: ${path.fileName}"
            }.onFailure { ex ->
                message = "Driver install failed: ${ex.message ?: ex::class.simpleName ?: "error"}"
            }
            onInvalidate()
        }, "database-driver-download").apply { isDaemon = true }.start()
    }

    private fun dsKey(id: String): String = "ds:$id"

    private fun objKey(dataSourceId: String, obj: DatabaseObject): String = "$dataSourceId:${obj.id}"

    private data class TreeLine(
        val key: String,
        val depth: Int,
        val label: String,
        val expandable: Boolean,
        val ref: NodeRef
    )

    private sealed interface NodeRef {
        data class DataSource(val id: String) : NodeRef
        data class Metadata(val dataSourceId: String, val obj: DatabaseObject) : NodeRef
        data object Message : NodeRef
    }

    private data class DataSourceForm(
        val id: String = UUID.randomUUID().toString(),
        var name: String,
        var driverId: String,
        var driverClass: String,
        var coordinates: String,
        var jdbcUrl: String,
        var username: String,
        var password: String = "",
        var activeIndex: Int = 0
    ) {
        fun lines(): List<String> = listOf(
            "Name:       $name",
            "Driver:     $driverId",
            "Class:      $driverClass",
            "Coordinates:$coordinates",
            "JDBC URL:   $jdbcUrl",
            "User:       $username",
            "Password:   ${"*".repeat(password.length)}"
        )

        fun next() {
            activeIndex = (activeIndex + 1) % 7
        }

        fun previous() {
            activeIndex = (activeIndex + 6) % 7
        }

        fun append(value: String) {
            when (activeIndex) {
                0 -> name += value
                1 -> driverId += value
                2 -> driverClass += value
                3 -> coordinates += value
                4 -> jdbcUrl += value
                5 -> username += value
                6 -> password += value
            }
        }

        fun backspace() {
            fun String.dropLastChar() = if (isEmpty()) this else dropLast(1)
            when (activeIndex) {
                0 -> name = name.dropLastChar()
                1 -> driverId = driverId.dropLastChar()
                2 -> driverClass = driverClass.dropLastChar()
                3 -> coordinates = coordinates.dropLastChar()
                4 -> jdbcUrl = jdbcUrl.dropLastChar()
                5 -> username = username.dropLastChar()
                6 -> password = password.dropLastChar()
            }
        }

        fun toDefinition(): DataSourceDefinition {
            return DataSourceDefinition(
                id = id,
                name = name.ifBlank { "local-h2" },
                driver = DriverSpec(
                    id = driverId.ifBlank { "h2" },
                    displayName = driverId.ifBlank { "h2" },
                    driverClass = driverClass.ifBlank { "org.h2.Driver" },
                    coordinates = coordinates.takeIf { it.isNotBlank() }
                ),
                jdbcUrl = jdbcUrl.ifBlank { "jdbc:h2:mem:kode-console;DB_CLOSE_DELAY=-1" },
                username = username,
                credentialReference = "$id.password".takeIf { password.isNotEmpty() },
                autoCommit = true
            )
        }

        companion object {
            fun fromDriver(driver: editor.database.driver.JdbcDriverDefinition): DataSourceForm =
                DataSourceForm(
                    name = "local-h2",
                    driverId = driver.id,
                    driverClass = driver.driverClass,
                    coordinates = driver.defaultMavenCoordinates.orEmpty(),
                    jdbcUrl = driver.defaultJdbcUrlTemplate.ifBlank { "jdbc:h2:mem:kode-console;DB_CLOSE_DELAY=-1" },
                    username = "sa"
                )
        }
    }
}
