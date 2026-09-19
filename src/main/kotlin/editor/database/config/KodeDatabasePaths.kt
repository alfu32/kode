package editor.database.config

import java.nio.file.Path
import java.nio.file.Paths

object KodeDatabasePaths {
    fun configRoot(): Path {
        System.getProperty("kode.config.dir")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }
        System.getenv("KODE_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }
        System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it, "kode").toAbsolutePath().normalize()
        }
        if (isWindows()) {
            System.getenv("APPDATA")?.takeIf { it.isNotBlank() }?.let {
                return Paths.get(it, "Kode").toAbsolutePath().normalize()
            }
        }
        return Paths.get(System.getProperty("user.home"), ".config", "kode").toAbsolutePath().normalize()
    }

    fun pluginRoot(): Path {
        System.getProperty("kode.plugins.dir")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }
        System.getenv("KODE_PLUGINS_HOME")?.takeIf { it.isNotBlank() }?.let {
            return Paths.get(it).toAbsolutePath().normalize()
        }
        return configRoot().resolve("plugins")
    }

    fun databaseConfigDir(): Path = configRoot().resolve("database")

    fun jdbcPluginDir(): Path = pluginRoot().resolve("jdbc")

    private fun isWindows(): Boolean =
        System.getProperty("os.name").lowercase().contains("windows")
}
