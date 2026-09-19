package editor.rest.http

import editor.rest.resolve.ResolvedHeader
import editor.rest.resolve.ResolvedRequest
import editor.rest.resolve.RestRequestResolver
import kotlinx.serialization.json.JsonObject
import editor.rest.model.RestNodePath
import java.io.InputStream
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
        onComplete: (RestResponse) -> Unit
    ): RestExecutionHandle {
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
        request.headers.forEach { header ->
            // Java's HttpClient owns transport-level headers. Passing these
            // through builder.header() either changes the wire semantics or
            // throws (notably for Connection and Host). The request model can
            // still retain them for editing and export, but execution must let
            // the client manage them.
            if (!isClientManagedHeader(header.name)) {
                builder.header(header.name, header.value)
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

    private fun isClientManagedHeader(name: String): Boolean =
        name.trim().lowercase() in CLIENT_MANAGED_HEADERS

    private data class CapturedBody(val bytes: ByteArray, val receivedBytes: Long, val truncated: Boolean)

    companion object {
        private val CLIENT_MANAGED_HEADERS = setOf(
            "connection",
            "content-length",
            "expect",
            "host",
            "keep-alive",
            "proxy-connection",
            "te",
            "trailer",
            "transfer-encoding",
            "upgrade"
        )

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
        onComplete: (RestResponse) -> Unit
    ): RestExecutionHandle {
        val requestResult = runCatching { RestRequestResolver(collection, path, workspaceRoot).materialize() }
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
        return executor.executeAsync(request, onComplete)
    }

    fun clearCookies() = executor.clearCookies()
}
