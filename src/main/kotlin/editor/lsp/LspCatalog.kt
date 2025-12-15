package editor.lsp

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermission
import java.time.Instant
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class LspServerCatalog(val servers: List<LspServerEntry> = emptyList())

@Serializable
data class LspServerEntry(
    val id: String,
    val name: String,
    val languages: List<String> = emptyList(),
    val description: String? = null,
    val homepage: String? = null,
    val license: String? = null,
    val preferredCommand: List<String>? = null,
    val versions: List<LspServerVersion> = emptyList()
)

@Serializable
data class LspServerVersion(
    val version: String,
    val downloads: List<LspServerDownload> = emptyList()
)

@Serializable
data class LspServerDownload(
    val os: String = "any",
    val arch: String = "any",
    val url: String,
    val sha256: String? = null,
    val unpackSubdir: String? = null,
    val command: List<String>? = null
)

@Serializable
data class LspInstallManifest(
    val id: String,
    val version: String,
    val installedAt: Long,
    val sourceUrl: String? = null,
    val platformOs: String? = null,
    val platformArch: String? = null,
    val command: List<String>? = null
)

data class LspServerStatus(
    val entry: LspServerEntry,
    val installState: InstallState,
    val installedVersion: String? = null,
    val installPath: Path? = null,
    val message: String? = null,
    val running: Boolean = false
)

enum class InstallState { INSTALLED, MISSING, UPDATE_AVAILABLE, MANUAL, ERROR }

data class InstallResult(val success: Boolean, val message: String)

