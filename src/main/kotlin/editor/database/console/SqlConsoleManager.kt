package editor.database.console

import editor.database.model.DataSourceId
import editor.database.query.SqlExecutionResult
import java.time.Instant

data class SqlConsole(
    val id: String,
    val dataSourceId: DataSourceId,
    val title: String,
    var catalog: String? = null,
    var schema: String? = null,
    var autoCommit: Boolean = true,
    var buffer: String = "",
    val history: MutableList<SqlHistoryEntry> = mutableListOf(),
    var lastResult: SqlExecutionResult? = null,
    var running: Boolean = false
)

data class SqlHistoryEntry(
    val timestamp: Instant,
    val dataSourceId: DataSourceId,
    val sql: String,
    val durationMs: Long,
    val success: Boolean
)

class SqlConsoleManager {
    private val consoles = linkedMapOf<String, SqlConsole>()
    private val counters = mutableMapOf<DataSourceId, Int>()

    fun create(dataSourceId: DataSourceId, dataSourceName: String): SqlConsole {
        val next = (counters[dataSourceId] ?: 0) + 1
        counters[dataSourceId] = next
        val id = "$dataSourceId:$next"
        val console = SqlConsole(
            id = id,
            dataSourceId = dataSourceId,
            title = "sql@$dataSourceName:$next",
            buffer = "-- $dataSourceName\n"
        )
        consoles[id] = console
        return console
    }

    fun get(id: String?): SqlConsole? =
        id?.let { consoles[it] }

    fun all(): List<SqlConsole> = consoles.values.toList()

    fun updateBuffer(id: String, text: String) {
        consoles[id]?.buffer = text
    }

    fun recordResult(id: String, sql: String, result: SqlExecutionResult) {
        consoles[id]?.let { console ->
            console.lastResult = result
            console.running = false
            console.history += SqlHistoryEntry(
                timestamp = result.startedAt,
                dataSourceId = console.dataSourceId,
                sql = sql,
                durationMs = result.executionTimeMs,
                success = result.success
            )
        }
    }
}
