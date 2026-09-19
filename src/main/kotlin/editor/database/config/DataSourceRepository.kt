package editor.database.config

import editor.database.model.DataSourceDefinition
import editor.database.model.DataSourceId
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

class DataSourceRepository(
    private val root: Path = KodeDatabasePaths.databaseConfigDir()
) {
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val file = root.resolve("datasources.json")

    fun load(): List<DataSourceDefinition> {
        if (!Files.isRegularFile(file)) return emptyList()
        return runCatching {
            json.decodeFromString<DataSourceStore>(Files.readString(file)).dataSources
        }.getOrDefault(emptyList())
    }

    fun save(dataSources: List<DataSourceDefinition>) {
        Files.createDirectories(root)
        Files.writeString(file, json.encodeToString(DataSourceStore(dataSources = dataSources.sortedBy { it.name.lowercase() })))
    }

    fun upsert(definition: DataSourceDefinition): List<DataSourceDefinition> {
        val next = load().filterNot { it.id == definition.id } + definition
        save(next)
        return next
    }

    fun remove(id: DataSourceId): List<DataSourceDefinition> {
        val next = load().filterNot { it.id == id }
        save(next)
        return next
    }

    @Serializable
    private data class DataSourceStore(
        val version: Int = 1,
        val dataSources: List<DataSourceDefinition> = emptyList()
    )
}
