package editor.lsp

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

data class LspPosition(val line: Int, val character: Int)
data class LspLocation(val uri: String, val range: LspRange)
data class LspRange(val start: LspPosition, val end: LspPosition)
data class LspStartResult(val success: Boolean, val message: String? = null)

class LspClient(
    private val command: List<String>,
    private val workdir: Path?,
    private val rootUri: String
) {

    private val json = Json { ignoreUnknownKeys = true }
    private val seq = AtomicInteger(1)
    private val pending = ConcurrentHashMap<Int, CompletableFuture<JsonElement>>()
    private val reader = Executors.newSingleThreadExecutor { Thread(it, "lsp-reader").apply { isDaemon = true } }
    private var proc: Process? = null
    private var input: BufferedInputStream? = null
    private var output: BufferedOutputStream? = null
    @Volatile private var initialized = false
    @Volatile private var lastError: String? = null

    fun start(): LspStartResult {
        if (proc != null) return LspStartResult(true)
        val pb = ProcessBuilder(command)
        if (workdir != null) pb.directory(workdir.toFile())
        pb.redirectError(ProcessBuilder.Redirect.INHERIT)
        return runCatching {
            val p = pb.start()
            proc = p
            input = BufferedInputStream(p.inputStream)
            output = BufferedOutputStream(p.outputStream)
            reader.submit { readLoop() }
            initialize()
            lastError = null
            LspStartResult(true)
        }.getOrElse { ex ->
            stop()
            lastError = ex.message ?: ex.javaClass.simpleName
            LspStartResult(false, lastError)
        }
    }

    fun stop() {
        pending.values.forEach { it.completeExceptionally(IllegalStateException("LSP stopped")) }
        pending.clear()
        initialized = false
        input?.close()
        output?.close()
        proc?.destroyForcibly()
        proc = null
    }

    fun openDocument(uri: String, languageId: String, text: String, version: Int) {
        if (!initialized) return
        sendNotification(
            "textDocument/didOpen",
            buildJsonObject {
                put("textDocument", buildJsonObject {
                    put("uri", uri)
                    put("languageId", languageId)
                    put("version", version)
                    put("text", text)
                })
            }
        )
    }

    fun changeDocument(uri: String, text: String, version: Int) {
        if (!initialized) return
        sendNotification(
            "textDocument/didChange",
            buildJsonObject {
                put("textDocument", buildJsonObject {
                    put("uri", uri)
                    put("version", version)
                })
                put("contentChanges", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject { put("text", text) })
                })
            }
        )
    }

    fun closeDocument(uri: String) {
        if (!initialized) return
        sendNotification(
            "textDocument/didClose",
            buildJsonObject {
                put("textDocument", buildJsonObject { put("uri", uri) })
            }
        )
    }

    fun definitions(uri: String, position: LspPosition, timeoutMs: Long = 800): List<LspLocation> {
        val params = buildPositionParams(uri, position)
        val result = request("textDocument/definition", params, timeoutMs) ?: return emptyList()
        return parseLocations(result)
    }

    fun references(uri: String, position: LspPosition, timeoutMs: Long = 800): List<LspLocation> {
        val params = buildPositionParams(uri, position).toMutableMap()
        params["context"] = buildJsonObject { put("includeDeclaration", true) }
        val result = request("textDocument/references", JsonObject(params), timeoutMs) ?: return emptyList()
        return parseLocations(result)
    }

    fun completions(uri: String, position: LspPosition, timeoutMs: Long = 800): List<String> {
        val params = buildPositionParams(uri, position)
        val result = request("textDocument/completion", params, timeoutMs) ?: return emptyList()
        val items = when {
            result is JsonObject && result["items"] != null -> result["items"]
            else -> result
        }
        if (items !is kotlinx.serialization.json.JsonArray) return emptyList()
        return items.mapNotNull { el ->
            val prim = (el as? JsonObject)?.get("label") as? JsonPrimitive
            prim?.content
        }
    }

    private fun initialize() {
        val id = seq.getAndIncrement()
        val fut = CompletableFuture<JsonElement>()
        pending[id] = fut
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", "initialize")
            put("params", buildJsonObject {
                put("processId", ProcessHandle.current().pid())
                put("rootUri", rootUri)
                put("capabilities", buildJsonObject {})
            })
        }
        sendMessage(msg)
        runCatching { fut.get(1000, TimeUnit.MILLISECONDS) }
        initialized = true
        sendNotification("initialized", buildJsonObject {})
    }

    private fun buildPositionParams(uri: String, position: LspPosition): JsonObject {
        return buildJsonObject {
            put("textDocument", buildJsonObject { put("uri", uri) })
            put("position", buildJsonObject {
                put("line", position.line)
                put("character", position.character)
            })
        }
    }

    private fun request(method: String, params: JsonObject, timeoutMs: Long): JsonElement? {
        if (!initialized) return null
        val id = seq.getAndIncrement()
        val fut = CompletableFuture<JsonElement>()
        pending[id] = fut
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("id", id)
            put("method", method)
            put("params", params)
        }
        sendMessage(msg)
        return runCatching { fut.get(timeoutMs, TimeUnit.MILLISECONDS) }.getOrNull()
    }

    private fun sendNotification(method: String, params: JsonObject) {
        if (!initialized) return
        val msg = buildJsonObject {
            put("jsonrpc", "2.0")
            put("method", method)
            put("params", params)
        }
        sendMessage(msg)
    }

    private fun sendMessage(msg: JsonObject) {
        val bytes = json.encodeToString(JsonObject.serializer(), msg).toByteArray(StandardCharsets.UTF_8)
        val header = "Content-Length: ${bytes.size}\r\n\r\n"
        val out = output ?: return
        synchronized(out) {
            out.write(header.toByteArray(StandardCharsets.UTF_8))
            out.write(bytes)
            out.flush()
        }
    }

    private fun readLoop() {
        val inp = input ?: return
        val buf = ByteArray(8192)
        while (true) {
            val content = readMessage(inp, buf) ?: break
            handleMessage(content)
        }
    }

    private fun readMessage(inp: InputStream, buf: ByteArray): String? {
        var contentLength = -1
        val headerBuilder = StringBuilder()
        while (true) {
            val line = readLine(inp, buf) ?: return null
            if (line.isEmpty()) break
            headerBuilder.append(line).append('\n')
            val lower = line.lowercase()
            if (lower.startsWith("content-length")) {
                contentLength = lower.substringAfter(":").trim().toIntOrNull() ?: -1
            }
        }
        if (contentLength <= 0) return null
        val body = ByteArray(contentLength)
        var read = 0
        while (read < contentLength) {
            val r = inp.read(body, read, contentLength - read)
            if (r <= 0) break
            read += r
        }
        return String(body, 0, read, StandardCharsets.UTF_8)
    }

    private fun readLine(inp: InputStream, buf: ByteArray): String? {
        var idx = 0
        while (true) {
            val b = inp.read()
            if (b == -1) return null
            if (b == '\r'.code) {
                val next = inp.read()
                if (next != '\n'.code) continue
                break
            }
            buf[idx++] = b.toByte()
            if (idx >= buf.size) break
        }
        return String(buf, 0, idx, StandardCharsets.UTF_8)
    }

    private fun handleMessage(content: String) {
        val parsed = runCatching { json.parseToJsonElement(content) }.getOrNull() ?: return
        if (parsed !is JsonObject) return
        val id = (parsed["id"] as? JsonPrimitive)?.content?.toIntOrNull()
        if (id != null) {
            val fut = pending.remove(id)
            val result = parsed["result"]
            if (fut != null && result != null) {
                fut.complete(result)
            } else if (fut != null) {
                fut.completeExceptionally(IllegalStateException("LSP error"))
            }
        }
    }

    private fun parseLocations(result: JsonElement): List<LspLocation> {
        val array = when (result) {
            is kotlinx.serialization.json.JsonArray -> result
            is JsonObject -> kotlinx.serialization.json.JsonArray(listOf(result))
            else -> return emptyList()
        }
        return array.mapNotNull { el ->
            val obj = el as? JsonObject ?: return@mapNotNull null
            val uri = (obj["uri"] as? JsonPrimitive)?.content ?: return@mapNotNull null
            val rangeObj = obj["range"] as? JsonObject ?: return@mapNotNull null
            val startObj = rangeObj["start"] as? JsonObject ?: return@mapNotNull null
            val endObj = rangeObj["end"] as? JsonObject ?: return@mapNotNull null
            val start = LspPosition(
                line = (startObj["line"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0,
                character = (startObj["character"] as? JsonPrimitive)?.content?.toIntOrNull() ?: 0
            )
            val end = LspPosition(
                line = (endObj["line"] as? JsonPrimitive)?.content?.toIntOrNull() ?: start.line,
                character = (endObj["character"] as? JsonPrimitive)?.content?.toIntOrNull() ?: start.character
            )
            LspLocation(uri, LspRange(start, end))
        }
    }
}
