package editor.rest

import editor.rest.model.RestApiProjectState
import editor.rest.model.RestEnvironmentCodec
import editor.rest.model.RestNodePath
import editor.rest.model.RestWorkspaceModel
import editor.rest.model.defaultCollection
import editor.rest.model.newRestFolder
import editor.rest.model.newRestRequest
import editor.rest.model.jsonObject
import editor.rest.model.string
import editor.rest.model.items
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

class RestModelTest {
    @Test
    fun storesEscapedEnvironmentValuesAndManagesActiveEnvironment() {
        val parsed = RestEnvironmentCodec.parse("base\\=url=https://example.test\\\\api\nname=dev")
        assertEquals("base=url", parsed[0].key)
        assertEquals("https://example.test\\api", parsed[0].value)
        assertEquals("base\\=url=https://example.test\\\\api\nname=dev", RestEnvironmentCodec.format(parsed))

        val model = RestWorkspaceModel()
        val environment = model.createEnvironment("dev")
        assertEquals(environment.id, model.activeEnvironmentId)
        assertTrue(model.renameEnvironment(environment.id, "development"))
        val copy = model.duplicateEnvironment(environment.id)!!
        assertTrue(model.activateEnvironment(copy.id))
        assertTrue(model.removeEnvironment(environment.id))
        assertEquals(copy.id, model.activeEnvironmentId)
    }

    @Test
    fun createsRenamesDuplicatesAndMovesPostmanItems() {
        val model = RestWorkspaceModel()
        val folder = model.addFolder(RestNodePath(), "v1")!!
        val request = model.addRequest(folder, "List")!!
        model.rename(request, "List Models")
        val duplicate = model.duplicate(request)!!
        model.move(duplicate, RestNodePath())

        assertEquals("List Models", model.node(request)?.string("name"))
        assertEquals("List Models", model.node(RestNodePath(listOf(1)))?.string("name"))
        assertTrue(model.collection.items().size == 2)
        assertTrue(model.collection.items()[1] is JsonObject)
    }

    @Test
    fun preservesUnknownPostmanFieldsAcrossImportAndExport() {
        val source = """
            {
              "info": {"name":"Imported","schema":"https://schema.getpostman.com/json/collection/v2.1.0/collection.json","x-info":{"keep":true}},
              "variable": [{"key":"host","value":"example.test"}],
              "item": [{
                "name":"Folder",
                "event":[{"listen":"test","script":{"exec":["pm.test('kept')"]}}],
                "item":[{"id":"abc","name":"Call","request":{"method":"GET","url":{"raw":"https://example.test"}},"response":[{"name":"saved"}],"x-item":"keep"}]
              }]
            }
        """.trimIndent()
        val sourceFile = kotlin.io.path.createTempFile("rest-import", ".json")
        val exportFile = kotlin.io.path.createTempFile("rest-export", ".json")
        sourceFile.toFile().writeText(source)
        val model = RestWorkspaceModel()
        assertTrue(model.importCollection(sourceFile).isSuccess)
        assertTrue(model.exportCollection(exportFile).isSuccess)
        val exported = Json.parseToJsonElement(exportFile.toFile().readText()) as JsonObject
        assertEquals("Imported", exported["info"]?.let { (it as JsonObject).string("name") })
        assertEquals("keep", exported["item"]?.toString()?.let { if (it.contains("x-item")) "keep" else null })
        assertTrue(exported.toString().contains("pm.test"))
        sourceFile.toFile().delete()
        exportFile.toFile().delete()
    }

    @Test
    fun importsOpenApiJsonIntoTheRequestedFolder() {
        val sourceFile = kotlin.io.path.createTempFile("openapi", ".json")
        sourceFile.toFile().writeText(
            """
            {
              "openapi":"3.0.0",
              "info":{"title":"Pets"},
              "servers":[{"url":"https://api.example.test"}],
              "paths":{
                "/pets/{id}":{
                  "get":{"tags":["Pets"],"summary":"Get pet","parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}]}
                }
              }
            }
            """.trimIndent()
        )
        val model = RestWorkspaceModel()
        val folder = model.addFolder(RestNodePath(), "Imported")!!
        assertEquals(1, model.importOpenApi(sourceFile, folder).getOrThrow())
        val importedFolder = model.node(folder.child(0))!!
        val request = importedFolder.items().first() as JsonObject
        assertEquals("GET", request.jsonObject("request")?.string("method"))
        assertTrue(request.jsonObject("request")?.jsonObject("url")?.string("raw")?.contains(":id") == true)
        sourceFile.toFile().delete()
    }

    @Test
    fun importsOpenApiYaml() {
        val sourceFile = kotlin.io.path.createTempFile("openapi", ".yaml")
        sourceFile.toFile().writeText(
            """
            openapi: 3.0.0
            info:
              title: YAML API
            paths:
              /health:
                get:
                  summary: Health
            """.trimIndent()
        )
        val model = RestWorkspaceModel()
        assertEquals(1, model.importOpenApi(sourceFile).getOrThrow())
        assertTrue(model.collection.items().isNotEmpty())
        sourceFile.toFile().delete()
    }
}
