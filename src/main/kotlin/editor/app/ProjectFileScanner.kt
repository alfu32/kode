package editor.app

import java.io.File
import java.io.IOException
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.util.ArrayDeque

object ProjectFileScanner {
    fun listFilesForIndex(root: Path, ignoreGlobs: List<String>): List<Path> {
        return listFilesForIndex(root, listOf(root), ignoreGlobs)
    }

    fun listFilesForIndex(root: Path, sourceRoots: List<Path>, ignoreGlobs: List<String>): List<Path> {
        if (sourceRoots.isEmpty()) return emptyList()
        val baseRules = ignoreGlobs + loadIgnoreFile(root)
        val baseMatcher = GitIgnoreMatcher.fromGlobs(baseRules)
        val files = LinkedHashSet<Path>()
        sourceRoots.forEach { sourceRoot ->
            if (!Files.exists(sourceRoot)) return@forEach
            val normalizedRoot = sourceRoot.toAbsolutePath().normalize()
            if (!normalizedRoot.startsWith(root.toAbsolutePath().normalize())) return@forEach
            val matcherStack = ArrayDeque<GitIgnoreMatcher>()
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
                if (rel.isNotEmpty() && mergedMatcher.isIgnored(rel, isDirectory = true)) {
                    matcherStack.pop()
                    return FileVisitResult.SKIP_SUBTREE
                }
                // Skip hidden directories unless explicitly un-ignored.
                val name = dir.fileName?.toString().orEmpty()
                if (name.startsWith(".") && rel.isNotEmpty() && !mergedMatcher.isExplicitlyIncluded(rel, true)) {
                    matcherStack.pop()
                    return FileVisitResult.SKIP_SUBTREE
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                val matcher = matcherStack.peek() ?: baseMatcher
                val rel = root.relativize(path).toString().replace(File.separatorChar, '/')
                val name = path.fileName?.toString().orEmpty()
                if (name.startsWith(".") && !matcher.isExplicitlyIncluded(rel, isDirectory = false)) {
                    return FileVisitResult.CONTINUE
                }
                if (!matcher.isIgnored(rel, isDirectory = false)) {
                    files.add(path)
                }
                return FileVisitResult.CONTINUE
            }

            override fun postVisitDirectory(dir: Path, exc: IOException?): FileVisitResult {
                matcherStack.pop()
                return FileVisitResult.CONTINUE
            }
            })
        }
        return files.toList()
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
            val normalized = relPath.replace(File.separatorChar, '/')
            var ignored = false
            rules.forEach { rule ->
                if (rule.dirOnly && !isDirectory) return@forEach
                if (rule.matches(normalized)) {
                    ignored = !rule.negated
                }
            }
            return ignored
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
                    val pattern = body.trimEnd('/').removePrefix("/")
                    IgnoreRule(
                        negated = negated,
                        dirOnly = dirOnly,
                        regex = globToRegex(pattern, body.startsWith("/"))
                    )
                }
        }
    }
}
