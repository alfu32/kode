package editor.db

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class DbServerManagerTest {
    private val manager = DbServerManager()
    private var oldProp: String? = null
    private var oldMode: String? = null

    @BeforeTest
    fun setUp() {
        oldProp = System.getProperty("kode.db.jar")
        oldMode = System.getProperty("kode.db.mode")
        val driverJar = Class.forName("org.h2.Driver").protectionDomain.codeSource?.location
        if (driverJar != null) {
            System.setProperty("kode.db.jar", java.nio.file.Paths.get(driverJar.toURI()).toString())
        }
    }

    @AfterTest
    fun tearDown() {
        if (oldProp != null) {
            System.setProperty("kode.db.jar", oldProp)
        } else {
            System.clearProperty("kode.db.jar")
        }
        if (oldMode != null) {
            System.setProperty("kode.db.mode", oldMode)
        } else {
            System.clearProperty("kode.db.mode")
        }
        manager.stop()
    }

    @Test
    fun startsAndCreatesDatabaseFiles() {
        val root = createTempDirectory()
        val status = manager.start(root)
        println("DB start status=${status.state} message=${status.message}")
        assertEquals(DbStatus.State.RUNNING, status.state, status.message)
        val dbFile = root.resolve(".kode/db/kode.mv.db")
        // wait briefly for file to materialize
        repeat(10) {
            if (dbFile.exists()) return@repeat
            Thread.sleep(100)
        }
        assertTrue(Files.exists(dbFile), "expected database file at $dbFile")
        manager.stop()
    }

    @Test
    fun supportsExplicitInMemoryMode() {
        System.setProperty("kode.db.mode", "memory")
        val root = createTempDirectory()

        val status = manager.start(root)

        assertEquals(DbStatus.State.RUNNING, status.state, status.message)
        assertTrue(manager.wasFreshStart())
        assertTrue(manager.jdbcUrl()?.contains("/mem:kode") == true)
        assertTrue(!Files.exists(root.resolve(".kode/db/kode.mv.db")))
    }
}
