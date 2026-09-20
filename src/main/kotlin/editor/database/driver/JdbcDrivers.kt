package editor.database.driver

import editor.database.config.KodeDatabasePaths
import editor.database.model.DriverSpec
import editor.database.model.JdbcDriverArtifact
import editor.database.model.JdbcRepositoryType
import java.net.HttpURLConnection
import java.net.URI
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.sql.Driver
import java.util.Locale
import java.util.Properties
import java.util.ServiceLoader
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Element

data class JdbcDriverDefinition(
    val id: String,
    val displayName: String,
    val databaseFamily: String,
    val driverClass: String = "",
    val defaultMavenCoordinates: String? = null,
    val defaultJdbcUrlTemplate: String = "",
    val urlPrefixes: List<String> = emptyList(),
    val artifact: JdbcDriverArtifact? = null,
    val aliasOf: String? = null,
    val jdbcUrlTemplates: List<JdbcUrlTemplate> = emptyList()
)

data class ResolvedJdbcDriver(
    val spec: DriverSpec,
    val driver: Driver,
    val classLoader: URLClassLoader? = null,
    val source: String
) : AutoCloseable {
    override fun close() {
        runCatching { classLoader?.close() }
    }
}

data class JdbcDownloadProgress(
    val phase: String,
    val currentArtifact: String = "",
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = -1L,
    val artifactIndex: Int = 0,
    val artifactCount: Int = 0
)

class JdbcDriverDownloadCancelled : RuntimeException("JDBC driver download cancelled")