class LspManager(
    private val catalogPath: Path? = resolveCatalogPath(),
    private val installRoot: Path = resolveInstallRoot(),
    private val platform: Platform = Platform.detect()
) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private var catalog: LspServerCatalog = loadCatalog()
    private val runningServers = mutableSetOf<String>()

    fun reloadCatalog(): List<LspServerStatus> {
        catalog = loadCatalog()
        return statuses()
    }

    fun statuses(): List<LspServerStatus> {
        val latest = latestCatalog()
        return latest.servers.map { entry -> statusFor(entry) }
    }

    fun install(serverId: String): InstallResult {
        val entry = catalog.servers.firstOrNull { it.id == serverId }
            ?: return InstallResult(false, "Unknown server: $serverId")
        val download = pickDownload(entry)
            ?: return InstallResult(false, "No download configured for this platform; install manually into ${installRoot.resolve(entry.id)}")
        val version = download.version.version
        val destDir = installRoot.resolve(entry.id)
        return runCatching {
            Files.createDirectories(destDir)
            val tempFile = Files.createTempFile("kode-lsp-${entry.id}", ".pkg")
            downloadTo(download.download.url, tempFile)
            unpack(download.download, tempFile, destDir)
            val manifest = LspInstallManifest(
                id = entry.id,
                version = version,
                installedAt = Instant.now().toEpochMilli(),
                sourceUrl = download.download.url,
                platformOs = platform.os,
                platformArch = platform.arch,
                command = download.download.command ?: entry.preferredCommand
            )
            val manifestPath = destDir.resolve("installed.json")
            Files.writeString(manifestPath, json.encodeToString(LspInstallManifest.serializer(), manifest))
            InstallResult(true, "Installed ${entry.name} $version to ${destDir.toAbsolutePath()}")
        }.getOrElse { ex ->
            InstallResult(false, "Install failed: ${ex.message ?: ex.javaClass.simpleName}")
        }
    }

    fun uninstall(serverId: String): Boolean {
        val destDir = installRoot.resolve(serverId)
        if (!Files.exists(destDir)) return false
        destDir.toFile().deleteRecursively()
        runningServers.remove(serverId)
        return true
    }

    fun catalogLocation(): Path? = catalogPath

    fun installLocation(): Path = installRoot

    fun start(serverId: String): InstallResult {
        val status = statusForId(serverId) ?: return InstallResult(false, "Unknown server: $serverId")
        if (status.installState == InstallState.MISSING || status.installState == InstallState.MANUAL) {
            return InstallResult(false, "Install ${status.entry.name} first")
        }
        runningServers += serverId
        return InstallResult(true, "Started ${status.entry.name}")
    }

    fun stop(serverId: String): InstallResult {
        val status = statusForId(serverId) ?: return InstallResult(false, "Unknown server: $serverId")
        if (!runningServers.remove(serverId)) {
            return InstallResult(false, "${status.entry.name} not running")
        }
        return InstallResult(true, "Stopped ${status.entry.name}")
    }

    fun restart(serverId: String): InstallResult {
        val stopped = stop(serverId)
        val started = start(serverId)
        val success = stopped.success && started.success
        val message = if (success) "Restarted ${statusForId(serverId)?.entry?.name ?: serverId}"
        else listOf(stopped.message, started.message).filterNotNull().joinToString("; ")
        return InstallResult(success, message)
    }

    fun isRunning(serverId: String): Boolean = runningServers.contains(serverId)

    private fun statusFor(entry: LspServerEntry): LspServerStatus {
        val destDir = installRoot.resolve(entry.id)
        val manifestPath = destDir.resolve("installed.json")
        if (!Files.exists(destDir)) {
            val nextDownload = pickDownload(entry)
            val state = if (nextDownload == null && entry.versions.isNotEmpty()) InstallState.MANUAL else InstallState.MISSING
            val message = if (state == InstallState.MANUAL) "Manual install required" else null
            return LspServerStatus(entry, state, installPath = destDir, message = message, running = isRunning(entry.id))
        }
        val manifest = runCatching {
            json.decodeFromString(LspInstallManifest.serializer(), Files.readString(manifestPath))
        }.getOrNull()
        val installedVersion = manifest?.version
        val latestVersion = entry.versions.firstOrNull()?.version
        val state = when {
            installedVersion == null -> InstallState.MANUAL
            latestVersion != null && latestVersion != installedVersion -> InstallState.UPDATE_AVAILABLE
            else -> InstallState.INSTALLED
        }
        return LspServerStatus(entry, state, installedVersion, destDir, running = isRunning(entry.id))
    }

    private fun statusForId(id: String): LspServerStatus? =
        latestCatalog().servers.firstOrNull { it.id == id }?.let { statusFor(it) }

    private fun pickDownload(entry: LspServerEntry): VersionedDownload? {
        entry.versions.forEach { version ->
            val match = version.downloads.firstOrNull { dl -> platform.matches(dl.os, dl.arch) }
            if (match != null) return VersionedDownload(version, match)
        }
        return null
    }

    private fun downloadTo(url: String, dest: Path) {
        val connection = java.net.URL(url).openConnection()
        connection.getInputStream().use { input ->
            Files.newOutputStream(dest).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun unpack(download: LspServerDownload, archive: Path, destDir: Path) {
        val lower = download.url.lowercase(Locale.ROOT)
        if (lower.endsWith(".zip")) {
            unzip(archive, destDir)
            return
        }
        if (lower.endsWith(".tar.gz") || lower.endsWith(".tgz")) {
            untar(java.util.zip.GZIPInputStream(Files.newInputStream(archive)), destDir)
            return
        }
        if (lower.endsWith(".tar")) {
            untar(Files.newInputStream(archive), destDir)
            return
        }
        // If we cannot unpack, just copy the archive into place so the user can extract manually.
        val targetName = Paths.get(download.url).fileName?.toString() ?: "package.bin"
        val target = destDir.resolve(targetName)
        Files.createDirectories(destDir)
        Files.copy(archive, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun unzip(zipPath: Path, destDir: Path) {
        Files.createDirectories(destDir)
        ZipInputStream(Files.newInputStream(zipPath)).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val resolved = destDir.resolve(entry.name).normalize()
                if (!resolved.startsWith(destDir)) {
                    entry = zis.nextEntry
                    continue
                }
                if (entry.isDirectory) {
                    Files.createDirectories(resolved)
                } else {
                    Files.createDirectories(resolved.parent)
                    Files.newOutputStream(resolved).use { out -> zis.copyTo(out) }
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun untar(input: java.io.InputStream, destDir: Path) {
        Files.createDirectories(destDir)
        val buffer = ByteArray(512)
        val stream = java.io.BufferedInputStream(input)
        while (true) {
            val read = stream.readNBytes(buffer, 0, 512)
            if (read < 512) break
            if (buffer.all { it.toInt() == 0 }) break
            val name = buffer.copyOfRange(0, 100).decodeToString().trim('\u0000', ' ')
            val sizeStr = buffer.copyOfRange(124, 136).decodeToString().trim('\u0000', ' ')
            val modeStr = buffer.copyOfRange(100, 108).decodeToString().trim('\u0000', ' ')
            val typeFlag = buffer[156].toInt().toChar()
            val size = sizeStr.toLongOrNull(8) ?: 0L
            val mode = modeStr.toIntOrNull(8) ?: 0
            val target = destDir.resolve(name).normalize()
            if (!target.startsWith(destDir)) {
                skipFully(stream, size)
                skipPadding(stream, size)
                continue
            }
            if (typeFlag == '5') {
                Files.createDirectories(target)
            } else {
                Files.createDirectories(target.parent)
                Files.newOutputStream(target).use { out ->
                    copyExact(stream, out, size)
                }
                applyPermissions(target, mode)
            }
            skipPadding(stream, size)
        }
    }

    private fun copyExact(input: java.io.InputStream, output: java.io.OutputStream, bytes: Long) {
        var remaining = bytes
        val buf = ByteArray(8192)
        while (remaining > 0) {
            val toRead = minOf(buf.size.toLong(), remaining).toInt()
            val read = input.read(buf, 0, toRead)
            if (read <= 0) break
            output.write(buf, 0, read)
            remaining -= read
        }
    }

    private fun skipFully(input: java.io.InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = input.skip(remaining)
            if (skipped <= 0) {
                if (input.read() == -1) break
                remaining--
            } else {
                remaining -= skipped
            }
        }
    }

    private fun skipPadding(input: java.io.InputStream, size: Long) {
        val padding = (512 - (size % 512)).takeIf { it in 1..511 } ?: 0
        if (padding == 0L) return
        skipFully(input, padding)
    }

    private fun applyPermissions(target: Path, mode: Int) {
        runCatching {
            if (!Files.getFileStore(target).supportsFileAttributeView("posix")) return
            val perms = mutableSetOf<PosixFilePermission>()
            val owner = (mode shr 6) and 7
            val group = (mode shr 3) and 7
            val other = mode and 7
            if (owner and 4 != 0) perms += PosixFilePermission.OWNER_READ
            if (owner and 2 != 0) perms += PosixFilePermission.OWNER_WRITE
            if (owner and 1 != 0) perms += PosixFilePermission.OWNER_EXECUTE
            if (group and 4 != 0) perms += PosixFilePermission.GROUP_READ
            if (group and 2 != 0) perms += PosixFilePermission.GROUP_WRITE
            if (group and 1 != 0) perms += PosixFilePermission.GROUP_EXECUTE
            if (other and 4 != 0) perms += PosixFilePermission.OTHERS_READ
            if (other and 2 != 0) perms += PosixFilePermission.OTHERS_WRITE
            if (other and 1 != 0) perms += PosixFilePermission.OTHERS_EXECUTE
            if (perms.isNotEmpty()) Files.setPosixFilePermissions(target, perms)
        }
    }

    private fun loadCatalog(): LspServerCatalog {
        val path = catalogPath ?: return LspServerCatalog()
        val content = runCatching { Files.readString(path) }.getOrNull() ?: return LspServerCatalog()
        return runCatching { json.decodeFromString(LspServerCatalog.serializer(), content) }
            .getOrDefault(LspServerCatalog())
    }

    private fun latestCatalog(): LspServerCatalog {
        if (catalog.servers.isEmpty()) {
            catalog = loadCatalog()
        }
        return catalog
    }

    companion object {
        private fun resolveCatalogPath(): Path? {
            val candidates = listOfNotNull(
                System.getProperty("kode.lsp.catalog")?.let { Paths.get(it) },
                System.getenv("KODE_LSP_CATALOG")?.let { Paths.get(it) },
                System.getProperty("kode.home")?.let { Paths.get(it).resolve("lsp/servers.json") },
                System.getenv("KODE_HOME")?.let { Paths.get(it).resolve("lsp/servers.json") },
                Paths.get("lsp/servers.json")
            )
            return candidates.firstOrNull { Files.exists(it) }
        }

        private fun resolveInstallRoot(): Path {
            val candidates = listOfNotNull(
                System.getProperty("kode.lsp.home")?.let { Paths.get(it) },
                System.getenv("KODE_LSP_HOME")?.let { Paths.get(it) },
                System.getProperty("kode.home")?.let { Paths.get(it).resolve("lsp") },
                System.getenv("KODE_HOME")?.let { Paths.get(it).resolve("lsp") }
            )
            val resolved = candidates.firstOrNull() ?: Paths.get("lsp")
            runCatching { Files.createDirectories(resolved) }
            return resolved
        }
    }
}

data class VersionedDownload(val version: LspServerVersion, val download: LspServerDownload)

data class Platform(val os: String, val arch: String) {
    fun matches(osValue: String, archValue: String): Boolean {
        val normalizedOs = osValue.lowercase(Locale.ROOT)
        val normalizedArch = archValue.lowercase(Locale.ROOT)
        val osMatch = normalizedOs == "any" || normalizedOs == os
        val archMatch = normalizedArch == "any" || normalizedArch == arch
        return osMatch && archMatch
    }

    companion object {
        fun detect(): Platform {
            val rawOs = System.getProperty("os.name")?.lowercase(Locale.ROOT) ?: "unknown"
            val rawArch = System.getProperty("os.arch")?.lowercase(Locale.ROOT) ?: "unknown"
            val os = when {
                rawOs.contains("win") -> "windows"
                rawOs.contains("mac") || rawOs.contains("darwin") -> "macos"
                rawOs.contains("nix") || rawOs.contains("nux") || rawOs.contains("linux") -> "linux"
                else -> rawOs.take(16)
            }
            val arch = when {
                rawArch.contains("aarch64") || rawArch.contains("arm64") -> "arm64"
                rawArch.contains("86") && rawArch.contains("64") -> "x86_64"
                rawArch.contains("86") -> "x86"
                else -> rawArch.take(16)
            }
            return Platform(os, arch)
        }
    }
}
