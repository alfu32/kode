package editor.app

import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.AccessDeniedException
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

object ProjectFileScanner {
    /**
     * Evaluates gitignore rules for one path without walking the whole project.
     * File-tree status classification uses this bounded operation while opening
     * a project or expanding a folder.
     */
    fun isIgnoredPath(
        root: Path,
        path: Path,
        ignoreGlobs: List<String> = emptyList()
    ): Boolean {
        val normalizedRoot = root.toAbsolutePath().normalize()
        val target = path.toAbsolutePath().normalize()
        if (!target.startsWith(normalizedRoot) || target == normalizedRoot) return false

        var matcher = GitIgnoreMatcher.fromGlobs(loadIgnoreFile(normalizedRoot) + ignoreGlobs)
        var current = normalizedRoot
        var ignored = false
        val relativeParts = normalizedRoot.relativize(target)
        relativeParts.forEach { part ->
            current = current.resolve(part)
            matcher = matcher.withAdditionalRules(loadIgnoreFile(current))
            val relative = normalizedRoot.relativize(current).toString().replace(File.separatorChar, '/')
            matcher.ignoreDecision(relative, isDirectory = Files.isDirectory(current))?.let { ignored = it }
        }
        return ignored
    }

    fun listFilesForIndex(root: Path, ignoreGlobs: List<String>): List<Path> {
        return listFilesForIndex(root, listOf(root), ignoreGlobs)
    }

