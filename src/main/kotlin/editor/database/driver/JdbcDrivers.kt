package editor.database.driver

import editor.database.config.KodeDatabasePaths
import editor.database.model.DriverSpec
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Driver
import java.util.Locale

data class JdbcDriverDefinition(
    val id: String,
    val displayName: String,
    val databaseFamily: String,
    val driverClass: String,
    val defaultMavenCoordinates: String? = null,
    val defaultJdbcUrlTemplate: String = "",
    val urlPrefixes: List<String> = emptyList()
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

class JdbcDriverRegistry(
    val definitions: List<JdbcDriverDefinition> = defaultDefinitions()
) {
    fun byId(id: String): JdbcDriverDefinition? =
        definitions.firstOrNull { it.id.equals(id, ignoreCase = true) }

    fun byUrl(url: String): JdbcDriverDefinition? =
        definitions.firstOrNull { def ->
            def.urlPrefixes.any { prefix -> url.startsWith(prefix, ignoreCase = true) }
        }

    fun specFor(id: String): DriverSpec? {
        val def = byId(id) ?: return null
        return DriverSpec(
            id = def.id,
            displayName = def.displayName,
            driverClass = def.driverClass,
            coordinates = def.defaultMavenCoordinates
        )
    }

    companion object {
        fun defaultDefinitions(): List<JdbcDriverDefinition> = listOf(
            JdbcDriverDefinition("h2", "H2 JDBC", "H2", "org.h2.Driver", "com.h2database:h2:2.2.224", "jdbc:h2:mem:kode", listOf("jdbc:h2:")),
            JdbcDriverDefinition("hsqldb", "HSQLDB JDBC", "HSQLDB", "org.hsqldb.jdbc.JDBCDriver", "org.hsqldb:hsqldb:2.7.3", "jdbc:hsqldb:mem:kode", listOf("jdbc:hsqldb:")),
            JdbcDriverDefinition("sqlite", "SQLite JDBC", "SQLite", "org.sqlite.JDBC", "org.xerial:sqlite-jdbc:3.50.3.0", "jdbc:sqlite:file.db", listOf("jdbc:sqlite:")),
            JdbcDriverDefinition("postgresql", "PostgreSQL JDBC", "PostgreSQL", "org.postgresql.Driver", "org.postgresql:postgresql:42.7.8", "jdbc:postgresql://localhost:5432/postgres", listOf("jdbc:postgresql:")),
            JdbcDriverDefinition("mysql", "MySQL JDBC", "MySQL", "com.mysql.cj.jdbc.Driver", "com.mysql:mysql-connector-j:9.4.0", "jdbc:mysql://localhost:3306/mysql", listOf("jdbc:mysql:")),
            JdbcDriverDefinition("mariadb", "MariaDB JDBC", "MariaDB", "org.mariadb.jdbc.Driver", "org.mariadb.jdbc:mariadb-java-client:3.5.5", "jdbc:mariadb://localhost:3306/mysql", listOf("jdbc:mariadb:")),
            JdbcDriverDefinition("oracle", "Oracle JDBC", "Oracle", "oracle.jdbc.OracleDriver", "com.oracle.database.jdbc:ojdbc11:23.9.0.25.07", "jdbc:oracle:thin:@localhost:1521/FREEPDB1", listOf("jdbc:oracle:")),
            JdbcDriverDefinition("sqlserver", "Microsoft SQL Server JDBC", "SQL Server", "com.microsoft.sqlserver.jdbc.SQLServerDriver", "com.microsoft.sqlserver:mssql-jdbc:13.2.0.jre11", "jdbc:sqlserver://localhost:1433;databaseName=master", listOf("jdbc:sqlserver:")),
            JdbcDriverDefinition("db2", "IBM DB2 JDBC", "DB2", "com.ibm.db2.jcc.DB2Driver", "com.ibm.db2:jcc:12.1.2.0", "jdbc:db2://localhost:50000/sample", listOf("jdbc:db2:")),
            JdbcDriverDefinition("derby", "Apache Derby JDBC", "Derby", "org.apache.derby.jdbc.EmbeddedDriver", "org.apache.derby:derby:10.17.1.0", "jdbc:derby:memory:kode;create=true", listOf("jdbc:derby:")),
            JdbcDriverDefinition("generic", "Generic JDBC Driver", "Generic", "", null, "", emptyList())
        )
    }
}

class JdbcDriverResolver(
    private val pluginRoot: Path = KodeDatabasePaths.jdbcPluginDir()
) {
    fun installedJar(spec: DriverSpec): Path? {
        spec.jarPath?.takeIf { it.isNotBlank() }?.let { configured ->
            val path = Path.of(configured).toAbsolutePath().normalize()
            if (Files.isRegularFile(path)) return path
        }
        val coordinate = spec.coordinates ?: return null
        val artifact = coordinate.split(':').getOrNull(1) ?: return null
        val version = coordinate.split(':').getOrNull(2) ?: return null
        val dir = pluginRoot.resolve(spec.id).resolve(version)
        if (!Files.isDirectory(dir)) return null
        return Files.list(dir).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".jar") }
                .filter { it.fileName.toString().contains(artifact, ignoreCase = true) }
                .findFirst()
                .orElse(null)
        }
    }

    fun installLocalJar(spec: DriverSpec, source: Path): Path {
        require(Files.isRegularFile(source)) { "Driver JAR not found: $source" }
        val version = spec.coordinates?.split(':')?.getOrNull(2) ?: "local"
        val dir = pluginRoot.resolve(spec.id.ifBlank { "generic" }).resolve(version)
        Files.createDirectories(dir)
        val target = dir.resolve(source.fileName.toString())
        Files.copy(source, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
        return target
    }

    fun mavenJarPath(spec: DriverSpec): Path? {
        val parts = spec.coordinates?.split(':') ?: return null
        if (parts.size < 3) return null
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val fileName = "$artifact-$version.jar"
        return pluginRoot.resolve(spec.id).resolve(version).resolve(fileName)
    }
}

