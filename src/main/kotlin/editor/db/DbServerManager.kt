package editor.db

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import java.sql.DriverManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.ArrayDeque
import org.h2.tools.Server

data class DbStatus(
    val state: State,
    val message: String = "",
    val jarPath: Path? = null,
    val baseDir: Path? = null,
    val port: Int? = null
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
    private var currentRoot: Path? = null
    private var status: DbStatus = DbStatus(DbStatus.State.STOPPED)
    private val portCounter = AtomicInteger(9092)
    private val logBuffer = ArrayDeque<String>()
    private var currentPort: Int? = null
    private var server: Server? = null

    fun start(projectRoot: Path): DbStatus = synchronized(lock) {
        if (currentRoot == projectRoot && status.state == DbStatus.State.RUNNING && server?.isRunning(true) == true) {
            return status
        }
        stopLocked()
        val jar = resolveJar() ?: run {
            status = DbStatus(DbStatus.State.ERROR, "DB jar not found near installation")
            return status
        }
        val baseDir = projectRoot.resolve(".kode/db")
        runCatching { Files.createDirectories(baseDir) }
        status = DbStatus(DbStatus.State.STARTING, jarPath = jar, baseDir = baseDir)
        currentRoot = projectRoot
        val startResult = startServer(baseDir)
        if (startResult is DbStatus && startResult.state == DbStatus.State.ERROR) {
            status = startResult
            return status
        }
        val ready = waitForServer(currentPort ?: -1)
        status = if (ready.isSuccess) {
            DbStatus(DbStatus.State.RUNNING, jarPath = jar, baseDir = baseDir, port = currentPort)
        } else {
            val err = "${ready.exceptionOrNull()?.message ?: "Failed to prime DB"}\n${readLogs()}".trim()
            stopLocked()
            DbStatus(DbStatus.State.ERROR, err.ifBlank { "Failed to start DB (jar=$jar)" }, jar, baseDir)
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
        runCatching { server?.stop() }
        server = null
        currentPort = null
        status = status.copy(state = DbStatus.State.STOPPED, port = null)
    }

    private fun primeDatabase(port: Int): Result<Unit> = runCatching {
        Class.forName("org.h2.Driver")
        val url = "jdbc:h2:tcp://127.0.0.1:$port/kode;AUTO_RECONNECT=TRUE;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1"
        DriverManager.getConnection(url, DB_USER, DB_PASS).use { conn ->
            conn.createStatement().use { stmt ->
                stmt.execute("CREATE SCHEMA IF NOT EXISTS KODE")
                stmt.execute("CREATE TABLE IF NOT EXISTS KODE.METADATA (K VARCHAR(64) PRIMARY KEY, V VARCHAR(256))")
            }
        }
    }

    private fun waitForServer(port: Int, attempts: Int = 20, delayMs: Long = 150): Result<Unit> = runCatching {
        repeat(attempts) {
            val result = primeDatabase(port)
            if (result.isSuccess) return@runCatching
            Thread.sleep(delayMs)
        }
        throw IllegalStateException("DB did not become ready after ${attempts * delayMs}ms")
    }

    private fun appendLog(line: String) {
        synchronized(lock) {
            if (logBuffer.size >= 50) logBuffer.removeFirst()
            logBuffer.addLast(line)
            if (status.state == DbStatus.State.ERROR) {
                status = status.copy(message = readLogs())
            }
        }
    }

    private fun readLogs(): String = synchronized(lock) {
        if (logBuffer.isEmpty()) ""
        else logBuffer.joinToString("\n")
    }

    private fun startServer(baseDir: Path): DbStatus? {
        var attempts = 0
        while (attempts < 10) {
            val port = portCounter.getAndIncrement()
            val result = runCatching {
                server = Server.createTcpServer(
                    "-tcp",
                    "-tcpPort", port.toString(),
                    "-tcpAllowOthers",
                    "-ifNotExists",
                    "-baseDir", baseDir.toString()
                ).start()
                currentPort = port
            }.onFailure {
                currentPort = null
            }
            if (result.isSuccess) return null
            attempts++
        }
        return DbStatus(DbStatus.State.ERROR, "Failed to bind H2 after $attempts attempts", null, baseDir)
    }

    private fun resolveJar(): Path? {
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
            kodeHome?.resolve("h2.jar"),
            kodeHome?.resolve("db-server.jar")
        )
        return candidates.firstOrNull { Files.exists(it) && Files.isRegularFile(it) }
    }

    fun jdbcUrl(): String? = synchronized(lock) {
        val port = currentPort ?: status.port ?: return null
        if (status.state != DbStatus.State.RUNNING) return null
        "jdbc:h2:tcp://127.0.0.1:$port/kode;AUTO_RECONNECT=TRUE;DB_CLOSE_ON_EXIT=FALSE;DB_CLOSE_DELAY=-1"
    }

    companion object {
        private const val DB_USER = "sa"
        private const val DB_PASS = "sa"
    }
}
