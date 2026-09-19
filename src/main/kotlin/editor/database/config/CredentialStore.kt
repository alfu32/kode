package editor.database.config

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermission
import java.util.Properties

class CredentialStore(
    private val root: Path = KodeDatabasePaths.databaseConfigDir()
) {
    private val file = root.resolve("credentials.properties")

    fun get(reference: String?): String? {
        if (reference.isNullOrBlank() || !Files.isRegularFile(file)) return null
        return Properties().also { props ->
            Files.newInputStream(file).use(props::load)
        }.getProperty(reference)
    }

    fun put(reference: String, secret: String) {
        val props = Properties()
        Files.createDirectories(root)
        if (Files.isRegularFile(file)) {
            Files.newInputStream(file).use(props::load)
        }
        props.setProperty(reference, secret)
        Files.newOutputStream(file).use { out ->
            props.store(out, "Kode database credentials")
        }
        restrictOwnerOnly(file)
    }

    fun remove(reference: String?) {
        if (reference.isNullOrBlank() || !Files.isRegularFile(file)) return
        val props = Properties()
        Files.newInputStream(file).use(props::load)
        props.remove(reference)
        Files.newOutputStream(file).use { out ->
            props.store(out, "Kode database credentials")
        }
        restrictOwnerOnly(file)
    }

    private fun restrictOwnerOnly(path: Path) {
        runCatching {
            Files.setPosixFilePermissions(
                path,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE)
            )
        }
    }
}
