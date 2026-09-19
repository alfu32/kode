package editor.database.ui

import editor.database.DatabaseService
import editor.database.driver.JdbcDriverDefinition
import editor.database.driver.JdbcDriverRegistry
import editor.database.model.DataSourceDefinition
import editor.database.model.DriverSpec
import editor.database.model.JdbcDriverArtifact
import editor.database.model.JdbcRepositoryType
import java.util.UUID
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

/** Modal editor for one project database connection. */
class DatabaseConnectionDialog(
    styleSheet: StyleSheet,
    private val service: DatabaseService,
    private val registry: JdbcDriverRegistry,
    existing: DataSourceDefinition? = null,
    private val onApply: (DataSourceDefinition, String?) -> Unit,
    private val onDismiss: () -> Unit,
    private val onDownloadDriver: (DataSourceDefinition) -> Unit = {},
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private data class FieldHit(val index: Int, val row: Int, val x: IntRange)
    private data class ButtonHit(val action: Action, val x: IntRange)
    private enum class Action { TEST, DOWNLOAD, APPLY, CANCEL }

    private class Field(var value: String) {
        var cursor: Int = value.length

        fun set(next: String) {
            value = next
            cursor = value.length
        }

        fun edit(event: UIEvent): Boolean {
            val key = event.key ?: return false
            when (key.lowercase()) {
                "left" -> if (cursor > 0) cursor-- else return false
                "right" -> if (cursor < value.length) cursor++ else return false
                "home" -> if (cursor > 0) cursor = 0 else return false
                "end" -> if (cursor < value.length) cursor = value.length else return false
                "backspace" -> if (cursor > 0) {
                    value = value.removeRange(cursor - 1, cursor)
                    cursor--
                } else return false
                "delete" -> if (cursor < value.length) {
                    value = value.removeRange(cursor, cursor + 1)
                } else return false
                else -> if (!event.ctrl && !event.alt && !event.meta && key.length == 1) {
                    value = value.substring(0, cursor) + key + value.substring(cursor)
                    cursor++
                } else return false
            }
            return true
        }
    }

    private val drivers = registry.definitions
    private val sessionId = existing?.id ?: UUID.randomUUID().toString()
    private val fields = arrayOf(
        Field(existing?.name.orEmpty()),
        Field(existing?.jdbcUrl.orEmpty()),
        Field(existing?.username.orEmpty()),
        Field(""),
        Field(existing?.defaultCatalog.orEmpty()),
        Field(existing?.defaultSchema.orEmpty())
    )
    private val customFields = arrayOf(Field(""), Field(""), Field(""), Field(""))
    private val versionField = Field("")
    private var selectedDriver = drivers.indexOfFirst { it.id == existing?.driver?.id }.takeIf { it >= 0 } ?: 0
    private var focus = 0
    private var driverDropdown = false
    private var status = ""
    private var testing = false
    private var fieldHits: List<FieldHit> = emptyList()
    private var buttonHits: List<ButtonHit> = emptyList()
    private var dropdownRows: IntRange = IntRange.EMPTY
    private var dropdownScroll = 0

    init {
        existing?.let {
            fields[0].set(it.name)
            fields[1].set(it.jdbcUrl)
            fields[2].set(it.username)
            fields[4].set(it.defaultCatalog.orEmpty())
            fields[5].set(it.defaultSchema.orEmpty())
            customFields[0].set(it.driver.driverClass)
            customFields[1].set(it.driver.coordinates.orEmpty())
            customFields[2].set(it.driver.artifact?.repository.orEmpty())
            customFields[3].set(it.driver.jarPath.orEmpty())
            versionField.set(it.driver.artifact?.version ?: it.driver.coordinates?.split(':')?.getOrNull(2).orEmpty())
        }
        if (existing == null) selectDriver(0)
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = minOf(cols, 86).coerceAtLeast(46)
        val height = minOf(rows, if (customMode()) 23 else 19).coerceAtLeast(if (customMode()) 18 else 14)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = ((rows - height) / 2).coerceAtLeast(0)
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(panel.fg, panel.bg)
        val input = styleSheet.getStyle("content").withDefaults(panel.fg, panel.bg)
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(input.fg, input.bg)
        val button = styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)

        fieldHits = emptyList()
        buttonHits = emptyList()
        dropdownRows = IntRange.EMPTY
        canvas.withStyle(panel) { drawRect(x, y, width, height) }
        drawBorder(canvas, border, x, y, width, height)
        canvas.withStyle(panel) {
            drawText(x + 2, y + 1, "New database connection".take(width - 4))
        }

        val labels = formLabels()
        val labelWidth = 16
        val startRow = y + 3
        val available = (width - labelWidth - 6).coerceAtLeast(10)
        val hits = mutableListOf<FieldHit>()
        labels.forEachIndexed { index, label ->
            val row = startRow + index
            if (row >= y + height - 3) return@forEachIndexed
            val selected = focus == index
            canvas.withStyle(if (selected) active else input) {
                drawText(x + 2, row, ("$label:").padEnd(labelWidth).take(labelWidth))
                val text = if (index == 0) driverLabel() else masked(valueForFocus(index))
                drawText(x + 2 + labelWidth, row, text.take(available).padEnd(available, ' '))
            }
            hits += FieldHit(index, row, (x + 2 + labelWidth) until (x + 2 + labelWidth + available))
        }
        fieldHits = hits

        val detailRow = startRow + labels.size
        canvas.withStyle(input) {
            drawText(x + 2, detailRow, "Driver: ${driverClassLabel()}".take(width - 4).padEnd(width - 4, ' '))
            drawText(x + 2, detailRow + 1, "Artifact: ${artifactLabel()}".take(width - 4).padEnd(width - 4, ' '))
            drawText(x + 2, detailRow + 2, (status.ifBlank { "" }).take(width - 4).padEnd(width - 4, ' '))
        }

        val buttonRow = y + height - 2
        lastButtonRow = buttonRow
        val labelsAndActions = listOf(
            " Test " to Action.TEST,
            " Download " to Action.DOWNLOAD,
            " Apply " to Action.APPLY,
            " Cancel " to Action.CANCEL
        )
        var cursor = x + width - labelsAndActions.sumOf { it.first.length } - (labelsAndActions.size - 1)
        val renderedButtons = mutableListOf<ButtonHit>()
        labelsAndActions.forEach { (label, action) ->
            canvas.withStyle(button) { drawText(cursor, buttonRow, label) }
            renderedButtons += ButtonHit(action, cursor until cursor + label.length)
            cursor += label.length + 1
        }
        buttonHits = renderedButtons

        if (driverDropdown && focus == 0) {
            val dropdownX = x + 2 + labelWidth
            val dropdownY = startRow + 1
            val dropdownHeight = drivers.size.coerceAtMost((rows - dropdownY - 1).coerceAtLeast(1))
            val maxScroll = (drivers.size - dropdownHeight).coerceAtLeast(0)
            dropdownScroll = dropdownScroll.coerceIn(0, maxScroll)
            if (selectedDriver < dropdownScroll) dropdownScroll = selectedDriver
            if (selectedDriver >= dropdownScroll + dropdownHeight) dropdownScroll = selectedDriver - dropdownHeight + 1
            dropdownScroll = dropdownScroll.coerceIn(0, maxScroll)
            dropdownRows = dropdownY until (dropdownY + dropdownHeight)
            canvas.withStyle(panel) {
                drawRect(dropdownX, dropdownY, available, dropdownHeight)
                drivers.drop(dropdownScroll).take(dropdownHeight).forEachIndexed { index, driver ->
                    val actualIndex = dropdownScroll + index
                    val label = "${if (actualIndex == selectedDriver) ">" else " "} ${driver.displayName}"
                    val style = if (actualIndex == selectedDriver) active else panel
                    withStyle(style) { drawText(dropdownX, dropdownY + index, label.take(available).padEnd(available, ' ')) }
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "mouse_down") {
            val mx = event.x ?: return true
            val my = event.y ?: return true
            buttonHits.firstOrNull { my == lastButtonRow && mx in it.x }?.let {
                activate(it.action)
                return true
            }
            if (driverDropdown && dropdownRows.contains(my)) {
                val index = dropdownScroll + my - dropdownRows.first
                    if (index in drivers.indices) selectDriver(index)
                driverDropdown = false
                onInvalidate()
                return true
            }
            fieldHits.firstOrNull { my == it.row && mx in it.x }?.let {
                focus = it.index
                if (focus == 0) driverDropdown = !driverDropdown
                onInvalidate()
                return true
            }
            return true
        }
        if (event.kind != "key_down") return true
        val key = event.key?.lowercase() ?: return true
        if (key == "escape" || key == "esc") {
            onDismiss()
            return true
        }
        if (driverDropdown) {
            when (key) {
                "up" -> {
                    selectDriver((selectedDriver - 1).coerceAtLeast(0))
                    onInvalidate()
                    return true
                }
                "down" -> {
                    selectDriver((selectedDriver + 1).coerceAtMost(drivers.lastIndex))
                    onInvalidate()
                    return true
                }
                "enter", "return" -> {
                    driverDropdown = false
                    onInvalidate()
                    return true
                }
            }
        }
        if (key == "tab" || key == "down") {
            focus = (focus + 1).coerceAtMost(buttonStart() + 3)
            onInvalidate()
            return true
        }
        if (key == "up") {
            focus = (focus - 1).coerceAtLeast(0)
            onInvalidate()
            return true
        }
        if (focus == 0 && key in setOf("enter", "return", "space")) {
            driverDropdown = !driverDropdown
            onInvalidate()
            return true
        }
        if (focus >= buttonStart()) {
            when (key) {
                "left" -> focus = (focus - 1).coerceAtLeast(buttonStart())
                "right" -> focus = (focus + 1).coerceAtMost(buttonStart() + 3)
                "enter", "return", "space" -> activate(Action.entries[focus - buttonStart()])
            }
            onInvalidate()
            return true
        }
        return editField(focus, event).also { if (it) onInvalidate() }
    }

    private var lastButtonRow: Int = -1

    private fun activate(action: Action) {
        when (action) {
            Action.CANCEL -> onDismiss()
            Action.APPLY -> {
                if (testing) return
                onApply(toDefinition(), fields[3].value.takeIf { it.isNotEmpty() })
            }
            Action.DOWNLOAD -> {
                if (testing) return
                onDownloadDriver(toDefinition())
            }
            Action.TEST -> {
                if (testing) return
                testing = true
                status = "Testing connection..."
                val definition = toDefinition()
                val password = fields[3].value.takeIf { it.isNotEmpty() }
                Thread({
                    val result = service.testConnection(definition, password)
                    status = if (result.success) {
                        "Success: ${result.databaseProduct.orEmpty()} ${result.databaseVersion.orEmpty()} (${result.latencyMs} ms)"
                    } else {
                        "Failed: ${result.message ?: result.sqlState ?: "unknown connection error"}"
                    }
                    testing = false
                    onInvalidate()
                }, "database-dialog-test").apply { isDaemon = true }.start()
            }
        }
    }

    private fun toDefinition(): DataSourceDefinition {
        val driver = selectedDriverDef()
        val custom = customMode()
        val id = sessionId
        val customArtifact = customArtifact()
        val catalogSpec = registry.specFor(driver.id)
        val driverClass = if (custom) customFields[0].value.trim() else catalogSpec?.driverClass ?: driver.driverClass
        val coordinates = if (custom) customFields[1].value.trim().takeIf { it.isNotEmpty() } else catalogSpec?.coordinates ?: driver.defaultMavenCoordinates
        val selectedVersion = versionField.value.trim().takeIf { it.isNotEmpty() }
        val standardArtifact = (catalogSpec?.artifact ?: driver.artifact)?.let { artifact ->
            selectedVersion?.let { artifact.copy(version = it) } ?: artifact
        }
        return DataSourceDefinition(
            id = id,
            name = fields[0].value.ifBlank { driver.databaseFamily },
            driver = DriverSpec(
                id = driver.id,
                displayName = driver.displayName,
                driverClass = driverClass,
                coordinates = coordinates,
                jarPath = customFields[3].value.trim().takeIf { custom && it.isNotEmpty() },
                artifact = if (custom) customArtifact else standardArtifact,
                providerId = if (custom) "custom-$id" else catalogSpec?.providerId ?: driver.aliasOf ?: driver.id
            ),
            jdbcUrl = fields[1].value,
            username = fields[2].value,
            credentialReference = "$id.password".takeIf { fields[3].value.isNotEmpty() },
            defaultCatalog = fields[4].value.takeIf { it.isNotBlank() },
            defaultSchema = fields[5].value.takeIf { it.isNotBlank() }
        )
    }

    private fun selectDriver(index: Int) {
        val previous = selectedDriver
        selectedDriver = index.coerceIn(drivers.indices)
        if (previous != selectedDriver) versionField.set("")
        val definition = selectedDriverDef()
        if (fields[1].value.isBlank() || fields[1].value.startsWith("jdbc:")) fields[1].set(definition.defaultJdbcUrlTemplate)
    }

    private fun selectedDriverDef(): JdbcDriverDefinition = drivers[selectedDriver]

    private fun customMode(): Boolean = selectedDriverDef().id == "custom-jdbc"

    private fun formLabels(): List<String> = if (customMode()) {
        listOf(
            "Connection type", "Name", "JDBC URL", "Username", "Password",
            "Driver class", "Maven coordinates", "Maven repository", "Local JAR", "Catalog", "Schema", "Driver version"
        )
    } else {
        listOf("Connection type", "Name", "JDBC URL", "Username", "Password", "Catalog", "Schema", "Driver version")
    }

    private fun buttonStart(): Int = formLabels().size

    private fun valueForFocus(index: Int): String = when {
        index in 1..4 -> fields[index - 1].value
        customMode() && index in 5..8 -> customFields[index - 5].value
        customMode() && index == 9 -> fields[4].value
        customMode() && index == 10 -> fields[5].value
        customMode() && index == 11 -> versionField.value
        index == 5 -> fields[4].value
        index == 6 -> fields[5].value
        index == 7 -> versionField.value
        else -> ""
    }

    private fun editField(index: Int, event: UIEvent): Boolean = when {
        index in 1..4 -> fields[index - 1].edit(event)
        customMode() && index in 5..8 -> customFields[index - 5].edit(event)
        customMode() && index == 9 -> fields[4].edit(event)
        customMode() && index == 10 -> fields[5].edit(event)
        customMode() && index == 11 -> versionField.edit(event)
        index == 5 -> fields[4].edit(event)
        index == 6 -> fields[5].edit(event)
        index == 7 -> versionField.edit(event)
        else -> false
    }

    private fun customArtifact(): JdbcDriverArtifact? {
        if (!customMode()) return null
        val localJar = customFields[3].value.trim()
        if (localJar.isNotEmpty()) return JdbcDriverArtifact(repositoryType = JdbcRepositoryType.LOCAL_FILE)
        val parts = customFields[1].value.trim().split(':')
        if (parts.size < 2 || parts[0].isBlank() || parts[1].isBlank()) return null
        return JdbcDriverArtifact(
            repositoryType = if (customFields[2].value.isBlank()) JdbcRepositoryType.MAVEN_CENTRAL else JdbcRepositoryType.MAVEN_CUSTOM,
            groupId = parts[0],
            artifactId = parts[1],
            version = versionField.value.trim().takeIf { it.isNotEmpty() } ?: parts.getOrNull(2),
            classifier = parts.getOrNull(3),
            repository = customFields[2].value.trim().takeIf { it.isNotEmpty() }
        )
    }

    private fun driverLabel(): String = "${selectedDriverDef().displayName} (${selectedDriverDef().id})"

    private fun driverClassLabel(): String =
        if (customMode()) customFields[0].value.ifBlank { "service provider discovery" }
        else registry.specFor(selectedDriverDef().id)?.driverClass ?: selectedDriverDef().driverClass

    private fun artifactLabel(): String {
        if (customMode()) {
            val local = customFields[3].value.trim()
            if (local.isNotEmpty()) return "local: $local"
            return customFields[1].value.ifBlank { "custom / configured" }
        }
        val artifact = registry.specFor(selectedDriverDef().id)?.artifact ?: selectedDriverDef().artifact
        return artifact?.let {
            listOfNotNull(it.groupId, it.artifactId, versionField.value.takeIf { version -> version.isNotBlank() }, it.classifier)
                .joinToString(":")
        } ?: selectedDriverDef().defaultMavenCoordinates ?: "local / configured"
    }

    fun setStatus(message: String) {
        status = message
        onInvalidate()
    }

    fun setResolvedVersion(version: String) {
        versionField.set(version)
        onInvalidate()
    }

    private fun masked(value: String): String = if (focus == 4) "*".repeat(value.length) else value

    private fun drawBorder(canvas: CanvasRenderer, style: StyleSet, x: Int, y: Int, width: Int, height: Int) {
        val right = x + width - 1
        val bottom = y + height - 1
        canvas.withStyle(style) {
            for (px in x..right) {
                drawText(px, y, if (px == x || px == right) "+" else "-")
                drawText(px, bottom, if (px == x || px == right) "+" else "-")
            }
            for (py in (y + 1) until bottom) {
                drawText(x, py, "|")
                drawText(right, py, "|")
            }
        }
    }
}
