package editor.rest.http

import editor.rest.resolve.ResolvedHeader
import editor.rest.resolve.ResolvedRequest
import editor.rest.resolve.RestRequestResolver
import kotlinx.serialization.json.JsonObject
import editor.rest.model.RestNodePath
import java.io.InputStream
import java.io.BufferedReader
import java.io.ByteArrayOutputStream
import java.io.InputStreamReader
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.HttpCookie
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.security.SecureRandom
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.net.http.WebSocket
import java.util.concurrent.CompletionStage
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLParameters
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

data class RestResponseHeader(val name: String, val value: String)

data class RestResponse(
    val request: ResolvedRequest,
    val startedAt: Long,
    val durationMs: Long,
    val statusCode: Int? = null,
    val statusText: String = "",
    val headers: List<RestResponseHeader> = emptyList(),
    val cookies: List<String> = emptyList(),
    val bodyBytes: ByteArray = ByteArray(0),
    val receivedBytes: Long = bodyBytes.size.toLong(),
    val truncated: Boolean = false,
    val contentType: String? = null,
    val finalUrl: String = request.url,
    val error: String? = null
) {
    val isSuccess: Boolean get() = error == null && statusCode in 200..299
    val bodyText: String get() = bodyBytes.toString(StandardCharsets.UTF_8)
}

class RestExecutionHandle internal constructor(private val cancellation: () -> Unit) {
    private val cancelled = AtomicBoolean(false)

    fun cancel() {
        if (cancelled.compareAndSet(false, true)) cancellation()
    }

    fun isCancelled(): Boolean = cancelled.get()
}

class RestCookieJar {
    private val cookies = linkedMapOf<String, MutableList<HttpCookie>>()

    @Synchronized
    fun headerFor(uri: URI): String? {
        val hostCookies = cookies.entries
            .filter { (domain, _) -> uri.host.equals(domain, true) || uri.host.endsWith(".$domain", true) }
            .flatMap { it.value }
            .filter { cookie -> !cookie.hasExpired() }
            .filter { cookie -> !cookie.secure || uri.scheme.equals("https", true) }
            .filter { cookie -> cookie.path == null || uri.path.isNullOrBlank() || uri.path.startsWith(cookie.path) }
        return hostCookies.takeIf { it.isNotEmpty() }?.joinToString("; ") { "${it.name}=${it.value}" }
    }

    @Synchronized
    fun accept(uri: URI, setCookieHeaders: List<String>) {
        setCookieHeaders.forEach { header ->
            runCatching {
                val parsed = HttpCookie.parse(header).firstOrNull() ?: return@runCatching
                val domain = parsed.domain?.removePrefix(".") ?: uri.host
                if (parsed.path == null) parsed.path = defaultPath(uri.path)
                val list = cookies.getOrPut(domain) { mutableListOf() }
                list.removeAll { it.name == parsed.name && it.path == parsed.path }
                if (!parsed.hasExpired()) list += parsed
            }
        }
    }

    @Synchronized
    fun clear() = cookies.clear()

    private fun defaultPath(path: String): String {
        if (path.isBlank() || !path.startsWith("/")) return "/"
        val slash = path.lastIndexOf('/')
        return if (slash <= 0) "/" else path.substring(0, slash)
    }
}

