package editor.lsp

import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.Locale

class LspService(
    private val manager: LspManager,
    private val projectRoot: Path
) {

    private val clientsByServer = mutableMapOf<String, LspClient>()
    private val languagesByServer = mutableMapOf<String, Set<String>>()
    private val lastMessages = mutableMapOf<String, String?>()

    fun statuses(): List<LspServerStatus> {
        val runningIds = clientsByServer.keys
        return manager.statuses().map { status ->
            val msg = lastMessages[status.entry.id] ?: status.message
            if (status.entry.id in runningIds) status.copy(running = true, message = msg) else status.copy(message = msg)
        }
    }

    fun startServer(serverId: String): InstallResult {
        if (clientsByServer.containsKey(serverId)) return InstallResult(true, "Already running")
        val entry = manager.entryForId(serverId) ?: return InstallResult(false, "Unknown server: $serverId")
        val command = resolveCommand(entry)
        if (command.error != null) {
            lastMessages[serverId] = command.error
            return InstallResult(false, command.error)
        }
        val resolved = command.command ?: return InstallResult(false, "No executable for ${entry.name}")
        val client = LspClient(resolved.cmd, resolved.workdir, projectRoot.toUri().toString())
        val started = client.start()
        return if (started.success) {
            clientsByServer[serverId] = client
            languagesByServer[serverId] = entry.languages.toSet()
            lastMessages[serverId] = null
            InstallResult(true, "Started ${entry.name}")
        } else {
            val msg = started.message ?: "Failed to start ${entry.name}"
            lastMessages[serverId] = msg
            InstallResult(false, msg)
        }
    }

    fun stopServer(serverId: String): InstallResult {
        val client = clientsByServer.remove(serverId) ?: return InstallResult(false, "Not running")
        client.stop()
        languagesByServer.remove(serverId)
        lastMessages[serverId] = null
        return InstallResult(true, "Stopped $serverId")
    }

    fun restartServer(serverId: String): InstallResult {
        stopServer(serverId)
        return startServer(serverId)
    }

    fun startForLanguage(language: String): Boolean {
        if (clientForLanguage(language) != null) return true
        val entry = manager.statuses().firstOrNull { it.entry.languages.any { lang -> lang.equals(language, true) } }
            ?: return false
        return startServer(entry.entry.id).success
    }

    fun openDocument(path: String, languageId: String?, text: String, version: Int) {
        val lang = languageId ?: return
        val client = clientForLanguage(lang) ?: return
        client.openDocument(toUri(path), lang.lowercase(Locale.ROOT), text, version)
    }

    fun changeDocument(path: String, text: String, version: Int) {
        clientsByServer.values.forEach { client ->
            client.changeDocument(toUri(path), text, version)
        }
    }

    fun closeDocument(path: String) {
        clientsByServer.values.forEach { client -> client.closeDocument(toUri(path)) }
    }

    fun definitions(path: String, languageId: String?, position: LspPosition): List<LspLocation> {
        val lang = languageId ?: return emptyList()
        val client = clientForLanguage(lang) ?: return emptyList()
        return client.definitions(toUri(path), position)
    }

    fun references(path: String, languageId: String?, position: LspPosition): List<LspLocation> {
        val lang = languageId ?: return emptyList()
        val client = clientForLanguage(lang) ?: return emptyList()
        return client.references(toUri(path), position)
    }

    fun completions(path: String, languageId: String?, position: LspPosition): List<String> {
        val lang = languageId ?: return emptyList()
        val client = clientForLanguage(lang) ?: return emptyList()
        return client.completions(toUri(path), position)
    }

    private fun clientForLanguage(language: String): LspClient? {
        val match = languagesByServer.entries.firstOrNull { (_, langs) -> langs.any { it.equals(language, true) } }
        return match?.let { clientsByServer[it.key] }
    }

    private fun resolveCommand(entry: LspServerEntry): CommandResolution {
        val installRoot = manager.installLocation()
        val installDir = installRoot.resolve(entry.id)
        val manifest = manager.readManifest(entry.id)
        val rawCommand = manifest?.command ?: entry.preferredCommand ?: return CommandResolution(
            command = null,
            error = "No command configured for ${entry.name}"
        )
        if (rawCommand.isEmpty()) return CommandResolution(null, "No command configured for ${entry.name}")

        val executable = rawCommand.first()
        val resolvedExecutable = resolveExecutable(executable, installDir, installRoot) ?: findOnPath(executable)
        if (resolvedExecutable == null && Paths.get(executable).isAbsolute) {
            return CommandResolution(null, "Executable $executable not found")
        }
        if (resolvedExecutable == null) {
            val installHint = installDir.resolve("installed.json").toAbsolutePath()
            return CommandResolution(
                null,
                "Executable $executable not found. Install ${entry.name} or update $installHint"
            )
        }
        val workdir = if (Files.exists(installDir)) installDir else installRoot
        val finalCommand = listOf(resolvedExecutable.toString()) + rawCommand.drop(1)
        return CommandResolution(LspCommand(finalCommand, workdir))
    }

    private fun resolveExecutable(name: String, installDir: Path, installRoot: Path): Path? {
        val path = Paths.get(name)
        if (path.isAbsolute) return executableIfPresent(path)
        val inInstallDir = executableIfPresent(installDir.resolve(name))
        if (inInstallDir != null) return inInstallDir
        val inRoot = executableIfPresent(installRoot.resolve(name))
        if (inRoot != null) return inRoot
        return null
    }

    private fun executableIfPresent(path: Path): Path? {
        if (!Files.exists(path)) return null
        if (!Files.isRegularFile(path)) return null
        if (Files.isExecutable(path)) return path.toAbsolutePath()
        runCatching {
            val store = Files.getFileStore(path)
            if (store.supportsFileAttributeView("posix")) {
                val perms = mutableSetOf(
                    java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                    java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                    java.nio.file.attribute.PosixFilePermission.OWNER_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.GROUP_READ,
                    java.nio.file.attribute.PosixFilePermission.GROUP_EXECUTE,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_READ,
                    java.nio.file.attribute.PosixFilePermission.OTHERS_EXECUTE
                )
                Files.setPosixFilePermissions(path, perms)
            }
        }
        return if (Files.isExecutable(path)) path.toAbsolutePath() else null
    }

    private fun findOnPath(executable: String): Path? {
        val pathVar = System.getenv("PATH") ?: return null
        return pathVar.split(File.pathSeparator).asSequence()
            .map { Paths.get(it).resolve(executable) }
            .firstOrNull { Files.isExecutable(it) }
    }

    private fun toUri(path: String): String = Paths.get(path).toUri().toString()
}

data class LspCommand(val cmd: List<String>, val workdir: Path?)

data class CommandResolution(val command: LspCommand?, val error: String? = null)

