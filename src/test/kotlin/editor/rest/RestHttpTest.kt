package editor.rest

import com.sun.net.httpserver.HttpServer
import editor.rest.http.RestHttpExecutor
import editor.rest.resolve.ResolvedRequest
import editor.rest.model.RestNodePath
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RestHttpTest {
    @Test
    fun executesRequestsCapturesDuplicateHeadersAndCarriesCookies() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/set") { exchange ->
            exchange.responseHeaders.add("Set-Cookie", "session=abc; Path=/")
            exchange.responseHeaders.add("X-Duplicate", "one")
            exchange.responseHeaders.add("X-Duplicate", "two")
            val body = "{\"ok\":true}".toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.createContext("/echo") { exchange ->
            val body = (exchange.requestHeaders.getFirst("Cookie") ?: "missing").toByteArray(StandardCharsets.UTF_8)
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val base = "http://127.0.0.1:${server.address.port}"
            val executor = RestHttpExecutor(responsePreviewLimit = 1024)
            val first = execute(executor, ResolvedRequest(RestNodePath(), "GET", "$base/set", emptyList()))
            assertEquals(200, first.statusCode)
            assertEquals(2, first.headers.count { it.name.equals("X-duplicate", true) })
            val second = execute(executor, ResolvedRequest(RestNodePath(), "GET", "$base/echo", emptyList()))
            assertEquals("session=abc", second.bodyText)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun reportsResponsePreviewTruncation() {
        val server = HttpServer.create(InetSocketAddress(0), 0)
        server.createContext("/") { exchange ->
            val body = "0123456789".repeat(100).toByteArray()
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val request = ResolvedRequest(RestNodePath(), "GET", "http://127.0.0.1:${server.address.port}/", emptyList())
            val result = execute(RestHttpExecutor(responsePreviewLimit = 32), request)
            assertTrue(result.truncated)
            assertEquals(1000, result.receivedBytes)
            assertEquals(32, result.bodyBytes.size)
        } finally {
            server.stop(0)
        }
    }

    private fun execute(executor: RestHttpExecutor, request: ResolvedRequest) =
        kotlin.run {
            val latch = CountDownLatch(1)
            var result: editor.rest.http.RestResponse? = null
            executor.executeAsync(request) {
                result = it
                latch.countDown()
            }
            assertTrue(latch.await(10, TimeUnit.SECONDS))
            result ?: error("No HTTP result")
        }
}
