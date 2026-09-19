package editor.rest.resolve

import editor.rest.model.RestNodePath
import editor.rest.model.array
import editor.rest.model.items
import editor.rest.model.jsonObject
import editor.rest.model.nodeAt
import editor.rest.model.string
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.Locale
import java.util.regex.Pattern
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

data class RestVariableValue(
    val key: String,
    val value: String,
    val type: String = "string",
    val source: String,
    val disabled: Boolean = false,
    val description: String? = null
)

data class RestVariableResolution(
    val value: String,
    val unresolved: Set<String> = emptySet(),
    val cycles: Set<String> = emptySet()
)

data class RestAuthResolution(
    val type: String,
    val definition: JsonObject,
    val source: String
)

data class ResolvedHeader(val name: String, val value: String)

data class ResolvedRequest(
    val path: RestNodePath,
    val method: String,
    val url: String,
    val headers: List<ResolvedHeader>,
    val body: ByteArray? = null,
    val timeoutMs: Long = 30_000,
    val followRedirects: Boolean = true,
    val sslValidation: Boolean = true
)

class RestRequestValidationException(message: String) : IllegalArgumentException(message)

class RestVariableResolver(private val collection: JsonObject, private val path: RestNodePath) {
    private val variables: LinkedHashMap<String, RestVariableValue> = linkedMapOf()

    init {
        val ancestors = mutableListOf<JsonObject>()
        ancestors += collection
        var current = collection
        path.indices.forEach { index ->
            current = current.items().getOrNull(index) as? JsonObject ?: return@forEach
            ancestors += current
        }
        ancestors.forEachIndexed { index, node ->
            val source = if (index == 0) "Collection" else node.string("name") ?: "Folder"
            readVariables(node, source).forEach { value ->
                if (!value.disabled) variables[value.key] = value
            }
        }
    }

    fun effectiveVariables(): List<RestVariableValue> = variables.values.toList()

    fun resolve(value: String): RestVariableResolution {
        val unresolved = linkedSetOf<String>()
        val cycles = linkedSetOf<String>()
        fun resolveName(name: String, stack: Set<String>): String {
            val variable = variables[name]
            if (variable == null) {
                unresolved += name
                return "{{$name}}"
            }
            if (name in stack) {
                cycles += name
                return "{{$name}}"
            }
            return replace(variable.value, stack + name, ::resolveName)
        }
        val result = replace(value, emptySet(), ::resolveName)
        return RestVariableResolution(result, unresolved, cycles)
    }

    private fun replace(value: String, stack: Set<String>, resolver: (String, Set<String>) -> String): String {
        val matcher = VARIABLE_PATTERN.matcher(value)
        val output = StringBuffer()
        while (matcher.find()) {
            val name = matcher.group(1).trim()
            val replacement = resolver(name, stack)
            matcher.appendReplacement(output, MatcherEscaper.quoteReplacement(replacement))
        }
        matcher.appendTail(output)
        return output.toString()
    }

    private fun readVariables(node: JsonObject, source: String): List<RestVariableValue> =
        (node.array("variable") ?: JsonArray(emptyList())).mapNotNull { element ->
            val obj = element as? JsonObject ?: return@mapNotNull null
            val key = obj.string("key")?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
            RestVariableValue(
                key = key,
                value = obj.string("value").orEmpty(),
                type = obj.string("type") ?: "string",
                source = source,
                disabled = obj.boolean("disabled"),
                description = obj.string("description")
            )
        }

    companion object {
        private val VARIABLE_PATTERN = Pattern.compile("\\{\\{([^{}]+)}}")
    }
}

