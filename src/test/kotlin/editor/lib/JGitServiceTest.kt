package editor.lib

import java.nio.file.Files
import java.nio.file.Path
import java.util.Comparator
import kotlin.test.Test
import kotlin.test.assertContains
import org.eclipse.jgit.api.Git

class JGitServiceTest {

    @Test
    fun statusHandlesBlobsAboveJgitBigFileThreshold() {
        val root = Files.createTempDirectory("kode-jgit-large-object-")
        try {
            Git.init().setDirectory(root.toFile()).call().use { git ->
                git.repository.config.setString("core", null, "bigFileThreshold", "1k")
                git.repository.config.save()

                val file = root.resolve("large.txt")
                Files.write(file, ByteArray(2 * 1024) { index -> (index % 251).toByte() })
                git.add().addFilepattern("large.txt").call()
                git.commit()
                    .setMessage("add large file")
                    .setAuthor("Test", "test@example.com")
                    .setCommitter("Test", "test@example.com")
                    .call()

                Files.write(file, ByteArray(2 * 1024) { index -> ((index + 1) % 251).toByte() })

                val status = JGitService(root.toFile()).statusPorcelain()
                assertContains(status, GitStatusEntry("M", "large.txt", staged = false))
            }
        } finally {
            deleteTree(root)
        }
    }

    private fun deleteTree(root: Path) {
        if (!Files.exists(root)) return
        Files.walk(root).use { paths ->
            paths.sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
        }
    }
}
