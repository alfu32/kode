package editor.database

import editor.database.config.CredentialStore
import editor.database.config.DataSourceRepository
import editor.database.model.DataSourceDefinition
import editor.database.model.DriverSpec
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull

class DataSourceRepositoryTest {
    @Test
    fun persistsDefinitionsWithoutPassword() {
        val root = Files.createTempDirectory("kode-datasources")
        val repo = DataSourceRepository(root)
        val credentials = CredentialStore(root)
        val definition = DataSourceDefinition(
            id = "local",
            name = "local-h2",
            driver = DriverSpec("h2", "H2", "org.h2.Driver", "com.h2database:h2:2.2.224"),
            jdbcUrl = "jdbc:h2:mem:test",
            username = "sa",
            credentialReference = "local.password"
        )

        repo.upsert(definition)
        credentials.put("local.password", "secret")

        val loaded = repo.load().single()
        assertEquals(definition, loaded)
        assertEquals("secret", credentials.get("local.password"))
        val raw = Files.readString(root.resolve("datasources.json"))
        assertFalse(raw.contains("secret"))
    }
}
