package editor.lib

import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals

class ProjectFolderStatusTest {
    @Test
    fun classifiesIgnoredSourceAndExcludedFolders() {
        val root = Files.createTempDirectory("kode-folder-status")
        try {
            val source = Files.createDirectories(root.resolve("src/main"))
            val excluded = Files.createDirectories(source.resolve("generated"))
            val docs = Files.createDirectories(root.resolve("docs"))
            val ignored = Files.createDirectories(root.resolve("build/cache"))
            Files.writeString(root.resolve(".gitignore"), "build/\n")

            val classifier = ProjectFolderStatusClassifier(
                rootProvider = { root },
                sourceRootsProvider = { listOf("src") },
                exclusionsProvider = { listOf("src/main/generated") },
                ignorePatternsProvider = { emptyList() }
            )

            assertEquals(ProjectFolderStatus.IN_SOURCE, classifier.status(source.toString()))
            assertEquals(ProjectFolderStatus.EXCLUDED_SOURCE, classifier.status(excluded.toString()))
            assertEquals(ProjectFolderStatus.OUTSIDE_SOURCE, classifier.status(docs.toString()))
            assertEquals(ProjectFolderStatus.GIT_IGNORED, classifier.status(ignored.toString()))
        } finally {
            root.toFile().deleteRecursively()
        }
    }
}
