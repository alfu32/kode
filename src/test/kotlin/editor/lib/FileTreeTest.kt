package editor.lib

import java.nio.file.Files
import kotlin.io.path.createTempDirectory
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTreeTest {

    @Test
    fun hidesKodeMetadataFilesAndDirectory() {
        val root = createTempDirectory()
        root.resolve("Visible.kt").writeText("fun visible() = Unit")
        root.resolve(".kode.json").writeText("{}")
        Files.createDirectories(root.resolve(".kode"))
        root.resolve(".kode/hidden.txt").writeText("hidden")

        val entries = FileTree.newFileTree(root.toString()).flattened()

        assertTrue(entries.any { it.name == "Visible.kt" })
        assertFalse(entries.any { it.name == ".kode" })
        assertFalse(entries.any { it.name == ".kode.json" })
        assertFalse(entries.any { it.name == "hidden.txt" })
    }
}
