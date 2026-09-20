package editor.rest.ui

import editor.rest.model.RestNodeKind
import editor.rest.model.RestNodePath
import editor.rest.model.RestTreeNode
import editor.rest.model.RestWorkspaceModel
import editor.rest.model.isRequestNode
import editor.rest.model.jsonObject
import editor.rest.model.items
import editor.rest.model.nodeKind
import editor.rest.model.string
import editor.rest.model.walkRestTree
import editor.ui.FormFieldRenderer
import editor.ui.ModalDialogFrame
import editor.ui.ModalDialogBounds
import editor.ui.ModalTextInputDialog
import editor.lib.SystemClipboard
import java.nio.file.Path
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class RestPanelView(
    styleSheet: StyleSheet,
    private val model: RestWorkspaceModel,
    private val workspaceRoot: Path,
    private val onOpen: (RestNodePath) -> Unit,
    private val onOpenEnvironment: (String) -> Unit = {},
    private val onRun: (RestNodePath) -> Unit,
    private val onChanged: () -> Unit,
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private sealed interface Action {
        data class Open(val path: RestNodePath) : Action
        data class Toggle(val path: RestNodePath) : Action
        data class Menu(val path: RestNodePath, val anchorX: Int? = null, val anchorY: Int? = null) : Action
        data class AddRequest(val path: RestNodePath) : Action
        data class AddFolder(val path: RestNodePath) : Action
        data class Run(val path: RestNodePath) : Action
        data object Import : Action
        data class ImportOpenApi(val parent: RestNodePath) : Action
        data object Export : Action
        data object NewEnvironment : Action
        data object CopyEnvironment : Action
        data object RenameEnvironment : Action
        data object ActivateEnvironment : Action
        data object RemoveEnvironment : Action
        data class OpenEnvironment(val id: String) : Action
    }

    private data class Hit(val range: IntRange, val action: Action)

    private val expanded = mutableSetOf("")
    private var selected = 0
    private var scroll = 0
    private var lines: List<RestTreeNode> = emptyList()
    private var hits: Map<Int, List<Hit>> = emptyMap()
    private var menu: RestNodeMenu? = null
    private var dialog: ModalTextInputDialog? = null
    private var confirm: RestConfirmDialog? = null
    private var moveDialog: RestMoveDialog? = null
    private var message = ""
    private var filter = ""
    private var filterCursor = 0
    private var filtering = false
    private var filterRange: IntRange = IntRange.EMPTY
    private var pendingImportParent = RestNodePath()
    private var pendingRenamePath: RestNodePath? = null
    private var environmentSelectionId: String? = null
    private var pendingEnvironmentAction: String? = null
    private var draggingEnvironmentSplitter = false
    private var splitterRow = -1
    private var environmentTop = -1

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val base = styleSheet.getStyle("content").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(base.fg, base.bg)
        val selectedStyle = styleSheet.getStyle("file-entry:selected").withDefaults(base.fg, base.bg)
        canvas.withStyle(base) { drawRect(0, 0, cols, rows) }
        canvas.withStyle(base) {
            drawText(0, 0, if (filtering) "Filter: " else "REST API".take(cols).padEnd(cols, ' '))
            drawText(0, 2, message.take(cols).padEnd(cols, ' '))
        }
        if (filtering) {
            val label = "Filter: "
            filterRange = FormFieldRenderer.draw(
                canvas = canvas,
                styleSheet = styleSheet,
                x = label.length,
                y = 0,
                width = (cols - label.length).coerceAtLeast(3),
                value = filter,
                cursor = filterCursor,
                focused = true
            )
        } else {
            filterRange = IntRange.EMPTY
        }
        val toolbar = mutableListOf<Hit>()
        val toolbarWidth = cols
        renderButton(canvas, button, 0, 1, " [+request] ", Action.AddRequest(RestNodePath()), toolbar, toolbarWidth)
        renderButton(canvas, button, 13, 1, " [+folder] ", Action.AddFolder(RestNodePath()), toolbar, toolbarWidth)
        renderButton(canvas, button, 24, 1, " [run] ", Action.Run(RestNodePath()), toolbar, toolbarWidth)
        renderButton(canvas, button, 32, 1, " [import] ", Action.Import, toolbar, toolbarWidth)
        renderButton(canvas, button, 43, 1, " [openapi] ", Action.ImportOpenApi(RestNodePath()), toolbar, toolbarWidth)
        renderButton(canvas, button, 55, 1, " [export] ", Action.Export, toolbar, toolbarWidth)

        lines = visibleTreeNodes()
        if (environmentSelectionId == null || model.environment(environmentSelectionId.orEmpty()) == null) {
            environmentSelectionId = model.activeEnvironmentId ?: model.environments.firstOrNull()?.id
        }
        selected = selected.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val listTop = 3
        val canSplit = rows - listTop >= 10
        val maxEnvironmentHeight = (rows - listTop - 4 - 1).coerceAtLeast(5)
        val environmentHeight = if (canSplit) model.ui.environmentPanelHeight.coerceIn(5, maxEnvironmentHeight) else 0
        splitterRow = if (canSplit) rows - environmentHeight - 1 else -1
        environmentTop = if (canSplit) splitterRow + 1 else rows
        val treeWidth = cols
        val treeBottom = if (canSplit) splitterRow else rows
        val visible = (treeBottom - listTop).coerceAtLeast(0)
        scroll = scroll.coerceIn(0, (lines.size - visible).coerceAtLeast(0))
        val rowHits = mutableMapOf<Int, MutableList<Hit>>()
        lines.drop(scroll).take(visible).forEachIndexed { visibleIndex, line ->
            val row = listTop + visibleIndex
            val index = scroll + visibleIndex
            val expandedNode = line.path.encode() in expanded
            val marker = if (line.hasChildren) if (expandedNode) "[-]" else "[+]" else "   "
            val prefix = " ".repeat(line.depth * 2)
            val method = line.method?.uppercase()?.padEnd(7)?.take(7).orEmpty()
            val label = prefix + marker + " " + method + line.name
            canvas.withStyle(if (index == selected) selectedStyle else base) {
                drawText(0, row, label.take(treeWidth).padEnd(treeWidth, ' '))
            }
            if (line.hasChildren) {
                rowHits.getOrPut(row) { mutableListOf() } += Hit(
                    prefix.length until prefix.length + marker.length,
                    Action.Toggle(line.path)
                )
            }
            val addParent = line.kind != RestNodeKind.REQUEST
            if (addParent && treeWidth > 8) {
                val addLabel = " [+] "
                val x = (treeWidth - addLabel.length).coerceAtLeast(prefix.length + marker.length + 1)
                rowHits.getOrPut(row) { mutableListOf() } += Hit(x until x + addLabel.length, Action.AddRequest(line.path))
                canvas.withStyle(button) { drawText(x, row, addLabel) }
            }
            val menuLabel = " [...] "
            if (treeWidth > menuLabel.length + 8) {
                val x = treeWidth - menuLabel.length - if (addParent) addLabelLength() else 0
                rowHits.getOrPut(row) { mutableListOf() } += Hit(x until x + menuLabel.length, Action.Menu(line.path, x, row))
                canvas.withStyle(button) { drawText(x, row, menuLabel) }
            }
        }
        val environmentHits = mutableMapOf<Int, MutableList<Hit>>()
        if (canSplit) {
            canvas.withStyle(styleSheet.getStyle("splitter").withDefaults(base.fg, base.bg)) {
                drawText(0, splitterRow, "-".repeat(cols).take(cols))
                if (cols >= 18) drawText(1, splitterRow, "[ environments ]".take(cols - 2))
            }
            renderEnvironments(canvas, 0, environmentTop, cols, rows, base, button, environmentHits)
        }
        val allHits = mutableMapOf<Int, MutableList<Hit>>()
        allHits.getOrPut(1) { mutableListOf() }.addAll(toolbar)
        rowHits.forEach { (row, values) -> allHits.getOrPut(row) { mutableListOf() }.addAll(values) }
        environmentHits.forEach { (row, values) -> allHits.getOrPut(row) { mutableListOf() }.addAll(values) }
        hits = allHits.mapValues { it.value.toList() }
        menu?.render(canvas)
        dialog?.render(canvas)
        confirm?.render(canvas)
        moveDialog?.render(canvas)
    }

    override fun dispatch(event: UIEvent): Boolean {
        dialog?.let { active ->
            val handled = active.dispatch(event)
            if (active.finished) {
                active.result?.let { handleRenameOrPath(it) } ?: run { pendingEnvironmentAction = null }
                dialog = null
            }
            onInvalidate()
            return handled
        }
        confirm?.let { active ->
            val handled = active.dispatch(event)
            if (active.finished) {
                if (active.confirmed) active.onConfirm()
                confirm = null
            }
            onInvalidate()
            return handled
        }
        moveDialog?.let { active ->
            val handled = active.dispatch(event)
            if (active.finished) {
                active.target?.let { target ->
                    val moved = model.move(active.path, target)
                    moved?.let { model.select(it) }
                    notifyChanged("Moved node")
                }
                moveDialog = null
            }
            onInvalidate()
            return handled
        }
        menu?.let { active ->
            val handled = active.dispatch(event)
            if (active.finished) {
                active.action?.let { executeMenu(it, active.path) }
                menu = null
            }
            onInvalidate()
            return handled
        }
        if (event.kind == "mouse_down" && splitterRow >= 0 && event.y == splitterRow) {
            draggingEnvironmentSplitter = true
            return true
        }
        if (event.kind == "mouse_move" && draggingEnvironmentSplitter) {
            val rows = event.rows ?: return true
            val y = event.y ?: return true
            model.setEnvironmentPanelHeight(rows - y - 1)
            onChanged()
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_up") {
            draggingEnvironmentSplitter = false
            return true
        }
        if (event.kind == "mouse_scroll") {
            if (environmentTop >= 0 && (event.y ?: -1) >= environmentTop) return true
            scroll = (scroll - (event.scrollDelta ?: 0)).coerceAtLeast(0)
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            if (filtering && y == 0 && x in filterRange) {
                filterCursor = (x - filterRange.first).coerceIn(0, filter.length)
                onInvalidate()
                return true
            }
            hits[y]?.firstOrNull { x in it.range }?.let { hit -> execute(hit.action); return true }
            val index = scroll + y - 3
            if (index !in lines.indices) return true
            selected = index
            val line = lines[index]
            if (event.button == 1 || event.button == 2) {
                openMenu(line.path, x, y)
            } else {
                openOrToggle(line)
            }
            onInvalidate()
            return true
        }
        if (event.kind != "key_down") return false
        if (filtering) {
            if (event.ctrl) {
                when (event.key?.lowercase()) {
                    "c" -> { SystemClipboard.setText(filter); return true }
                    "x" -> { SystemClipboard.setText(filter); filter = ""; filterCursor = 0; onInvalidate(); return true }
                    "v" -> {
                        val paste = SystemClipboard.getText()
                        filter = filter.substring(0, filterCursor) + paste + filter.substring(filterCursor)
                        filterCursor += paste.length
                        onInvalidate()
                        return true
                    }
                }
            }
            when (event.key?.lowercase()) {
                "escape", "esc" -> {
                    filter = ""
                    filterCursor = 0
                    filtering = false
                }
                "enter", "return" -> filtering = false
                "backspace" -> if (filterCursor > 0) {
                    filter = filter.removeRange(filterCursor - 1, filterCursor)
                    filterCursor--
                }
                "delete" -> if (filterCursor < filter.length) filter = filter.removeRange(filterCursor, filterCursor + 1)
                "home" -> filterCursor = 0
                "end" -> filterCursor = filter.length
                "left" -> filterCursor = (filterCursor - 1).coerceAtLeast(0)
                "right" -> filterCursor = (filterCursor + 1).coerceAtMost(filter.length)
                else -> if (!event.ctrl && !event.alt && !event.meta && event.key?.length == 1) {
                    filter = filter.substring(0, filterCursor) + event.key + filter.substring(filterCursor)
                    filterCursor++
                }
            }
            selected = 0
            scroll = 0
            onInvalidate()
            return true
        }
        when (event.key?.lowercase()) {
            "up" -> selected = (selected - 1).coerceAtLeast(0)
            "down" -> selected = (selected + 1).coerceAtMost((lines.size - 1).coerceAtLeast(0))
            "pageup" -> selected = (selected - 10).coerceAtLeast(0)
            "pagedown" -> selected = (selected + 10).coerceAtMost((lines.size - 1).coerceAtLeast(0))
            "enter", "right" -> lines.getOrNull(selected)?.let(::openOrToggle)
            "left" -> lines.getOrNull(selected)?.let { expanded.remove(it.path.encode()) }
            "n" -> lines.getOrNull(selected)?.let { execute(Action.AddRequest(if (it.kind == RestNodeKind.REQUEST) it.path.parent else it.path)) }
            "f" -> lines.getOrNull(selected)?.let { execute(Action.AddFolder(if (it.kind == RestNodeKind.REQUEST) it.path.parent else it.path)) }
            "r" -> lines.getOrNull(selected)?.let { onRun(it.path) }
            "i" -> execute(Action.Import)
            "e" -> execute(Action.Export)
            "delete" -> lines.getOrNull(selected)?.let { requestDelete(it.path) }
            "m" -> lines.getOrNull(selected)?.let { openMenu(it.path) }
            "/" -> {
                filtering = true
                filterCursor = filter.length
            }
            else -> return false
        }
        onInvalidate()
        return true
    }

    private fun execute(action: Action) {
        when (action) {
            is Action.Open -> onOpen(action.path)
            is Action.Toggle -> if (!expanded.add(action.path.encode())) expanded.remove(action.path.encode())
            is Action.Menu -> openMenu(action.path, action.anchorX, action.anchorY)
            is Action.AddRequest -> model.addRequest(validParent(action.path))?.let { path -> model.select(path); onOpen(path); notifyChanged("Added request") }
            is Action.AddFolder -> model.addFolder(validParent(action.path))?.let { path -> model.select(path); onOpen(path); notifyChanged("Added folder") }
            is Action.Run -> onRun(action.path)
            Action.Import -> if (model.collection.items().isNotEmpty()) {
                confirm = RestConfirmDialog(
                    styleSheet,
                    RestNodePath(),
                    "Replace the existing REST collection?",
                    onInvalidate,
                onConfirm = { openInput("Import Postman collection", "File", workspaceRoot.resolve("collection.postman_collection.json").toString(), RestNodePath()) }
            )
            } else openInput("Import Postman collection", "File", workspaceRoot.resolve("collection.postman_collection.json").toString(), RestNodePath())
            is Action.ImportOpenApi -> openInput(
                "Import OpenAPI into ${model.node(action.parent)?.string("name") ?: model.collectionName()}",
                "File",
                workspaceRoot.resolve("openapi.yaml").toString(),
                action.parent
            )
            Action.Export -> openInput("Export Postman collection", "File", workspaceRoot.resolve("collection.postman_collection.json").toString())
            Action.NewEnvironment -> openEnvironmentInput("New environment", "New environment", "new")
            Action.CopyEnvironment -> environmentSelectionId?.let { id ->
                val source = model.environment(id) ?: return@let
                openEnvironmentInput("Copy environment", "${source.name} copy", "copy", id)
            }
            Action.RenameEnvironment -> environmentSelectionId?.let { id ->
                model.environment(id)?.let { openEnvironmentInput("Rename environment", it.name, "rename", id) }
            }
            Action.ActivateEnvironment -> environmentSelectionId?.let { id ->
                if (model.activateEnvironment(id)) notifyChanged("Activated environment")
            }
            Action.RemoveEnvironment -> environmentSelectionId?.let { id ->
                if (model.removeEnvironment(id)) {
                    environmentSelectionId = model.activeEnvironmentId
                    notifyChanged("Removed environment")
                }
            }
            is Action.OpenEnvironment -> {
                environmentSelectionId = action.id
                onOpenEnvironment(action.id)
            }
        }
    }

    private fun validParent(path: RestNodePath): RestNodePath =
        if (model.node(path)?.isRequestNode() == true) path.parent else path

    private fun openOrToggle(line: RestTreeNode) {
        if (line.hasChildren && line.path.encode() !in expanded) expanded += line.path.encode()
        else onOpen(line.path)
        model.select(line.path)
    }

    private fun openMenu(path: RestNodePath, anchorX: Int? = null, anchorY: Int? = null) {
        menu = RestNodeMenu(
            styleSheet,
            path,
            model.node(path)?.let { if (path.isRoot) RestNodeKind.COLLECTION else it.nodeKind() } ?: RestNodeKind.REQUEST,
            anchorX,
            anchorY
        )
    }

    private fun executeMenu(action: RestMenuAction, path: RestNodePath) {
        when (action) {
            RestMenuAction.OPEN -> onOpen(path)
            RestMenuAction.ADD_REQUEST -> execute(Action.AddRequest(validParent(path)))
            RestMenuAction.ADD_FOLDER -> execute(Action.AddFolder(validParent(path)))
            RestMenuAction.RUN -> onRun(path)
            RestMenuAction.RENAME -> openInput(
                "Rename \"${model.node(path)?.string("name") ?: model.collectionName()}\"",
                "Name",
                model.node(path)?.string("name") ?: model.collectionName(),
                path
            )
            RestMenuAction.DUPLICATE -> model.duplicate(path)?.let { model.select(it); onOpen(it); notifyChanged("Duplicated node") }
            RestMenuAction.MOVE_UP -> model.moveUp(path)?.let { model.select(it); notifyChanged("Moved node") }
            RestMenuAction.MOVE_DOWN -> model.moveDown(path)?.let { model.select(it); notifyChanged("Moved node") }
            RestMenuAction.MOVE_TO -> moveDialog = RestMoveDialog(styleSheet, path, model.collection.walkRestTree().filter { it.kind != RestNodeKind.REQUEST }, onInvalidate)
            RestMenuAction.DELETE -> requestDelete(path)
            RestMenuAction.IMPORT -> execute(Action.ImportOpenApi(if (model.node(path)?.isRequestNode() == true) path.parent else path))
            RestMenuAction.EXPORT -> execute(Action.Export)
        }
    }

    private fun requestDelete(path: RestNodePath) {
        val node = model.node(path) ?: return
        if (node.items().isNotEmpty()) confirm = RestConfirmDialog(styleSheet, path, "Delete non-empty folder?", onInvalidate, onConfirm = { deleteSelected(path) })
        else deleteSelected(path)
    }

    private fun deleteSelected(path: RestNodePath) {
        if (model.delete(path)) notifyChanged("Deleted node")
    }

    private fun openInput(title: String, label: String, initial: String, context: RestNodePath? = null) {
        pendingImportParent = context ?: RestNodePath()
        pendingRenamePath = if (title.startsWith("Rename \"")) context else null
        dialog = ModalTextInputDialog(styleSheet, title, label, initial, onInvalidate = onInvalidate)
    }

    private fun openEnvironmentInput(title: String, initial: String, action: String, id: String? = null) {
        pendingEnvironmentAction = action + ":" + (id.orEmpty())
        pendingRenamePath = null
        dialog = ModalTextInputDialog(styleSheet, title, "Name", initial, onInvalidate = onInvalidate)
    }

    private fun handleRenameOrPath(value: String) {
        val title = dialog?.title ?: return
        val environmentAction = pendingEnvironmentAction
        pendingEnvironmentAction = null
        if (environmentAction != null) {
            val parts = environmentAction.split(':', limit = 2)
            val action = parts.firstOrNull()
            val id = parts.getOrNull(1).orEmpty().ifBlank { null }
            when (action) {
                "new" -> {
                    val environment = model.createEnvironment(value)
                    environmentSelectionId = environment.id
                    onOpenEnvironment(environment.id)
                    notifyChanged("Created environment")
                }
                "copy" -> id?.let { sourceId ->
                    model.duplicateEnvironment(sourceId, value)?.let { environment ->
                        environmentSelectionId = environment.id
                        onOpenEnvironment(environment.id)
                        notifyChanged("Copied environment")
                    }
                }
                "rename" -> id?.let {
                    if (model.renameEnvironment(it, value)) notifyChanged("Renamed environment")
                }
            }
            return
        }
        if (title.startsWith("Rename \"")) {
            val path = pendingRenamePath ?: lines.getOrNull(selected)?.path ?: return
            model.rename(path, value)
            notifyChanged("Renamed node")
            return
        }
        val file = Path.of(value)
        if (title.startsWith("Import OpenAPI")) {
            model.importOpenApi(file, pendingImportParent)
                .onSuccess { count -> notifyChanged("Imported $count OpenAPI folder(s)") }
                .onFailure { message = it.message ?: "OpenAPI import failed" }
        } else if (title.startsWith("Import")) {
            model.importCollection(file).onSuccess { notifyChanged("Imported collection") }.onFailure { message = it.message ?: "Import failed" }
        } else {
            model.exportCollection(file).onSuccess { message = "Exported collection to $file" }.onFailure { message = it.message ?: "Export failed" }
        }
    }

    private fun notifyChanged(text: String) {
        message = text
        onChanged()
        onInvalidate()
    }

    private fun visibleTreeNodes(): List<RestTreeNode> {
        val all = model.collection.walkRestTree()
        val query = filter.trim().lowercase()
        if (query.isBlank()) {
            return all.filter { node ->
                node.path.indices.indices.all { depth -> node.path.indices.take(depth).joinToString("/") in expanded }
            }
        }
        val matching = all.filter { node ->
            val request = model.node(node.path)?.jsonObject("request")
            listOfNotNull(node.name, node.method, request?.get("url")?.toString()).any { it.lowercase().contains(query) }
        }.map { it.path }.toMutableSet()
        all.filter { it.path in matching }.forEach { node ->
            node.path.indices.indices.forEach { depth -> matching += RestNodePath(node.path.indices.take(depth)) }
        }
        return all.filter { it.path in matching }
    }

    private fun renderButton(canvas: CanvasRenderer, style: StyleSet, x: Int, y: Int, label: String, action: Action, target: MutableList<Hit>, cols: Int) {
        if (x >= cols) return
        val visible = label.take(cols - x)
        canvas.withStyle(style) { drawText(x, y, visible) }
        target += Hit(x until (x + visible.length), action)
    }

    private fun addLabelLength(): Int = 5

    private fun renderEnvironments(
        canvas: CanvasRenderer,
        x: Int,
        top: Int,
        width: Int,
        rows: Int,
        base: StyleSet,
        button: StyleSet,
        target: MutableMap<Int, MutableList<Hit>>
    ) {
        if (width < 12 || top >= rows) return
        canvas.withStyle(styleSheet.getStyle("splitter").withDefaults(base.fg, base.bg)) {
            if (x > 0) for (row in 0 until rows) drawText(x - 1, row, "│")
        }
        val title = "Environments"
        canvas.withStyle(base) { drawText(x, top, title.take(width).padEnd(width, ' ')) }
        val buttons = listOf(
            "[+]" to Action.NewEnvironment,
            "[copy]" to Action.CopyEnvironment,
            "[rename]" to Action.RenameEnvironment,
            "[activate]" to Action.ActivateEnvironment,
            "[-]" to Action.RemoveEnvironment
        )
        var buttonX = x
        buttons.forEach { (label, action) ->
            if (buttonX < x + width) {
                val visible = label.take((x + width - buttonX).coerceAtLeast(0))
                canvas.withStyle(button) { drawText(buttonX, top + 1, visible) }
                target.getOrPut(top + 1) { mutableListOf() } += Hit(buttonX until buttonX + visible.length, action)
                buttonX += label.length + 1
            }
        }
        model.environments.forEachIndexed { index, environment ->
            val row = top + 3 + index
            if (row >= rows) return@forEachIndexed
            val marker = if (environment.id == model.activeEnvironmentId) "*" else " "
            val selectedMarker = if (environment.id == environmentSelectionId) ">" else " "
            val label = "$selectedMarker[$marker] ${environment.name}"
            canvas.withStyle(if (environment.id == environmentSelectionId) styleSheet.getStyle("file-entry:selected").withDefaults(base.fg, base.bg) else base) {
                drawText(x, row, label.take(width).padEnd(width, ' '))
            }
            target.getOrPut(row) { mutableListOf() } += Hit(x until x + width, Action.OpenEnvironment(environment.id))
        }
    }
}

