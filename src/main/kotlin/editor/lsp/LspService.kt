package editor.lsp

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

    fun statuses(): List<LspServerStatus> {
        val runningIds = clientsByServer.keys
        return manager.statuses().map { status ->
            if (status.entry.id in runningIds) status.copy(running = true) else status
        }
    }

    fun startServer(serverId: String): InstallResult {
        if (clientsByServer.containsKey(serverId)) return InstallResult(true, "Already running")
        val entry = manager.entryForId(serverId) ?: return InstallResult(false, "Unknown server: $serverId")
        val command = resolveCommand(entry) ?: return InstallResult(false, "No executable for ${entry.name}")
        val client = LspClient(command.cmd, command.workdir, projectRoot.toUri().toString())
        val started = client.start()
        return if (started) {
            clientsByServer[serverId] = client
            languagesByServer[serverId] = entry.languages.toSet()
            InstallResult(true, "Started ${entry.name}")
        } else {
            InstallResult(false, "Failed to start ${entry.name}")
        }
    }

    fun stopServer(serverId: String): InstallResult {
        val client = clientsByServer.remove(serverId) ?: return InstallResult(false, "Not running")
        client.stop()
        languagesByServer.remove(serverId)
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

    private fun resolveCommand(entry: LspServerEntry): LspCommand? {
        val installDir = manager.installLocation().resolve(entry.id)
        val manifest = manager.readManifest(entry.id)
        val rawCommand = manifest?.command ?: entry.preferredCommand ?: return null
        val resolvedCmd = rawCommand.mapIndexed { idx, part ->
            if (idx == 0 && !Paths.get(part).isAbsolute) {
                val candidate = installDir.resolve(part)
                if (Files.exists(candidate)) candidate.toAbsolutePath().toString() else part
            } else part
        }
        val workdir = if (Files.exists(installDir)) installDir else projectRoot
        return LspCommand(resolvedCmd, workdir)
    }

    private fun toUri(path: String): String = Paths.get(path).toUri().toString()
}

data class LspCommand(val cmd: List<String>, val workdir: Path?)
