package editor.lib

// =============================================================
// GitService.kt
// =============================================================

import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.revwalk.RevObject
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.api.errors.NoHeadException
import org.eclipse.jgit.errors.MissingObjectException
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.eclipse.jgit.dircache.DirCache
import org.eclipse.jgit.treewalk.TreeWalk
import org.eclipse.jgit.treewalk.filter.PathFilter
import java.io.File
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.nio.charset.StandardCharsets

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
    fun diff(path: String, staged: Boolean = false): String
    fun diffContents(path: String, staged: Boolean = false): Pair<String, String>

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
        .build()

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
            val hash = obj?.name() ?: return@mapNotNull null
            hash to ref.name.substringAfterLast("/")
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

    override fun diff(path: String, staged: Boolean): String {
        val entries: List<DiffEntry> = git.diff()
            .setPathFilter(PathFilter.create(path))
            .setCached(staged)
            .call()
        val output = ByteArrayOutputStream()
        DiffFormatter(output).use { fmt ->
            fmt.setRepository(repo)
            fmt.isDetectRenames = true
            fmt.format(entries)
        }
        val rendered = output.toString(StandardCharsets.UTF_8)
        if (rendered.isNotBlank()) return rendered
        val file = File(repo.workTree, path)
        if (file.exists()) {
            val content = runCatching { file.readText() }.getOrDefault("")
            return buildString {
                appendLine("--- /dev/null")
                appendLine("+++ b/$path")
                content.lineSequence().forEach { appendLine("+$it") }
            }.trimEnd()
        }
        return "No diff available for $path"
    }

    override fun diffContents(path: String, staged: Boolean): Pair<String, String> {
        val oldText = if (staged) loadFromHead(path) else loadFromIndex(path)
        val newText = if (staged) loadFromIndex(path) else loadFromWorkingTree(path)
        return (oldText ?: "") to (newText ?: "")
    }

    private fun loadFromHead(path: String): String? {
        val headId = repo.resolve("HEAD^{tree}") ?: return null
        val tw = TreeWalk(repo)
        tw.addTree(headId)
        tw.isRecursive = true
        tw.filter = PathFilter.create(path)
        return if (tw.next()) {
            val objectId = tw.getObjectId(0)
            repo.open(objectId).getCachedBytes(Int.MAX_VALUE).toString(StandardCharsets.UTF_8)
        } else null
    }

    private fun loadFromIndex(path: String): String? {
        val dirCache: DirCache = repo.readDirCache()
        val entry = dirCache.getEntry(path) ?: return null
        val objectId = entry.objectId ?: return null
        return repo.open(objectId).getCachedBytes(Int.MAX_VALUE).toString(StandardCharsets.UTF_8)
    }

    private fun loadFromWorkingTree(path: String): String? {
        val file = File(repo.workTree, path)
        return runCatching { file.readText() }.getOrNull()
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

        val objId = repo.resolve(commitHash) ?: throw IllegalArgumentException("unknown commit: $commitHash")
        val revObj = RevWalk(repo).use { walk ->
            val any = walk.parseAny(objId)
            any as? RevObject ?: throw IllegalArgumentException("unable to resolve object for $commitHash")
        }

        git.tag()
            .setName(tag)
            .setObjectId(revObj)
            .call()
    }
}
