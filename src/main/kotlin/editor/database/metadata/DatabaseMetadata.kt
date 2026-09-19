package editor.database.metadata

import editor.database.connection.DatabaseConnectionManager
import editor.database.model.DataSourceId
import editor.database.model.DatabaseCapabilities
import editor.database.model.DatabaseObject
import editor.database.model.DatabaseObjectType
import editor.database.model.MetadataRequest
import editor.database.model.MetadataRequestKind
import editor.database.model.MetadataResult
import java.sql.Connection
import java.sql.DatabaseMetaData
import java.sql.ResultSet
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

interface DatabaseIntrospector {
    fun introspect(connection: Connection, request: MetadataRequest): MetadataResult
}

class MetadataCache {
    private val cache = ConcurrentHashMap<CacheKey, MetadataResult>()

    fun get(dataSourceId: DataSourceId, request: MetadataRequest): MetadataResult? =
        cache[CacheKey(dataSourceId, request)]

    fun put(dataSourceId: DataSourceId, request: MetadataRequest, result: MetadataResult) {
        cache[CacheKey(dataSourceId, request)] = result
    }

    fun invalidateDataSource(dataSourceId: DataSourceId) {
        cache.keys.removeIf { it.dataSourceId == dataSourceId }
    }

    fun invalidate(dataSourceId: DataSourceId, request: MetadataRequest) {
        cache.remove(CacheKey(dataSourceId, request))
    }

    private data class CacheKey(val dataSourceId: DataSourceId, val request: MetadataRequest)
}

class DatabaseMetadataService(
    private val connections: DatabaseConnectionManager,
    private val introspector: DatabaseIntrospector = JdbcDatabaseIntrospector(),
    private val cache: MetadataCache = MetadataCache()
) {
    fun introspect(dataSourceId: DataSourceId, request: MetadataRequest, refresh: Boolean = false): MetadataResult {
        if (!refresh) {
            cache.get(dataSourceId, request)?.let { return it }
        } else {
            cache.invalidate(dataSourceId, request)
        }
        val result = introspector.introspect(connections.requireConnection(dataSourceId), request)
        cache.put(dataSourceId, request, result)
        return result
    }

    fun refreshAll(dataSourceId: DataSourceId) {
        cache.invalidateDataSource(dataSourceId)
    }
}

class JdbcDatabaseIntrospector : DatabaseIntrospector {
    override fun introspect(connection: Connection, request: MetadataRequest): MetadataResult {
        val meta = connection.metaData
        return when (request.kind) {
            MetadataRequestKind.ROOT -> root(meta)
            MetadataRequestKind.CATALOG -> schemasOrCategories(meta, request.catalog)
            MetadataRequestKind.SCHEMA -> categories(meta, request.catalog, request.schema)
            MetadataRequestKind.CATEGORY -> categoryChildren(meta, request)
            MetadataRequestKind.OBJECT -> objectChildren(meta, request)
        }
    }

    private fun root(meta: DatabaseMetaData): MetadataResult {
        val catalogs = readRows(meta.catalogs) { rs ->
            val name = rs.getString("TABLE_CAT") ?: return@readRows null
            DatabaseObject(
                id = "catalog:$name",
                name = name,
                objectType = DatabaseObjectType.CATALOG,
                catalog = name,
                hasChildren = true
            )
        }
        if (catalogs.isNotEmpty()) {
            return MetadataResult(catalogs, capabilities(meta))
        }
        val schemas = schemas(meta, null)
        return MetadataResult(schemas.ifEmpty { categories(meta, null, null).objects }, capabilities(meta))
    }

    private fun schemasOrCategories(meta: DatabaseMetaData, catalog: String?): MetadataResult {
        val schemas = schemas(meta, catalog).ifEmpty {
            if (catalog != null) schemas(meta, null) else emptyList()
        }
        return if (schemas.isNotEmpty()) MetadataResult(schemas, capabilities(meta)) else categories(meta, catalog, null)
    }

    private fun schemas(meta: DatabaseMetaData, catalog: String?): List<DatabaseObject> =
        readRows(meta.getSchemas(catalog, null)) { rs ->
            val name = rs.getString("TABLE_SCHEM") ?: return@readRows null
            DatabaseObject(
                id = "schema:${catalog.orEmpty()}:$name",
                name = name,
                qualifiedName = listOfNotNull(catalog, name).joinToString("."),
                objectType = DatabaseObjectType.SCHEMA,
                catalog = catalog,
                schema = name,
                hasChildren = true
            )
        }

