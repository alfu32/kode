package editor.database

import editor.database.config.CredentialStore
import editor.database.config.DataSourceRepository
import editor.database.connection.DatabaseConnectionManager
import editor.database.driver.JdbcDriverRegistry
import editor.database.metadata.DatabaseMetadataService
import editor.database.model.DataSourceDefinition
import editor.database.model.DatabaseObjectType
import editor.database.model.MetadataRequest
import editor.database.model.MetadataRequestKind
import editor.database.query.SqlExecutionRequest
import editor.database.query.SqlExecutionService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class JdbcDatabaseIntegrationTest {
    @Test
    fun connectsExecutesAndIntrospectsH2() {
        val root = Files.createTempDirectory("kode-db-integration")
        val credentials = CredentialStore(root)
        val connections = DatabaseConnectionManager(credentials)
        val service = JdbcDatabaseService(
            repository = DataSourceRepository(root),
            credentialStore = credentials,
            connections = connections,
            metadata = DatabaseMetadataService(connections),
            executor = SqlExecutionService(connections)
        )
        val definition = DataSourceDefinition(
            id = "h2-test",
            name = "h2-test",
            driver = JdbcDriverRegistry().specFor("h2")!!,
            jdbcUrl = "jdbc:h2:mem:kode-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            username = "sa"
        )
        service.saveDataSource(definition)

        val status = service.connect(definition.id)
        assertEquals(editor.database.model.ConnectionState.CONNECTED, status.state)

        service.execute(SqlExecutionRequest(definition.id, "console", "create table customer(id int primary key, name varchar(40))"))
        service.execute(SqlExecutionRequest(definition.id, "console", "insert into customer values (1, 'Alice'), (2, null)"))
        val result = service.execute(SqlExecutionRequest(definition.id, "console", "select id, name from customer order by id"))

        assertTrue(result.success)
        assertEquals(listOf("ID", "NAME"), result.resultSets.single().columns.map { it.name.uppercase() })
        assertEquals("Alice", result.resultSets.single().rows[0][1].value)
        assertTrue(result.resultSets.single().rows[1][1].nullValue)

        val rootObjects = service.introspect(definition.id, MetadataRequest(MetadataRequestKind.ROOT)).objects
        assertTrue(rootObjects.isNotEmpty())
        val tableObjects = findTables(service, definition.id, rootObjects)
        assertNotNull(tableObjects.firstOrNull { it.name.equals("CUSTOMER", ignoreCase = true) })

        service.disconnect(definition.id)
    }

    @Test
    fun returnsSqlErrorsAsResults() {
        val root = Files.createTempDirectory("kode-db-error")
        val credentials = CredentialStore(root)
        val connections = DatabaseConnectionManager(credentials)
        val service = JdbcDatabaseService(
            repository = DataSourceRepository(root),
            credentialStore = credentials,
            connections = connections,
            metadata = DatabaseMetadataService(connections),
            executor = SqlExecutionService(connections)
        )
        val definition = DataSourceDefinition(
            id = "h2-error",
            name = "h2-error",
            driver = JdbcDriverRegistry().specFor("h2")!!,
            jdbcUrl = "jdbc:h2:mem:kode-error-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            username = "sa"
        )
        service.saveDataSource(definition)
        service.connect(definition.id)

        val result = service.execute(SqlExecutionRequest(definition.id, "console", "select * from missing_table"))

        assertTrue(!result.success)
        assertNotNull(result.error?.sqlState)
        service.disconnect(definition.id)
    }

    @Test
    fun supportsUpdateCountsAndRollback() {
        val root = Files.createTempDirectory("kode-db-transaction")
        val credentials = CredentialStore(root)
        val connections = DatabaseConnectionManager(credentials)
        val service = JdbcDatabaseService(
            repository = DataSourceRepository(root),
            credentialStore = credentials,
            connections = connections,
            metadata = DatabaseMetadataService(connections),
            executor = SqlExecutionService(connections)
        )
        val definition = DataSourceDefinition(
            id = "h2-tx",
            name = "h2-tx",
            driver = JdbcDriverRegistry().specFor("h2")!!,
            jdbcUrl = "jdbc:h2:mem:kode-tx-${System.nanoTime()};DB_CLOSE_DELAY=-1",
            username = "sa",
            autoCommit = false
        )
        service.saveDataSource(definition)
        service.connect(definition.id)

        service.execute(SqlExecutionRequest(definition.id, "console", "create table item(id int primary key, name varchar(40))"))
        val insert = service.execute(SqlExecutionRequest(definition.id, "console", "insert into item values (1, 'draft')"))
        val update = service.execute(SqlExecutionRequest(definition.id, "console", "update item set name = 'changed' where id = 1"))
        service.rollback(definition.id)
        val count = service.execute(SqlExecutionRequest(definition.id, "console", "select count(*) as total from item"))

        assertEquals(listOf(1), insert.updateCounts)
        assertEquals(listOf(1), update.updateCounts)
        assertEquals("0", count.resultSets.single().rows.single().single().value)
        service.disconnect(definition.id)
    }

    private fun findTables(
        service: JdbcDatabaseService,
        dataSourceId: String,
        objects: List<editor.database.model.DatabaseObject>
    ): List<editor.database.model.DatabaseObject> {
        val out = mutableListOf<editor.database.model.DatabaseObject>()
        objects.forEach { obj ->
            when (obj.objectType) {
                DatabaseObjectType.CATEGORY -> {
                    val category = obj.attributes["categoryType"]?.let { DatabaseObjectType.valueOf(it) }
                    if (category == DatabaseObjectType.TABLE) {
                        out += service.introspect(
                            dataSourceId,
                            MetadataRequest(
                                MetadataRequestKind.CATEGORY,
                                catalog = obj.catalog,
                                schema = obj.schema,
                                category = DatabaseObjectType.TABLE
                            )
                        ).objects
                    }
                }
                DatabaseObjectType.CATALOG -> out += findTables(
                    service,
                    dataSourceId,
                    service.introspect(dataSourceId, MetadataRequest(MetadataRequestKind.CATALOG, catalog = obj.catalog ?: obj.name)).objects
                )
                DatabaseObjectType.SCHEMA -> out += findTables(
                    service,
                    dataSourceId,
                    service.introspect(dataSourceId, MetadataRequest(MetadataRequestKind.SCHEMA, catalog = obj.catalog, schema = obj.schema ?: obj.name)).objects
                )
                else -> Unit
            }
        }
        return out
    }
}
