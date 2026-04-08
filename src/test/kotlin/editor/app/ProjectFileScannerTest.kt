package editor.app

import java.nio.file.Files
import java.nio.file.FileSystems
import java.nio.file.attribute.PosixFilePermission
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProjectFileScannerTest {

    @Test
    fun skipsDotGitAndIgnoredGlobs() {
        val root = createTempDirectory()
        val gitDir = root.resolve(".git")
        Files.createDirectories(gitDir)
        val buildDir = root.resolve("build")
        Files.createDirectories(buildDir)
        val keep = root.resolve("src/Main.kt").also {
            Files.createDirectories(it.parent)
            it.writeText("fun main() {}")
        }
        val ignored = root.resolve("tmp/generated/File.kt").also {
            Files.createDirectories(it.parent)
            it.writeText("ignored")
        }

        val files = ProjectFileScanner.listFilesForIndex(
            root,
            ignoreGlobs = listOf("tmp/**")
        )

        assertEquals(listOf(keep), files)
    }

    @Test
    fun skipsUnreadableDirectories() {
        if (!FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) return

        val root = createTempDirectory()
        val keep = root.resolve("src/Main.kt").also {
            Files.createDirectories(it.parent)
            it.writeText("fun main() {}")
        }
        val blockedDir = root.resolve("blocked")
        Files.createDirectories(blockedDir)
        blockedDir.resolve("Secret.kt").writeText("private")

        val originalPermissions = Files.getPosixFilePermissions(blockedDir)
        try {
            Files.setPosixFilePermissions(blockedDir, setOf(PosixFilePermission.OWNER_WRITE))

            val files = ProjectFileScanner.listFilesForIndex(root, ignoreGlobs = emptyList())

            assertTrue(keep in files)
            assertTrue(files.none { it.startsWith(blockedDir) })
        } finally {
            Files.setPosixFilePermissions(blockedDir, originalPermissions)
        }
    }
}
