package editor.rest.model

import java.util.UUID
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

const val POSTMAN_COLLECTION_SCHEMA =
    "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"

@Serializable
data class RestApiUiState(
    val selectedPath: String? = null,
    val responsePanelHeight: Int = 12,
    val prettyPrintResponses: Boolean = true,
    val environmentPanelHeight: Int = 8
)

@Serializable
data class RestEnvironmentValue(
    val key: String,
    val value: String,
    val enabled: Boolean = true,
    val type: String = "default",
    val description: String? = null,
    val secret: Boolean = false
)

@Serializable
data class RestEnvironment(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "New environment",
    val values: List<RestEnvironmentValue> = emptyList()
)

@Serializable
data class RestApiProjectState(
    val version: Int = 1,
    val collection: JsonObject = defaultCollection(),
    val ui: RestApiUiState = RestApiUiState(),
    val environments: List<RestEnvironment> = emptyList(),
    val activeEnvironmentId: String? = null
)

data class RestNodePath(val indices: List<Int> = emptyList()) {
    val isRoot: Boolean get() = indices.isEmpty()
    val parent: RestNodePath get() = RestNodePath(indices.dropLast(1))
    val lastIndex: Int get() = indices.last()

    fun child(index: Int): RestNodePath = RestNodePath(indices + index)
    fun encode(): String = indices.joinToString("/")

    companion object {
        fun decode(value: String?): RestNodePath = RestNodePath(
            value.orEmpty().takeIf { it.isNotBlank() }
                ?.split('/')
                ?.mapNotNull { it.toIntOrNull() }
                .orEmpty()
        )
    }
}

enum class RestNodeKind { COLLECTION, FOLDER, REQUEST }

data class RestTreeNode(
    val path: RestNodePath,
    val kind: RestNodeKind,
    val name: String,
    val method: String? = null,
    val depth: Int = 0,
    val hasChildren: Boolean = false
)

fun defaultCollection(name: String = "REST API"): JsonObject = buildJsonObject {
    putJsonObject("info") {
        put("name", name)
        put("schema", POSTMAN_COLLECTION_SCHEMA)
    }
    putJsonArray("item") {}
}

fun newRestFolder(name: String = "New Folder"): JsonObject = buildJsonObject {
    put("name", name)
    putJsonArray("item") {}
}

fun newRestRequest(name: String = "New Request"): JsonObject = buildJsonObject {
    put("id", UUID.randomUUID().toString())
    put("name", name)
    putJsonObject("request") {
        put("method", "GET")
        putJsonArray("header") {}
        putJsonObject("url") { put("raw", "") }
    }
    putJsonArray("response") {}
}

fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

fun JsonObject.jsonObject(key: String): JsonObject? = this[key] as? JsonObject

fun JsonObject.array(key: String): JsonArray? = this[key] as? JsonArray

fun JsonObject.withField(key: String, value: JsonElement): JsonObject = buildJsonObject {
    for ((currentKey, currentValue) in this@withField) {
        if (currentKey != key) put(currentKey, currentValue)
    }
    put(key, value)
}

fun JsonObject.withOptionalField(key: String, value: JsonElement?): JsonObject =
    if (value == null) withoutField(key) else withField(key, value)

fun JsonObject.withoutField(key: String): JsonObject = JsonObject(
    filterKeys { it != key }
)

fun JsonObject.withName(name: String): JsonObject = withField("name", JsonPrimitive(name))

fun JsonObject.items(): JsonArray = array("item") ?: JsonArray(emptyList())

fun JsonObject.isRequestNode(): Boolean = jsonObject("request") != null

fun JsonObject.isFolderNode(): Boolean = !isRequestNode() && this["item"] is JsonArray

fun JsonObject.nodeKind(): RestNodeKind = when {
    isRequestNode() -> RestNodeKind.REQUEST
    else -> RestNodeKind.FOLDER
}

fun JsonObject.requestMethod(): String? = jsonObject("request")?.string("method")

fun JsonObject.infoName(): String = jsonObject("info")?.string("name") ?: "REST API"

fun JsonObject.replaceItems(items: JsonArray): JsonObject = withField("item", items)

fun JsonObject.replaceItem(index: Int, item: JsonObject): JsonObject {
    val current = items().toMutableList()
    if (index !in current.indices) return this
    current[index] = item
    return replaceItems(JsonArray(current))
}

fun JsonObject.insertItem(index: Int, item: JsonObject): JsonObject {
    val current = items().toMutableList()
    current.add(index.coerceIn(0, current.size), item)
    return replaceItems(JsonArray(current))
}

fun JsonObject.removeItem(index: Int): Pair<JsonObject, JsonObject?> {
    val current = items().toMutableList()
    if (index !in current.indices) return this to null
    val removed = current.removeAt(index) as? JsonObject
    return replaceItems(JsonArray(current)) to removed
}

fun deepCopyWithNewRequestIds(node: JsonObject): JsonObject {
    var copy = node
    if (copy.isRequestNode()) copy = copy.withField("id", JsonPrimitive(UUID.randomUUID().toString()))
    val children = copy.items()
    if (children.isEmpty()) return copy
    return copy.replaceItems(JsonArray(children.mapNotNull { (it as? JsonObject)?.let(::deepCopyWithNewRequestIds) }))
}

fun JsonObject.nodeAt(path: RestNodePath): JsonObject? {
    var current: JsonObject = this
    for (index in path.indices) {
        current = current.items().getOrNull(index) as? JsonObject ?: return null
    }
    return current
}

fun JsonObject.replaceNode(path: RestNodePath, replacement: JsonObject): JsonObject {
    if (path.isRoot) return replacement
    val parent = nodeAt(path.parent) ?: return this
    val updatedParent = parent.replaceItem(path.lastIndex, replacement)
    return replaceNode(path.parent, updatedParent)
}

fun JsonObject.insertAt(parentPath: RestNodePath, index: Int, item: JsonObject): JsonObject {
    val parent = nodeAt(parentPath) ?: return this
    return replaceNode(parentPath, parent.insertItem(index, item))
}

fun JsonObject.removeAt(path: RestNodePath): Pair<JsonObject, JsonObject?> {
    if (path.isRoot) return this to null
    val parent = nodeAt(path.parent) ?: return this to null
    val (updatedParent, removed) = parent.removeItem(path.lastIndex)
    return replaceNode(path.parent, updatedParent) to removed
}

fun JsonObject.walkRestTree(): List<RestTreeNode> {
    val result = mutableListOf<RestTreeNode>()
    result += RestTreeNode(RestNodePath(), RestNodeKind.COLLECTION, infoName(), hasChildren = items().isNotEmpty())
    fun visit(parent: JsonObject, parentPath: RestNodePath, depth: Int) {
        parent.items().forEachIndexed { index, element ->
            val item = element as? JsonObject ?: return@forEachIndexed
            val path = parentPath.child(index)
            val kind = item.nodeKind()
            result += RestTreeNode(
                path = path,
                kind = kind,
                name = item.string("name") ?: if (kind == RestNodeKind.REQUEST) "Unnamed Request" else "Unnamed Folder",
                method = item.requestMethod(),
                depth = depth,
                hasChildren = item.items().isNotEmpty()
            )
            if (kind == RestNodeKind.FOLDER) visit(item, path, depth + 1)
        }
    }
    visit(this, RestNodePath(), 1)
    return result
}

private val JsonPrimitive.contentOrNull: String?
    get() = if (isString || content != "null") content else null
