package editor.lib

import editor.app.ProjectFileScanner
import java.nio.file.Files
import java.nio.file.Path

/** Visual classification used by the project file tree. */
enum class ProjectFolderStatus {
    GIT_IGNORED,
    IN_SOURCE,
    OUTSIDE_SOURCE,
    EXCLUDED_SOURCE
}

/**
 * Resolves folder status from the live project configuration. The provider
 * lambdas make source roots, exclusions, and git changes visible without
 * rebuilding the file-tree component.
 */
class ProjectFolderStatusClassifier(
    private val rootProvider: () -> Path,
    private val sourceRootsProvider: () -> List<String>,
    private val exclusionsProvider: () -> List<String>,
    private val ignorePatternsProvider: () -> List<String>
) {
    fun status(path: String): ProjectFolderStatus? {
        val root = rootProvider().toAbsolutePath().normalize()
        val target = Path.of(path).toAbsolutePath().normalize()
        if (!target.startsWith(root) || !Files.isDirectory(target)) return null

        val inSource = configuredPaths(root, sourceRootsProvider()).any(target::startsWith)
        val excluded = inSource && configuredPaths(root, exclusionsProvider()).any(target::startsWith)
        if (excluded) return ProjectFolderStatus.EXCLUDED_SOURCE
        if (ProjectFileScanner.isIgnoredPath(root, target, ignorePatternsProvider())) {
            return ProjectFolderStatus.GIT_IGNORED
        }
        return if (inSource) ProjectFolderStatus.IN_SOURCE else ProjectFolderStatus.OUTSIDE_SOURCE
    }

    private fun configuredPaths(root: Path, values: List<String>): List<Path> = values.mapNotNull { raw ->
        val value = raw.trim()
        if (value.isEmpty()) return@mapNotNull null
        runCatching {
            val path = Path.of(value)
            (if (path.isAbsolute) path else root.resolve(path)).normalize()
        }.getOrNull()
    }
}
