package editor.app

import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.NativeLibrary
import com.sun.jna.Pointer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.util.Locale

internal data class TtydLibrary(
    val path: Path,
    val assetName: String
)

internal object TtydAdapter {
    private const val RESOURCE_ROOT = "/native/ttyd"

    fun resolveLibrary(): TtydLibrary? {
        val asset = assetNameForCurrentPlatform() ?: return null
        return extractBundledLibrary(asset)
    }

    fun describePlatform(
        osName: String = System.getProperty("os.name").orEmpty(),
        archName: String = System.getProperty("os.arch").orEmpty()
    ): String = "${normalizeOs(osName)}/${normalizeArch(archName)}"

    fun assetNameForCurrentPlatform(): String? =
        assetNameFor(System.getProperty("os.name").orEmpty())

    internal fun assetNameFor(osName: String): String? =
        when (normalizeOs(osName)) {
            "linux" -> "ttyd.linux.so"
            "macos" -> "ttyd.macos.dylib"
            "windows" -> "ttyd.msvc.dll"
            else -> null
        }

    fun invokeMain(library: TtydLibrary, argv: List<String>): Int {
        val nativeLibrary = NativeLibrary.getInstance(library.path.toString())
        val main = nativeLibrary.getFunction("main", Function.C_CONVENTION)
        val nativeArgs = argv.map { toNativeCString(it) }
        val argvMemory = Memory(((nativeArgs.size + 1) * Native.POINTER_SIZE).toLong())
        nativeArgs.forEachIndexed { index, nativeString ->
            argvMemory.setPointer((index * Native.POINTER_SIZE).toLong(), nativeString)
        }
        argvMemory.setPointer((nativeArgs.size * Native.POINTER_SIZE).toLong(), Pointer.NULL)
        return main.invokeInt(arrayOf(nativeArgs.size, argvMemory))
    }

    private fun toNativeCString(value: String): Memory {
        val bytes = value.toByteArray(StandardCharsets.UTF_8)
        val memory = Memory((bytes.size + 1).toLong())
        memory.write(0, bytes, 0, bytes.size)
        memory.setByte(bytes.size.toLong(), 0)
        return memory
    }

    private fun extractBundledLibrary(asset: String): TtydLibrary? {
        val resource = "$RESOURCE_ROOT/$asset"
        val bytes = TtydAdapter::class.java.getResourceAsStream(resource)?.use { it.readBytes() } ?: return null
        val hash = sha256(bytes).take(16)
        for (root in resolveCacheRoots()) {
            val cacheDir = root.resolve("kode").resolve("ttyd-lib").resolve(hash)
            val target = cacheDir.resolve(asset)
            runCatching {
                Files.createDirectories(cacheDir)
                if (!Files.exists(target) || Files.size(target) != bytes.size.toLong()) {
                    Files.write(target, bytes)
                }
                target.toFile().setReadable(true, false)
            }.onSuccess {
                return TtydLibrary(target, asset)
            }
        }
        return null
    }

    private fun resolveCacheRoots(): List<Path> {
        val roots = linkedSetOf<Path>()
        System.getenv("KODE_TTYD_CACHE")?.takeIf { it.isNotBlank() }?.let {
            roots.add(Paths.get(it).toAbsolutePath().normalize())
        }
        if (isWindows()) {
            System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }?.let {
                roots.add(Paths.get(it).toAbsolutePath().normalize())
            }
        }
        System.getenv("XDG_CACHE_HOME")?.takeIf { it.isNotBlank() }?.let {
            roots.add(Paths.get(it).toAbsolutePath().normalize())
        }
        roots.add(Paths.get(System.getProperty("user.home"), ".cache").toAbsolutePath().normalize())
        roots.add(Paths.get(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize())
        return roots.toList()
    }

    private fun normalizeOs(osName: String): String {
        val os = osName.lowercase(Locale.ROOT)
        return when {
            os.startsWith("win") || os.contains("windows") -> "windows"
            os.contains("mac") || os.contains("darwin") -> "macos"
            os.contains("linux") || os.contains("nux") -> "linux"
            else -> os.takeIf { it.isNotBlank() } ?: "unknown"
        }
    }

    private fun normalizeArch(archName: String): String {
        val arch = archName.lowercase(Locale.ROOT).replace("-", "_")
        return when {
            arch == "amd64" || arch == "x64" || arch == "x86_64" -> "x86_64"
            arch == "x86" || arch == "i386" || arch == "i486" || arch == "i586" || arch == "i686" -> "i686"
            arch == "aarch64" || arch == "arm64" -> "aarch64"
            arch == "armhf" || arch == "armv7l" || arch == "armv7" -> "armhf"
            arch.startsWith("arm") -> "arm"
            arch == "mips64el" -> "mips64el"
            arch == "mips64" -> "mips64"
            arch.startsWith("mips") -> "mips"
            arch == "s390x" -> "s390x"
            else -> arch.takeIf { it.isNotBlank() } ?: "unknown"
        }
    }

    private fun sha256(bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xff) }
    }

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase(Locale.ROOT).contains("windows")
}
