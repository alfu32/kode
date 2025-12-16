package editor.app

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
