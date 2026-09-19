package editor.rest.model

import java.nio.file.Files
import java.nio.file.Path
import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.yaml.snakeyaml.Yaml

/** Converts OpenAPI 2/3 path operations into editable Postman-shaped request items. */
object RestOpenApiImporter {
    fun read(file: Path): List<JsonObject> {
        val document = parse(file)
        require(document.string("openapi") != null || document.string("swagger") != null) {
            "The selected document is not an OpenAPI or Swagger definition"
        }
        val baseUrl = baseUrl(document)
        val groups = linkedMapOf<String, MutableList<JsonObject>>()
        val paths = document.jsonObject("paths") ?: error("OpenAPI document is missing paths")
        paths.forEach { (path, pathItem) ->
            val pathObject = pathItem as? JsonObject ?: return@forEach
            pathObject.forEach { (method, operationElement) ->
                if (method.lowercase(Locale.ROOT) !in HTTP_METHODS) return@forEach
                val operation = operationElement as? JsonObject ?: return@forEach
                val tag = operation.array("tags")?.firstString() ?: path.trim('/').substringBefore('/').ifBlank { "default" }
                groups.getOrPut(tag) { mutableListOf() } += requestItem(document, baseUrl, path, method, operation, pathObject)
            }
        }
        val title = document.jsonObject("info")?.string("title")?.takeIf { it.isNotBlank() }
            ?: "Imported OpenAPI"
        return groups.map { (name, requests) ->
            buildJsonObject {
                put("name", if (groups.size == 1 && name == "default") title else name)
                put("item", JsonArray(requests))
            }
        }
    }

    private fun requestItem(
        document: JsonObject,
        baseUrl: String,
        path: String,
        method: String,
        operation: JsonObject,
        pathItem: JsonObject
    ): JsonObject {
        val parameters = buildList {
            addAll(pathItem.array("parameters").orEmpty())
            addAll(operation.array("parameters").orEmpty())
        }.mapNotNull { it as? JsonObject }
        val variables = parameters.filter { it.string("in") == "path" }.mapNotNull { parameter ->
            parameter.string("name")?.let { name -> buildJsonObject { put("key", name); put("value", "") } }
        }
        val query = parameters.filter { it.string("in") == "query" }.mapNotNull { parameter ->
            parameter.string("name")?.let { name ->
                buildJsonObject { put("key", name); put("value", parameter.exampleValue().orEmpty()) }
            }
        }
        val headers = parameters.filter { it.string("in") == "header" }.mapNotNull { parameter ->
            parameter.string("name")?.let { name ->
                buildJsonObject { put("key", name); put("value", parameter.exampleValue().orEmpty()) }
            }
        }.toMutableList()
        val requestBody = requestBody(document, operation)
        requestBody.header?.let { header -> headers += header }
        val rawPath = path.replace(Regex("\\{([^}]+)}")) { ":${it.groupValues[1]}" }
        val rawUrl = baseUrl.trimEnd('/') + "/" + rawPath.trimStart('/')
        val url = buildJsonObject {
            put("raw", rawUrl)
            if (query.isNotEmpty()) put("query", JsonArray(query))
            if (variables.isNotEmpty()) put("variable", JsonArray(variables))
        }
        val request = buildJsonObject {
            put("method", method.uppercase(Locale.ROOT))
            put("header", JsonArray(headers))
            put("url", url)
            requestBody.body?.let { put("body", it) }
        }
        val displayName = operation.string("summary")
            ?: operation.string("operationId")
            ?: "${method.uppercase(Locale.ROOT)} $path"
        return buildJsonObject {
            put("name", displayName)
            put("request", request)
            put("response", buildJsonArray {})
        }
    }

    private data class BodyResult(val body: JsonObject?, val header: JsonObject?)

