package org.github.alfu32.ktx.lib

// =============================================================
// GitService.kt
// =============================================================

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevObject
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import java.io.File
import java.time.Instant

// =============================================================
// Interface + Data Classes
// =============================================================

interface GitService {
    // ----- Read -----
    fun statusPorcelain(): List<GitStatusEntry>
    fun listCommits(limit: Int? = null): List<GitCommitEntry>
    fun listBranches(): List<String>
    fun currentBranch(): String
    fun checkoutBranch(name: String)

    // ----- Write -----
    fun stage(paths: List<String>)
    fun stage(path: String) = stage(listOf(path))
    fun commit(message: String)
    fun tagCommit(
        tag: String,
        commitHash: String,
        moveIfExists: Boolean = false
    )
}

data class GitStatusEntry(
    val code: String,
    val path: String
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

class JGitService(root: File) : GitService {

    private lateinit var objectId: ObjectId
    private val repo = FileRepositoryBuilder()
        .setWorkTree(root)
        .readEnvironment()
        .findGitDir(root)
        .build()

    private val git = Git(repo)

    override fun statusPorcelain(): List<GitStatusEntry> {
        val st = git.status().call()
        val r = mutableListOf<GitStatusEntry>()

        st.added.forEach { r += GitStatusEntry("A", it) }
        st.changed.forEach { r += GitStatusEntry("M", it) }
        st.modified.forEach { r += GitStatusEntry("M", it) }
        st.removed.forEach { r += GitStatusEntry("D", it) }
        st.missing.forEach { r += GitStatusEntry("D", it) }
        st.untracked.forEach { r += GitStatusEntry("??", it) }

        return r
    }

    override fun listCommits(limit: Int?): List<GitCommitEntry> {
        val call = git.log()
        if (limit != null) call.setMaxCount(limit)

        val commits = call.call()
        val tags = git.tagList().call()

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
        git.branchList().call().map { it.name.substringAfterLast("/") }

    override fun currentBranch(): String =
        repo.branch

    override fun checkoutBranch(name: String) {
        git.checkout().setName(name).call()
    }

    override fun stage(paths: List<String>) {
        val add = git.add()
        paths.forEach { add.addFilepattern(it) }
        add.call()
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