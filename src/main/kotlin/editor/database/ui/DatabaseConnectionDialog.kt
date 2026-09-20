package editor.database.ui

import editor.database.DatabaseService
import editor.database.driver.JdbcDriverDefinition
import editor.database.driver.JdbcDriverRegistry
import editor.database.model.DataSourceDefinition
import editor.database.model.DriverSpec
import editor.database.model.JdbcDriverArtifact
import editor.database.model.JdbcRepositoryType
import editor.ui.FormFieldRenderer
import editor.ui.ModalDialogFrame
import editor.lib.SystemClipboard
import java.util.UUID
import react.BaseComponent
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
    private val frame = ModalDialogFrame(styleSheet)
    private data class FieldHit(val index: Int, val row: Int, val x: IntRange)
    private data class ButtonHit(val action: Action, val x: IntRange)
    private data class UrlSuggestionRow(val group: String, val index: Int, val label: String?, val value: String?)
    private data class UrlSuggestionHit(val group: String, val index: Int, val row: Int)
    private enum class Action { TEST, DOWNLOAD, APPLY, CANCEL }

    private class Field(var value: String) {
        var cursor: Int = value.length

        fun set(next: String) {
            value = next
            cursor = value.length
        }

        fun edit(event: UIEvent): Boolean {
            val key = event.key ?: return false
            if (event.ctrl) {
                when (key.lowercase()) {
                    "c" -> { SystemClipboard.setText(value); return true }
                    "x" -> { SystemClipboard.setText(value); value = ""; cursor = 0; return true }
                    "v" -> {
                        val paste = SystemClipboard.getText()
                        value = value.substring(0, cursor) + paste + value.substring(cursor)
                        cursor += paste.length
                        return true
                    }
                }
            }
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

    private val initialDefinition = existing
    private val drivers = registry.definitions
    private val sessionId = initialDefinition?.id ?: UUID.randomUUID().toString()
    private val fields = arrayOf(
        Field(initialDefinition?.name.orEmpty()),
        Field(initialDefinition?.jdbcUrl.orEmpty()),
        Field(initialDefinition?.username.orEmpty()),
        Field(""),
        Field(initialDefinition?.defaultCatalog.orEmpty()),
        Field(initialDefinition?.defaultSchema.orEmpty())
    )
    private val customFields = arrayOf(Field(""), Field(""), Field(""), Field(""))
    private val versionField = Field("")
    private var selectedDriver = drivers.indexOfFirst { it.id == initialDefinition?.driver?.id }.takeIf { it >= 0 } ?: 0
    private var focus = 0
    private var driverDropdown = false
    private var status = ""
    private var testing = false
    private var fieldHits: List<FieldHit> = emptyList()
    private var buttonHits: List<ButtonHit> = emptyList()
    private var dropdownRows: IntRange = IntRange.EMPTY
    private var dropdownScroll = 0
    private var urlDropdown = false
    private var urlSuggestionHits: List<UrlSuggestionHit> = emptyList()
    private var urlDropdownRows: IntRange = IntRange.EMPTY
    private var urlDropdownScroll = 0
    private var urlSelectionOrdinal = 0

    init {
        initialDefinition?.let {
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
        if (existing == null) fields[1].set(drivers[selectedDriver].defaultJdbcUrlTemplate)
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val preferredWidth = minOf(cols, 86).coerceAtLeast(46)
        val preferredHeight = minOf(rows, if (customMode()) 23 else 19).coerceAtLeast(if (customMode()) 18 else 14)
        val bounds = frame.bounds(canvas, preferredWidth, preferredHeight)
        val width = bounds.width
        val height = bounds.height
        val x = bounds.x
        val y = bounds.y
        val panel = frame.panelStyle()
        val input = styleSheet.getStyle("content").withDefaults(panel.fg, panel.bg)
        val button = frame.buttonStyle()
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(panel.fg, panel.bg)

        fieldHits = emptyList()
        buttonHits = emptyList()
        dropdownRows = IntRange.EMPTY
        urlDropdownRows = IntRange.EMPTY
        frame.render(
            canvas,
            bounds,
            if (initialDefinition == null) "New database connection" else "Edit database connection"
        )

        val labels = formLabels()
        val labelWidth = 16
        val startRow = y + 3
        val available = (width - labelWidth - 6).coerceAtLeast(10)
        val hits = mutableListOf<FieldHit>()
        labels.forEachIndexed { index, label ->
            val row = startRow + index
            if (row >= y + height - 3) return@forEachIndexed
            val selected = focus == index
            canvas.withStyle(input) {
                drawText(x + 2, row, ("$label:").padEnd(labelWidth).take(labelWidth))
            }
            val value = if (index == 0) driverLabel() else valueForFocus(index)
            val fieldRange = FormFieldRenderer.draw(
                canvas = canvas,
                styleSheet = styleSheet,
                x = x + 2 + labelWidth,
                y = row,
                width = available,
                value = value,
                cursor = cursorForFocus(index),
                focused = selected,
                mask = index == 4,
                showCursor = selected && index != 0
            )
            hits += FieldHit(index, row, fieldRange)
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
            val buttonStyle = if (focus == buttonStart() + renderedButtons.size) active else button
            canvas.withStyle(buttonStyle) { drawText(cursor, buttonRow, label) }
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
        if (urlDropdown && focus == 2) {
            renderUrlSuggestions(canvas, panel, active, x + 2 + labelWidth, startRow + 3, available, rows)
        }
    }

    private fun renderUrlSuggestions(
        canvas: CanvasRenderer,
        panel: react.StyleSet,
        active: react.StyleSet,
        dropdownX: Int,
        dropdownY: Int,
        available: Int,
        rows: Int
    ) {
        val entries = urlSuggestionRows()
        if (entries.isEmpty()) return
        val maxHeight = (rows - dropdownY - 1).coerceAtLeast(1)
        val height = entries.size.coerceAtMost(maxHeight)
        val maxScroll = (entries.size - height).coerceAtLeast(0)
        urlDropdownScroll = urlDropdownScroll.coerceIn(0, maxScroll)
        val visible = entries.drop(urlDropdownScroll).take(height)
        urlDropdownRows = dropdownY until (dropdownY + visible.size)
        val hits = mutableListOf<UrlSuggestionHit>()
        canvas.withStyle(panel) {
            drawRect(dropdownX, dropdownY, available, height)
            visible.forEachIndexed { offset, entry ->
                val row = dropdownY + offset
                val ordinal = entries.take(urlDropdownScroll + offset + 1).count { it.value != null } - 1
                if (entry.value == null) {
                    withStyle(active) { drawText(dropdownX, row, "[${entry.label}]".take(available).padEnd(available, ' ')) }
                } else {
                    val text = "${entry.label}: ${entry.value}".take(available - 2).padEnd(available - 2, ' ')
                    withStyle(if (ordinal == urlSelectionOrdinal) active else panel) {
                        drawText(dropdownX, row, ("  " + text).take(available).padEnd(available, ' '))
                    }
                    hits += UrlSuggestionHit(entry.group, entry.index, row)
                }
            }
        }
        urlSuggestionHits = hits
    }

    private fun urlSuggestionRows(): List<UrlSuggestionRow> {
        val suggestions = service.jdbcUrlSuggestionService.forDriver(selectedDriverDef().id)
        return buildList {
            if (suggestions.recents.isNotEmpty()) {
                add(UrlSuggestionRow("Recents", -1, "Recents", null))
                suggestions.recents.forEachIndexed { index, value -> add(UrlSuggestionRow("recent", index, value, value)) }
            }
            if (suggestions.standard.isNotEmpty()) {
                add(UrlSuggestionRow("Standard", -1, "Standard", null))
                suggestions.standard.forEachIndexed { index, value -> add(UrlSuggestionRow("standard", index, value.label, value.template)) }
            }
        }
    }

    private fun openUrlSuggestions() {
        if (urlSuggestionRows().none { it.value != null }) return
        urlDropdown = true
        urlSelectionOrdinal = 0
        urlDropdownScroll = 0
        driverDropdown = false
        onInvalidate()
    }

    private fun selectUrlSuggestion(hit: UrlSuggestionHit) {
        val entry = urlSuggestionRows().firstOrNull { it.group == hit.group && it.index == hit.index } ?: return
        entry.value?.let { fields[1].set(it) }
        urlDropdown = false
        focus = 2
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "mouse_down") {
            val mx = event.x ?: return true
            val my = event.y ?: return true
            buttonHits.firstOrNull { my == lastButtonRow && mx in it.x }?.let {
                focus = buttonStart() + buttonHits.indexOf(it)
                activate(it.action)
                return true
            }
            if (urlDropdown && urlDropdownRows.contains(my)) {
                urlSuggestionHits.firstOrNull { it.row == my }?.let(::selectUrlSuggestion)
                onInvalidate()
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
                setCursorForFocus(it.index, (mx - it.x.first).coerceAtLeast(0))
                if (focus == 0) driverDropdown = !driverDropdown
                if (focus == 2 && mx >= it.x.last - 2) openUrlSuggestions()
                onInvalidate()
                return true
            }
            return true
        }
        if (event.kind != "key_down") return true
        val key = event.key?.lowercase() ?: return true
        if (urlDropdown && focus == 2) {
            when (key) {
                "escape", "esc" -> {
                    urlDropdown = false
                    onInvalidate()
                    return true
                }
                "up", "down" -> {
                    val count = urlSuggestionRows().count { it.value != null }
                    if (count > 0) {
                        val delta = if (key == "up") -1 else 1
                        urlSelectionOrdinal = (urlSelectionOrdinal + delta).coerceIn(0, count - 1)
                        onInvalidate()
                    }
                    return true
                }
                "enter", "return" -> {
                    val selected = urlSuggestionRows().filter { it.value != null }.getOrNull(urlSelectionOrdinal)
                    selected?.let { selectUrlSuggestion(UrlSuggestionHit(it.group, it.index, -1)) }
                    onInvalidate()
                    return true
                }
                else -> urlDropdown = false
            }
        }
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
        if (focus == 2 && key == "down") {
            openUrlSuggestions()
            return true
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
            credentialReference = if (fields[3].value.isNotEmpty()) "$id.password" else initialDefinition?.credentialReference,
            properties = initialDefinition?.properties.orEmpty(),
            defaultCatalog = fields[4].value.takeIf { it.isNotBlank() },
            defaultSchema = fields[5].value.takeIf { it.isNotBlank() },
            autoCommit = initialDefinition?.autoCommit ?: true,
            readOnly = initialDefinition?.readOnly ?: false
        )
    }

    private fun selectDriver(index: Int) {
        val previous = selectedDriver
        selectedDriver = index.coerceIn(drivers.indices)
        urlDropdown = false
        if (previous != selectedDriver) versionField.set("")
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

    private fun cursorForFocus(index: Int): Int = when {
        index in 1..4 -> fields[index - 1].cursor
        customMode() && index in 5..8 -> customFields[index - 5].cursor
        customMode() && index == 9 -> fields[4].cursor
        customMode() && index == 10 -> fields[5].cursor
        customMode() && index == 11 -> versionField.cursor
        index == 5 -> fields[4].cursor
        index == 6 -> fields[5].cursor
        index == 7 -> versionField.cursor
        else -> 0
    }

    private fun setCursorForFocus(index: Int, cursor: Int) {
        when {
            index in 1..4 -> fields[index - 1].cursor = cursor.coerceIn(0, fields[index - 1].value.length)
            customMode() && index in 5..8 -> customFields[index - 5].cursor = cursor.coerceIn(0, customFields[index - 5].value.length)
            customMode() && index == 9 -> fields[4].cursor = cursor.coerceIn(0, fields[4].value.length)
            customMode() && index == 10 -> fields[5].cursor = cursor.coerceIn(0, fields[5].value.length)
            customMode() && index == 11 -> versionField.cursor = cursor.coerceIn(0, versionField.value.length)
            index == 5 -> fields[4].cursor = cursor.coerceIn(0, fields[4].value.length)
            index == 6 -> fields[5].cursor = cursor.coerceIn(0, fields[5].value.length)
            index == 7 -> versionField.cursor = cursor.coerceIn(0, versionField.value.length)
        }
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

}
