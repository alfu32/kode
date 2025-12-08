package editor.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString

@Serializable
data class ProjectSession(
    val version: Int = 1,
    val recentFiles: List<RecentFileEntry> = emptyList(),
    val openEditors: List<EditorSessionState> = emptyList(),
)

@Serializable
data class RecentFileEntry(
    val path: String,
    val lastOpenedEpochMillis: Long,
    val editor: EditorSessionState? = null,
)

@Serializable
data class EditorSessionState(
    val path: String,
    val text: String,
    val cursorLine: Int,
    val cursorColumn: Int,
    val selectionStart: PositionState? = null,
    val selectionEnd: PositionState? = null,
    val scrollTop: Int = 0,
    val mime: String? = null,
    val language: String? = null,
)

@Serializable
data class PositionState(val line: Int, val column: Int)

class ProjectSessionManager(
    projectRoot: String = System.getProperty("user.dir")
    ) {
    private val root: Path = Paths.get(projectRoot).toAbsolutePath().normalize()
    private val stateFile: Path = root.resolve(".kode.json")
    private val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
    }

    fun load(): ProjectSession {
        if (!Files.exists(stateFile)) return ProjectSession()
        return runCatching {
            val content = Files.readString(stateFile)
            json.decodeFromString<ProjectSession>(content)
        }.getOrElse { ProjectSession() }
    }

    fun save(session: ProjectSession) {
        val content = json.encodeToString(session)
        Files.writeString(stateFile, content)
    }

    fun toRelative(path: String): String =
        runCatching { root.relativize(Paths.get(path)).toString() }.getOrDefault(path)

    fun toAbsolute(path: String): String =
        root.resolve(path).normalize().toString()
}
