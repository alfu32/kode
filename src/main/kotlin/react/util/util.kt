package react.util

fun runCommand(vararg cmd: String): String? = try {
    ProcessBuilder(*cmd)
        .redirectErrorStream(true)
        .start()
        .inputStream.bufferedReader().use { it.readText() }
} catch (_: Exception) { null }