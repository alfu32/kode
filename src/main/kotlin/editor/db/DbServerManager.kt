package editor.db

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit

data class DbStatus(
    val state: State,
    val message: String = "",
    val jarPath: Path? = null,
    val baseDir: Path? = null
) {
    enum class State { STOPPED, STARTING, RUNNING, ERROR }
}

/**
 * Manages a portable database server process per project. The database is expected to be an
 * external runnable jar (e.g., H2) launched in server mode with its data directory under the
 * project root. If the jar is missing, the manager reports ERROR but does not crash the app.
 */
class DbServerManager {
    private val lock = Any()
    private var process: Process? = null
    private var currentRoot: Path? = null
    private var status: DbStatus = DbStatus(DbStatus.State.STOPPED)

    fun start(projectRoot: Path): DbStatus = synchronized(lock) {
        if (currentRoot == projectRoot && status.state == DbStatus.State.RUNNING && process?.isAlive == true) {
            return status
        }
        stopLocked()
        val jar = resolveJar(projectRoot)
        if (jar == null) {
            status = DbStatus(DbStatus.State.ERROR, "DB jar not found", null, null)
            return status
        }
        val baseDir = projectRoot.resolve(".kode/db")
        runCatching { Files.createDirectories(baseDir) }
        status = DbStatus(DbStatus.State.STARTING, jarPath = jar, baseDir = baseDir)
        currentRoot = projectRoot
        val cmd = listOf(
            "java",
            "-jar",
            jar.toString(),
            "-tcp",
            "-tcpAllowOthers=false",
            "-baseDir",
            baseDir.toString()
        )
        process = runCatching { ProcessBuilder(cmd).start() }.getOrNull()
        val alive = process?.waitFor(200, TimeUnit.MILLISECONDS) == false || process?.isAlive == true
        status = if (alive) {
            DbStatus(DbStatus.State.RUNNING, jarPath = jar, baseDir = baseDir)
        } else {
            val err = process?.inputStream?.bufferedReader()?.readText().orEmpty() +
                process?.errorStream?.bufferedReader()?.readText().orEmpty()
            stopLocked()
            DbStatus(DbStatus.State.ERROR, err.ifBlank { "Failed to start DB" }, jar, baseDir)
        }
        return status
    }

    fun stop(): DbStatus = synchronized(lock) {
        stopLocked()
        status
    }

    fun restart(projectRoot: Path): DbStatus {
        stop()
        return start(projectRoot)
    }

    fun status(): DbStatus = synchronized(lock) { status }

    fun clearIndex(): Boolean = synchronized(lock) {
        val dir = status.baseDir ?: return false
        if (!Files.exists(dir)) return true
        runCatching {
            Files.walk(dir)
                .sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
            Files.createDirectories(dir)
        }.isSuccess
    }

    private fun stopLocked() {
        process?.destroy()
        runCatching { process?.waitFor(200, TimeUnit.MILLISECONDS) }
        process = null
        status = status.copy(state = DbStatus.State.STOPPED)
    }

    private fun resolveJar(projectRoot: Path): Path? {
        System.getProperty("kode.db.jar")?.let {
            val p = Paths.get(it)
            if (Files.exists(p)) return p
        }
        System.getenv("KODE_DB_JAR")?.let {
            val p = Paths.get(it)
            if (Files.exists(p)) return p
        }
        val kodeHome = System.getProperty("kode.home")?.let { Paths.get(it) }
            ?: System.getenv("KODE_HOME")?.let { Paths.get(it) }
            ?: runCatching {
                DbServerManager::class.java.protectionDomain.codeSource?.location?.toURI()?.let { Paths.get(it).parent }
            }.getOrNull()
        val candidates = listOfNotNull(
            projectRoot.resolve("db/db-server.jar"),
            projectRoot.resolve("codeintel/db-server.jar"),
            projectRoot.resolve("db-server.jar"),
            projectRoot.resolve("db/h2.jar"),
            projectRoot.resolve("h2.jar"),
            kodeHome?.resolve("h2.jar"),
            kodeHome?.resolve("db-server.jar"),
            Paths.get("h2.jar"),
            Paths.get("db-server.jar")
        )
        return candidates.firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
    }
}