enum class RestMenuAction {
    OPEN, ADD_REQUEST, ADD_FOLDER, RUN, RENAME, DUPLICATE, MOVE_UP, MOVE_DOWN, MOVE_TO, DELETE, IMPORT, EXPORT
}

class RestNodeMenu(
    styleSheet: StyleSheet,
    val path: RestNodePath,
    kind: RestNodeKind,
    private val anchorX: Int? = null,
    private val anchorY: Int? = null
) : BaseComponent(styleSheet) {
    private val frame = ModalDialogFrame(styleSheet)
    private val actions = when (kind) {
        RestNodeKind.COLLECTION -> listOf(RestMenuAction.ADD_REQUEST, RestMenuAction.ADD_FOLDER, RestMenuAction.RUN, RestMenuAction.RENAME, RestMenuAction.IMPORT, RestMenuAction.EXPORT)
        RestNodeKind.FOLDER -> listOf(RestMenuAction.OPEN, RestMenuAction.ADD_REQUEST, RestMenuAction.ADD_FOLDER, RestMenuAction.RUN, RestMenuAction.RENAME, RestMenuAction.IMPORT, RestMenuAction.DUPLICATE, RestMenuAction.MOVE_UP, RestMenuAction.MOVE_DOWN, RestMenuAction.MOVE_TO, RestMenuAction.DELETE)
        RestNodeKind.REQUEST -> listOf(RestMenuAction.OPEN, RestMenuAction.RUN, RestMenuAction.RENAME, RestMenuAction.DUPLICATE, RestMenuAction.MOVE_UP, RestMenuAction.MOVE_DOWN, RestMenuAction.MOVE_TO, RestMenuAction.DELETE)
    }
    var action: RestMenuAction? = null
        private set
    var finished: Boolean = false
        private set
    private var selected = 0
    private var firstRow = 0
    private var lastRange: IntRange = IntRange.EMPTY
    private var bounds = ModalDialogBounds(0, 0, 1, 1)
    private var visibleActionCount = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val style = frame.panelStyle()
        val button = frame.buttonStyle()
        val width = actions.maxOfOrNull { it.name.lowercase().replace('_', ' ').length }?.plus(6)?.coerceAtMost(cols)?.coerceAtLeast(18) ?: 18
        val height = (actions.size + 2).coerceAtMost(rows).coerceAtLeast(3)
        val x = (anchorX ?: (cols - width) / 2).coerceIn(0, (cols - width).coerceAtLeast(0))
        val below = (anchorY ?: 0) + 1
        val y = if (below + height <= rows) below else ((anchorY ?: rows) - height).coerceAtLeast(0)
        bounds = ModalDialogBounds(x, y, width, height)
        firstRow = bounds.y + 1
        frame.render(canvas, bounds, "Actions")
        visibleActionCount = (height - 2).coerceAtLeast(0).coerceAtMost(actions.size)
        actions.take(visibleActionCount).forEachIndexed { index, item ->
            val label = " ${item.name.lowercase().replace('_', ' ')} ".padEnd(bounds.contentWidth, ' ')
            canvas.withStyle(if (index == selected) button else style) { drawText(bounds.contentX, firstRow + index, label.take(bounds.contentWidth)) }
        }
        lastRange = bounds.x until bounds.x + bounds.width
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            if (y in firstRow until firstRow + visibleActionCount && x in lastRange) {
                selected = y - firstRow
                action = actions[selected]
                finished = true
            } else if (x !in lastRange) finished = true
            return true
        }
        if (event.kind != "key_down") return true
        when (event.key?.lowercase()) {
            "escape", "esc" -> finished = true
            "up" -> selected = (selected - 1).coerceAtLeast(0)
            "down" -> selected = (selected + 1).coerceAtMost(actions.lastIndex)
            "enter", "return" -> { action = actions[selected]; finished = true }
        }
        return true
    }
}

