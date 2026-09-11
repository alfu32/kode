package editor.app

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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
}
