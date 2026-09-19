package editor.database

import editor.database.metadata.MetadataCache
import editor.database.model.DatabaseObject
import editor.database.model.DatabaseObjectType
import editor.database.model.MetadataRequest
import editor.database.model.MetadataRequestKind
import editor.database.model.MetadataResult
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class MetadataCacheTest {
    @Test
    fun invalidatesIndividualRequestsAndDataSources() {
        val cache = MetadataCache()
        val root = MetadataRequest(MetadataRequestKind.ROOT)
        val schema = MetadataRequest(MetadataRequestKind.SCHEMA, schema = "PUBLIC")
        val result = MetadataResult(
            listOf(
                DatabaseObject(
                    id = "schema:PUBLIC",
                    name = "PUBLIC",
                    objectType = DatabaseObjectType.SCHEMA
                )
            )
        )

        cache.put("local", root, result)
        cache.put("local", schema, result)
        cache.invalidate("local", root)

        assertNull(cache.get("local", root))
        assertEquals(result, cache.get("local", schema))

        cache.invalidateDataSource("local")

        assertNull(cache.get("local", schema))
    }
}
