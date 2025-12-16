package editor.codeintel

import java.sql.Connection
import java.sql.DriverManager
import java.util.Locale

class DbCodeIntelStore(
    private val urlProvider: () -> String?
) {
    @Volatile
    private var initialized = false
    @Volatile
    private var hasDataCache: Boolean = false

    private fun connection(): Connection? {
        val url = urlProvider() ?: return null
        return runCatching { DriverManager.getConnection(url, DB_USER, DB_PASS) }.getOrNull()
    }

    private fun ensureSchema(conn: Connection) {
        if (initialized) return
        synchronized(this) {
            if (initialized) return
            conn.createStatement().use { stmt ->
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS symbols (
                        name_lc VARCHAR(256),
                        name VARCHAR(256),
                        kind VARCHAR(64),
                        language VARCHAR(64),
                        file VARCHAR(2048),
                        line INT,
                        start_col INT,
                        end_col INT,
                        range_start INT,
                        range_end INT
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS symbols_name_idx ON symbols(name_lc)")
                stmt.execute("CREATE INDEX IF NOT EXISTS symbols_file_idx ON symbols(file)")
                stmt.execute(
                    """
                    CREATE TABLE IF NOT EXISTS usages (
                        name_lc VARCHAR(256),
                        name VARCHAR(256),
                        file VARCHAR(2048),
                        line INT,
                        start_col INT,
                        end_col INT,
                        is_decl BOOLEAN
                    )
                    """.trimIndent()
                )
                stmt.execute("CREATE INDEX IF NOT EXISTS usages_name_idx ON usages(name_lc)")
                stmt.execute("CREATE INDEX IF NOT EXISTS usages_file_idx ON usages(file)")
            }
            initialized = true
        }
    }

    fun storeFile(path: String, language: String?, defs: List<SymbolDef>, identifiers: Map<Int, List<IdentifierToken>>) {
        val conn = connection() ?: return
        conn.use { c ->
            ensureSchema(c)
            hasDataCache = true
            c.autoCommit = false
            c.prepareStatement("DELETE FROM symbols WHERE file = ?").use {
                it.setString(1, path)
                it.executeUpdate()
            }
            c.prepareStatement("DELETE FROM usages WHERE file = ?").use {
                it.setString(1, path)
                it.executeUpdate()
            }
            c.prepareStatement(
                "INSERT INTO symbols(name_lc,name,kind,language,file,line,start_col,end_col,range_start,range_end) VALUES (?,?,?,?,?,?,?,?,?,?)"
            ).use { ps ->
                defs.forEach { def ->
                    ps.setString(1, def.name.lowercase(Locale.ROOT))
                    ps.setString(2, def.name)
                    ps.setString(3, def.kind.name)
                    ps.setString(4, language)
                    ps.setString(5, path)
                    ps.setInt(6, def.line ?: -1)
                    ps.setInt(7, def.startColumn ?: -1)
                    ps.setInt(8, (def.startColumn ?: 0) + def.name.length)
                    ps.setInt(9, def.range.first)
                    ps.setInt(10, def.range.last)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.prepareStatement(
                "INSERT INTO usages(name_lc,name,file,line,start_col,end_col,is_decl) VALUES (?,?,?,?,?,?,?)"
            ).use { ps ->
                identifiers.values.flatten().forEach { tok ->
                    ps.setString(1, tok.name.lowercase(Locale.ROOT))
                    ps.setString(2, tok.name)
                    ps.setString(3, tok.filePath ?: path)
                    ps.setInt(4, tok.line)
                    ps.setInt(5, tok.start)
                    ps.setInt(6, tok.end)
                    ps.setBoolean(7, tok.declaration)
                    ps.addBatch()
                }
                ps.executeBatch()
            }
            c.commit()
        }
    }

    data class Loaded(val defs: List<SymbolDef>, val usages: List<IdentifierToken>)

    fun loadAll(): Loaded {
        val conn = connection() ?: return Loaded(emptyList(), emptyList())
        conn.use { c ->
            ensureSchema(c)
            val defs = mutableListOf<SymbolDef>()
            val usages = mutableListOf<IdentifierToken>()
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT name, kind, language, file, line, start_col, range_start, range_end FROM symbols").use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val kind = runCatching { SymbolKind.valueOf(rs.getString(2)) }.getOrNull() ?: SymbolKind.VARIABLE
                        val lang = rs.getString(3)
                        val file = rs.getString(4) ?: continue
                        val line = rs.getInt(5)
                        val startCol = rs.getInt(6)
                        val rangeStart = rs.getInt(7)
                        val rangeEnd = rs.getInt(8)
                        defs += SymbolDef(
                            name = name,
                            kind = kind,
                            filePath = file,
                            range = rangeStart..rangeEnd,
                            line = line,
                            startColumn = startCol,
                            language = lang
                        )
                    }
                }
                stmt.executeQuery("SELECT name, file, line, start_col, end_col, is_decl FROM usages").use { rs ->
                    while (rs.next()) {
                        val name = rs.getString(1) ?: continue
                        val file = rs.getString(2) ?: continue
                        val line = rs.getInt(3)
                        val start = rs.getInt(4)
                        val end = rs.getInt(5)
                        val decl = rs.getBoolean(6)
                        usages += IdentifierToken(
                            line = line,
                            start = start,
                            end = end,
                            declaration = decl,
                            name = name,
                            filePath = file
                        )
                    }
                }
            }
            hasDataCache = defs.isNotEmpty() || usages.isNotEmpty()
            return Loaded(defs, usages)
        }
    }

    fun hasData(): Boolean {
        if (hasDataCache) return true
        val conn = connection() ?: return false
        conn.use { c ->
            ensureSchema(c)
            c.createStatement().use { stmt ->
                stmt.executeQuery("SELECT 1 FROM symbols LIMIT 1").use { rs ->
                    if (rs.next()) {
                        hasDataCache = true
                        return true
                    }
                }
            }
        }
        return hasDataCache
    }

    companion object {
        private const val DB_USER = "sa"
        private const val DB_PASS = "sa"
    }
}
