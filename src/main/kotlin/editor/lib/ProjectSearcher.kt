package editor.lib

import java.nio.file.Files
import java.nio.file.Path
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
        Files.walk(root).use { paths ->
            paths.filter { it.isRegularFile() }.forEach { path ->
                val name = path.fileName?.toString() ?: path.toString()
                if (filterRegex != null && !filterRegex.containsMatchIn(name)) return@forEach
                val lines = runCatching { Files.readAllLines(path) }.getOrNull() ?: return@forEach
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
            }
        }
        return ProjectSearchResult(matches = matches, patternError = null)
    }
}
