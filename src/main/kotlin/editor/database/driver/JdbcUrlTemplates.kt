package editor.database.driver

import editor.database.config.KodeDatabasePaths
import editor.database.model.DataSourceDefinition
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

@Serializable
data class JdbcUrlTemplate(
    val id: String,
    val label: String,
    val template: String,
    val description: String? = null,
    val advanced: Boolean = false,
    val legacy: Boolean = false,
    val minDriverVersion: String? = null,
    val note: String? = null
)

@Serializable
data class JdbcUrlDriverTemplates(
    val driverId: String,
    val recentFamily: String? = null,
    val defaultPort: Int? = null,
    val inherit: String? = null,
    val prepend: Boolean = false,
    val templates: List<JdbcUrlTemplate> = emptyList(),
    val note: String? = null
)

@Serializable
data class JdbcUrlTemplateCatalog(
    val version: Int = 1,
    val drivers: List<JdbcUrlDriverTemplates> = emptyList()
)

data class JdbcUrlSuggestions(
    val recents: List<String>,
    val standard: List<JdbcUrlTemplate>
)

/** Loads the immutable, resource-backed JDBC URL morphology catalog. */
class JdbcUrlTemplateRepository(
    private val resourceName: String = "database-jdbc-url-templates.json",
    private val registry: JdbcDriverRegistry = JdbcDriverRegistry()
) {
    private val json = Json { ignoreUnknownKeys = true }
    val catalog: JdbcUrlTemplateCatalog = loadCatalog()
    private val entries = catalog.drivers.associateBy { it.driverId }
    private val effectiveCache = mutableMapOf<String, List<JdbcUrlTemplate>>()

    init {
        validate()
    }

    fun entry(driverId: String): JdbcUrlDriverTemplates? = entries[driverId]

    fun recentFamily(driverId: String): String {
        val driver = registry.byId(driverId)
        val entry = entries[driverId]
        return entry?.recentFamily
            ?: entry?.inherit?.let(::recentFamily)
            ?: driver?.aliasOf?.let(::recentFamily)
            ?: driverId
    }

    fun templates(driverId: String): List<JdbcUrlTemplate> = effectiveCache.getOrPut(driverId) {
        val entry = entries[driverId] ?: return@getOrPut emptyList()
        val inherited = entry.inherit?.let(::templates).orEmpty()
        val own = entry.templates + registry.byId(driverId)?.jdbcUrlTemplates.orEmpty()
        if (entry.prepend) own + inherited else inherited + own
    }

    fun validate() {
        val builtInIds = registry.definitions.map { it.id }.toSet()
        val resourceIds = entries.keys
        check(resourceIds.containsAll(builtInIds)) {
            "JDBC URL catalog is missing driver entries: ${builtInIds - resourceIds}"
        }
        check((resourceIds - builtInIds).isEmpty()) {
            "JDBC URL catalog contains unknown driver entries: ${resourceIds - builtInIds}"
        }
        entries.values.forEach { entry ->
            resolveInheritance(entry.driverId, mutableSetOf())
            val ids = mutableSetOf<String>()
            templates(entry.driverId).forEach { template ->
                check(ids.add(template.id)) { "Duplicate JDBC URL template '${template.id}' for ${entry.driverId}" }
                check(template.template.isNotBlank() && template.template.startsWith("jdbc:")) {
                    "Invalid JDBC URL template '${template.id}' for ${entry.driverId}"
                }
                val lower = template.template.lowercase()
                check(listOf("{password}", "{token}", "{secret}", "{accesskey}", "{apikey}").none(lower::contains)) {
                    "Sensitive placeholder in JDBC URL template '${template.id}'"
                }
                check(listOf(
                    "trustservercertificate=true", "sslverification=none", "nocertcheck",
                    "tlsallowinvalidhostnames=true", "validateservercertificate=0"
                ).none(lower::contains)) {
                    "TLS bypass in JDBC URL template '${template.id}'"
                }
            }
        }
    }

    private fun resolveInheritance(driverId: String, visiting: MutableSet<String>): JdbcUrlDriverTemplates {
        val entry = entries[driverId] ?: error("Unknown JDBC URL catalog driver: $driverId")
        check(visiting.add(driverId)) { "JDBC URL catalog inheritance cycle at $driverId" }
        entry.inherit?.let { resolveInheritance(it, visiting) }
        visiting.remove(driverId)
        return entry
    }

    private fun loadCatalog(): JdbcUrlTemplateCatalog {
        val stream = JdbcUrlTemplateRepository::class.java.classLoader.getResourceAsStream(resourceName)
            ?: error("Missing JDBC URL template resource: $resourceName")
        return stream.use { json.decodeFromString<JdbcUrlTemplateCatalog>(it.bufferedReader().readText()) }
    }
}

/** Local MRU store for safe JDBC URLs. It never lives in a project workspace. */
class JdbcUrlRecentRepository(
    private val root: Path = KodeDatabasePaths.databaseConfigDir()
) {
    private val file = root.resolve("jdbc-url-recents.json")
    private val json = Json { prettyPrint = true; encodeDefaults = true; ignoreUnknownKeys = true }

    @Serializable
    private data class Store(val version: Int = 1, val families: Map<String, List<String>> = emptyMap())

    fun load(family: String): List<String> = read().families[family].orEmpty().take(12)

    fun remember(family: String, rawUrl: String): Boolean {
        val url = rawUrl.trim()
        if (url.isEmpty() || containsSecret(url)) return false
        val next = read().families.toMutableMap()
        next[family] = listOf(url) + next[family].orEmpty().filter { it != url }.take(11)
        Files.createDirectories(root)
        Files.writeString(file, json.encodeToString(Store(families = next)))
        return true
    }

    fun seed(family: String, urls: Iterable<String>) {
        urls.toList().asReversed().forEach { remember(family, it) }
    }

    private fun read(): Store = if (!Files.isRegularFile(file)) Store() else runCatching {
        json.decodeFromString<Store>(Files.readString(file))
    }.getOrDefault(Store())

    companion object {
        private val sensitiveName = Regex(
            "(?i)(?:^|[?;&])\\s*(?:password|passwd|pwd|pass|secret|clientsecret|oauthclientsecret|token|accesstoken|oauthaccesstoken|oauthrefreshtoken|apikey|api_key|privatekey|secretaccesskey|accesskeyid|sessiontoken|credentials)\\s*="
        )
        private val userInfo = Regex("(?i)://[^/@\\s]+:[^/@\\s]+@")

        fun containsSecret(url: String): Boolean = sensitiveName.containsMatchIn(url) || userInfo.containsMatchIn(url)
    }
}

class JdbcUrlSuggestionService(
    private val registry: JdbcDriverRegistry,
    private val templates: JdbcUrlTemplateRepository = JdbcUrlTemplateRepository(registry = registry),
    private val recents: JdbcUrlRecentRepository = JdbcUrlRecentRepository()
) {
    fun forDriver(driverId: String): JdbcUrlSuggestions = JdbcUrlSuggestions(
        recents = recents.load(templates.recentFamily(driverId)),
        standard = templates.templates(driverId)
    )

    fun remember(definition: DataSourceDefinition): Boolean {
        val family = templates.recentFamily(definition.driver.id)
        return recents.remember(family, definition.jdbcUrl)
    }

    fun remember(driverId: String, url: String): Boolean =
        recents.remember(templates.recentFamily(driverId), url)

    fun seed(definitions: Iterable<DataSourceDefinition>) {
        definitions.forEach { remember(it) }
    }

    fun recentRepository(): JdbcUrlRecentRepository = recents
}
