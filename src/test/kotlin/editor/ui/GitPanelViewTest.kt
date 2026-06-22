package editor.ui

import editor.lib.GitCommitEntry
import editor.lib.GitStatusEntry
import editor.lib.IGitService
import java.nio.file.Paths
import kotlin.test.Test
import kotlin.test.assertNull
import react.StyleSheet
import react.renderer.NoopRenderer

class GitPanelViewTest {

    @Test
    fun statusFailureDoesNotCrashPanelStartupOrRender() {
        val error = runCatching {
            val view = GitPanelView(
                styleSheet = StyleSheet(),
                root = Paths.get("."),
                git = throwingStatusGitService()
            )

            view.render(NoopRenderer(cols = 80, rows = 12))
        }.exceptionOrNull()

        assertNull(error)
    }

    private fun throwingStatusGitService(): IGitService = object : IGitService {
        override fun statusPorcelain(): List<GitStatusEntry> =
            throw RuntimeException("large object exceeds size limit")

        override fun listCommits(limit: Int?): List<GitCommitEntry> = emptyList()
        override fun listBranches(): List<String> = listOf("main")
        override fun currentBranch(): String = "main"
        override fun checkoutBranch(name: String) = Unit
        override fun diff(path: String, staged: Boolean): String = ""
        override fun diffContents(path: String, staged: Boolean): Pair<String, String> = "" to ""
        override fun filesForCommit(hash: String): List<String> = emptyList()
        override fun contentAtCommit(hash: String, path: String): String? = null
        override fun stage(paths: List<String>) = Unit
        override fun unstage(paths: List<String>) = Unit
        override fun commit(message: String) = Unit
        override fun tagCommit(tag: String, commitHash: String, moveIfExists: Boolean) = Unit
    }
}
