package editor.lib

// =============================================================
// GitService.kt
// =============================================================

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevObject
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.time.Instant

// =============================================================
// Interface + Data Classes
// =============================================================

interface IGitService {
    // ----- Read -----
    fun statusPorcelain(): List<GitStatusEntry>
    fun listCommits(limit: Int? = null): List<GitCommitEntry>
    fun listBranches(): List<String>
    fun currentBranch(): String
    fun checkoutBranch(name: String)

    // ----- Write -----
    fun stage(paths: List<String>)
    fun stage(path: String) = stage(listOf(path))
    fun unstage(paths: List<String>)
    fun unstage(path: String) = unstage(listOf(path))
    fun commit(message: String)
    fun tagCommit(
        tag: String,
        commitHash: String,
        moveIfExists: Boolean = false
    )
}

data class GitStatusEntry(
    val code: String,
    val path: String,
    val staged: Boolean = false
)

data class GitCommitEntry(
    val hash: String,
    val author: String,
    val date: Instant?,
    val message: String,
    val tag: String?
)

// =============================================================
// Semantic Tag Scoring (Version Comparison)
// =============================================================

private fun scoreSemanticTag(tag: String, slotBits: Int = 10): Long? {
    val nums = Regex("""\d+""")
        .findAll(tag)
        .map { it.value.toLongOrNull() }
        .filterNotNull()
        .toList()

    if (nums.isEmpty()) return null
    val n = nums.size

    var score = 0L
    nums.forEachIndexed { idx, value ->
        if (value >= (1L shl slotBits)) return null // does not fit in slot
        val shift = (n - 1 - idx) * slotBits
        score = score or (value shl shift)
    }
    return score
}

// =============================================================
// JGit Reference Implementation
// =============================================================

class JGitService(root: File) : IGitService {

    private lateinit var objectId: ObjectId
    private val repo = FileRepositoryBuilder()
        .setWorkTree(root)
        .setGitDir(File(root, ".git"))
        .readEnvironment()
        .setup()

    private val git = Git(repo)

    override fun statusPorcelain(): List<GitStatusEntry> {
        val st = git.status().call()
        val r = mutableListOf<GitStatusEntry>()

        st.added.forEach { r += GitStatusEntry("A", it, staged = true) }
        st.changed.forEach { r += GitStatusEntry("M", it, staged = true) }
        st.removed.forEach { r += GitStatusEntry("D", it, staged = true) }
        st.modified.forEach { r += GitStatusEntry("M", it, staged = false) }
        st.missing.forEach { r += GitStatusEntry("D", it, staged = false) }
        st.untracked.forEach { r += GitStatusEntry("??", it, staged = false) }

        return r
    }

    override fun listCommits(limit: Int?): List<GitCommitEntry> {
        val commits = runCatching {
            val call = git.log()
            if (limit != null) call.setMaxCount(limit)
            call.call().toList()
        }.getOrElse { ex ->
            return when (ex) {
                is NoHeadException, is MissingObjectException -> emptyList()
                else -> throw ex
            }
        }
        val tags = runCatching { git.tagList().call() }.getOrDefault(emptyList())

        // Map commit->tags
        val tagMap: Map<String, List<String>> = tags.mapNotNull { ref ->
            val peeled = repo.refDatabase.peel(ref)
            val obj = peeled.peeledObjectId
            obj?.name()?.let { hash -> hash to ref.name.substringAfterLast("/") }
        }.groupBy({ it.first }, { it.second })

        return commits.map {
            val hash = it.id.name

            val bestTag = tagMap[hash]
                ?.map { t -> scoreSemanticTag(t) to t }
                ?.maxByOrNull { it.first?:0 }
                ?.second

            GitCommitEntry(
                hash = hash,
                author = it.authorIdent.name,
                date = it.authorIdent.whenAsInstant,
                message = it.fullMessage.trim(),
                tag = bestTag
            )
        }
    }

    override fun listBranches(): List<String> =
        runCatching { git.branchList().call().map { it.name.substringAfterLast("/") } }
            .getOrDefault(emptyList())

    override fun currentBranch(): String =
        runCatching { repo.fullBranch ?: "(no branch)" }.getOrDefault("(no branch)")

    override fun checkoutBranch(name: String) {
        git.checkout().setName(name).call()
    }

    override fun stage(paths: List<String>) {
        val add = git.add()
        paths.forEach { add.addFilepattern(it) }
        add.call()
    }

    override fun unstage(paths: List<String>) {
        val reset = git.reset()
        paths.forEach { reset.addPath(it) }
        reset.call()
    }

    override fun commit(message: String) {
        git.commit().setMessage(message).call()
    }

    override fun tagCommit(tag: String, commitHash: String, moveIfExists: Boolean) {
        val existing = git.tagList().call().firstOrNull {
            it.name.endsWith("/$tag")
        }

        if (existing != null && moveIfExists) {
            git.tagDelete().setTags(tag).call()
        } else if (existing != null) {
            throw IllegalStateException("tag already exists: $tag")
        }

        git.tag()
            .setName(tag)
            .setObjectId(repo.resolve(commitHash) as RevObject?)
            .call()
    }
}

private fun FileRepositoryBuilder.setup(): org.eclipse.jgit.lib.Repository {
    val dir = gitDir
    if (dir == null || !dir.exists()) {
        throw IllegalStateException("Not a git repository at ${workTree?.path}")
    }
    return build()
}