class RestConfirmDialog(
    styleSheet: StyleSheet,
    val path: RestNodePath,
    private val prompt: String,
    private val onInvalidate: () -> Unit,
    val onConfirm: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private val frame = ModalDialogFrame(styleSheet)
    private var focus = 0 // 0 = confirm, 1 = cancel
    private var okRange: IntRange = IntRange.EMPTY
    private var cancelRange: IntRange = IntRange.EMPTY
    private var buttonRow = -1
    var confirmed = false
        private set
    var finished = false
        private set

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val bounds = frame.bounds(canvas, minOf(cols, 64).coerceAtLeast(36), 8)
        val style = frame.panelStyle()
        val button = frame.buttonStyle()
        frame.render(canvas, bounds, "Confirm")
        canvas.withStyle(style) { drawText(bounds.contentX, bounds.y + 3, prompt.take(bounds.contentWidth)) }
        buttonRow = bounds.bottom - 2
        val okLabel = " Delete "
        val cancelLabel = " Cancel "
        val buttonX = bounds.right - okLabel.length - cancelLabel.length - 1
        okRange = buttonX until buttonX + okLabel.length
        cancelRange = (okRange.last + 1) until (okRange.last + 1 + cancelLabel.length)
        canvas.withStyle(if (focus == 0) button else style) { drawText(okRange.first, buttonRow, okLabel) }
        canvas.withStyle(if (focus == 1) button else style) { drawText(cancelRange.first, buttonRow, cancelLabel) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            when {
                y == buttonRow && x in okRange -> { focus = 0; confirmed = true; finished = true }
                y == buttonRow && x in cancelRange -> { focus = 1; finished = true }
            }
        }
        if (event.kind == "key_down") when (event.key?.lowercase()) {
            "escape", "esc" -> { focus = 1; finished = true }
            "tab", "down", "right" -> focus = (focus + 1) % 2
            "up", "left" -> focus = (focus + 1) % 2
            "enter", "return", "delete" -> if (focus == 0) { confirmed = true; finished = true } else finished = true
        }
        onInvalidate()
        return true
    }
}

