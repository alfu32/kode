package editor.database

import editor.database.driver.JdbcDriverRegistry
import editor.database.driver.JdbcUrlRecentRepository
import editor.database.driver.JdbcUrlTemplateRepository
import editor.database.driver.JdbcUrlSuggestionService
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class JdbcUrlTemplatesTest {
    @Test
    fun catalogCoversEveryBuiltInDriverAndResolvesAliases() {
        val registry = JdbcDriverRegistry()
        val repository = JdbcUrlTemplateRepository(registry = registry)

        repository.validate()
        assertEquals(
            repository.templates("postgresql").map { it.id },
            repository.templates("cockroachdb").map { it.id }
        )
        assertTrue(repository.templates("azure-sql").first().id == "azure")
        assertTrue(repository.templates("sqlite").none { it.template.contains("//") })
        assertTrue(repository.templates("tarantool").isEmpty())
    }

    @Test
    fun recentsAreMruBoundedExactAndPersistedByFamily() {
        val root = Files.createTempDirectory("kode-jdbc-recents")
        val repository = JdbcUrlRecentRepository(root)

        assertTrue(repository.remember("postgresql", "  jdbc:postgresql://one/db  "))
        assertTrue(repository.remember("postgresql", "jdbc:postgresql://two/db"))
        assertTrue(repository.remember("postgresql", " jdbc:postgresql://one/db "))
        repeat(20) { index -> repository.remember("postgresql", "jdbc:postgresql://host$index/db") }

        val loaded = JdbcUrlRecentRepository(root).load("postgresql")
        assertEquals(12, loaded.size)
        assertEquals("jdbc:postgresql://host19/db", loaded.first())
        assertFalse(loaded.contains("jdbc:postgresql://one/db"))
        assertTrue(JdbcUrlRecentRepository(root).load("mysql").isEmpty())
    }

    @Test
    fun sensitiveUrlsAreRejectedButSafeParametersAreRemembered() {
        val root = Files.createTempDirectory("kode-jdbc-security")
        val repository = JdbcUrlRecentRepository(root)

        assertFalse(repository.remember("postgresql", "jdbc:postgresql://user:password@host/db"))
        assertFalse(repository.remember("postgresql", "jdbc:postgresql://host/db?PWD=secret"))
        assertFalse(repository.remember("postgresql", "jdbc:postgresql://host/db;OAuthClientSecret=secret"))
        assertTrue(repository.remember("postgresql", "jdbc:postgresql://host/db?applicationName=kode&sslmode=require"))
        assertEquals(1, repository.load("postgresql").size)
    }

    @Test
    fun aliasesAndSparkShareRecentFamilies() {
        val registry = JdbcDriverRegistry()
        val templateRepository = JdbcUrlTemplateRepository(registry = registry)
        val recentRepository = JdbcUrlRecentRepository(Files.createTempDirectory("kode-jdbc-families"))
        val service = JdbcUrlSuggestionService(registry, templateRepository, recentRepository)

        service.remember("postgresql", "jdbc:postgresql://shared/db")
        service.remember("hive", "jdbc:hive2://shared:10000/db")

        assertEquals("jdbc:postgresql://shared/db", service.forDriver("cockroachdb").recents.single())
        assertEquals("jdbc:hive2://shared:10000/db", service.forDriver("spark-thrift").recents.single())
    }
}
