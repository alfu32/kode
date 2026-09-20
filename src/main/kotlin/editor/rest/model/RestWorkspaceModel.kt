package editor.rest.model

import java.nio.file.Files
import java.nio.file.Path
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Mutable editing source of truth for the single REST collection owned by a workspace. */
class RestWorkspaceModel(
    initial: RestApiProjectState = RestApiProjectState(),
    private val onChanged: (() -> Unit)? = null
) {
    var collection: JsonObject = initial.collection
        private set
    var ui: RestApiUiState = initial.ui
        private set
    var environments: List<RestEnvironment> = initial.environments
        private set
    var activeEnvironmentId: String? = initial.activeEnvironmentId
        private set

    fun restore(state: RestApiProjectState) {
        collection = state.collection
        ui = state.ui
        environments = state.environments
        activeEnvironmentId = state.activeEnvironmentId?.takeIf { id -> environments.any { it.id == id } }
        onChanged?.invoke()
    }

    fun projectState(): RestApiProjectState = RestApiProjectState(
        collection = collection,
        ui = ui,
        environments = environments,
        activeEnvironmentId = activeEnvironmentId
    )

    fun environment(id: String): RestEnvironment? = environments.firstOrNull { it.id == id }

    fun activeEnvironment(): RestEnvironment? = activeEnvironmentId?.let(::environment)

    fun activeEnvironmentVariables(): Map<String, String> = activeEnvironment()
        ?.values
        ?.filter { it.enabled }
        ?.associate { it.key to it.value }
        .orEmpty()

    fun createEnvironment(name: String = "New environment"): RestEnvironment {
        val environment = RestEnvironment(name = name.ifBlank { "New environment" })
        environments = environments + environment
        if (activeEnvironmentId == null) activeEnvironmentId = environment.id
        onChanged?.invoke()
        return environment
    }

    fun renameEnvironment(id: String, name: String): Boolean {
        if (name.isBlank() || environment(id) == null) return false
        environments = environments.map { if (it.id == id) it.copy(name = name) else it }
        onChanged?.invoke()
        return true
    }

    fun duplicateEnvironment(id: String, name: String? = null): RestEnvironment? {
        val source = environment(id) ?: return null
        val copy = source.copy(id = UUID.randomUUID().toString(), name = name ?: "${source.name} copy")
        environments = environments + copy
        onChanged?.invoke()
        return copy
    }

    fun activateEnvironment(id: String): Boolean {
        if (environment(id) == null) return false
        activeEnvironmentId = id
        onChanged?.invoke()
        return true
    }

    fun removeEnvironment(id: String): Boolean {
        if (environment(id) == null) return false
        environments = environments.filterNot { it.id == id }
        if (activeEnvironmentId == id) activeEnvironmentId = environments.firstOrNull()?.id
        onChanged?.invoke()
        return true
    }

    fun updateEnvironmentText(id: String, text: String): Boolean {
        val current = environment(id) ?: return false
        environments = environments.map {
            if (it.id == id) it.copy(values = RestEnvironmentCodec.parse(text)) else it
        }
        onChanged?.invoke()
        return current != environment(id)
    }

    fun selectedPath(): RestNodePath = RestNodePath.decode(ui.selectedPath)

    fun select(path: RestNodePath) {
        ui = ui.copy(selectedPath = path.encode().takeIf { it.isNotEmpty() })
        onChanged?.invoke()
    }

    fun setResponsePanelHeight(height: Int) {
        ui = ui.copy(responsePanelHeight = height.coerceIn(5, 60))
        onChanged?.invoke()
    }

    fun setEnvironmentPanelHeight(height: Int) {
        ui = ui.copy(environmentPanelHeight = height.coerceIn(5, 60))
        onChanged?.invoke()
    }

    fun setPrettyPrintResponses(enabled: Boolean) {
        ui = ui.copy(prettyPrintResponses = enabled)
        onChanged?.invoke()
    }

    fun collectionName(): String = collection.infoName()

    fun node(path: RestNodePath): JsonObject? = collection.nodeAt(path)

    fun updateNode(path: RestNodePath, transform: (JsonObject) -> JsonObject): Boolean {
        val current = collection.nodeAt(path) ?: return false
        collection = collection.replaceNode(path, transform(current))
        onChanged?.invoke()
        return true
    }

    fun updateCollection(transform: (JsonObject) -> JsonObject) {
        collection = transform(collection)
        onChanged?.invoke()
    }

    fun rename(path: RestNodePath, name: String) {
        if (path.isRoot) {
            val info = collection.jsonObject("info") ?: JsonObject(emptyMap())
            collection = collection.withField("info", info.withName(name))
        } else {
            node(path)?.let { collection = collection.replaceNode(path, it.withName(name)) }
        }
        onChanged?.invoke()
    }

    fun addFolder(parent: RestNodePath, name: String = "New Folder"): RestNodePath? =
        addItem(parent, newRestFolder(name))

    fun addRequest(parent: RestNodePath, name: String = "New Request"): RestNodePath? =
        addItem(parent, newRestRequest(name))

    private fun addItem(parent: RestNodePath, item: JsonObject): RestNodePath? {
        val parentNode = collection.nodeAt(parent) ?: return null
        if (!parent.isRoot && !parentNode.isFolderNode()) return null
        val index = parentNode.items().size
        collection = collection.insertAt(parent, index, item)
        onChanged?.invoke()
        return parent.child(index)
    }

    fun delete(path: RestNodePath): Boolean {
        if (path.isRoot || collection.nodeAt(path) == null) return false
        val (updated, removed) = collection.removeAt(path)
        if (removed == null) return false
        collection = updated
        if (selectedPath().indices == path.indices || selectedPath().indices.startsWith(path.indices)) select(path.parent)
        onChanged?.invoke()
        return true
    }

    fun duplicate(path: RestNodePath): RestNodePath? {
        if (path.isRoot) return null
        val node = collection.nodeAt(path) ?: return null
        val copy = deepCopyWithNewRequestIds(node)
        val parent = collection.nodeAt(path.parent) ?: return null
        val targetIndex = path.lastIndex + 1
        collection = collection.insertAt(path.parent, targetIndex, copy)
        onChanged?.invoke()
        return path.parent.child(targetIndex)
    }

    fun move(path: RestNodePath, targetParent: RestNodePath, targetIndex: Int? = null): RestNodePath? {
        if (path.isRoot || targetParent.indices.startsWith(path.indices)) return null
        val node = collection.nodeAt(path) ?: return null
        val target = collection.nodeAt(targetParent) ?: return null
        if (!targetParent.isRoot && !target.isFolderNode()) return null
        val (without, removed) = collection.removeAt(path)
        if (removed == null) return null
        var index = targetIndex ?: target.items().size
        if (path.parent.indices == targetParent.indices && path.lastIndex < index) index--
        val newCollection = without.insertAt(targetParent, index.coerceAtLeast(0), node)
        collection = newCollection
        onChanged?.invoke()
        return targetParent.child(index.coerceAtLeast(0))
    }

    fun moveUp(path: RestNodePath): RestNodePath? {
        if (path.isRoot || path.lastIndex <= 0) return null
        return move(path, path.parent, path.lastIndex - 1)
    }

    fun moveDown(path: RestNodePath): RestNodePath? {
        if (path.isRoot) return null
        val siblingCount = collection.nodeAt(path.parent)?.items()?.size ?: return null
        if (path.lastIndex >= siblingCount - 1) return null
        return move(path, path.parent, path.lastIndex + 2)
    }

    fun importCollection(file: Path): Result<Unit> = runCatching {
        val parsed = Json.parseToJsonElement(Files.readString(file)) as? JsonObject
            ?: error("Postman collection must be a JSON object")
        validateCollection(parsed)
        collection = parsed
        select(RestNodePath())
        onChanged?.invoke()
    }

    fun importOpenApi(file: Path, parent: RestNodePath = RestNodePath()): Result<Int> = runCatching {
        val target = collection.nodeAt(parent) ?: error("Import target does not exist")
        require(parent.isRoot || target.isFolderNode()) { "OpenAPI can only be imported into a collection or folder" }
        val imported = RestOpenApiImporter.read(file)
        var updated = collection
        var index = target.items().size
        imported.forEach { item ->
            updated = updated.insertAt(parent, index++, item)
        }
        collection = updated
        onChanged?.invoke()
        imported.size
    }

    fun exportCollection(file: Path): Result<Unit> = runCatching {
        file.parent?.let(Files::createDirectories)
        val temp = file.resolveSibling(".${file.fileName}.kode-export.tmp-${UUID.randomUUID()}")
        Files.writeString(temp, Json { prettyPrint = true }.encodeToString(JsonObject.serializer(), collection))
        Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        fun validateCollection(collection: JsonObject) {
            require(collection.jsonObject("info") != null) { "Postman collection is missing info" }
            require(collection["item"] is kotlinx.serialization.json.JsonArray) {
                "Postman collection is missing item[]"
            }
            val schema = collection.jsonObject("info")?.string("schema")
            if (schema != null && !schema.contains("collection/v2.1.0/collection.json")) {
                error("Unsupported Postman collection schema: $schema")
            }
        }
    }
}

private fun List<Int>.startsWith(prefix: List<Int>): Boolean = size >= prefix.size && take(prefix.size) == prefix