    private fun requestBody(document: JsonObject, operation: JsonObject): BodyResult {
        val openApiBody = operation.jsonObject("requestBody")
        if (openApiBody != null) {
            val content = openApiBody.jsonObject("content") ?: return BodyResult(null, null)
            val media = content.entries.firstOrNull() ?: return BodyResult(null, null)
            val mediaType = media.key
            val value = (media.value as? JsonObject)?.exampleValue()
                ?: sample((media.value as? JsonObject)?.jsonObject("schema"))
                ?: ""
            return BodyResult(
                buildJsonObject {
                    put("mode", "raw")
                    put("raw", value)
                    put("options", buildJsonObject { put("raw", buildJsonObject { put("language", mediaType.languageName()) }) })
                },
                buildJsonObject { put("key", "Content-Type"); put("value", mediaType) }
            )
        }
        val bodyParameter = operation.array("parameters").orEmpty().mapNotNull { it as? JsonObject }
            .firstOrNull { it.string("in") == "body" }
        if (bodyParameter != null) {
            val schema = bodyParameter.jsonObject("schema")
            return BodyResult(
                buildJsonObject { put("mode", "raw"); put("raw", sample(schema) ?: "{}") },
                (operation.array("consumes")?.firstString() ?: document.array("consumes")?.firstString())?.let {
                    buildJsonObject { put("key", "Content-Type"); put("value", it) }
                }
            )
        }
        return BodyResult(null, null)
    }

    private fun baseUrl(document: JsonObject): String {
        document.array("servers")?.firstOrNull()?.let { server ->
            (server as? JsonObject)?.string("url")?.let { return it }
        }
        val scheme = document.array("schemes")?.firstString() ?: "https"
        val host = document.string("host") ?: "localhost"
        return "$scheme://$host${document.string("basePath") ?: ""}"
    }

    private fun sample(schema: JsonObject?): String? {
        schema ?: return null
        schema.exampleValue()?.let { return it }
        return when (schema.string("type")) {
            "object" -> "{}"
            "array" -> "[]"
            "boolean" -> "false"
            "integer", "number" -> "0"
            else -> ""
        }
    }

    private fun parse(file: Path): JsonObject {
        val text = Files.readString(file)
        if (file.fileName.toString().lowercase(Locale.ROOT).endsWith(".json") || text.trimStart().startsWith("{")) {
            return Json.parseToJsonElement(text) as? JsonObject ?: error("OpenAPI JSON must be an object")
        }
        return toJson(Yaml().load<Any?>(text)) as? JsonObject ?: error("OpenAPI YAML must be an object")
    }

    private fun toJson(value: Any?): JsonElement = when (value) {
        null -> kotlinx.serialization.json.JsonNull
        is Map<*, *> -> JsonObject(value.entries.associate { (key, item) -> key.toString() to toJson(item) })
        is Iterable<*> -> JsonArray(value.map(::toJson))
        is Boolean -> JsonPrimitive(value)
        is Number -> JsonPrimitive(value.toString())
        else -> JsonPrimitive(value.toString())
    }

    private fun JsonObject.exampleValue(): String? {
        val value = this["example"] ?: this.jsonObject("examples")?.values?.firstOrNull()?.let { (it as? JsonObject)?.get("value") }
        return value?.let { if (it is JsonPrimitive && it.isString) it.content else it.toString() }
    }

    private fun String.languageName(): String {
        val mediaType = lowercase(Locale.ROOT).substringBefore(';')
        val subtype = mediaType.substringAfter('/', mediaType)
        return when (subtype) {
            "javascript", "x-javascript" -> "javascript"
            "x-www-form-urlencoded" -> "text"
            "xml" -> "xml"
            "html" -> "html"
            else -> subtype.substringBefore('+')
        }
    }

    private fun JsonArray.firstString(): String? = firstOrNull()?.let { (it as? JsonPrimitive)?.content }

    private val HTTP_METHODS = setOf("get", "post", "put", "patch", "delete", "head", "options", "trace")
}