    fun listFilesForIndex(
        root: Path,
        sourceRoots: List<Path>,
        ignoreGlobs: List<String>,
        forcedIgnoreGlobs: List<String> = emptyList(),
    ): List<Path> {
        if (sourceRoots.isEmpty()) return emptyList()
        // Read the root gitignore even when the project is not a Git repository.
        // Project exclusions are matched separately so nested gitignore rules
        // cannot re-include a path selected by the user.
        val baseRules = loadIgnoreFile(root) + ignoreGlobs
        val baseMatcher = GitIgnoreMatcher.fromGlobs(baseRules)
        val forcedMatcher = GitIgnoreMatcher.fromGlobs(forcedIgnoreGlobs)
        val files = LinkedHashSet<Path>()
        sourceRoots.forEach { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@forEach
            val normalizedRoot = sourceRoot.toAbsolutePath().normalize()
            if (!normalizedRoot.startsWith(root.toAbsolutePath().normalize())) return@forEach
            val matcherStack = ArrayDeque<GitIgnoreMatcher>()
            runCatching {
                Files.walkFileTree(normalizedRoot, object : SimpleFileVisitor<Path>() {
                    override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val parentMatcher = matcherStack.peek() ?: baseMatcher
                        val mergedMatcher = parentMatcher.withAdditionalRules(loadIgnoreFile(dir))
                        matcherStack.push(mergedMatcher)
                        val rel = root.relativize(dir).toString().replace(File.separatorChar, '/')
                        // Always skip git metadata and build outputs early.
                        if (rel.startsWith(".git") || rel.startsWith("build")) {
                            matcherStack.pop()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (rel.isNotEmpty() && forcedMatcher.isIgnored(rel, isDirectory = true)) {
                            matcherStack.pop()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (rel.isNotEmpty() && mergedMatcher.isIgnored(rel, isDirectory = true)) {
                            matcherStack.pop()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        // Skip hidden directories unless explicitly un-ignored.
                        if (rel.isNotEmpty() && isHiddenPath(dir) && !mergedMatcher.isExplicitlyIncluded(rel, true)) {
                            matcherStack.pop()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        if (!Files.isReadable(dir)) {
                            matcherStack.pop()
                            return FileVisitResult.SKIP_SUBTREE
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                        val matcher = matcherStack.peek() ?: baseMatcher
                        val rel = root.relativize(path).toString().replace(File.separatorChar, '/')
                        if (forcedMatcher.isIgnored(rel, isDirectory = false)) {
                            return FileVisitResult.CONTINUE
                        }
                        if (isHiddenPath(path) && !matcher.isExplicitlyIncluded(rel, isDirectory = false)) {
                            return FileVisitResult.CONTINUE
                        }
                        if (!matcher.isIgnored(rel, isDirectory = false)) {
                            files.add(path)
                        }
                        return FileVisitResult.CONTINUE
                    }

                    override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                        return when (exc) {
                            is AccessDeniedException, is FileSystemLoopException, is NoSuchFileException -> FileVisitResult.CONTINUE
                            else -> FileVisitResult.CONTINUE
                        }
                    }

                    override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                        if (matcherStack.isNotEmpty()) matcherStack.pop()
                        return FileVisitResult.CONTINUE
                    }
                })
            }.getOrElse { }
        }
        return files.toList()
    }

    private fun isHiddenPath(path: Path): Boolean {
        val name = path.fileName?.toString().orEmpty()
        if (name.startsWith(".")) return true
        return runCatching { Files.isHidden(path) }.getOrDefault(false)
    }

    private fun loadIgnoreFile(dir: Path): List<String> {
        val file = dir.resolve(".gitignore")
        if (!Files.exists(file)) return emptyList()
        return runCatching { Files.readAllLines(file) }.getOrDefault(emptyList())
    }

    private class GitIgnoreMatcher private constructor(
        private val rules: List<IgnoreRule>
    ) {

        fun isIgnored(relPath: String, isDirectory: Boolean): Boolean {
            return ignoreDecision(relPath, isDirectory) ?: false
        }

        fun ignoreDecision(relPath: String, isDirectory: Boolean): Boolean? {
            val normalized = relPath.replace(File.separatorChar, '/')
            var decision: Boolean? = null
            rules.forEach { rule ->
                if (rule.dirOnly && !isDirectory) return@forEach
                if (rule.matches(normalized)) {
                    decision = !rule.negated
                }
            }
            return decision
        }

        fun isExplicitlyIncluded(relPath: String, isDirectory: Boolean): Boolean {
            val normalized = relPath.replace(File.separatorChar, '/')
            var included = false
            rules.forEach { rule ->
                if (rule.dirOnly && !isDirectory) return@forEach
                if (rule.matches(normalized)) {
                    included = rule.negated
                }
            }
            return included
        }

        fun withAdditionalRules(globs: List<String>): GitIgnoreMatcher {
            val extra = Companion.parse(globs)
            if (extra.isEmpty()) return this
            return GitIgnoreMatcher(rules + extra)
        }

        private data class IgnoreRule(
            val negated: Boolean,
            val dirOnly: Boolean,
            val regex: Regex
        ) {
            fun matches(path: String): Boolean = regex.matches(path)
        }

        companion object {
            fun fromGlobs(globs: List<String>) = GitIgnoreMatcher(parse(globs))

            private fun globToRegex(pattern: String, anchored: Boolean): Regex {
                val sb = StringBuilder()
                sb.append(if (anchored) "^" else "(?:^|.*/)")
                var i = 0
                while (i < pattern.length) {
                    val c = pattern[i]
                    when {
                        c == '*' && i + 1 < pattern.length && pattern[i + 1] == '*' -> {
                            sb.append(".*")
                            i++
                        }
                        c == '*' -> sb.append("[^/]*")
                        c == '?' -> sb.append("[^/]")
                        c == '/' -> sb.append("/")
                        else -> sb.append(Regex.escape(c.toString()))
                    }
                    i++
                }
                sb.append('$')
                return Regex(sb.toString())
            }

            fun parse(globs: List<String>): List<IgnoreRule> =
                globs.mapNotNull { raw ->
                    val noComment = raw.trimStart()
                    if (noComment.isEmpty() || noComment.startsWith("#")) return@mapNotNull null
                    val trimmed = raw.trim()
                    if (trimmed.isEmpty()) return@mapNotNull null
                    val negated = trimmed.startsWith("!")
                    val body = if (negated) trimmed.substring(1) else trimmed
                    val dirOnly = body.endsWith("/")
                    val pattern = body.trimEnd('/').removePrefix("/").replace('\\', '/')
                    IgnoreRule(
                        negated = negated,
                        dirOnly = dirOnly,
                        regex = globToRegex(pattern, body.startsWith("/"))
                    )
                }
        }
    }
}
