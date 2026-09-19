package editor.database.query

import editor.database.connection.DatabaseConnectionManager
import editor.database.model.DataSourceId
import editor.lib.Position
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.SQLWarning
import java.sql.Statement
import java.sql.Types
import java.time.Instant
import java.util.UUID
import kotlin.math.max

data class SqlExecutionRequest(
    val dataSourceId: DataSourceId,
    val consoleId: String,
    val sql: String,
    val catalog: String? = null,
    val schema: String? = null,
    val maxRows: Int = 500,
    val fetchSize: Int = 200
)

data class SqlExecutionResult(
    val resultSets: List<SqlResultSet> = emptyList(),
    val updateCounts: List<Int> = emptyList(),
    val warnings: List<String> = emptyList(),
    val executionTimeMs: Long = 0,
    val error: SqlExecutionError? = null,
    val startedAt: Instant = Instant.now()
) {
    val success: Boolean get() = error == null
}

data class SqlResultSet(
    val label: String,
    val columns: List<SqlColumn>,
    val rows: List<List<SqlCell>>,
    val limited: Boolean
)

data class SqlColumn(
    val name: String,
    val jdbcType: Int,
    val typeName: String,
    val nullable: Boolean
)

data class SqlCell(
    val value: String?,
    val jdbcType: Int,
    val nullValue: Boolean
)

data class SqlExecutionError(
    val message: String,
    val sqlState: String? = null,
    val vendorCode: Int? = null
)

data class LocatedSqlStatement(
    val sql: String,
    val startOffset: Int,
    val endOffset: Int
)

class SqlExecutionService(
    private val connections: DatabaseConnectionManager
) {
    @Volatile
    private var activeStatement: Statement? = null

    fun execute(request: SqlExecutionRequest): SqlExecutionResult {
        val started = Instant.now()
        val startNs = System.nanoTime()
        return runCatching {
            val connection = connections.requireConnection(request.dataSourceId)
            request.catalog?.takeIf { it.isNotBlank() }?.let { runCatching { connection.catalog = it } }
            request.schema?.takeIf { it.isNotBlank() }?.let { runCatching { connection.schema = it } }
            connection.createStatement().use { statement ->
                activeStatement = statement
                statement.maxRows = request.maxRows.coerceAtLeast(1)
                statement.fetchSize = request.fetchSize.coerceAtLeast(1)
                val resultSets = mutableListOf<SqlResultSet>()
                val updateCounts = mutableListOf<Int>()
                var hasResultSet = statement.execute(request.sql)
                var index = 1
                while (true) {
                    if (hasResultSet) {
                        statement.resultSet?.use { rs ->
                            resultSets += convertResultSet("Results $index", rs, request.maxRows)
                            index++
                        }
                    } else {
                        val updateCount = statement.updateCount
                        if (updateCount == -1) break
                        updateCounts += updateCount
                    }
                    hasResultSet = statement.moreResults
                }
                SqlExecutionResult(
                    resultSets = resultSets,
                    updateCounts = updateCounts,
                    warnings = collectWarnings(statement.warnings),
                    executionTimeMs = (System.nanoTime() - startNs) / 1_000_000,
                    startedAt = started
                )
            }
        }.getOrElse { ex ->
            val sql = ex as? SQLException
            SqlExecutionResult(
                executionTimeMs = (System.nanoTime() - startNs) / 1_000_000,
                error = SqlExecutionError(
                    message = ex.message ?: ex::class.simpleName ?: "SQL execution failed",
                    sqlState = sql?.sqlState,
                    vendorCode = sql?.errorCode
                ),
                startedAt = started
            )
        }.also {
            activeStatement = null
        }
    }

    fun cancel(): Boolean =
        runCatching {
            activeStatement?.cancel()
            true
        }.getOrDefault(false)

    private fun convertResultSet(label: String, rs: ResultSet, maxRows: Int): SqlResultSet {
        val meta = rs.metaData
        val columns = (1..meta.columnCount).map { idx ->
            SqlColumn(
                name = meta.getColumnLabel(idx).ifBlank { meta.getColumnName(idx) },
                jdbcType = meta.getColumnType(idx),
                typeName = meta.getColumnTypeName(idx),
                nullable = meta.isNullable(idx) != java.sql.ResultSetMetaData.columnNoNulls
            )
        }
        val rows = mutableListOf<List<SqlCell>>()
        var limited = false
        while (rs.next()) {
            if (rows.size >= maxRows) {
                limited = true
                break
            }
            rows += columns.indices.map { zero ->
                val idx = zero + 1
                val value = rs.getObject(idx)
                val isNull = rs.wasNull()
                SqlCell(if (isNull) null else formatValue(value), columns[zero].jdbcType, isNull)
            }
        }
        return SqlResultSet(label, columns, rows, limited)
    }

    private fun formatValue(value: Any?): String? =
        when (value) {
            null -> null
            is ByteArray -> value.joinToString(prefix = "0x", separator = "") { "%02x".format(it) }
            else -> value.toString()
        }

    private fun collectWarnings(first: SQLWarning?): List<String> {
        val out = mutableListOf<String>()
        var warning = first
        while (warning != null) {
            out += warning.message.orEmpty().ifBlank { warning::class.simpleName ?: "warning" }
            warning = warning.nextWarning
        }
        return out
    }
}