    private fun categories(meta: DatabaseMetaData, catalog: String?, schema: String?): MetadataResult {
        val result = mutableListOf<DatabaseObject>()
        val tableTypes = tableTypes(meta)
        if ("TABLE" in tableTypes || "BASE TABLE" in tableTypes || tableTypes.isEmpty()) {
            result += category("Tables", DatabaseObjectType.TABLE, catalog, schema)
        }
        if ("VIEW" in tableTypes || tableTypes.isEmpty()) {
            result += category("Views", DatabaseObjectType.VIEW, catalog, schema)
        }
        if (supportsProcedures(meta)) {
            result += category("Procedures", DatabaseObjectType.PROCEDURE, catalog, schema)
        }
        if (supportsFunctions(meta)) {
            result += category("Functions", DatabaseObjectType.FUNCTION, catalog, schema)
        }
        result += category("Types", DatabaseObjectType.TYPE, catalog, schema)
        return MetadataResult(result, capabilities(meta))
    }

    private fun category(name: String, type: DatabaseObjectType, catalog: String?, schema: String?) =
        DatabaseObject(
            id = "category:${catalog.orEmpty()}:${schema.orEmpty()}:$type",
            name = name,
            objectType = DatabaseObjectType.CATEGORY,
            catalog = catalog,
            schema = schema,
            attributes = mapOf("categoryType" to type.name),
            hasChildren = true
        )

    private fun categoryChildren(meta: DatabaseMetaData, request: MetadataRequest): MetadataResult {
        return when (request.category) {
            DatabaseObjectType.TABLE -> tables(meta, request, arrayOf("TABLE", "BASE TABLE"))
            DatabaseObjectType.VIEW -> tables(meta, request, arrayOf("VIEW"))
            DatabaseObjectType.PROCEDURE -> procedures(meta, request)
            DatabaseObjectType.FUNCTION -> functions(meta, request)
            DatabaseObjectType.TYPE -> types(meta, request)
            else -> MetadataResult(emptyList(), capabilities(meta))
        }
    }

    private fun tables(meta: DatabaseMetaData, request: MetadataRequest, types: Array<String>): MetadataResult {
        fun load(catalog: String?) = readRows(meta.getTables(catalog, request.schema, "%", types)) { rs ->
                val name = rs.getString("TABLE_NAME") ?: return@readRows null
                val schema = rs.getString("TABLE_SCHEM") ?: request.schema
                val catalog = rs.getString("TABLE_CAT") ?: request.catalog
                val type = when (rs.getString("TABLE_TYPE")?.uppercase(Locale.ROOT)) {
                    "VIEW" -> DatabaseObjectType.VIEW
                    "BASE TABLE", "TABLE" -> DatabaseObjectType.TABLE
                    else -> DatabaseObjectType.TABLE
                }
                DatabaseObject(
                    id = "object:${catalog.orEmpty()}:${schema.orEmpty()}:$type:$name",
                    name = name,
                    qualifiedName = listOfNotNull(schema, name).joinToString("."),
                    objectType = type,
                    catalog = catalog,
                    schema = schema,
                    attributes = mapOf("remarks" to (rs.getString("REMARKS") ?: "")),
                    hasChildren = true
                )
            }
        val objects = load(request.catalog).ifEmpty {
            if (request.catalog != null) load(null) else emptyList()
        }
        return MetadataResult(
            objects,
            capabilities(meta)
        )
    }

    private fun procedures(meta: DatabaseMetaData, request: MetadataRequest): MetadataResult =
        MetadataResult(
            readRows(meta.getProcedures(request.catalog, request.schema, "%")) { rs ->
                val name = rs.getString("PROCEDURE_NAME") ?: return@readRows null
                DatabaseObject(
                    id = "procedure:${request.catalog.orEmpty()}:${request.schema.orEmpty()}:$name",
                    name = name,
                    qualifiedName = listOfNotNull(request.schema, name).joinToString("."),
                    objectType = DatabaseObjectType.PROCEDURE,
                    catalog = request.catalog,
                    schema = request.schema
                )
            },
            capabilities(meta)
        )

    private fun functions(meta: DatabaseMetaData, request: MetadataRequest): MetadataResult =
        MetadataResult(
            readRows(meta.getFunctions(request.catalog, request.schema, "%")) { rs ->
                val name = rs.getString("FUNCTION_NAME") ?: return@readRows null
                DatabaseObject(
                    id = "function:${request.catalog.orEmpty()}:${request.schema.orEmpty()}:$name",
                    name = name,
                    qualifiedName = listOfNotNull(request.schema, name).joinToString("."),
                    objectType = DatabaseObjectType.FUNCTION,
                    catalog = request.catalog,
                    schema = request.schema
                )
            },
            capabilities(meta)
        )

