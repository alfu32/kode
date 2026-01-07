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
    val version: Int = 4,
    val recentFiles: List<RecentFileEntry> = emptyList(),
    val openEditors: List<EditorSessionState> = emptyList(),
    val sourceRoots: List<String> = emptyList(),
)

@Serializable
data class RecentFileEntry(
    val path: String,
    val lastOpenedEpochMillis: Long,
    val lastModifiedMillis: Long? = null,
    val editor: EditorSessionState? = null,
    val dirty: Boolean = false,
    val viewerType: ViewerType = ViewerType.CODE
)

@Serializable
data class EditorSessionState(
    val path: String,
    val buffer: BufferPersistState,
    val scrollTop: Int = 0,
    val mime: String? = null,
    val language: String? = null,
    val grammarLanguage: String? = null,
    val grammarAvailable: Boolean = false,
    val lastModifiedMillis: Long? = null,
)

@Serializable
enum class ViewerType { CODE, HEX, IMAGE }

class ProjectSessionManager(
    projectRoot: Path = Paths.get(System.getProperty("user.dir"))
    ) {
    private val root: Path = projectRoot.toAbsolutePath().normalize()
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
