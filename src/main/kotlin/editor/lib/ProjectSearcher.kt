package editor.lib

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.FileVisitResult
import java.nio.file.attribute.BasicFileAttributes
import java.io.IOException
import kotlin.io.path.isRegularFile

data class ProjectSearchMatch(
    val filePath: String,
    val lineNumber: Int,
    val lineText: String,
    val matchRange: IntRange
)

data class ProjectSearchResult(
    val matches: List<ProjectSearchMatch>,
    val patternError: String? = null
)

class ProjectSearcher {
    fun search(root: Path, query: String, fileFilter: String?): ProjectSearchResult {
        if (query.isBlank()) return ProjectSearchResult(emptyList(), patternError = null)
        val regex = try {
            Regex(query)
        } catch (e: Exception) {
            return ProjectSearchResult(emptyList(), patternError = e.message ?: "Invalid search regex")
        }
        val filterRegex = if (fileFilter.isNullOrBlank()) null else try {
            Regex(fileFilter)
        } catch (e: Exception) {
            return ProjectSearchResult(emptyList(), patternError = e.message ?: "Invalid filename regex")
        }

        val matches = mutableListOf<ProjectSearchMatch>()
        Files.walkFileTree(root, object : SimpleFileVisitor<Path>() {
            override fun visitFile(path: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (!path.isRegularFile()) return FileVisitResult.CONTINUE
                val relative = try {
                    root.relativize(path).toString()
                } catch (_: Exception) {
                    path.fileName?.toString() ?: path.toString()
                }
                if (filterRegex != null && !filterRegex.containsMatchIn(relative)) return FileVisitResult.CONTINUE
                val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return FileVisitResult.CONTINUE
                lines.forEachIndexed { idx, line ->
                    regex.findAll(line).forEach { mr ->
                        matches.add(
                            ProjectSearchMatch(
                                filePath = path.toString(),
                                lineNumber = idx,
                                lineText = line,
                                matchRange = mr.range
                            )
                        )
                    }
                }
                return FileVisitResult.CONTINUE
            }

            override fun visitFileFailed(path: Path, exc: IOException): FileVisitResult {
                return FileVisitResult.CONTINUE
            }
        })
        return ProjectSearchResult(matches = matches, patternError = null)
    }
}
