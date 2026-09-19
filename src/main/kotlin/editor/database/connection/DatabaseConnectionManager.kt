package editor.database.connection

import editor.database.config.CredentialStore
import editor.database.driver.JdbcDriverLoader
import editor.database.model.ConnectionState
import editor.database.model.ConnectionStatus
import editor.database.model.ConnectionTestResult
import editor.database.model.DataSourceDefinition
import editor.database.model.DataSourceId
import java.sql.Connection
import java.sql.Driver
import java.sql.SQLException
import java.util.Properties
import java.util.concurrent.ConcurrentHashMap
import kotlin.system.measureTimeMillis

class DatabaseConnectionManager(
    private val credentialStore: CredentialStore = CredentialStore(),
    private val driverLoader: JdbcDriverLoader = JdbcDriverLoader()
) : AutoCloseable {
    private val sessions = ConcurrentHashMap<DataSourceId, DatabaseSession>()
    private val statuses = ConcurrentHashMap<DataSourceId, ConnectionStatus>()

    fun status(id: DataSourceId): ConnectionStatus =
        statuses[id] ?: ConnectionStatus(id)

    fun connect(definition: DataSourceDefinition): ConnectionStatus {
        statuses[definition.id] = ConnectionStatus(definition.id, ConnectionState.CONNECTING)
        return runCatching {
            disconnect(definition.id)
            val driver = driverLoader.load(definition.driver)
            val connection = connectWith(driver.driver, definition)
            connection.autoCommit = definition.autoCommit
            connection.isReadOnly = definition.readOnly
            definition.defaultCatalog?.takeIf { it.isNotBlank() }?.let { runCatching { connection.catalog = it } }
            definition.defaultSchema?.takeIf { it.isNotBlank() }?.let { runCatching { connection.schema = it } }
            sessions[definition.id] = DatabaseSession(definition, connection, driver)
            ConnectionStatus(definition.id, ConnectionState.CONNECTED)
        }.getOrElse { ex ->
            ConnectionStatus(definition.id, ConnectionState.ERROR, sqlMessage(ex))
        }.also { statuses[definition.id] = it }
    }

    fun testConnection(definition: DataSourceDefinition, passwordOverride: String? = null): ConnectionTestResult {
        val started = System.nanoTime()
        return runCatching {
            driverLoader.load(definition.driver).use { loaded ->
                connectWith(loaded.driver, definition, passwordOverride).use { connection ->
                    val meta = connection.metaData
                    ConnectionTestResult(
                        success = true,
                        databaseProduct = meta.databaseProductName,
                        databaseVersion = meta.databaseProductVersion,
                        driverName = meta.driverName,
                        driverVersion = meta.driverVersion,
                        latencyMs = (System.nanoTime() - started) / 1_000_000
                    )
                }
            }
        }.getOrElse { ex ->
            val sql = ex as? SQLException
            ConnectionTestResult(
                success = false,
                latencyMs = (System.nanoTime() - started) / 1_000_000,
                sqlState = sql?.sqlState,
                vendorCode = sql?.errorCode,
                message = sqlMessage(ex)
            )
        }
    }

    fun disconnect(id: DataSourceId): ConnectionStatus {
        sessions.remove(id)?.close()
        return ConnectionStatus(id, ConnectionState.DISCONNECTED).also { statuses[id] = it }
    }

    fun session(id: DataSourceId): DatabaseSession? {
        val session = sessions[id] ?: return null
        return if (session.connection.isClosed) {
            disconnect(id)
            null
        } else session
    }

    fun requireConnection(id: DataSourceId): Connection =
        session(id)?.connection ?: throw IllegalStateException("Data source is not connected: $id")

    fun commit(id: DataSourceId) {
        requireConnection(id).commit()
    }

    fun rollback(id: DataSourceId) {
        requireConnection(id).rollback()
    }

    fun setAutoCommit(id: DataSourceId, value: Boolean) {
        requireConnection(id).autoCommit = value
    }

    fun autoCommit(id: DataSourceId): Boolean? =
        session(id)?.connection?.autoCommit

    override fun close() {
        sessions.keys.toList().forEach(::disconnect)
    }

    private fun connectWith(driver: Driver, definition: DataSourceDefinition, passwordOverride: String? = null): Connection {
        val props = Properties()
        definition.properties.forEach { (key, value) -> props.setProperty(key, value) }
        if (definition.username.isNotBlank()) props.setProperty("user", definition.username)
        val password = passwordOverride ?: credentialStore.get(definition.credentialReference)
        password?.let { props.setProperty("password", it) }
        return driver.connect(definition.jdbcUrl, props)
            ?: throw SQLException("Driver ${definition.driver.driverClass} did not accept URL")
    }

    private fun sqlMessage(ex: Throwable): String {
        val sql = generateSequence(ex) { it.cause }.filterIsInstance<SQLException>().firstOrNull()
        return sql?.let { "${it.message} (SQLState=${it.sqlState}, code=${it.errorCode})" }
            ?: (ex.message ?: ex::class.simpleName ?: "error")
    }
}

data class DatabaseSession(
    val definition: DataSourceDefinition,
    val connection: Connection,
    val driver: AutoCloseable
) : AutoCloseable {
    override fun close() {
        runCatching { connection.close() }
        runCatching { driver.close() }
    }
}