class JdbcDriverDownloader(
    private val resolver: JdbcDriverResolver = JdbcDriverResolver(),
    private val repositories: List<String> = listOf("https://repo1.maven.org/maven2")
) {
    fun download(spec: DriverSpec): Path {
        val target = resolver.mavenJarPath(spec) ?: throw IllegalArgumentException("No Maven coordinates for ${spec.id}")
        if (Files.isRegularFile(target)) return target
        val parts = requireNotNull(spec.coordinates).split(':')
        if (parts.size < 3) throw IllegalArgumentException("Invalid Maven coordinates: ${spec.coordinates}")
        val groupPath = parts[0].replace('.', '/')
        val artifact = parts[1]
        val version = parts[2]
        val fileName = "$artifact-$version.jar"
        Files.createDirectories(target.parent)
        val errors = mutableListOf<String>()
        for (repo in repositories) {
            val url = "${repo.trimEnd('/')}/$groupPath/$artifact/$version/$fileName"
            val temp = target.resolveSibling("$fileName.download")
            runCatching {
                java.net.URI(url).toURL().openStream().use { input ->
                    Files.newOutputStream(temp).use { output -> input.copyTo(output) }
                }
                Files.move(temp, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                return target
            }.onFailure { ex ->
                runCatching { Files.deleteIfExists(temp) }
                errors += "${ex::class.simpleName}: ${ex.message}"
            }
        }
        throw IllegalStateException("Unable to download ${spec.coordinates}: ${errors.joinToString("; ")}")
    }
}

class JdbcDriverLoader(
    private val resolver: JdbcDriverResolver = JdbcDriverResolver()
) {
    fun load(spec: DriverSpec): ResolvedJdbcDriver {
        require(spec.driverClass.isNotBlank()) { "Driver class is required for ${spec.displayName}" }
        resolver.installedJar(spec)?.let { jar ->
            val loader = URLClassLoader(arrayOf(jar.toUri().toURL()), null)
            val cls = Class.forName(spec.driverClass, true, loader)
            val driver = cls.getDeclaredConstructor().newInstance() as Driver
            return ResolvedJdbcDriver(spec, driver, loader, jar.toString())
        }
        val cls = Class.forName(spec.driverClass)
        val driver = cls.getDeclaredConstructor().newInstance() as Driver
        return ResolvedJdbcDriver(spec, driver, null, "application-classpath")
    }
}

fun sanitizeJdbcUrl(url: String): String =
    url.replace(Regex("(?i)(password|pwd)=([^;&?]+)"), "$1=****")

fun driverIdFromName(name: String): String =
    name.lowercase(Locale.ROOT).replace(Regex("[^a-z0-9]+"), "-").trim('-').ifBlank { "generic" }
