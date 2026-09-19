package editor.app

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import editor.database.model.DataSourceDefinition
import editor.database.model.DatabaseProjectState
import editor.database.model.DatabaseSessionState
import editor.database.model.DriverSpec

class ProjectSessionManagerTest {
    @Test
    fun detectsConfigAndSelectsOnlyConventionalSourceFolders() {
        val root = createTempDirectory()
        Files.createDirectories(root.resolve("src"))
        Files.createDirectories(root.resolve("include"))
        val manager = ProjectSessionManager(root)

        assertFalse(manager.hasConfig())
        assertEquals(listOf("src", "include"), manager.defaultSourceRoots())

        manager.save(ProjectSession(sourceRoots = listOf("src", "include")))
        assertTrue(manager.hasConfig())
    }

    @Test
    fun persistsDatabaseConnectionsAndSqlSessions() {
        val root = createTempDirectory()
        val manager = ProjectSessionManager(root)
        val dataSource = DataSourceDefinition(
            id = "db-1",
            name = "local",
            driver = DriverSpec(id = "h2", driverClass = "org.h2.Driver"),
            jdbcUrl = "jdbc:h2:mem:kode"
        )
        val session = DatabaseSessionState(
            id = "db-1:1",
            dataSourceId = dataSource.id,
            title = "sql@local:1",
            buffer = "select 1;"
        )

        manager.save(ProjectSession(database = DatabaseProjectState(listOf(dataSource), listOf(session))))

        val loaded = manager.load()
        assertEquals(listOf(dataSource), loaded.database.dataSources)
        assertEquals(listOf(session), loaded.database.sessions)
    }
}
