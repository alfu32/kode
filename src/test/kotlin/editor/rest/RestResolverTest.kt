package editor.rest

import editor.rest.model.RestApiProjectState
import editor.rest.model.RestNodePath
import editor.rest.model.RestWorkspaceModel
import editor.rest.model.jsonObject
import editor.rest.model.withField
import editor.rest.resolve.RestRequestResolver
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

class RestResolverTest {
    @Test
    fun resolvesNestedVariablesPathVariablesAndInheritedBearerAuth() {
        val request = buildJsonObject {
            put("id", "request")
            put("name", "Get user")
            put("variable", buildJsonArray { add(variable("user", "42")) })
            put("request", buildJsonObject {
                put("method", "GET")
                put("url", buildJsonObject {
                    put("raw", "https://{{host}}/{{version}}/users/:user")
                    put("variable", buildJsonArray { add(variable("user", "{{user}}")) })
                    put("query", buildJsonArray { add(variable("page", "{{page}}")) })
                })
                put("header", buildJsonArray { add(variable("X-Env", "{{environment}}")) })
            })
        }
        val folder = buildJsonObject {
            put("name", "Test")
            put("variable", buildJsonArray { add(variable("host", "test.example.com")); add(variable("page", "2")) })
            put("item", buildJsonArray { add(request) })
        }
        val collection = buildJsonObject {
            put("info", buildJsonObject { put("name", "API"); put("schema", "https://schema.getpostman.com/json/collection/v2.1.0/collection.json") })
            put("variable", buildJsonArray { add(variable("host", "prod.example.com")); add(variable("version", "v1")); add(variable("environment", "prod")); add(variable("token", "secret")) })
            put("auth", buildJsonObject { put("type", "bearer"); put("bearer", buildJsonArray { add(variable("token", "{{token}}")) }) })
            put("item", buildJsonArray { add(folder) })
        }
        val path = RestNodePath(listOf(0, 0))
        val resolved = RestRequestResolver(collection, path, kotlin.io.path.createTempDirectory("rest-root")).materialize()
        assertEquals("https://test.example.com/v1/users/42?page=2", resolved.url)
        assertEquals("prod", resolved.headers.first { it.name == "X-Env" }.value)
        assertTrue(resolved.headers.any { it.name == "Authorization" && it.value == "Bearer secret" })
    }

    @Test
    fun rejectsCyclesAndUnsupportedAuthenticationBeforeNetworkActivity() {
        val request = buildJsonObject {
            put("name", "Cycle")
            put("variable", buildJsonArray { add(variable("a", "{{b}}")); add(variable("b", "{{a}}")) })
            put("request", buildJsonObject { put("method", "GET"); put("url", "https://example.test/{{a}}") })
        }
        val collection = buildJsonObject {
            put("info", buildJsonObject { put("name", "API") })
            put("item", buildJsonArray { add(request) })
        }
        assertFailsWith<IllegalArgumentException> {
            RestRequestResolver(collection, RestNodePath(listOf(0)), kotlin.io.path.createTempDirectory("rest-root")).materialize()
        }
    }

    @Test
    fun resolvesApiKeyIntoQueryAndRejectsDisabledVariables() {
        val request = buildJsonObject {
            put("name", "Search")
            put("request", buildJsonObject {
                put("method", "GET")
                put("url", buildJsonObject {
                    put("raw", "https://example.test/search")
                    put("query", buildJsonArray { add(variable("page", "1")) })
                })
            })
        }
        val collection = buildJsonObject {
            put("info", buildJsonObject { put("name", "API") })
            put("variable", buildJsonArray {
                add(variable("apiKey", "secret"))
                add(variable("disabled", "should-not-resolve", disabled = true))
            })
            put("auth", buildJsonObject {
                put("type", "apikey")
                put("apikey", buildJsonArray {
                    add(variable("key", "api_key"))
                    add(variable("value", "{{apiKey}}"))
                    add(variable("in", "query"))
                })
            })
            put("item", buildJsonArray { add(request) })
        }
        val resolved = RestRequestResolver(collection, RestNodePath(listOf(0)), kotlin.io.path.createTempDirectory("rest-root")).materialize()
        assertEquals("https://example.test/search?page=1&api_key=secret", resolved.url)
        assertTrue(resolved.headers.none { it.name.equals("api_key", true) })

        val disabledRequest = request.withField(
            "request",
            request.jsonObject("request")!!.withField(
                "url",
                buildJsonObject { put("raw", "https://example.test/{{disabled}}") }
            )
        )
        val invalid = collection.withField("item", buildJsonArray { add(disabledRequest) })
        assertFailsWith<IllegalArgumentException> {
            RestRequestResolver(invalid, RestNodePath(listOf(0)), kotlin.io.path.createTempDirectory("rest-root")).materialize()
        }
    }

    private fun variable(key: String, value: String, disabled: Boolean = false) = buildJsonObject {
        put("key", key)
        put("value", value)
        put("type", "string")
        if (disabled) put("disabled", true)
    }
}
