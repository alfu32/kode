package editor.app

import java.io.File
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

object Logger {
    private val json = Json { prettyPrint = true }
    private val logFile = File(".kode.log.json")

    @Synchronized
    fun logRegexError(source: String, message: String, pattern: String) {
        val entry = RegexErrorEntry(
            timestamp = Instant.now().toString(),
            source = source,
            pattern = pattern,
            message = message
        )
        val existing = readEntries().toMutableList()
        existing.add(entry)
        logFile.writeText(json.encodeToString(existing))
    }

    private fun readEntries(): List<RegexErrorEntry> {
        if (!logFile.exists()) return emptyList()
        return runCatching { json.decodeFromString<List<RegexErrorEntry>>(logFile.readText()) }
            .getOrDefault(emptyList())
    }

    @Serializable
    private data class RegexErrorEntry(
        val timestamp: String,
        val source: String,
        val pattern: String,
        val message: String
    )
}
