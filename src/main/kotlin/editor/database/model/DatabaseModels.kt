package editor.database.model

import kotlinx.serialization.Serializable

@Serializable
enum class JdbcRepositoryType {
    MAVEN_CENTRAL,
    MAVEN_CUSTOM,
    VENDOR_URL,
    LOCAL_FILE
}

@Serializable
data class JdbcDriverArtifact(
    val repositoryType: JdbcRepositoryType = JdbcRepositoryType.MAVEN_CENTRAL,
    val groupId: String? = null,
    val artifactId: String? = null,
    val version: String? = null,
    val classifier: String? = null,
    val repository: String? = null,
    val vendorDownloadPage: String? = null,
    val downloadUrl: String? = null
)

typealias DataSourceId = String

@Serializable
data class DriverSpec(
    val id: String = "generic",
    val displayName: String = "Generic JDBC Driver",
    val driverClass: String,
    val coordinates: String? = null,
    val jarPath: String? = null,
    val artifact: JdbcDriverArtifact? = null,
    val providerId: String? = null
)

@Serializable
data class DataSourceDefinition(
    val id: DataSourceId,
    val name: String,
    val driver: DriverSpec,
    val jdbcUrl: String,
    val username: String = "",
    val credentialReference: String? = null,
    val properties: Map<String, String> = emptyMap(),
    val defaultCatalog: String? = null,
    val defaultSchema: String? = null,
    val autoCommit: Boolean = true,
    val readOnly: Boolean = false
)

enum class ConnectionState {
    DISCONNECTED,
    CONNECTING,
    CONNECTED,
    ERROR
}

data class ConnectionStatus(
    val dataSourceId: DataSourceId,
    val state: ConnectionState = ConnectionState.DISCONNECTED,
    val message: String? = null
)

data class ConnectionTestResult(
    val success: Boolean,
    val databaseProduct: String? = null,
    val databaseVersion: String? = null,
    val driverName: String? = null,
    val driverVersion: String? = null,
    val latencyMs: Long = 0,
    val sqlState: String? = null,
    val vendorCode: Int? = null,
    val message: String? = null
)

enum class DatabaseObjectType {
    DATA_SOURCE,
    CATALOG,
    SCHEMA,
    CATEGORY,
    TABLE,
    VIEW,
    COLUMN,
    PROCEDURE,
    FUNCTION,
    PACKAGE,
    TYPE,
    INDEX,
    PRIMARY_KEY,
    FOREIGN_KEY,
    MESSAGE,
    ERROR
}

@Serializable
data class DatabaseProjectState(
    val dataSources: List<DataSourceDefinition> = emptyList(),
    val sessions: List<DatabaseSessionState> = emptyList()
)

@Serializable
data class DatabaseSessionState(
    val id: String,
    val dataSourceId: DataSourceId,
    val title: String,
    val catalog: String? = null,
    val schema: String? = null,
    val autoCommit: Boolean = true,
    val buffer: String = ""
)

data class DatabaseObject(
    val id: String,
    val name: String,
    val qualifiedName: String = name,
    val objectType: DatabaseObjectType,
    val catalog: String? = null,
    val schema: String? = null,
    val attributes: Map<String, String> = emptyMap(),
    val hasChildren: Boolean = false
)

data class DatabaseCapabilities(
    val catalogs: Boolean = false,
    val schemas: Boolean = false,
    val tables: Boolean = true,
    val views: Boolean = true,
    val procedures: Boolean = true,
    val functions: Boolean = true,
    val types: Boolean = true,
    val transactions: Boolean = true,
    val savepoints: Boolean = true
)

enum class MetadataRequestKind {
    ROOT,
    CATALOG,
    SCHEMA,
    CATEGORY,
    OBJECT
}

data class MetadataRequest(
    val kind: MetadataRequestKind,
    val catalog: String? = null,
    val schema: String? = null,
    val category: DatabaseObjectType? = null,
    val objectName: String? = null,
    val objectType: DatabaseObjectType? = null
)

data class MetadataResult(
    val objects: List<DatabaseObject>,
    val capabilities: DatabaseCapabilities? = null,
    val message: String? = null
)