    private fun types(meta: DatabaseMetaData, request: MetadataRequest): MetadataResult =
        MetadataResult(
            readRows(meta.getTypeInfo()) { rs ->
                val name = rs.getString("TYPE_NAME") ?: return@readRows null
                DatabaseObject(
                    id = "type:$name",
                    name = name,
                    objectType = DatabaseObjectType.TYPE,
                    attributes = mapOf("dataType" to rs.getInt("DATA_TYPE").toString())
                )
            },
            capabilities(meta)
        )

    private fun objectChildren(meta: DatabaseMetaData, request: MetadataRequest): MetadataResult {
        val table = request.objectName ?: return MetadataResult(emptyList(), capabilities(meta))
        val objects = mutableListOf<DatabaseObject>()
        objects += readRows(meta.getColumns(request.catalog, request.schema, table, "%")) { rs ->
            val name = rs.getString("COLUMN_NAME") ?: return@readRows null
            DatabaseObject(
                id = "column:${request.catalog.orEmpty()}:${request.schema.orEmpty()}:$table:$name",
                name = name,
                qualifiedName = "${table}.${name}",
                objectType = DatabaseObjectType.COLUMN,
                catalog = request.catalog,
                schema = request.schema,
                attributes = mapOf(
                    "type" to (rs.getString("TYPE_NAME") ?: ""),
                    "size" to rs.getInt("COLUMN_SIZE").toString(),
                    "nullable" to rs.getInt("NULLABLE").toString(),
                    "ordinal" to rs.getInt("ORDINAL_POSITION").toString()
                )
            )
        }
        objects += readRows(meta.getPrimaryKeys(request.catalog, request.schema, table)) { rs ->
            val name = rs.getString("PK_NAME") ?: "PRIMARY KEY"
            DatabaseObject(
                id = "pk:${request.catalog.orEmpty()}:${request.schema.orEmpty()}:$table:$name:${rs.getString("COLUMN_NAME")}",
                name = name,
                objectType = DatabaseObjectType.PRIMARY_KEY,
                catalog = request.catalog,
                schema = request.schema,
                attributes = mapOf("column" to (rs.getString("COLUMN_NAME") ?: ""))
            )
        }
        objects += readRows(meta.getIndexInfo(request.catalog, request.schema, table, false, false)) { rs ->
            val name = rs.getString("INDEX_NAME") ?: return@readRows null
            DatabaseObject(
                id = "idx:${request.catalog.orEmpty()}:${request.schema.orEmpty()}:$table:$name:${rs.getString("COLUMN_NAME")}",
                name = name,
                objectType = DatabaseObjectType.INDEX,
                catalog = request.catalog,
                schema = request.schema,
                attributes = mapOf(
                    "column" to (rs.getString("COLUMN_NAME") ?: ""),
                    "nonUnique" to rs.getBoolean("NON_UNIQUE").toString()
                )
            )
        }
        return MetadataResult(objects.distinctBy { it.id }, capabilities(meta))
    }

    private fun tableTypes(meta: DatabaseMetaData): Set<String> =
        readRows(meta.tableTypes) { it.getString("TABLE_TYPE")?.uppercase(Locale.ROOT) }.filterNotNull().toSet()

    private fun supportsProcedures(meta: DatabaseMetaData): Boolean =
        runCatching { meta.supportsStoredProcedures() }.getOrDefault(true)

    private fun supportsFunctions(meta: DatabaseMetaData): Boolean =
        runCatching { meta.databaseMajorVersion >= 0 }.getOrDefault(true)

    private fun capabilities(meta: DatabaseMetaData): DatabaseCapabilities =
        DatabaseCapabilities(
            catalogs = runCatching { meta.supportsCatalogsInTableDefinitions() || meta.catalogs.use { it.next() } }.getOrDefault(false),
            schemas = runCatching { meta.supportsSchemasInTableDefinitions() || meta.schemas.use { it.next() } }.getOrDefault(false),
            transactions = runCatching { meta.supportsTransactions() }.getOrDefault(true),
            savepoints = runCatching { meta.supportsSavepoints() }.getOrDefault(false)
        )

    private fun <T> readRows(resultSet: ResultSet, mapper: (ResultSet) -> T?): List<T> =
        resultSet.use { rs ->
            val out = mutableListOf<T>()
            while (rs.next()) {
                mapper(rs)?.let { out += it }
            }
            out
        }
}