class RestRequestResolver(
    private val collection: JsonObject,
    private val path: RestNodePath,
    private val workspaceRoot: Path
) {
    private val variables = RestVariableResolver(collection, path)
    private val unresolved = linkedSetOf<String>()
    private val cycles = linkedSetOf<String>()

    fun effectiveVariables(): List<RestVariableValue> = variables.effectiveVariables()

    fun resolve(value: String): RestVariableResolution = variables.resolve(value)

    fun effectiveAuth(): RestAuthResolution? {
        var result: RestAuthResolution? = null
        var current = collection
        collection.jsonObject("auth")?.let { result = RestAuthResolution(it.string("type") ?: "noauth", it, "Collection") }
        path.indices.forEach { index ->
            current = current.items().getOrNull(index) as? JsonObject ?: return@forEach
            current.jsonObject("auth")?.let {
                result = RestAuthResolution(it.string("type") ?: "noauth", it, current.string("name") ?: "Folder")
            }
            current.jsonObject("request")?.jsonObject("auth")?.let {
                result = RestAuthResolution(it.string("type") ?: "noauth", it, current.string("name") ?: "Request")
            }
        }
        return result
    }

    fun materialize(): ResolvedRequest {
        unresolved.clear()
        cycles.clear()
        val item = collection.nodeAt(path) ?: throw RestRequestValidationException("Request does not exist")
        val request = item.jsonObject("request") ?: throw RestRequestValidationException("Selected node is not a request")
        val method = request.string("method")?.trim().orEmpty().ifBlank { "GET" }
        val url = resolveUrl(request)
        val uri = runCatching { java.net.URI(url) }.getOrElse {
            throw RestRequestValidationException("Invalid URL: ${it.message ?: url}")
        }
        if (uri.scheme?.lowercase(Locale.ROOT) !in setOf("http", "https")) {
            throw RestRequestValidationException("Unsupported URL scheme: ${uri.scheme ?: "missing"}")
        }
        val headers = readHeaders(request)
        val auth = effectiveAuth()
        val authQuery = applyAuth(headers, auth)
        val body = buildBody(request, headers)
        val finalUrl = authQuery?.let { appendQuery(url, it.first, it.second) } ?: url
        val settings = item.jsonObject("protocolProfileBehavior") ?: collection.jsonObject("protocolProfileBehavior")
        val timeout = settings?.long("requestTimeout")?.coerceAtLeast(1) ?: 30_000L
        val followRedirects = settings?.boolean("disableRedirects") != true
        val sslValidation = settings?.boolean("disableSslVerification") != true
        if (unresolved.isNotEmpty()) throw RestRequestValidationException("Unresolved variable(s): ${unresolved.joinToString { "{{$it}}" }}")
        if (cycles.isNotEmpty()) throw RestRequestValidationException("Variable substitution cycle: ${cycles.joinToString()}")
        return ResolvedRequest(path, method, finalUrl, headers, body.bytes, timeout, followRedirects, sslValidation)
    }

    private fun resolveValue(value: String): String {
        val result = resolve(value)
        unresolved += result.unresolved
        cycles += result.cycles
        return result.value
    }

    private fun resolveUrl(request: JsonObject): String {
        val urlElement = request["url"]
        val url = when (urlElement) {
            is JsonPrimitive -> urlElement.content
            is JsonObject -> urlElement.string("raw") ?: structuredUrl(urlElement)
            else -> ""
        }
        if (url.isBlank()) throw RestRequestValidationException("URL is empty")
        var resolved = resolveValue(url)
        val urlObject = urlElement as? JsonObject
        urlObject?.array("variable")?.forEach { element ->
            val variable = element as? JsonObject ?: return@forEach
            val key = variable.string("key") ?: return@forEach
            val value = resolveValue(variable.string("value").orEmpty())
            resolved = resolved.replace(":$key", value)
        }
        val query = urlObject?.array("query")?.mapNotNull { element ->
            val queryEntry = element as? JsonObject ?: return@mapNotNull null
            if (queryEntry.boolean("disabled")) return@mapNotNull null
            val key = resolveValue(queryEntry.string("key").orEmpty())
            val value = resolveValue(queryEntry.string("value").orEmpty())
            if (key.isBlank()) null else "${encode(key)}=${encode(value)}"
        }
        if (query != null) {
            resolved = resolved.substringBefore('?') + query.takeIf { it.isNotEmpty() }?.let { "?${it.joinToString("&")}" }.orEmpty()
        }
        return resolved
    }

    private fun structuredUrl(url: JsonObject): String {
        val protocol = url.string("protocol") ?: "http"
        val host = (url["host"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().joinToString(".")
        val path = (url["path"] as? JsonArray)?.mapNotNull { (it as? JsonPrimitive)?.content }.orEmpty().joinToString("/")
        return "$protocol://$host/${path.trimStart('/')}"
    }

    private fun readHeaders(request: JsonObject): MutableList<ResolvedHeader> =
        (request.array("header") ?: JsonArray(emptyList())).mapNotNullTo(mutableListOf()) { element ->
            val header = element as? JsonObject ?: return@mapNotNullTo null
            if (header.boolean("disabled")) return@mapNotNullTo null
            val name = resolveValue(header.string("key").orEmpty())
            if (name.isBlank()) null else ResolvedHeader(name, resolveValue(header.string("value").orEmpty()))
        }

    private fun applyAuth(headers: MutableList<ResolvedHeader>, auth: RestAuthResolution?): Pair<String, String>? {
        val definition = auth ?: return null
        when (definition.type.lowercase(Locale.ROOT)) {
            "noauth" -> Unit
            "bearer" -> {
                val token = authValue(definition.definition, "token")
                headers.removeAll { it.name.equals("Authorization", true) }
                headers += ResolvedHeader("Authorization", "Bearer $token")
            }
            "basic" -> {
                val user = authValue(definition.definition, "username")
                val password = authValue(definition.definition, "password")
                val encoded = Base64.getEncoder().encodeToString("$user:$password".toByteArray(StandardCharsets.UTF_8))
                headers.removeAll { it.name.equals("Authorization", true) }
                headers += ResolvedHeader("Authorization", "Basic $encoded")
            }
            "apikey" -> {
                val key = authValue(definition.definition, "key")
                val value = authValue(definition.definition, "value")
                when (authValue(definition.definition, "in").lowercase(Locale.ROOT)) {
                    "query" -> return key to value
                    else -> {
                        headers.removeAll { it.name.equals(key, true) }
                        headers += ResolvedHeader(key, value)
                    }
                }
            }
            else -> throw RestRequestValidationException("Cannot send request: ${definition.type} authentication execution is not implemented yet.")
        }
        return null
    }

    private fun authValue(auth: JsonObject, key: String): String {
        val type = auth.string("type").orEmpty()
        val value = (auth.array(type)?.firstOrNull { (it as? JsonObject)?.string("key") == key } as? JsonObject)
            ?.string("value")
            ?: auth.string(key)
            ?: ""
        return resolveValue(value)
    }

    private data class BodyResult(val bytes: ByteArray?, val contentType: String? = null)

    private fun buildBody(request: JsonObject, headers: MutableList<ResolvedHeader>): BodyResult {
        val body = request.jsonObject("body") ?: return BodyResult(null)
        val mode = body.string("mode")?.lowercase(Locale.ROOT) ?: return BodyResult(null)
        val result = when (mode) {
            "raw" -> {
                val language = body.jsonObject("options")?.jsonObject("raw")?.string("language")
                val type = when (language?.lowercase(Locale.ROOT)) {
                    "json" -> "application/json"
                    "xml" -> "application/xml"
                    "html" -> "text/html"
                    else -> "text/plain"
                }
                BodyResult(resolveValue(body.string("raw").orEmpty()).toByteArray(StandardCharsets.UTF_8), type)
            }
            "urlencoded" -> {
                val content = entries(body.array("urlencoded")).joinToString("&") { "${encode(it.first)}=${encode(it.second)}" }
                BodyResult(content.toByteArray(StandardCharsets.UTF_8), "application/x-www-form-urlencoded")
            }
            "formdata" -> buildMultipart(body.array("formdata"), headers)
            "file" -> {
                val source = body.jsonObject("file")?.string("src") ?: throw RestRequestValidationException("Request body file is missing")
                val file = workspaceRoot.resolve(resolveValue(source)).normalize()
                if (!Files.isRegularFile(file)) throw RestRequestValidationException("Body file does not exist: $source")
                BodyResult(Files.readAllBytes(file), null)
            }
            "graphql" -> {
                val query = resolveValue(body.jsonObject("graphql")?.string("query").orEmpty())
                val variables = body.jsonObject("graphql")?.get("variables") ?: buildJsonObject {}
                BodyResult(buildJsonObject { put("query", query); put("variables", resolveJson(variables)) }.toString().toByteArray(), "application/json")
            }
            else -> BodyResult(null)
        }
        result.contentType?.let { type ->
            if (headers.none { it.name.equals("Content-Type", true) }) headers += ResolvedHeader("Content-Type", type)
        }
        return result
    }

    private fun entries(array: JsonArray?): List<Pair<String, String>> = array.orEmpty().mapNotNull { element ->
        val entry = element as? JsonObject ?: return@mapNotNull null
        if (entry.boolean("disabled")) return@mapNotNull null
        val key = resolveValue(entry.string("key").orEmpty())
        val value = resolveValue(entry.string("value").orEmpty())
        if (key.isBlank()) null else key to value
    }

    private fun buildMultipart(fields: JsonArray?, headers: MutableList<ResolvedHeader>): BodyResult {
        val boundary = "kode-${System.nanoTime()}"
        val output = java.io.ByteArrayOutputStream()
        fields.orEmpty().mapNotNull { it as? JsonObject }.forEach { field ->
            if (field.boolean("disabled")) return@forEach
            val key = resolveValue(field.string("key").orEmpty())
            if (key.isBlank()) return@forEach
            val type = field.string("type")?.lowercase(Locale.ROOT) ?: "text"
            val value = resolveValue(field.string("value").orEmpty())
            output.write("--$boundary\r\n".toByteArray(StandardCharsets.UTF_8))
            if (type == "file") {
                val file = workspaceRoot.resolve(value).normalize()
                if (!Files.isRegularFile(file)) throw RestRequestValidationException("Multipart body file does not exist: $value")
                output.write("Content-Disposition: form-data; name=\"$key\"; filename=\"${file.fileName}\"\r\n\r\n".toByteArray(StandardCharsets.UTF_8))
                Files.newInputStream(file).use { it.copyTo(output) }
            } else {
                output.write("Content-Disposition: form-data; name=\"$key\"\r\n\r\n$value".toByteArray(StandardCharsets.UTF_8))
            }
            output.write("\r\n".toByteArray(StandardCharsets.UTF_8))
        }
        output.write("--$boundary--\r\n".toByteArray(StandardCharsets.UTF_8))
        return BodyResult(output.toByteArray(), "multipart/form-data; boundary=$boundary")
    }

    private fun resolveJson(element: JsonElement): JsonElement = when (element) {
        is JsonObject -> JsonObject(element.mapValues { (_, value) -> resolveJson(value) })
        is JsonArray -> JsonArray(element.map(::resolveJson))
        is JsonPrimitive -> if (element.isString) JsonPrimitive(resolveValue(element.content)) else element
        else -> element
    }
}

private object MatcherEscaper {
    fun quoteReplacement(value: String): String = value.replace("\\", "\\\\").replace("$", "\\$")
}

private fun JsonObject.boolean(key: String): Boolean =
    (this[key] as? JsonPrimitive)?.content?.toBooleanStrictOrNull() ?: false

private fun JsonObject.long(key: String): Long? =
    (this[key] as? JsonPrimitive)?.content?.toLongOrNull()

private fun encode(value: String): String = URLEncoder.encode(value, StandardCharsets.UTF_8)

private fun appendQuery(url: String, key: String, value: String): String =
    url + (if (url.contains('?')) "&" else "?") + encode(key) + "=" + encode(value)
