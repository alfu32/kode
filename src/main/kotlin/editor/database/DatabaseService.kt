package editor.database

import editor.database.config.CredentialStore
import editor.database.config.DataSourceRepository
import editor.database.connection.DatabaseConnectionManager
import editor.database.driver.JdbcDriverDownloader
import editor.database.driver.JdbcDriverRegistry
import editor.database.driver.JdbcDriverResolver
import editor.database.driver.JdbcDownloadProgress
import editor.database.metadata.DatabaseMetadataService
import editor.database.model.ConnectionStatus
import editor.database.model.ConnectionTestResult
import editor.database.model.DataSourceDefinition
import editor.database.model.DataSourceId
import editor.database.model.MetadataRequest
import editor.database.model.MetadataResult
import editor.database.query.SqlExecutionRequest
import editor.database.query.SqlExecutionResult
import editor.database.query.SqlExecutionService
import java.nio.file.Path

interface DatabaseService {
    fun dataSources(): List<DataSourceDefinition>
    fun saveDataSource(definition: DataSourceDefinition, password: String? = null): DataSourceDefinition
    fun removeDataSource(id: DataSourceId)
    fun connect(id: DataSourceId): ConnectionStatus
    fun disconnect(id: DataSourceId): ConnectionStatus
    fun status(id: DataSourceId): ConnectionStatus
    fun testConnection(definition: DataSourceDefinition, password: String? = null): ConnectionTestResult
    fun introspect(id: DataSourceId, request: MetadataRequest, refresh: Boolean = false): MetadataResult
    fun refreshAll(id: DataSourceId)
    fun execute(request: SqlExecutionRequest): SqlExecutionResult
    fun cancelExecution(): Boolean
    fun commit(id: DataSourceId)
    fun rollback(id: DataSourceId)
    fun setAutoCommit(id: DataSourceId, value: Boolean)
    fun autoCommit(id: DataSourceId): Boolean?
    fun installLocalDriver(definition: DataSourceDefinition, jar: Path): Path
    fun downloadDriver(
        definition: DataSourceDefinition,
        progress: (JdbcDownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Path
    fun replaceDataSources(definitions: List<DataSourceDefinition>)
}

class JdbcDatabaseService(
    private val repository: DataSourceRepository = DataSourceRepository(),
    private val credentialStore: CredentialStore = CredentialStore(),
    private val connections: DatabaseConnectionManager = DatabaseConnectionManager(credentialStore),
    private val metadata: DatabaseMetadataService = DatabaseMetadataService(connections),
    private val executor: SqlExecutionService = SqlExecutionService(connections),
    private val driverResolver: JdbcDriverResolver = JdbcDriverResolver(),
    private val driverDownloader: JdbcDriverDownloader = JdbcDriverDownloader(driverResolver),
    val driverRegistry: JdbcDriverRegistry = JdbcDriverRegistry()
) : DatabaseService, AutoCloseable {
    @Volatile
    private var cachedDataSources: List<DataSourceDefinition> = repository.load()

    override fun dataSources(): List<DataSourceDefinition> = cachedDataSources

    override fun replaceDataSources(definitions: List<DataSourceDefinition>) {
        cachedDataSources = definitions
    }

    override fun saveDataSource(definition: DataSourceDefinition, password: String?): DataSourceDefinition {
        if (cachedDataSources.any { it.id == definition.id }) {
            disconnect(definition.id)
        }
        if (!password.isNullOrEmpty()) {
            val ref = definition.credentialReference ?: "${definition.id}.password"
            credentialStore.put(ref, password)
            val withRef = definition.copy(credentialReference = ref)
            cachedDataSources = cachedDataSources.filterNot { it.id == withRef.id } + withRef
            repository.save(cachedDataSources)
            return withRef
        }
        cachedDataSources = cachedDataSources.filterNot { it.id == definition.id } + definition
        repository.save(cachedDataSources)
        return definition
    }

    override fun removeDataSource(id: DataSourceId) {
        val current = cachedDataSources.firstOrNull { it.id == id }
        disconnect(id)
        credentialStore.remove(current?.credentialReference)
        cachedDataSources = cachedDataSources.filterNot { it.id == id }
        repository.save(cachedDataSources)
    }

    override fun connect(id: DataSourceId): ConnectionStatus {
        val definition = cachedDataSources.firstOrNull { it.id == id }
            ?: return ConnectionStatus(id, editor.database.model.ConnectionState.ERROR, "Unknown data source: $id")
        return connections.connect(definition)
    }

    override fun disconnect(id: DataSourceId): ConnectionStatus =
        connections.disconnect(id).also { metadata.refreshAll(id) }

    override fun status(id: DataSourceId): ConnectionStatus =
        connections.status(id)

    override fun testConnection(definition: DataSourceDefinition, password: String?): ConnectionTestResult =
        connections.testConnection(definition, password)

    override fun introspect(id: DataSourceId, request: MetadataRequest, refresh: Boolean): MetadataResult =
        metadata.introspect(id, request, refresh)

    override fun refreshAll(id: DataSourceId) {
        metadata.refreshAll(id)
    }

    override fun execute(request: SqlExecutionRequest): SqlExecutionResult =
        executor.execute(request)

    override fun cancelExecution(): Boolean = executor.cancel()

    override fun commit(id: DataSourceId) {
        connections.commit(id)
    }

    override fun rollback(id: DataSourceId) {
        connections.rollback(id)
    }

    override fun setAutoCommit(id: DataSourceId, value: Boolean) {
        connections.setAutoCommit(id, value)
    }

    override fun autoCommit(id: DataSourceId): Boolean? =
        connections.autoCommit(id)

    override fun installLocalDriver(definition: DataSourceDefinition, jar: Path): Path =
        driverResolver.installLocalJar(definition.driver, jar)

    override fun downloadDriver(
        definition: DataSourceDefinition,
        progress: (JdbcDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ): Path = driverDownloader.download(definition.driver, progress, isCancelled)

    override fun close() {
        connections.close()
    }
}