class RestMoveDialog(
    styleSheet: StyleSheet,
    val path: RestNodePath,
    folders: List<RestTreeNode>,
    private val onInvalidate: () -> Unit
) : BaseComponent(styleSheet) {
    private val frame = ModalDialogFrame(styleSheet)
    private val options = folders.filter { it.path.indices != path.indices && !it.path.indices.startsWith(path.indices) }
    private var selected = 0
    private var focus = 0 // 0 = folder list, 1 = move, 2 = cancel
    private var moveRange: IntRange = IntRange.EMPTY
    private var cancelRange: IntRange = IntRange.EMPTY
    private var buttonRow = -1
    private var listStart = -1
    var target: RestNodePath? = null
        private set
    var finished = false
        private set

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val preferredHeight = (options.size + 4).coerceAtMost((rows - 2).coerceAtLeast(7))
        val bounds = frame.bounds(canvas, minOf(cols, 70).coerceAtLeast(34), preferredHeight.coerceAtLeast(7))
        val style = frame.panelStyle()
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(style.fg, style.bg)
        val button = frame.buttonStyle()
        frame.render(canvas, bounds, "Move to folder")
        val visible = (bounds.height - 4).coerceAtLeast(1)
        listStart = bounds.y + 2
        selected = selected.coerceIn(0, (options.size - 1).coerceAtLeast(0))
        options.take(visible).forEachIndexed { index, folder ->
            val label = " ".repeat(folder.depth * 2) + folder.name
            val rowStyle = if (index == selected && focus == 0) active else style
            canvas.withStyle(rowStyle) { drawText(bounds.contentX, bounds.y + 2 + index, label.take(bounds.contentWidth)) }
        }
        buttonRow = bounds.bottom - 2
        val moveLabel = " Move "
        val cancelLabel = " Cancel "
        val buttonX = bounds.right - moveLabel.length - cancelLabel.length - 1
        moveRange = buttonX until buttonX + moveLabel.length
        cancelRange = (moveRange.last + 1) until (moveRange.last + 1 + cancelLabel.length)
        canvas.withStyle(if (focus == 1) button else style) { drawText(moveRange.first, buttonRow, moveLabel) }
        canvas.withStyle(if (focus == 2) button else style) { drawText(cancelRange.first, buttonRow, cancelLabel) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> { focus = 2; finished = true }
                "tab", "down" -> if (event.key.equals("tab", true) || focus != 0) focus = (focus + 1) % 3 else selected = (selected + 1).coerceAtMost((options.size - 1).coerceAtLeast(0))
                "up" -> if (focus == 0) selected = (selected - 1).coerceAtLeast(0) else focus = (focus + 2) % 3
                "left" -> if (focus == 2) focus = 1
                "right" -> if (focus == 1) focus = 2
                "enter", "return" -> when (focus) {
                    0, 1 -> { target = options.getOrNull(selected)?.path; finished = true }
                    else -> finished = true
                }
            }
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            when {
                y == buttonRow && x in moveRange -> { focus = 1; target = options.getOrNull(selected)?.path; finished = true }
                y == buttonRow && x in cancelRange -> { focus = 2; finished = true }
                else -> {
                    val row = y - listStart
                    if (row in 0 until options.size.coerceAtMost((buttonRow - listStart).coerceAtLeast(0))) {
                        selected = row
                        focus = 0
                    }
                }
            }
            onInvalidate()
            return true
        }
        return true
    }
}

private fun List<Int>.startsWith(prefix: List<Int>): Boolean = size >= prefix.size && take(prefix.size) == prefix
