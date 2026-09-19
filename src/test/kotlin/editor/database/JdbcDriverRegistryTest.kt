package editor.database

import editor.database.driver.JdbcDriverLoader
import editor.database.driver.JdbcDriverRegistry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class JdbcDriverRegistryTest {
    @Test
    fun resolvesKnownDriverByUrl() {
        val registry = JdbcDriverRegistry()

        val driver = registry.byUrl("jdbc:h2:mem:kode")

        assertEquals("h2", driver?.id)
    }

    @Test
    fun loadsVisibleH2DriverThroughDriverAbstraction() {
        val spec = JdbcDriverRegistry().specFor("h2")!!

        JdbcDriverLoader().load(spec).use { loaded ->
            assertTrue(loaded.driver.acceptsURL("jdbc:h2:mem:kode"))
        }
    }

    @Test
    fun resolvesAliasesToOneSharedDriverArtifact() {
        val registry = JdbcDriverRegistry()

        val alias = registry.specFor("cockroachdb")!!

        assertEquals("postgresql", alias.providerId)
        assertEquals("org.postgresql", alias.artifact?.groupId)
        assertEquals("postgresql", alias.artifact?.artifactId)
        assertEquals(null, alias.artifact?.version)
    }

    @Test
    fun exposesCustomJdbcSourceWithoutInventingAnArtifact() {
        val spec = JdbcDriverRegistry().specFor("custom-jdbc")!!

        assertEquals(null, spec.artifact)
        assertEquals("custom-jdbc", spec.providerId)
    }
}
