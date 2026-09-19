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
    private val onRun: (RestNodePath) -> Unit,
    private val onChanged: () -> Unit,
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private sealed interface Action {
        data class Open(val path: RestNodePath) : Action
        data class Toggle(val path: RestNodePath) : Action
        data class Menu(val path: RestNodePath) : Action
        data class AddRequest(val path: RestNodePath) : Action
        data class AddFolder(val path: RestNodePath) : Action
        data class Run(val path: RestNodePath) : Action
        data object Import : Action
        data object Export : Action
    }

    private data class Hit(val range: IntRange, val action: Action)

    private val expanded = mutableSetOf("")
    private var selected = 0
    private var scroll = 0
    private var lines: List<RestTreeNode> = emptyList()
    private var hits: Map<Int, List<Hit>> = emptyMap()
    private var menu: RestNodeMenu? = null
    private var dialog: RestInputDialog? = null
    private var confirm: RestConfirmDialog? = null
    private var moveDialog: RestMoveDialog? = null
    private var message = ""
    private var filter = ""
    private var filterCursor = 0
    private var filtering = false

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val base = styleSheet.getStyle("content").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(base.fg, base.bg)
        val selectedStyle = styleSheet.getStyle("file-entry:selected").withDefaults(base.fg, base.bg)
        canvas.withStyle(base) { drawRect(0, 0, cols, rows) }
        canvas.withStyle(base) {
            val heading = if (filtering) "Filter: $filter" else "REST API"
            drawText(0, 0, heading.take(cols).padEnd(cols, ' '))
            drawText(0, 2, message.take(cols).padEnd(cols, ' '))
        }
        val toolbar = mutableListOf<Hit>()
        renderButton(canvas, button, 0, 1, " [+request] ", Action.AddRequest(RestNodePath()), toolbar, cols)
        renderButton(canvas, button, 13, 1, " [+folder] ", Action.AddFolder(RestNodePath()), toolbar, cols)
        renderButton(canvas, button, 24, 1, " [run] ", Action.Run(RestNodePath()), toolbar, cols)
        renderButton(canvas, button, 32, 1, " [import] ", Action.Import, toolbar, cols)
        renderButton(canvas, button, 43, 1, " [export] ", Action.Export, toolbar, cols)

        lines = visibleTreeNodes()
        selected = selected.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val listTop = 3
        val visible = (rows - listTop).coerceAtLeast(0)
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
                drawText(0, row, label.take(cols).padEnd(cols, ' '))
            }
            if (line.hasChildren) {
                rowHits.getOrPut(row) { mutableListOf() } += Hit(
                    prefix.length until prefix.length + marker.length,
                    Action.Toggle(line.path)
                )
            }
            val addParent = line.kind != RestNodeKind.REQUEST
            if (addParent && cols > 8) {
                val addLabel = " [+] "
                val x = (cols - addLabel.length).coerceAtLeast(prefix.length + marker.length + 1)
                rowHits.getOrPut(row) { mutableListOf() } += Hit(x until x + addLabel.length, Action.AddRequest(line.path))
                canvas.withStyle(button) { drawText(x, row, addLabel) }
            }
            val menuLabel = " [...] "
            if (cols > menuLabel.length + 8) {
                val x = cols - menuLabel.length - if (addParent) addLabelLength() else 0
                rowHits.getOrPut(row) { mutableListOf() } += Hit(x until x + menuLabel.length, Action.Menu(line.path))
                canvas.withStyle(button) { drawText(x, row, menuLabel) }
            }
        }
        hits = (mapOf(1 to toolbar) + rowHits).mapValues { it.value.toList() }
        menu?.render(canvas)
        dialog?.render(canvas)
        confirm?.render(canvas)
        moveDialog?.render(canvas)
    }

    override fun dispatch(event: UIEvent): Boolean {
        dialog?.let { active ->
            val handled = active.dispatch(event)
            if (active.finished) {
                active.result?.let { handleRenameOrPath(it) }
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
        if (event.kind == "mouse_scroll") {
            scroll = (scroll - (event.scrollDelta ?: 0)).coerceAtLeast(0)
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            hits[y]?.firstOrNull { x in it.range }?.let { hit -> execute(hit.action); return true }
            val index = scroll + y - 3
            if (index !in lines.indices) return true
            selected = index
            val line = lines[index]
            if (event.button == 1 || event.button == 2) {
                openMenu(line.path)
            } else {
                openOrToggle(line)
            }
            onInvalidate()
            return true
        }
        if (event.kind != "key_down") return false
        if (filtering) {
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
            is Action.Menu -> openMenu(action.path)
            is Action.AddRequest -> model.addRequest(validParent(action.path))?.let { path -> model.select(path); onOpen(path); notifyChanged("Added request") }
            is Action.AddFolder -> model.addFolder(validParent(action.path))?.let { path -> model.select(path); onOpen(path); notifyChanged("Added folder") }
            is Action.Run -> onRun(action.path)
            Action.Import -> if (model.collection.items().isNotEmpty()) {
                confirm = RestConfirmDialog(
                    styleSheet,
                    RestNodePath(),
                    "Replace the existing REST collection?",
                    onInvalidate,
                    onConfirm = { openInput("Import Postman collection", workspaceRoot.resolve("collection.postman_collection.json").toString()) }
                )
            } else openInput("Import Postman collection", workspaceRoot.resolve("collection.postman_collection.json").toString())
            Action.Export -> openInput("Export Postman collection", workspaceRoot.resolve("collection.postman_collection.json").toString())
        }
    }

    private fun validParent(path: RestNodePath): RestNodePath =
        if (model.node(path)?.isRequestNode() == true) path.parent else path

    private fun openOrToggle(line: RestTreeNode) {
        if (line.hasChildren && line.path.encode() !in expanded) expanded += line.path.encode()
        else onOpen(line.path)
        model.select(line.path)
    }

    private fun openMenu(path: RestNodePath) {
        menu = RestNodeMenu(styleSheet, path, model.node(path)?.let { if (path.isRoot) RestNodeKind.COLLECTION else it.nodeKind() } ?: RestNodeKind.REQUEST)
    }

    private fun executeMenu(action: RestMenuAction, path: RestNodePath) {
        when (action) {
            RestMenuAction.OPEN -> onOpen(path)
            RestMenuAction.ADD_REQUEST -> execute(Action.AddRequest(validParent(path)))
            RestMenuAction.ADD_FOLDER -> execute(Action.AddFolder(validParent(path)))
            RestMenuAction.RUN -> onRun(path)
            RestMenuAction.RENAME -> openInput("Rename", model.node(path)?.string("name") ?: model.collectionName())
            RestMenuAction.DUPLICATE -> model.duplicate(path)?.let { model.select(it); onOpen(it); notifyChanged("Duplicated node") }
            RestMenuAction.MOVE_UP -> model.moveUp(path)?.let { model.select(it); notifyChanged("Moved node") }
            RestMenuAction.MOVE_DOWN -> model.moveDown(path)?.let { model.select(it); notifyChanged("Moved node") }
            RestMenuAction.MOVE_TO -> moveDialog = RestMoveDialog(styleSheet, path, model.collection.walkRestTree().filter { it.kind != RestNodeKind.REQUEST }, onInvalidate)
            RestMenuAction.DELETE -> requestDelete(path)
            RestMenuAction.IMPORT -> execute(Action.Import)
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

    private fun openInput(title: String, initial: String) {
        dialog = RestInputDialog(styleSheet, title, "Path", initial, onInvalidate)
    }

    private fun handleRenameOrPath(value: String) {
        val title = dialog?.title ?: return
        if (title == "Rename") {
            val path = lines.getOrNull(selected)?.path ?: return
            model.rename(path, value)
            notifyChanged("Renamed node")
            return
        }
        val file = Path.of(value)
        if (title.startsWith("Import")) {
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
}

enum class RestMenuAction {
    OPEN, ADD_REQUEST, ADD_FOLDER, RUN, RENAME, DUPLICATE, MOVE_UP, MOVE_DOWN, MOVE_TO, DELETE, IMPORT, EXPORT
}

class RestNodeMenu(
    styleSheet: StyleSheet,
    val path: RestNodePath,
    kind: RestNodeKind
) : BaseComponent(styleSheet) {
    private val actions = when (kind) {
        RestNodeKind.COLLECTION -> listOf(RestMenuAction.ADD_REQUEST, RestMenuAction.ADD_FOLDER, RestMenuAction.RUN, RestMenuAction.RENAME, RestMenuAction.IMPORT, RestMenuAction.EXPORT)
        RestNodeKind.FOLDER -> listOf(RestMenuAction.OPEN, RestMenuAction.ADD_REQUEST, RestMenuAction.ADD_FOLDER, RestMenuAction.RUN, RestMenuAction.RENAME, RestMenuAction.DUPLICATE, RestMenuAction.MOVE_UP, RestMenuAction.MOVE_DOWN, RestMenuAction.MOVE_TO, RestMenuAction.DELETE)
        RestNodeKind.REQUEST -> listOf(RestMenuAction.OPEN, RestMenuAction.RUN, RestMenuAction.RENAME, RestMenuAction.DUPLICATE, RestMenuAction.MOVE_UP, RestMenuAction.MOVE_DOWN, RestMenuAction.MOVE_TO, RestMenuAction.DELETE)
    }
    var action: RestMenuAction? = null
        private set
    var finished: Boolean = false
        private set
    private var selected = 0
    private var firstRow = 0
    private var lastRange: IntRange = IntRange.EMPTY

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(style.fg, style.bg)
        val width = actions.maxOfOrNull { it.name.length }?.plus(6)?.coerceAtMost(cols)?.coerceAtLeast(18) ?: 18
        val height = actions.size.coerceAtMost(rows - 2).coerceAtLeast(1)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        firstRow = ((rows - height) / 2).coerceAtLeast(1)
        canvas.withStyle(style) { drawRect(x, firstRow, width, height) }
        actions.take(height).forEachIndexed { index, item ->
            val label = " ${item.name.lowercase().replace('_', ' ')} ".padEnd(width, ' ')
            canvas.withStyle(if (index == selected) button else style) { drawText(x, firstRow + index, label.take(width)) }
        }
        lastRange = x until x + width
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            if (y in firstRow until firstRow + actions.size && x in lastRange) {
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

class RestInputDialog(
    styleSheet: StyleSheet,
    val title: String,
    private val label: String,
    initial: String,
    private val onInvalidate: () -> Unit
) : BaseComponent(styleSheet) {
    private var value = initial
    private var cursor = value.length
    var result: String? = null
        private set
    var finished = false
        private set

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = minOf(cols, 84).coerceAtLeast(32)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = (rows / 2 - 3).coerceAtLeast(1)
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(style.fg, style.bg)
        canvas.withStyle(style) { drawRect(x, y, width, 7) }
        canvas.withStyle(style) {
            drawText(x + 2, y + 1, title.take(width - 4))
            drawText(x + 2, y + 3, "$label: ${value.take(width - 10)}".take(width - 4))
            drawText(x + width - 16, y + 5, "[OK] [Cancel]")
        }
        canvas.withStyle(button) { drawText(x + width - 16, y + 5, "[OK] [Cancel]") }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val y = event.y ?: return true
            if (y >= (event.rows ?: 0) / 2 + 1) {
                result = value.trim()
                finished = true
            }
            return true
        }
        if (event.kind != "key_down") return true
        when (event.key?.lowercase()) {
            "escape", "esc" -> finished = true
            "enter", "return" -> { result = value.trim(); finished = true }
            "backspace" -> if (cursor > 0) { value = value.removeRange(cursor - 1, cursor); cursor-- }
            "left" -> cursor = (cursor - 1).coerceAtLeast(0)
            "right" -> cursor = (cursor + 1).coerceAtMost(value.length)
            else -> if (!event.ctrl && !event.alt && !event.meta && event.key?.length == 1) {
                value = value.substring(0, cursor) + event.key + value.substring(cursor)
                cursor++
            }
        }
        onInvalidate()
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
    var confirmed = false
        private set
    var finished = false
        private set

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = minOf(cols, 56).coerceAtLeast(30)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = (rows / 2 - 2).coerceAtLeast(1)
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        val button = styleSheet.getStyle("lsp-button").withDefaults(style.fg, style.bg)
        canvas.withStyle(style) { drawRect(x, y, width, 5) }
        canvas.withStyle(style) { drawText(x + 2, y + 1, prompt.take(width - 4)); drawText(x + width - 18, y + 3, "[Delete] [Cancel]") }
        canvas.withStyle(button) { drawText(x + width - 18, y + 3, "[Delete] [Cancel]") }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") { confirmed = true; finished = true; return true }
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> finished = true
                "enter", "return", "delete" -> { confirmed = true; finished = true }
            }
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
    private val options = folders.filter { it.path.indices != path.indices && !it.path.indices.startsWith(path.indices) }
    private var selected = 0
    var target: RestNodePath? = null
        private set
    var finished = false
        private set

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = minOf(cols, 70).coerceAtLeast(34)
        val height = minOf(rows - 2, options.size + 2).coerceAtLeast(3)
        val x = ((cols - width) / 2).coerceAtLeast(0)
        val y = ((rows - height) / 2).coerceAtLeast(1)
        val style = styleSheet.getStyle("project-search-dialog").withDefaults()
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(style.fg, style.bg)
        canvas.withStyle(style) { drawRect(x, y, width, height); drawText(x + 2, y + 1, "Move to folder") }
        options.take(height - 2).forEachIndexed { index, folder ->
            val label = " ".repeat(folder.depth * 2) + folder.name
            canvas.withStyle(if (index == selected) active else style) { drawText(x + 2, y + 2 + index, label.take(width - 4)) }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> finished = true
                "up" -> selected = (selected - 1).coerceAtLeast(0)
                "down" -> selected = (selected + 1).coerceAtMost((options.size - 1).coerceAtLeast(0))
                "enter", "return" -> { target = options.getOrNull(selected)?.path; finished = true }
            }
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_down") {
            val row = (event.y ?: 0) - 2
            target = options.getOrNull(row)?.path
            finished = true
            return true
        }
        return true
    }
}

private fun List<Int>.startsWith(prefix: List<Int>): Boolean = size >= prefix.size && take(prefix.size) == prefix