class JdbcDriverRegistry(
    val definitions: List<JdbcDriverDefinition> = defaultDefinitions()
) {
    fun byId(id: String): JdbcDriverDefinition? =
        definitions.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun byUrl(url: String): JdbcDriverDefinition? =
        definitions.firstOrNull { definition ->
            definition.urlPrefixes.any { prefix -> url.startsWith(prefix, ignoreCase = true) }
        }

    fun specFor(id: String): DriverSpec? {
        val definition = byId(id) ?: return null
        val provider = definition.aliasOf?.let(::byId) ?: definition
        val artifact = definition.artifact ?: provider.artifact
        val coordinates = definition.defaultMavenCoordinates
            ?: provider.defaultMavenCoordinates
            ?: artifact?.coordinateLabel()
        return DriverSpec(
            id = definition.id,
            displayName = definition.displayName,
            driverClass = definition.driverClass.ifBlank { provider.driverClass },
            coordinates = coordinates,
            artifact = artifact,
            providerId = provider.id
        )
    }

    companion object {
        fun defaultDefinitions(): List<JdbcDriverDefinition> = listOf(
            maven("postgresql", "PostgreSQL", "PostgreSQL", "org.postgresql:postgresql", "org.postgresql.Driver", "jdbc:postgresql:", "jdbc:postgresql://localhost:5432/postgres"),
            maven("mysql", "MySQL", "MySQL", "com.mysql:mysql-connector-j", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:", "jdbc:mysql://localhost:3306/mysql"),
            maven("mariadb", "MariaDB", "MariaDB", "org.mariadb.jdbc:mariadb-java-client", "org.mariadb.jdbc.Driver", "jdbc:mariadb:", "jdbc:mariadb://localhost:3306/mysql"),
            maven("oracle", "Oracle", "Oracle", "com.oracle.database.jdbc:ojdbc11", "oracle.jdbc.OracleDriver", "jdbc:oracle:", "jdbc:oracle:thin:@localhost:1521/FREEPDB1"),
            maven("sqlserver", "Microsoft SQL Server", "SQL Server", "com.microsoft.sqlserver:mssql-jdbc", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:", "jdbc:sqlserver://localhost:1433;databaseName=master"),
            maven("db2", "IBM Db2 LUW", "DB2", "com.ibm.db2:jcc", "com.ibm.db2.jcc.DB2Driver", "jdbc:db2:", "jdbc:db2://localhost:50000/sample"),
            maven("sqlite", "SQLite", "SQLite", "org.xerial:sqlite-jdbc", "org.sqlite.JDBC", "jdbc:sqlite:", "jdbc:sqlite:file.db"),
            maven("h2", "H2", "H2", "com.h2database:h2", "org.h2.Driver", "jdbc:h2:", "jdbc:h2:mem:kode"),
            maven("hsqldb", "HSQLDB / HyperSQL", "HSQLDB", "org.hsqldb:hsqldb", "org.hsqldb.jdbc.JDBCDriver", "jdbc:hsqldb:", "jdbc:hsqldb:mem:kode"),
            maven("derby-embedded", "Apache Derby Embedded", "Derby", "org.apache.derby:derby", "org.apache.derby.jdbc.EmbeddedDriver", "jdbc:derby:", "jdbc:derby:memory:kode;create=true"),
            maven("derby-client", "Apache Derby Network", "Derby", "org.apache.derby:derbyclient", "org.apache.derby.jdbc.ClientDriver", "jdbc:derby:", "jdbc:derby://localhost:1527/kode"),
            maven("firebird", "Firebird", "Firebird", "org.firebirdsql.jdbc:jaybird", "org.firebirdsql.jdbc.FBDriver", "jdbc:firebirdsql:", "jdbc:firebirdsql://localhost/kode"),
            maven("informix", "IBM Informix", "Informix", "com.ibm.informix:jdbc", "com.informix.jdbc.IfxDriver", "jdbc:informix-sqli:", "jdbc:informix-sqli://localhost:9088/kode"),
            maven("iris", "InterSystems IRIS", "IRIS", "com.intersystems:intersystems-jdbc", "com.intersystems.jdbc.IRISDriver", "jdbc:IRIS:", "jdbc:IRIS://localhost:1972/kode"),
            maven("tibero", "TmaxTibero", "Tibero", "com.tmaxtibero:tbjdbc8", "com.tmax.tibero.jdbc.TbDriver", "jdbc:tibero:thin:", "jdbc:tibero:thin:@localhost:8629:kode"),
            maven("teradata", "Teradata Vantage", "Teradata", "com.teradata.jdbc:terajdbc", "com.teradata.jdbc.TeraDriver", "jdbc:teradata:", "jdbc:teradata://localhost/database=kode"),
            maven("vertica", "Vertica", "Vertica", "com.vertica.jdbc:vertica-jdbc", "com.vertica.jdbc.Driver", "jdbc:vertica:", "jdbc:vertica://localhost:5433/kode"),
            maven("exasol", "Exasol", "Exasol", "com.exasol:exasol-jdbc", "com.exasol.jdbc.EXADriver", "jdbc:exa:", "jdbc:exa:localhost..1"),
            maven("sap-hana", "SAP HANA", "SAP HANA", "com.sap.cloud.db.jdbc:ngdbc", "com.sap.db.jdbc.Driver", "jdbc:sap:", "jdbc:sap://localhost:39015"),
            maven("snowflake", "Snowflake", "Snowflake", "net.snowflake:snowflake-jdbc", "net.snowflake.client.jdbc.SnowflakeDriver", "jdbc:snowflake:", "jdbc:snowflake://account.snowflakecomputing.com"),
            maven("redshift", "Amazon Redshift", "Redshift", "com.amazon.redshift:redshift-jdbc42", "com.amazon.redshift.jdbc.Driver", "jdbc:redshift:", "jdbc:redshift://localhost:5439/kode"),
            maven("bigquery", "Google BigQuery", "BigQuery", "com.google.cloud:google-cloud-bigquery-jdbc", "", "jdbc:bigquery:", "jdbc:bigquery://https://www.googleapis.com/bigquery/v2:443;ProjectId=project", classifier = "all"),
            maven("trino", "Trino", "Trino", "io.trino:trino-jdbc", "io.trino.jdbc.TrinoDriver", "jdbc:trino:", "jdbc:trino://localhost:8080/kode"),
            maven("presto", "PrestoDB", "PrestoDB", "com.facebook.presto:presto-jdbc", "com.facebook.presto.jdbc.PrestoDriver", "jdbc:presto:", "jdbc:presto://localhost:8080/kode"),
            maven("hive", "Apache Hive", "Hive", "org.apache.hive:hive-jdbc", "org.apache.hive.jdbc.HiveDriver", "jdbc:hive2:", "jdbc:hive2://localhost:10000/kode"),
            maven("databricks", "Databricks", "Databricks", "com.databricks:databricks-jdbc", "", "jdbc:databricks:", "jdbc:databricks://localhost:443/default"),
            maven("clickhouse", "ClickHouse", "ClickHouse", "com.clickhouse:clickhouse-jdbc-all", "com.clickhouse.jdbc.ClickHouseDriver", "jdbc:clickhouse:", "jdbc:clickhouse://localhost:8123/kode"),
            maven("duckdb", "DuckDB", "DuckDB", "org.duckdb:duckdb_jdbc", "org.duckdb.DuckDBDriver", "jdbc:duckdb:", "jdbc:duckdb:kode.db"),
            maven("singlestore", "SingleStore", "SingleStore", "com.singlestore:singlestore-jdbc-client", "", "jdbc:singlestore:", "jdbc:singlestore://localhost:3306/kode"),
            maven("spanner", "Google Cloud Spanner", "Spanner", "com.google.cloud:google-cloud-spanner-jdbc", "com.google.cloud.spanner.jdbc.JdbcDriver", "jdbc:cloudspanner:", "jdbc:cloudspanner:/projects/project/instances/instance/databases/database"),
            maven("ignite2", "Apache Ignite 2", "Ignite", "org.apache.ignite:ignite-core", "org.apache.ignite.IgniteJdbcThinDriver", "jdbc:ignite:thin:", "jdbc:ignite:thin://127.0.0.1"),
            maven("mongodb-atlas", "MongoDB Atlas SQL", "MongoDB", "org.mongodb:mongodb-jdbc", "", "jdbc:mongodb:", "jdbc:mongodb://localhost"),
            maven("neo4j", "Neo4j", "Neo4j", "org.neo4j:neo4j-jdbc", "", "jdbc:neo4j:", "jdbc:neo4j://localhost"),
            vendor("athena", "Amazon Athena", "Athena", "com.amazon.athena.jdbc.AthenaDriver", "jdbc:athena:", "https://docs.aws.amazon.com/athena/latest/ug/connect-with-jdbc.html"),
            vendor("documentdb", "Amazon DocumentDB SQL/JDBC", "DocumentDB", "", "jdbc:documentdb:", "https://docs.aws.amazon.com/documentdb/latest/developerguide/connect-ec2.html"),
            JdbcDriverDefinition(
                id = "elasticsearch",
                displayName = "Elasticsearch SQL JDBC",
                databaseFamily = "Elasticsearch",
                driverClass = "org.elasticsearch.xpack.sql.jdbc.EsDriver",
                defaultJdbcUrlTemplate = "jdbc:es://localhost:9200",
                urlPrefixes = listOf("jdbc:es:"),
                artifact = JdbcDriverArtifact(
                    repositoryType = JdbcRepositoryType.MAVEN_CUSTOM,
                    groupId = "org.elasticsearch.plugin",
                    artifactId = "x-pack-sql-jdbc",
                    repository = "https://artifacts.elastic.co/maven"
                )
            ),
            alias("cockroachdb", "CockroachDB", "CockroachDB", "postgresql", "org.postgresql.Driver", "jdbc:postgresql:"),
            alias("greenplum", "Greenplum", "Greenplum", "postgresql", "org.postgresql.Driver", "jdbc:postgresql:"),
            alias("yugabytedb", "YugabyteDB YSQL", "YugabyteDB", "postgresql", "org.postgresql.Driver", "jdbc:postgresql:"),
            alias("timescaledb", "TimescaleDB", "TimescaleDB", "postgresql", "org.postgresql.Driver", "jdbc:postgresql:"),
            alias("citus", "Citus", "Citus", "postgresql", "org.postgresql.Driver", "jdbc:postgresql:"),
            alias("aurora-postgresql", "Amazon Aurora PostgreSQL", "Aurora PostgreSQL", "postgresql", "org.postgresql.Driver", "jdbc:postgresql:"),
            alias("tidb", "TiDB", "TiDB", "mysql", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:"),
            alias("oceanbase-mysql", "OceanBase MySQL mode", "OceanBase", "mysql", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:"),
            alias("aurora-mysql", "Amazon Aurora MySQL", "Aurora MySQL", "mysql", "com.mysql.cj.jdbc.Driver", "jdbc:mysql:"),
            alias("azure-sql", "Azure SQL Database", "Azure SQL", "sqlserver", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "jdbc:sqlserver:"),
            alias("sqlserver-localdb", "Microsoft SQL Server LocalDB", "SQL Server", "sqlserver", "sqlserver", "jdbc:sqlserver:"),
            alias("spark-thrift", "Apache Spark SQL / Thrift Server", "Spark SQL", "hive", "org.apache.hive.jdbc.HiveDriver", "jdbc:hive2:"),
            vendor("sybase-ase", "SAP/Sybase ASE", "Sybase ASE", "com.sybase.jdbc4.jdbc.SybDriver", "jdbc:sybase:Tds:", "https://help.sap.com/docs/SAP_ASE"),
            vendor("denodo", "Denodo VDP", "Denodo", "", "jdbc:vdb:", "https://www.denodo.com/en/platform/technical-documentation"),
            vendor("mimer", "Mimer SQL", "Mimer", "", "jdbc:mimer:", "https://developer.mimer.com/"),
            vendor("openedge", "Progress OpenEdge", "OpenEdge", "com.ddtek.jdbc.openedge.OpenEdgeDriver", "jdbc:datadirect:openedge:", "https://www.progress.com/openedge"),
            vendor("phoenix", "Apache Phoenix", "Phoenix", "org.apache.phoenix.jdbc.PhoenixDriver", "jdbc:phoenix:", "https://phoenix.apache.org/"),
            custom("tarantool", "Tarantool", "jdbc:tarantool:"),
            custom("couchbase", "Couchbase", "jdbc:couchbase:"),
            custom("cassandra", "Apache Cassandra", "jdbc:cassandra:"),
            custom("redis", "Redis", "jdbc:redis:"),
            custom("dynamodb", "Amazon DynamoDB", "jdbc:dynamodb:"),
            JdbcDriverDefinition(
                id = "custom-jdbc",
                displayName = "Custom JDBC",
                databaseFamily = "Custom",
                defaultJdbcUrlTemplate = "jdbc:",
                urlPrefixes = listOf("jdbc:")
            )
        )

        private fun maven(
            id: String,
            displayName: String,
            family: String,
            coordinates: String,
            driverClass: String,
            prefix: String,
            url: String,
            classifier: String? = null
        ): JdbcDriverDefinition {
            val parts = coordinates.split(':', limit = 2)
            val artifact = JdbcDriverArtifact(
                repositoryType = JdbcRepositoryType.MAVEN_CENTRAL,
                groupId = parts[0],
                artifactId = parts[1],
                classifier = classifier
            )
            return JdbcDriverDefinition(id, displayName, family, driverClass, coordinates, url, listOf(prefix), artifact)
        }

        private fun vendor(id: String, displayName: String, family: String, driverClass: String, prefix: String, page: String) =
            JdbcDriverDefinition(
                id = id,
                displayName = displayName,
                databaseFamily = family,
                driverClass = driverClass,
                defaultJdbcUrlTemplate = prefix,
                urlPrefixes = listOf(prefix),
                artifact = JdbcDriverArtifact(
                    repositoryType = JdbcRepositoryType.VENDOR_URL,
                    vendorDownloadPage = page
                )
            )

        private fun custom(id: String, displayName: String, prefix: String) =
            JdbcDriverDefinition(
                id = id,
                displayName = displayName,
                databaseFamily = displayName,
                defaultJdbcUrlTemplate = prefix,
                urlPrefixes = listOf(prefix)
            )

        private fun alias(
            id: String,
            displayName: String,
            family: String,
            provider: String,
            driverClass: String,
            prefix: String
        ) = JdbcDriverDefinition(
            id = id,
            displayName = displayName,
            databaseFamily = family,
            driverClass = driverClass,
            defaultJdbcUrlTemplate = prefix,
            urlPrefixes = listOf(prefix),
            aliasOf = provider
        )
    }
}

class JdbcDriverResolver(
    private val pluginRoot: Path = KodeDatabasePaths.jdbcPluginDir()
) {
    fun installedJars(spec: DriverSpec): List<Path> {
        spec.jarPath?.let { configured ->
            val paths = configured.split(java.io.File.pathSeparator).map { Path.of(it).toAbsolutePath().normalize() }
            val local = paths.filter(Files::isRegularFile)
            if (local.isNotEmpty()) return local
        }
        val root = driverDirectory(spec, resolvedVersion(spec))
        if (!Files.isDirectory(root)) return emptyList()
        return Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .sorted()
                .toList()
        }
    }

    fun installedJar(spec: DriverSpec): Path? = installedJars(spec).firstOrNull()

    fun installLocalJar(spec: DriverSpec, source: Path): Path {
        require(Files.isRegularFile(source)) { "Driver JAR not found: $source" }
        val version = spec.artifact?.version ?: spec.coordinates?.split(':')?.getOrNull(2) ?: "local"
        val dir = driverDirectory(spec, version)
        Files.createDirectories(dir)
        val target = dir.resolve(source.fileName.toString())
        Files.copy(source, target, StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun persistedVersion(spec: DriverSpec, artifact: JdbcDriverArtifact): String? {
        val file = driverDirectory(spec).resolve("resolved.properties")
        if (!Files.isRegularFile(file)) return null
        val properties = Properties()
        runCatching { Files.newInputStream(file).use(properties::load) }.getOrNull() ?: return null
        return properties.getProperty(artifact.coordinateLabel())
    }

    fun persistVersion(spec: DriverSpec, artifact: JdbcDriverArtifact, version: String) {
        val dir = driverDirectory(spec)
        Files.createDirectories(dir)
        val file = dir.resolve("resolved.properties")
        val properties = Properties()
        if (Files.isRegularFile(file)) Files.newInputStream(file).use(properties::load)
        properties.setProperty(artifact.coordinateLabel(), version)
        Files.newOutputStream(file).use { output -> properties.store(output, "Kode JDBC driver resolutions") }
    }

    fun driverDirectory(spec: DriverSpec, version: String? = null): Path {
        val root = pluginRoot.resolve(spec.providerId ?: spec.id)
        return version?.let(root::resolve) ?: root
    }

    private fun resolvedVersion(spec: DriverSpec): String? {
        val artifact = spec.artifact ?: artifactFromCoordinates(spec.coordinates) ?: return spec.coordinates?.split(':')?.getOrNull(2)
        return artifact.version ?: spec.coordinates?.split(':')?.getOrNull(2) ?: persistedVersion(spec, artifact)
    }
}

private data class MavenCoordinate(
    val groupId: String,
    val artifactId: String,
    val version: String,
    val classifier: String? = null
) {
    val key: String get() = "$groupId:$artifactId:$version:${classifier.orEmpty()}"
    override fun toString(): String = "$groupId:$artifactId:$version${classifier?.let { ":$it" }.orEmpty()}"
}

class JdbcDriverDownloader(
    private val resolver: JdbcDriverResolver = JdbcDriverResolver(),
    private val repositories: List<String> = listOf("https://repo.maven.apache.org/maven2")
) {
    fun download(
        spec: DriverSpec,
        progress: (JdbcDownloadProgress) -> Unit = {},
        isCancelled: () -> Boolean = { false }
    ): Path {
        val artifact = spec.artifact ?: artifactFromCoordinates(spec.coordinates)
            ?: throw IllegalArgumentException("No JDBC artifact configured for ${spec.displayName}")
        checkCancelled(isCancelled)
        return when (artifact.repositoryType) {
            JdbcRepositoryType.LOCAL_FILE -> {
                val sources = spec.jarPath
                    ?.split(java.io.File.pathSeparator)
                    ?.filter { it.isNotBlank() }
                    ?.map { Path.of(it) }
                    ?: emptyList()
                require(sources.isNotEmpty()) { "No local JDBC JAR configured for ${spec.displayName}" }
                sources.map { resolver.installLocalJar(spec, it) }.first()
            }
            JdbcRepositoryType.VENDOR_URL -> downloadVendor(spec, artifact, progress, isCancelled)
            JdbcRepositoryType.MAVEN_CENTRAL, JdbcRepositoryType.MAVEN_CUSTOM ->
                downloadMaven(spec, artifact, progress, isCancelled)
        }
    }

    private fun downloadMaven(
        spec: DriverSpec,
        artifact: JdbcDriverArtifact,
        progress: (JdbcDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ): Path {
        require(!artifact.groupId.isNullOrBlank() && !artifact.artifactId.isNullOrBlank()) {
            "Maven groupId and artifactId are required for ${spec.displayName}"
        }
        val repository = when (artifact.repositoryType) {
            JdbcRepositoryType.MAVEN_CUSTOM -> artifact.repository
            else -> null
        } ?: repositories.first()
        val version = artifact.version
            ?: resolver.persistedVersion(spec, artifact)
            ?: resolveLatestVersion(repository, artifact, progress, isCancelled)
        val root = MavenCoordinate(artifact.groupId!!, artifact.artifactId!!, version, artifact.classifier)
        val coordinates = resolveRuntimeGraph(repository, root, progress, isCancelled)
        val targetDir = resolver.driverDirectory(spec, version)
        Files.createDirectories(targetDir)
        coordinates.forEachIndexed { index, coordinate ->
            checkCancelled(isCancelled)
            val fileName = artifactFileName(coordinate.artifactId, coordinate.version, coordinate.classifier)
            downloadFile(
                url = mavenUrl(repository, coordinate, fileName),
                target = targetDir.resolve(fileName),
                artifactLabel = coordinate.toString(),
                artifactIndex = index,
                artifactCount = coordinates.size,
                progress = progress,
                isCancelled = isCancelled
            )
        }
        resolver.persistVersion(spec, artifact, version)
        return targetDir.resolve(artifactFileName(root.artifactId, root.version, root.classifier))
    }

    private fun downloadVendor(
        spec: DriverSpec,
        artifact: JdbcDriverArtifact,
        progress: (JdbcDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ): Path {
        val url = artifact.downloadUrl
            ?: throw IllegalStateException(
                "${spec.displayName} requires a vendor JAR. Download it from ${artifact.vendorDownloadPage ?: "the vendor"} and install it as a custom JDBC driver."
            )
        val version = artifact.version ?: "vendor"
        val targetDir = resolver.driverDirectory(spec, version)
        Files.createDirectories(targetDir)
        val fileName = artifact.artifactId?.let { "$it.jar" } ?: url.substringAfterLast('/').substringBefore('?').ifBlank { "driver.jar" }
        val target = targetDir.resolve(fileName)
        downloadFile(url, target, fileName, 0, 1, progress, isCancelled)
        resolver.persistVersion(spec, artifact, version)
        return target
    }

    private fun resolveLatestVersion(
        repository: String,
        artifact: JdbcDriverArtifact,
        progress: (JdbcDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ): String {
        val url = "${repository.trimEnd('/')}/${artifact.groupId!!.replace('.', '/')}/${artifact.artifactId}/maven-metadata.xml"
        progress(JdbcDownloadProgress("Resolving latest stable version", artifact.coordinateLabel()))
        val xml = readUrl(url, isCancelled)
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(xml.inputStream())
        val versions = (0 until document.getElementsByTagName("version").length)
            .asSequence()
            .mapNotNull { document.getElementsByTagName("version").item(it).textContent?.trim() }
            .filter {
                val upper = it.uppercase(Locale.ROOT)
                it.isNotBlank() && listOf("SNAPSHOT", "ALPHA", "BETA", "MILESTONE", "PREVIEW", "RC", "-EA", ".EA", "-DEV", ".DEV")
                    .none(upper::contains)
            }
            .toList()
        return versions.maxWithOrNull(versionComparator)
            ?: throw IllegalStateException("No stable version found for ${artifact.coordinateLabel()}")
    }

    private fun resolveRuntimeGraph(
        repository: String,
        root: MavenCoordinate,
        progress: (JdbcDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ): List<MavenCoordinate> {
        val visited = linkedSetOf<String>()
        val resolved = mutableListOf<MavenCoordinate>()
        val managed = mutableMapOf<String, String>()
        fun visit(coordinate: MavenCoordinate, rootPom: Boolean = false) {
            checkCancelled(isCancelled)
            if (!visited.add(coordinate.key)) return
            val pomUrl = mavenUrl(repository, coordinate, "${coordinate.artifactId}-${coordinate.version}.pom")
            val dependencies = runCatching {
                progress(JdbcDownloadProgress("Resolving dependencies", coordinate.toString()))
                parseDependencies(readUrl(pomUrl, isCancelled), coordinate, managed)
            }.getOrElse { error ->
                if (rootPom) throw IllegalStateException("Unable to resolve ${coordinate}: ${error.message}", error)
                emptyList()
            }
            dependencies.forEach(::visit)
            resolved += coordinate
        }
        visit(root, rootPom = true)
        return resolved
    }

    private fun parseDependencies(
        pom: ByteArray,
        coordinate: MavenCoordinate,
        managed: MutableMap<String, String>
    ): List<MavenCoordinate> {
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.inputStream())
        val project = document.documentElement
        val properties = mutableMapOf(
            "project.version" to coordinate.version,
            "pom.version" to coordinate.version,
            "project.groupId" to coordinate.groupId
        )
        directChildren(child(project, "properties")).forEach { property ->
            properties[property.tagName.substringAfterLast(':')] = property.textContent.trim()
        }
        val dependencyManagement = child(project, "dependencyManagement")?.let { child(it, "dependencies") }
        directChildren(dependencyManagement, "dependency").forEach { dependency ->
            val group = interpolate(text(dependency, "groupId"), properties)
            val artifact = interpolate(text(dependency, "artifactId"), properties)
            val version = interpolate(text(dependency, "version"), properties)
            if (!group.isNullOrBlank() && !artifact.isNullOrBlank() && !version.isNullOrBlank()) {
                managed["$group:$artifact"] = version
            }
        }
        val dependencies = child(project, "dependencies") ?: return emptyList()
        return directChildren(dependencies, "dependency").mapNotNull { dependency ->
            val group = interpolate(text(dependency, "groupId"), properties) ?: return@mapNotNull null
            val artifact = interpolate(text(dependency, "artifactId"), properties) ?: return@mapNotNull null
            val scope = interpolate(text(dependency, "scope"), properties) ?: "compile"
            val optional = interpolate(text(dependency, "optional"), properties).equals("true", ignoreCase = true)
            val type = interpolate(text(dependency, "type"), properties) ?: "jar"
            if (optional || scope in setOf("test", "provided", "system", "import") || type != "jar") return@mapNotNull null
            val version = interpolate(text(dependency, "version"), properties)
                ?: managed["$group:$artifact"]
                ?: return@mapNotNull null
            if (version.uppercase(Locale.ROOT).contains("SNAPSHOT")) return@mapNotNull null
            MavenCoordinate(group, artifact, version, interpolate(text(dependency, "classifier"), properties))
        }
    }

    private fun downloadFile(
        url: String,
        target: Path,
        artifactLabel: String,
        artifactIndex: Int,
        artifactCount: Int,
        progress: (JdbcDownloadProgress) -> Unit,
        isCancelled: () -> Boolean
    ) {
        if (Files.isRegularFile(target)) {
            val size = Files.size(target)
            progress(JdbcDownloadProgress("Ready", artifactLabel, size, size, artifactIndex + 1, artifactCount))
            return
        }
        val temporary = target.resolveSibling(".${target.fileName}.download")
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            val total = connection.contentLengthLong
            var downloaded = 0L
            Files.createDirectories(target.parent)
            connection.inputStream.use { input ->
                Files.newOutputStream(temporary).use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        checkCancelled(isCancelled)
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        downloaded += count
                        progress(JdbcDownloadProgress("Downloading", artifactLabel, downloaded, total, artifactIndex + 1, artifactCount))
                    }
                }
            }
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
        } finally {
            connection.disconnect()
            runCatching { Files.deleteIfExists(temporary) }
        }
    }

    private fun readUrl(url: String, isCancelled: () -> Boolean): ByteArray {
        checkCancelled(isCancelled)
        val connection = URI(url).toURL().openConnection() as HttpURLConnection
        connection.connectTimeout = 20_000
        connection.readTimeout = 30_000
        try {
            if (connection.responseCode !in 200..299) throw IllegalStateException("HTTP ${connection.responseCode}")
            return connection.inputStream.use { it.readBytes() }
        } finally {
            connection.disconnect()
        }
    }

    private fun checkCancelled(isCancelled: () -> Boolean) {
        if (isCancelled()) throw JdbcDriverDownloadCancelled()
    }

    companion object {
        private val versionComparator = Comparator<String> { left, right -> compareVersions(left, right) }

        private fun compareVersions(left: String, right: String): Int {
            val leftParts = left.split(Regex("[.-]"))
            val rightParts = right.split(Regex("[.-]"))
            val size = maxOf(leftParts.size, rightParts.size)
            for (index in 0 until size) {
                val a = leftParts.getOrNull(index).orEmpty()
                val b = rightParts.getOrNull(index).orEmpty()
                val result = when {
                    a.toLongOrNull() != null && b.toLongOrNull() != null -> a.toLong().compareTo(b.toLong())
                    a.toLongOrNull() != null -> 1
                    b.toLongOrNull() != null -> -1
                    else -> qualifierRank(a).compareTo(qualifierRank(b)).takeIf { it != 0 } ?: a.compareTo(b, ignoreCase = true)
                }
                if (result != 0) return result
            }
            return 0
        }

        private fun qualifierRank(value: String): Int = when (value.lowercase(Locale.ROOT)) {
            "snapshot" -> -5
            "alpha", "a" -> -4
            "beta", "b" -> -3
            "rc" -> -2
            "final", "ga", "release" -> 1
            else -> 0
        }
    }
}

class JdbcDriverLoader(
    private val resolver: JdbcDriverResolver = JdbcDriverResolver()
) {
    fun load(spec: DriverSpec): ResolvedJdbcDriver {
        val jars = resolver.installedJars(spec)
        if (jars.isNotEmpty()) {
            val loader = URLClassLoader(
                jars.map { it.toUri().toURL() }.toTypedArray(),
                ClassLoader.getPlatformClassLoader()
            )
            val driver = discover(loader, spec)
            if (driver != null) return ResolvedJdbcDriver(spec, driver, loader, jars.joinToString())
            loader.close()
        }
        val driver = discover(Thread.currentThread().contextClassLoader ?: JdbcDriverLoader::class.java.classLoader, spec)
            ?: throw IllegalArgumentException("Driver ${spec.driverClass.ifBlank { spec.displayName }} is not installed")
        return ResolvedJdbcDriver(spec, driver, null, "application-classpath")
    }

    private fun discover(loader: ClassLoader, spec: DriverSpec): Driver? {
        val providers = runCatching { ServiceLoader.load(Driver::class.java, loader).toList() }.getOrDefault(emptyList())
        providers.firstOrNull { spec.driverClass.isNotBlank() && it.javaClass.name == spec.driverClass }?.let { return it }
        providers.firstOrNull()?.let { return it }
        if (spec.driverClass.isBlank()) return null
        return runCatching {
            Class.forName(spec.driverClass, true, loader).getDeclaredConstructor().newInstance() as Driver
        }.getOrNull()
    }
}

private fun JdbcDriverArtifact.coordinateLabel(): String =
    listOfNotNull(groupId, artifactId, classifier?.takeIf { it.isNotBlank() }).joinToString(":")

private fun artifactFromCoordinates(coordinates: String?): JdbcDriverArtifact? {
    val parts = coordinates?.split(':') ?: return null
    if (parts.size < 2) return null
    return JdbcDriverArtifact(
        groupId = parts[0],
        artifactId = parts[1],
        version = parts.getOrNull(2),
        classifier = parts.getOrNull(3)
    )
}

private fun artifactFileName(artifactId: String, version: String, classifier: String?): String =
    "$artifactId-$version${classifier?.let { "-$it" }.orEmpty()}.jar"

private fun mavenUrl(repository: String, coordinate: MavenCoordinate, fileName: String): String =
    "${repository.trimEnd('/')}/${coordinate.groupId.replace('.', '/')}/${coordinate.artifactId}/${coordinate.version}/$fileName"

private fun child(parent: Element?, name: String): Element? =
    directChildren(parent, name).firstOrNull()

private fun directChildren(parent: Element?, name: String? = null): List<Element> {
    if (parent == null) return emptyList()
    val out = mutableListOf<Element>()
    val nodes = parent.childNodes
    for (index in 0 until nodes.length) {
        val node = nodes.item(index)
        if (node is Element && (name == null || node.tagName.substringAfterLast(':') == name)) out += node
    }
    return out
}

private fun text(parent: Element, name: String): String? =
    child(parent, name)?.textContent?.trim()?.takeIf { it.isNotEmpty() }

private fun interpolate(value: String?, properties: Map<String, String>): String? {
    var result = value?.trim()?.takeIf { it.isNotEmpty() } ?: return null
    repeat(5) {
        val next = Regex("\\$\\{([^}]+)}").replace(result) { match -> properties[match.groupValues[1]] ?: match.value }
        if (next == result) return result
        result = next
    }
    return result.takeIf { !it.contains("${'$'}{") }
}

fun sanitizeJdbcUrl(url: String): String =
    url.replace(Regex("(?i)(password|pwd)=([^;&?]+)"), "$1=****")

fun driverIdFromName(name: String): String =
    name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "generic" }
