package editor.app

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString
import kotlinx.serialization.decodeFromString
import editor.lib.BufferPersistState
import editor.lib.PositionState

@Serializable
data class ProjectSession(
    val version: Int = 2,
    val recentFiles: List<RecentFileEntry> = emptyList(),
    val openEditors: List<EditorSessionState> = emptyList(),
)

@Serializable
data class RecentFileEntry(
    val path: String,
    val lastOpenedEpochMillis: Long,
    val lastModifiedMillis: Long? = null,
    val editor: EditorSessionState? = null,
)

@Serializable
data class EditorSessionState(
    val path: String,
    val buffer: BufferPersistState,
    val scrollTop: Int = 0,
    val mime: String? = null,
    val language: String? = null,
    val lastModifiedMillis: Long? = null,
)

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
