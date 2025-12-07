package editor.grammars

import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class RegexGrammarDefinition(
    val language: String,
    val extensions: List<String> = emptyList(),
    val tokens: Map<String, String> = emptyMap()
)

@Serializable
data class RegexGrammarIndex(val languages: List<String> = emptyList())

object RegexGrammarLoader {
    private val json = Json { ignoreUnknownKeys = true }

    fun loadFromResources(resourceRoot: String = "/regex-grammars"): List<RegexGrammarDefinition> {
        val indexStream = RegexGrammarLoader::class.java.getResourceAsStream("$resourceRoot/index.json")
            ?: return emptyList()
        val indexJson = indexStream.use { it.readBytes().toString(Charsets.UTF_8) }
        val index = json.decodeFromString<RegexGrammarIndex>(indexJson)
        return index.languages.mapNotNull { lang ->
            RegexGrammarLoader::class.java.getResourceAsStream("$resourceRoot/$lang.json")?.use { stream ->
                val content = stream.readBytes().toString(Charsets.UTF_8)
                runCatching { json.decodeFromString<RegexGrammarDefinition>(content) }.getOrNull()
            }
        }
    }

    fun loadFromDirectory(dir: Path): List<RegexGrammarDefinition> {
        if (!Files.isDirectory(dir)) return emptyList()
        val root = dir.toAbsolutePath()
        val indexFile = root.resolve("index.json")
        val index = if (Files.exists(indexFile)) {
            val text = Files.readString(indexFile)
            runCatching { json.decodeFromString<RegexGrammarIndex>(text) }.getOrNull() ?: RegexGrammarIndex()
        } else {
            RegexGrammarIndex()
        }
        val languages = if (index.languages.isNotEmpty()) {
            index.languages
        } else {
            Files.list(root).use { paths ->
                paths.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".json") }
                    .map { it.fileName.toString().removeSuffix(".json") }
                    .toList()
            }
        }
        return languages.mapNotNull { lang ->
            val file = root.resolve("$lang.json")
            if (!Files.exists(file)) return@mapNotNull null
            val text = Files.readString(file)
            runCatching { json.decodeFromString<RegexGrammarDefinition>(text) }.getOrNull()
        }
    }
}