class RestHttpExecutor(
    private val cookieJar: RestCookieJar = RestCookieJar(),
    private val responsePreviewLimit: Int = 10 * 1024 * 1024
) {
    fun executeAsync(
        request: ResolvedRequest,
        onComplete: (RestResponse) -> Unit,
        onStream: (String) -> Unit = {}
    ): RestExecutionHandle {
        if (request.url.startsWith("ws://", true) || request.url.startsWith("wss://", true)) {
            return executeWebSocket(request, onComplete, onStream)
        }
        if (request.headers.any { it.name.equals("Accept", true) && it.value.split(',').any { value -> value.trim().equals("text/event-stream", true) } }) {
            return executeSse(request, onComplete, onStream)
        }
        val started = System.currentTimeMillis()
        val uri = URI(request.url)
        val httpRequest = buildRequest(request, uri)
        val client = buildClient(request)
        val future = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
            .orTimeout(request.timeoutMs, TimeUnit.MILLISECONDS)
        val handle = RestExecutionHandle { future.cancel(true) }
        future.whenComplete { response, throwable ->
            val finished = System.currentTimeMillis()
            val result = if (throwable != null) {
                RestResponse(
                    request = request,
                    startedAt = started,
                    durationMs = finished - started,
                    error = networkMessage(throwable, handle.isCancelled())
                )
            } else {
                readResponse(request, started, finished, response)
            }
            onComplete(result)
        }
        return handle
    }

    private fun executeSse(
        request: ResolvedRequest,
        onComplete: (RestResponse) -> Unit,
        onStream: (String) -> Unit
    ): RestExecutionHandle {
        val started = System.currentTimeMillis()
        val uri = URI(request.url)
        val httpRequest = buildRequest(request, uri)
        val client = buildClient(request)
        val future = client.sendAsync(httpRequest, HttpResponse.BodyHandlers.ofInputStream())
        val stream = AtomicReference<InputStream?>()
        val readerThread = AtomicReference<Thread?>()
        lateinit var handle: RestExecutionHandle
        handle = RestExecutionHandle {
            future.cancel(true)
            stream.getAndSet(null)?.close()
            readerThread.get()?.interrupt()
        }
        future.whenComplete { response, throwable ->
            if (throwable != null) {
                onComplete(RestResponse(request, started, System.currentTimeMillis() - started, error = networkMessage(throwable, handle.isCancelled())))
                return@whenComplete
            }
            val input = response.body()
            stream.set(input)
            val thread = Thread({
                val captured = ByteArrayOutputStream()
                try {
                    BufferedReader(InputStreamReader(input, StandardCharsets.UTF_8)).useLines { lines ->
                        lines.forEach { line ->
                            if (handle.isCancelled()) return@forEach
                            val event = line + "\n"
                            if (captured.size() < responsePreviewLimit) {
                                val bytes = event.toByteArray(StandardCharsets.UTF_8)
                                captured.write(bytes, 0, minOf(bytes.size, responsePreviewLimit - captured.size()))
                            }
                            onStream(event)
                        }
                    }
                    onComplete(streamResponse(request, started, response.statusCode(), response.headers().map(), response.uri(), captured.toByteArray(), handle.isCancelled()))
                } catch (error: Throwable) {
                    if (!handle.isCancelled()) onComplete(RestResponse(request, started, System.currentTimeMillis() - started, error = networkMessage(error, false)))
                    else onComplete(RestResponse(request, started, System.currentTimeMillis() - started, error = "Request cancelled"))
                } finally {
                    stream.set(null)
                }
            }, "rest-sse-reader")
            readerThread.set(thread)
            thread.isDaemon = true
            thread.start()
        }
        return handle
    }

    private fun executeWebSocket(
        request: ResolvedRequest,
        onComplete: (RestResponse) -> Unit,
        onStream: (String) -> Unit
    ): RestExecutionHandle {
        val started = System.currentTimeMillis()
        val client = buildClient(request)
        val captured = ByteArrayOutputStream()
        val completed = AtomicBoolean(false)
        val socket = AtomicReference<WebSocket?>()
        fun finish(error: String? = null) {
            if (!completed.compareAndSet(false, true)) return
            onComplete(RestResponse(
                request = request,
                startedAt = started,
                durationMs = System.currentTimeMillis() - started,
                statusCode = if (error == null) 101 else null,
                statusText = if (error == null) "Switching Protocols" else "",
                bodyBytes = captured.toByteArray(),
                receivedBytes = captured.size().toLong(),
                contentType = "text/plain",
                error = error
            ))
        }
        val listener = object : WebSocket.Listener {
            override fun onOpen(webSocket: WebSocket) {
                socket.set(webSocket)
                onStream("[websocket connected]\n")
                webSocket.request(1)
            }

            override fun onText(webSocket: WebSocket, data: CharSequence, last: Boolean): CompletionStage<*>? {
                val text = data.toString()
                val bytes = text.toByteArray(StandardCharsets.UTF_8)
                if (captured.size() < responsePreviewLimit) captured.write(bytes, 0, minOf(bytes.size, responsePreviewLimit - captured.size()))
                onStream(text + if (last) "\n" else "")
                webSocket.request(1)
                return null
            }

            override fun onBinary(webSocket: WebSocket, data: java.nio.ByteBuffer, last: Boolean): CompletionStage<*>? {
                val bytes = ByteArray(data.remaining())
                data.get(bytes)
                if (captured.size() < responsePreviewLimit) captured.write(bytes, 0, minOf(bytes.size, responsePreviewLimit - captured.size()))
                onStream(bytes.toString(StandardCharsets.UTF_8) + if (last) "\n" else "")
                webSocket.request(1)
                return null
            }

            override fun onClose(webSocket: WebSocket, statusCode: Int, reason: String): CompletionStage<*>? {
                finish()
                return null
            }

            override fun onError(webSocket: WebSocket, error: Throwable) {
                finish(networkMessage(error, false))
            }
        }
        val webSocketBuilder = client.newWebSocketBuilder()
            .connectTimeout(Duration.ofMillis(request.timeoutMs.coerceAtLeast(1)))
        request.headers.forEach { header ->
            when (header.name.trim().lowercase()) {
                "host", "connection", "content-length", "expect", "upgrade", "sec-websocket-key",
                "sec-websocket-version", "sec-websocket-extensions", "transfer-encoding" -> Unit
                else -> webSocketBuilder.header(header.name, header.value)
            }
        }
        val future = webSocketBuilder.buildAsync(URI(request.url), listener)
        val handle = RestExecutionHandle {
            socket.getAndSet(null)?.abort()
            future.cancel(true)
            finish("Request cancelled")
        }
        future.whenComplete { connected, throwable ->
            if (throwable != null && !handle.isCancelled()) finish(networkMessage(throwable, false))
            else if (connected != null) socket.compareAndSet(null, connected)
        }
        return handle
    }

    fun clearCookies() = cookieJar.clear()

    private fun buildRequest(request: ResolvedRequest, uri: URI): HttpRequest {
        val builder = HttpRequest.newBuilder(uri)
            .timeout(Duration.ofMillis(request.timeoutMs.coerceAtLeast(1)))
        val body = request.body
        if (body == null || request.method.equals("GET", true) && body.isEmpty()) {
            builder.method(request.method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.method(request.method, HttpRequest.BodyPublishers.ofByteArray(body))
        }
        if (request.headers.any { header ->
                header.name.equals("Expect", true) &&
                    header.value.split(',').any { it.trim().equals("100-continue", true) }
            }) {
            builder.expectContinue(true)
        }
        request.headers.forEach { header ->
            when (header.name.trim().lowercase()) {
                // The body publisher owns the correct byte count.
                "content-length" -> Unit
                // java.net.http derives Host/:authority from the URI. It has no
                // safe per-request Host override API; the global restricted-
                // header switch is intentionally not enabled by the editor.
                "host" -> Unit
                // Upgrade requires the WebSocket API (or a lower-level client),
                // so it cannot be expressed by HttpRequest.Builder.
                "upgrade" -> Unit
                // Connection and Expect are applied through the request/client
                // builder APIs below, because header() rejects both headers.
                "connection", "expect", "keep-alive", "proxy-connection",
                "te", "trailer", "transfer-encoding" -> Unit
                else -> builder.header(header.name, header.value)
            }
        }
        if (request.headers.none { it.name.equals("Cookie", true) }) {
            cookieJar.headerFor(uri)?.let { builder.header("Cookie", it) }
        }
        return builder.build()
    }

    private fun buildClient(request: ResolvedRequest): HttpClient {
        val builder = HttpClient.newBuilder()
            .connectTimeout(Duration.ofMillis(request.timeoutMs.coerceAtLeast(1)))
            .followRedirects(if (request.followRedirects) HttpClient.Redirect.NORMAL else HttpClient.Redirect.NEVER)
        val connectionHeader = request.headers.firstOrNull { it.name.equals("Connection", true) }
        if (connectionHeader != null) {
            // Connection is an HTTP/1.1 hop-by-hop header. HTTP/2 forbids it,
            // while HTTP/1.1 keep-alive is the client's normal behavior.
            builder.version(HttpClient.Version.HTTP_1_1)
        }
        if (!request.sslValidation) {
            val trustAll = arrayOf<TrustManager>(object : X509TrustManager {
                override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = emptyArray()
                override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
                override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) = Unit
            })
            val context = SSLContext.getInstance("TLS")
            context.init(null, trustAll, SecureRandom())
            val parameters = SSLParameters()
            parameters.endpointIdentificationAlgorithm = ""
            builder.sslContext(context).sslParameters(parameters)
        }
        return builder.build()
    }

    private fun readResponse(
        request: ResolvedRequest,
        started: Long,
        finished: Long,
        response: HttpResponse<InputStream>
    ): RestResponse {
        val headers = response.headers().map().flatMap { (name, values) -> values.map { value -> RestResponseHeader(name, value) } }
        val setCookies = headers.filter { it.name.equals("Set-Cookie", true) }.map { it.value }
        cookieJar.accept(response.uri(), setCookies)
        val body = response.body()
        val captured = readLimited(body)
        return RestResponse(
            request = request,
            startedAt = started,
            durationMs = finished - started,
            statusCode = response.statusCode(),
            statusText = statusText(response.statusCode()),
            headers = headers,
            cookies = setCookies,
            bodyBytes = captured.bytes,
            receivedBytes = captured.receivedBytes,
            truncated = captured.truncated,
            contentType = headers.firstOrNull { it.name.equals("Content-Type", true) }?.value,
            finalUrl = response.uri().toString()
        )
    }

    private fun streamResponse(
        request: ResolvedRequest,
        started: Long,
        statusCode: Int,
        responseHeaders: Map<String, List<String>>,
        uri: URI,
        body: ByteArray,
        cancelled: Boolean
    ): RestResponse {
        val headers = responseHeaders.flatMap { (name, values) -> values.map { value -> RestResponseHeader(name, value) } }
        val setCookies = headers.filter { it.name.equals("Set-Cookie", true) }.map { it.value }
        cookieJar.accept(uri, setCookies)
        return RestResponse(
            request = request,
            startedAt = started,
            durationMs = System.currentTimeMillis() - started,
            statusCode = statusCode,
            statusText = statusText(statusCode),
            headers = headers,
            cookies = setCookies,
            bodyBytes = body,
            receivedBytes = body.size.toLong(),
            truncated = body.size >= responsePreviewLimit,
            contentType = headers.firstOrNull { it.name.equals("Content-Type", true) }?.value,
            finalUrl = uri.toString(),
            error = if (cancelled) "Request cancelled" else null
        )
    }

    private fun readLimited(input: InputStream): CapturedBody {
        input.use { stream ->
            val output = java.io.ByteArrayOutputStream(minOf(responsePreviewLimit, 64 * 1024))
            val buffer = ByteArray(16 * 1024)
            var received = 0L
            var read: Int
            while (stream.read(buffer).also { read = it } >= 0) {
                if (read == 0) continue
                received += read
                if (output.size() < responsePreviewLimit) {
                    val remaining = responsePreviewLimit - output.size()
                    output.write(buffer, 0, minOf(read, remaining))
                }
            }
            return CapturedBody(output.toByteArray(), received, received > responsePreviewLimit)
        }
    }

    private fun networkMessage(error: Throwable, cancelled: Boolean): String {
        if (cancelled || error is java.util.concurrent.CancellationException) return "Request cancelled"
        val cause = generateSequence(error) { it.cause }.lastOrNull() ?: error
        return when (cause) {
            is java.net.http.HttpTimeoutException -> "Request timeout: ${cause.message.orEmpty()}".trimEnd()
            is java.net.ConnectException -> "Connection refused: ${cause.message.orEmpty()}".trimEnd()
            else -> cause.message ?: cause::class.simpleName ?: "Network request failed"
        }
    }

    private fun statusText(code: Int): String = STATUS_TEXT[code].orEmpty()

    private data class CapturedBody(val bytes: ByteArray, val receivedBytes: Long, val truncated: Boolean)

    companion object {
        private val STATUS_TEXT = mapOf(
            200 to "OK", 201 to "Created", 202 to "Accepted", 204 to "No Content",
            301 to "Moved Permanently", 302 to "Found", 304 to "Not Modified",
            400 to "Bad Request", 401 to "Unauthorized", 403 to "Forbidden", 404 to "Not Found",
            405 to "Method Not Allowed", 408 to "Request Timeout", 409 to "Conflict",
            429 to "Too Many Requests", 500 to "Internal Server Error", 502 to "Bad Gateway",
            503 to "Service Unavailable", 504 to "Gateway Timeout"
        )
    }
}

class RestApiRuntime(private val workspaceRoot: java.nio.file.Path) {
    private val executor = RestHttpExecutor()

    fun executeAsync(
        collection: JsonObject,
        path: RestNodePath,
        onComplete: (RestResponse) -> Unit,
        environmentVariables: Map<String, String> = emptyMap(),
        onStream: (String) -> Unit = {}
    ): RestExecutionHandle {
        val requestResult = runCatching { RestRequestResolver(collection, path, workspaceRoot, environmentVariables).materialize() }
        val request = requestResult.getOrNull()
        if (request == null) {
            val cancelled = AtomicBoolean(false)
            val handle = RestExecutionHandle { cancelled.set(true) }
            CompletableFuture.runAsync {
                if (!cancelled.get()) {
                    val fallback = ResolvedRequest(path, "GET", "", emptyList())
                    onComplete(RestResponse(fallback, System.currentTimeMillis(), 0, error = requestResult.exceptionOrNull()?.message ?: "Request validation failed"))
                }
            }
            return handle
        }
        return executor.executeAsync(request, onComplete, onStream)
    }

    fun clearCookies() = executor.clearCookies()
}