class SqlStatementLocator {
    fun locate(text: String, cursor: Position, selection: String?): LocatedSqlStatement {
        if (!selection.isNullOrBlank()) {
            val start = text.indexOf(selection).coerceAtLeast(0)
            return LocatedSqlStatement(selection.trim(), start, start + selection.length)
        }
        val cursorOffset = offsetFor(text, cursor)
        val boundaries = statementBoundaries(text)
        val match = boundaries.firstOrNull { cursorOffset in it.first..it.second }
            ?: boundaries.firstOrNull { it.first >= cursorOffset }
            ?: (0 to text.length)
        val raw = text.substring(match.first, match.second).trim()
        return LocatedSqlStatement(raw, match.first, match.second)
    }

    fun statements(text: String): List<LocatedSqlStatement> {
        return statementBoundaries(text).mapNotNull { (start, end) ->
            val sql = text.substring(start, end).trim()
            if (sql.isBlank()) null else LocatedSqlStatement(sql, start, end)
        }
    }

    private fun statementBoundaries(text: String): List<Pair<Int, Int>> {
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        var i = 0
        var single = false
        var double = false
        var lineComment = false
        var blockComment = false
        while (i < text.length) {
            val ch = text[i]
            val next = text.getOrNull(i + 1)
            when {
                lineComment -> if (ch == '\n') lineComment = false
                blockComment -> if (ch == '*' && next == '/') {
                    blockComment = false
                    i++
                }
                single -> when {
                    ch == '\'' && next == '\'' -> i++
                    ch == '\'' -> single = false
                }
                double -> when {
                    ch == '"' && next == '"' -> i++
                    ch == '"' -> double = false
                }
                ch == '-' && next == '-' -> {
                    lineComment = true
                    i++
                }
                ch == '/' && next == '*' -> {
                    blockComment = true
                    i++
                }
                ch == '\'' -> single = true
                ch == '"' -> double = true
                ch == ';' -> {
                    result += start to i
                    start = i + 1
                }
            }
            i++
        }
        if (start < text.length) result += start to text.length
        return result
    }

    private fun offsetFor(text: String, cursor: Position): Int {
        var offset = 0
        var line = 0
        while (line < cursor.line && offset < text.length) {
            val next = text.indexOf('\n', offset)
            if (next < 0) return text.length
            offset = next + 1
            line++
        }
        return (offset + cursor.column).coerceIn(0, text.length)
    }
}
