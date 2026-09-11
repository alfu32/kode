package editor.codeintel.index

import editor.codeintel.model.Confidence
import editor.codeintel.model.ExpressionTypeRecord
import editor.codeintel.model.FileDependencyKind
import editor.codeintel.model.FileDependencyRecord
import editor.codeintel.model.FileId
import editor.codeintel.model.FileRecord
import editor.codeintel.model.FileSemanticDelta
import editor.codeintel.model.ImportRecord
import editor.codeintel.model.OccurrenceKind
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.RelationKind
import editor.codeintel.model.RelationRecord
import editor.codeintel.model.ScopeId
import editor.codeintel.model.ScopeKind
import editor.codeintel.model.ScopeRecord
import editor.codeintel.model.SemanticSource
import editor.codeintel.model.SourceRange
import editor.codeintel.model.SymbolId
import editor.codeintel.model.SymbolKind
import editor.codeintel.model.SymbolRecord
import editor.codeintel.model.TypeHint
import editor.codeintel.model.TypeHintKind
import editor.codeintel.model.TypeId
import editor.codeintel.model.TypeRef
import editor.codeintel.model.TypeRole
import editor.codeintel.model.UnresolvedTypeRef
import editor.codeintel.resolver.ResolvedSemanticProject
import java.sql.Connection
import java.sql.DriverManager
import java.sql.ResultSet
import java.sql.Types

class H2SemanticStore(
    private val urlProvider: () -> String?
) : SemanticStore {
    @Volatile
    private var initializedUrl: String? = null

    override fun replaceFile(delta: FileSemanticDelta, resolved: ResolvedSemanticProject) =
        replaceFiles(listOf(delta), resolved)

    override fun replaceFiles(deltas: Collection<FileSemanticDelta>, resolved: ResolvedSemanticProject) {
        if (deltas.isEmpty()) return
        connection()?.use { connection ->
            ensureSchema(connection)
            connection.autoCommit = false
            runCatching {
                deltas.forEach { deleteFile(connection, it.fileId) }
                deltas.forEach { delta -> insertFileData(connection, delta, resolved) }
                connection.commit()
            }.getOrElse { error ->
                runCatching { connection.rollback() }
                throw error
            }
        }
    }

    private fun insertFileData(
        connection: Connection,
        delta: FileSemanticDelta,
        resolved: ResolvedSemanticProject
    ) {
        insertFile(connection, delta)
        val symbols = resolved.symbols.filter { it.fileId == delta.fileId }
        val symbolIds = symbols.mapTo(mutableSetOf()) { it.id }
        insertScopes(connection, delta.scopes)
        insertSymbols(connection, symbols)
        insertOccurrences(connection, resolved.occurrences.filter { it.fileId == delta.fileId })
        insertRelations(connection, delta.fileId, resolved.relations.filter { it.from in symbolIds })
        insertTypes(connection, delta.fileId, symbols, resolved)
        insertUnresolvedTypes(connection, delta.fileId, delta.unresolvedTypes)
        insertImports(connection, delta.imports)
        insertTypeHints(connection, delta.fileId, delta.typeHints)
        insertExpressionTypes(connection, delta.expressionTypes)
        insertDependencies(
            connection,
            delta.fileId,
            resolved.dependencies.filter { it.fromFileId == delta.fileId }
        )
    }
    override fun loadFiles(): List<FileSemanticDelta> {
        val connection = connection() ?: return emptyList()
        connection.use { conn ->
            ensureSchema(conn)
            val files = linkedMapOf<FileId, FileRecord>()
            val loadedExportHashes = mutableMapOf<FileId, String>()
            conn.createStatement().use { statement ->
                statement.executeQuery(
                    "SELECT file_id,path,language_id,content_hash,parse_version,semantic_version,dependency_generation,exported_surface_hash FROM semantic_files"
                ).use { result ->
                    while (result.next()) {
                        val file = FileRecord(
                            id = FileId(result.getLong(1)),
                            path = result.getString(2),
                            languageId = result.getString(3),
                            contentHash = result.getString(4),
                            parseVersion = result.getLong(5),
                            semanticVersion = result.getLong(6),
                            dependencyGeneration = result.getLong(7)
                        )
                        files[file.id] = file
                        result.getString(8)?.let { loadedExportHashes[file.id] = it }
                    }
                }
            }
            val scopes = loadScopes(conn).groupBy { it.fileId }
            val symbols = loadSymbols(conn)
                .map { it.copy(declaredTypeId = null, inferredTypeId = null) }
                .groupBy { it.fileId }
            val occurrences = loadOccurrences(conn)
                .map { occurrence ->
                    if (occurrence.kind == OccurrenceKind.DECLARATION) occurrence
                    else occurrence.copy(resolvedSymbolId = null)
                }
                .groupBy { it.fileId }
            val unresolved = loadUnresolvedTypes(conn).groupBy { it.first }.mapValues { entry -> entry.value.map { it.second } }
            val imports = loadImports(conn).groupBy { it.fileId }
            val hints = loadTypeHints(conn).groupBy { it.first }.mapValues { entry -> entry.value.map { it.second } }
            val expressionTypes = loadExpressionTypes(conn).groupBy { it.fileId }
            val relationsByFile = loadRelations(conn)
                .filter { it.second.kind in setOf(RelationKind.CONTAINS, RelationKind.MEMBER_OF) }
                .groupBy { it.first }
                .mapValues { entry -> entry.value.map { it.second } }
            return files.values.map { file ->
                FileSemanticDelta(
                    file = file,
                    scopes = scopes[file.id].orEmpty(),
                    symbols = symbols[file.id].orEmpty(),
                    occurrences = occurrences[file.id].orEmpty(),
                    relations = relationsByFile[file.id].orEmpty(),
                    unresolvedTypes = unresolved[file.id].orEmpty(),
                    imports = imports[file.id].orEmpty(),
                    typeHints = hints[file.id].orEmpty(),
                    expressionTypes = expressionTypes[file.id].orEmpty(),
                    exportedSurfaceHash = loadedExportHashes[file.id] ?: file.contentHash
                )
            }
        }
    }

    override fun hasData(): Boolean {
        val connection = connection() ?: return false
        connection.use { conn ->
            ensureSchema(conn)
            conn.createStatement().use { statement ->
                statement.executeQuery("SELECT 1 FROM semantic_files LIMIT 1").use { return it.next() }
            }
        }
    }

    override fun loadDependencies(): List<FileDependencyRecord> {
        val connection = connection() ?: return emptyList()
        connection.use { conn ->
            ensureSchema(conn)
            return query(
                conn,
                "SELECT from_file_id,to_file_id,kind,generation FROM semantic_file_dependencies"
            ) { result ->
                FileDependencyRecord(
                    fromFileId = FileId(result.getLong(1)),
                    toFileId = FileId(result.getLong(2)),
                    kind = FileDependencyKind.valueOf(result.getString(3)),
                    generation = result.getLong(4)
                )
            }
        }
    }

    private fun connection(): Connection? {
        val url = urlProvider() ?: return null
        return runCatching { DriverManager.getConnection(url, DB_USER, DB_PASS) }.getOrNull()
    }

    private fun ensureSchema(connection: Connection) {
        val url = connection.metaData.url
        if (initializedUrl == url) return
        synchronized(this) {
            if (initializedUrl == url) return
            connection.createStatement().use { statement ->
                SCHEMA.forEach(statement::execute)
                runCatching {
                    statement.execute("ALTER TABLE semantic_files ADD COLUMN exported_surface_hash VARCHAR(64)")
                }
                runCatching {
                    statement.execute("ALTER TABLE semantic_types ADD COLUMN knowledge_level VARCHAR(32)")
                }
            }
            initializedUrl = url
        }
    }

    private fun deleteFile(connection: Connection, fileId: FileId) {
        CHILD_TABLES.forEach { table ->
            connection.prepareStatement("DELETE FROM $table WHERE file_id = ?").use { statement ->
                statement.setLong(1, fileId.value)
                statement.executeUpdate()
            }
        }
        connection.prepareStatement("DELETE FROM semantic_files WHERE file_id = ?").use { statement ->
            statement.setLong(1, fileId.value)
            statement.executeUpdate()
        }
    }

    private fun insertFile(connection: Connection, delta: FileSemanticDelta) {
        val file = delta.file
        connection.prepareStatement(
            "INSERT INTO semantic_files(file_id,path,language_id,content_hash,parse_version,semantic_version,dependency_generation,exported_surface_hash) VALUES(?,?,?,?,?,?,?,?)"
        ).use { statement ->
            statement.setLong(1, file.id.value)
            statement.setString(2, file.path)
            statement.setString(3, file.languageId)
            statement.setString(4, file.contentHash)
            statement.setLong(5, file.parseVersion)
            statement.setLong(6, file.semanticVersion)
            statement.setLong(7, file.dependencyGeneration)
            statement.setString(8, delta.exportedSurfaceHash)
            statement.executeUpdate()
        }
    }

    private fun insertScopes(connection: Connection, records: List<ScopeRecord>) {
        connection.prepareStatement(
            "INSERT INTO semantic_scopes(scope_id,file_id,parent_id,owner_symbol_id,kind,start_offset,end_offset) VALUES(?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, record.id.value)
                statement.setLong(2, record.fileId.value)
                statement.setNullableLong(3, record.parentId?.value)
                statement.setNullableLong(4, record.ownerSymbolId?.value)
                statement.setString(5, record.kind.name)
                statement.setInt(6, record.range.startOffset)
                statement.setInt(7, record.range.endOffset)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertSymbols(connection: Connection, records: List<SymbolRecord>) {
        connection.prepareStatement(
            "INSERT INTO semantic_symbols(symbol_id,file_id,name,qualified_name,kind,declaration_start,declaration_end,name_start,name_end,scope_id,owner_symbol_id,declared_type_id,inferred_type_id,flags,confidence_source,confidence_value) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, record.id.value)
                statement.setLong(2, record.fileId.value)
                statement.setString(3, record.name)
                statement.setString(4, record.qualifiedName)
                statement.setString(5, record.kind.name)
                statement.setInt(6, record.declarationRange.startOffset)
                statement.setInt(7, record.declarationRange.endOffset)
                statement.setInt(8, record.nameRange.startOffset)
                statement.setInt(9, record.nameRange.endOffset)
                statement.setLong(10, record.scopeId.value)
                statement.setNullableLong(11, record.ownerSymbolId?.value)
                statement.setNullableLong(12, record.declaredTypeId?.value)
                statement.setNullableLong(13, record.inferredTypeId?.value)
                statement.setLong(14, record.flags)
                statement.setString(15, record.confidence.source.name)
                statement.setFloat(16, record.confidence.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertOccurrences(connection: Connection, records: List<OccurrenceRecord>) {
        connection.prepareStatement(
            "INSERT INTO semantic_occurrences(occurrence_id,file_id,start_offset,end_offset,text_value,kind,scope_id,resolved_symbol_id,receiver_occurrence_id,confidence_source,confidence_value) VALUES(?,?,?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, record.id)
                statement.setLong(2, record.fileId.value)
                statement.setInt(3, record.range.startOffset)
                statement.setInt(4, record.range.endOffset)
                statement.setString(5, record.text)
                statement.setString(6, record.kind.name)
                statement.setLong(7, record.scopeId.value)
                statement.setNullableLong(8, record.resolvedSymbolId?.value)
                statement.setNullableLong(9, record.receiverOccurrenceId)
                statement.setString(10, record.confidence.source.name)
                statement.setFloat(11, record.confidence.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertRelations(
        connection: Connection,
        fileId: FileId,
        records: List<RelationRecord>
    ) {
        connection.prepareStatement(
            "INSERT INTO semantic_relations(file_id,from_symbol,to_symbol,kind,confidence_source,confidence_value) VALUES(?,?,?,?,?,?)"
        ).use { statement ->
            records.distinctBy { Triple(it.from, it.to, it.kind) }.forEach { record ->
                statement.setLong(1, fileId.value)
                statement.setLong(2, record.from.value)
                statement.setLong(3, record.to.value)
                statement.setString(4, record.kind.name)
                statement.setString(5, record.confidence.source.name)
                statement.setFloat(6, record.confidence.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertTypes(
        connection: Connection,
        fileId: FileId,
        symbols: List<SymbolRecord>,
        resolved: ResolvedSemanticProject
    ) {
        val ids = symbols.flatMap { listOfNotNull(it.declaredTypeId, it.inferredTypeId) }.distinct()
        connection.prepareStatement(
            "INSERT INTO semantic_types(type_id,file_id,kind,name,symbol_id,confidence_source,confidence_value,knowledge_level) VALUES(?,?,?,?,?,?,?,?)"
        ).use { statement ->
            ids.mapNotNull(resolved.types::get).forEach { record ->
                val type = record.ref
                statement.setLong(1, record.id.value)
                statement.setLong(2, fileId.value)
                statement.setString(3, type.javaClass.simpleName.uppercase())
                statement.setString(4, (type as? TypeRef.Primitive)?.name)
                statement.setNullableLong(5, (type as? TypeRef.Named)?.symbolId?.value)
                statement.setString(6, record.confidence.source.name)
                statement.setFloat(7, record.confidence.value)
                statement.setString(8, record.knowledgeLevel.name)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertUnresolvedTypes(
        connection: Connection,
        fileId: FileId,
        records: List<UnresolvedTypeRef>
    ) {
        connection.prepareStatement(
            "INSERT INTO semantic_unresolved_types(file_id,owner_symbol_id,scope_id,name,role,start_offset,end_offset,confidence_source,confidence_value) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, fileId.value)
                statement.setLong(2, record.ownerSymbolId.value)
                statement.setLong(3, record.scopeId.value)
                statement.setString(4, record.name)
                statement.setString(5, record.role.name)
                statement.setInt(6, record.range.startOffset)
                statement.setInt(7, record.range.endOffset)
                statement.setString(8, record.confidence.source.name)
                statement.setFloat(9, record.confidence.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertImports(connection: Connection, records: List<ImportRecord>) {
        connection.prepareStatement(
            "INSERT INTO semantic_imports(file_id,scope_id,path,alias,wildcard,start_offset,end_offset) VALUES(?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, record.fileId.value)
                statement.setLong(2, record.scopeId.value)
                statement.setString(3, record.path)
                statement.setString(4, record.alias)
                statement.setBoolean(5, record.wildcard)
                statement.setInt(6, record.range.startOffset)
                statement.setInt(7, record.range.endOffset)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertTypeHints(connection: Connection, fileId: FileId, records: List<TypeHint>) {
        connection.prepareStatement(
            "INSERT INTO semantic_type_hints(file_id,target_symbol_id,scope_id,start_offset,end_offset,referenced_name,kind,confidence_source,confidence_value) VALUES(?,?,?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, fileId.value)
                statement.setLong(2, record.targetSymbolId.value)
                statement.setLong(3, record.scopeId.value)
                statement.setInt(4, record.expressionRange.startOffset)
                statement.setInt(5, record.expressionRange.endOffset)
                statement.setString(6, record.referencedName)
                statement.setString(7, record.kind.name)
                statement.setString(8, record.confidence.source.name)
                statement.setFloat(9, record.confidence.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertExpressionTypes(connection: Connection, records: List<ExpressionTypeRecord>) {
        connection.prepareStatement(
            "INSERT INTO semantic_expression_types(file_id,start_offset,end_offset,type_kind,type_name,type_symbol_id,confidence_source,confidence_value) VALUES(?,?,?,?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, record.fileId.value)
                statement.setInt(2, record.range.startOffset)
                statement.setInt(3, record.range.endOffset)
                statement.setString(4, record.type.javaClass.simpleName.uppercase())
                statement.setString(5, (record.type as? TypeRef.Primitive)?.name)
                statement.setNullableLong(6, (record.type as? TypeRef.Named)?.symbolId?.value)
                statement.setString(7, record.confidence.source.name)
                statement.setFloat(8, record.confidence.value)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun insertDependencies(
        connection: Connection,
        fileId: FileId,
        records: List<FileDependencyRecord>
    ) {
        connection.prepareStatement(
            "INSERT INTO semantic_file_dependencies(file_id,from_file_id,to_file_id,kind,generation) VALUES(?,?,?,?,?)"
        ).use { statement ->
            records.forEach { record ->
                statement.setLong(1, fileId.value)
                statement.setLong(2, record.fromFileId.value)
                statement.setLong(3, record.toFileId.value)
                statement.setString(4, record.kind.name)
                statement.setLong(5, record.generation)
                statement.addBatch()
            }
            statement.executeBatch()
        }
    }

    private fun loadScopes(connection: Connection): List<ScopeRecord> = query(connection,
        "SELECT scope_id,file_id,parent_id,owner_symbol_id,kind,start_offset,end_offset FROM semantic_scopes"
    ) { result ->
        ScopeRecord(
            ScopeId(result.getLong(1)),
            FileId(result.getLong(2)),
            result.nullableLong(3)?.let(::ScopeId),
            result.nullableLong(4)?.let(::SymbolId),
            ScopeKind.valueOf(result.getString(5)),
            SourceRange(result.getInt(6), result.getInt(7))
        )
    }

    private fun loadSymbols(connection: Connection): List<SymbolRecord> = query(connection,
        "SELECT symbol_id,file_id,name,qualified_name,kind,declaration_start,declaration_end,name_start,name_end,scope_id,owner_symbol_id,declared_type_id,inferred_type_id,flags,confidence_source,confidence_value FROM semantic_symbols"
    ) { result ->
        SymbolRecord(
            SymbolId(result.getLong(1)), FileId(result.getLong(2)), result.getString(3), result.getString(4),
            SymbolKind.valueOf(result.getString(5)), SourceRange(result.getInt(6), result.getInt(7)),
            SourceRange(result.getInt(8), result.getInt(9)), ScopeId(result.getLong(10)),
            result.nullableLong(11)?.let(::SymbolId), result.nullableLong(12)?.let(::TypeId),
            result.nullableLong(13)?.let(::TypeId), result.getLong(14), result.confidence(15, 16)
        )
    }

    private fun loadOccurrences(connection: Connection): List<OccurrenceRecord> = query(connection,
        "SELECT occurrence_id,file_id,start_offset,end_offset,text_value,kind,scope_id,resolved_symbol_id,receiver_occurrence_id,confidence_source,confidence_value FROM semantic_occurrences"
    ) { result ->
        OccurrenceRecord(
            result.getLong(1), FileId(result.getLong(2)), SourceRange(result.getInt(3), result.getInt(4)),
            result.getString(5), OccurrenceKind.valueOf(result.getString(6)), ScopeId(result.getLong(7)),
            result.nullableLong(8)?.let(::SymbolId), result.nullableLong(9), result.confidence(10, 11)
        )
    }

    private fun loadRelations(connection: Connection): List<Pair<FileId, RelationRecord>> = query(connection,
        "SELECT file_id,from_symbol,to_symbol,kind,confidence_source,confidence_value FROM semantic_relations"
    ) { result ->
        FileId(result.getLong(1)) to RelationRecord(
            SymbolId(result.getLong(2)), SymbolId(result.getLong(3)), RelationKind.valueOf(result.getString(4)),
            result.confidence(5, 6)
        )
    }

    private fun loadUnresolvedTypes(connection: Connection): List<Pair<FileId, UnresolvedTypeRef>> = query(connection,
        "SELECT file_id,owner_symbol_id,scope_id,name,role,start_offset,end_offset,confidence_source,confidence_value FROM semantic_unresolved_types"
    ) { result ->
        FileId(result.getLong(1)) to UnresolvedTypeRef(
            SymbolId(result.getLong(2)), ScopeId(result.getLong(3)), result.getString(4),
            TypeRole.valueOf(result.getString(5)), SourceRange(result.getInt(6), result.getInt(7)), result.confidence(8, 9)
        )
    }

    private fun loadImports(connection: Connection): List<ImportRecord> = query(connection,
        "SELECT file_id,scope_id,path,alias,wildcard,start_offset,end_offset FROM semantic_imports"
    ) { result ->
        ImportRecord(
            FileId(result.getLong(1)), ScopeId(result.getLong(2)), result.getString(3), result.getString(4),
            result.getBoolean(5), SourceRange(result.getInt(6), result.getInt(7))
        )
    }

    private fun loadTypeHints(connection: Connection): List<Pair<FileId, TypeHint>> = query(connection,
        "SELECT file_id,target_symbol_id,scope_id,start_offset,end_offset,referenced_name,kind,confidence_source,confidence_value FROM semantic_type_hints"
    ) { result ->
        FileId(result.getLong(1)) to TypeHint(
            SymbolId(result.getLong(2)), ScopeId(result.getLong(3)), SourceRange(result.getInt(4), result.getInt(5)),
            result.getString(6), TypeHintKind.valueOf(result.getString(7)), result.confidence(8, 9)
        )
    }

    private fun loadExpressionTypes(connection: Connection): List<ExpressionTypeRecord> = query(
        connection,
        "SELECT file_id,start_offset,end_offset,type_kind,type_name,type_symbol_id,confidence_source,confidence_value FROM semantic_expression_types"
    ) { result ->
        val type = when (result.getString(4)) {
            "PRIMITIVE" -> TypeRef.Primitive(result.getString(5))
            "NAMED" -> result.nullableLong(6)?.let { TypeRef.Named(SymbolId(it)) } ?: TypeRef.Unknown
            else -> TypeRef.Unknown
        }
        ExpressionTypeRecord(
            fileId = FileId(result.getLong(1)),
            range = SourceRange(result.getInt(2), result.getInt(3)),
            type = type,
            confidence = result.confidence(7, 8)
        )
    }

    private fun <T> query(connection: Connection, sql: String, mapper: (ResultSet) -> T): List<T> {
        val result = mutableListOf<T>()
        connection.createStatement().use { statement ->
            statement.executeQuery(sql).use { rows ->
                while (rows.next()) result += mapper(rows)
            }
        }
        return result
    }

    private fun java.sql.PreparedStatement.setNullableLong(index: Int, value: Long?) {
        if (value == null) setNull(index, Types.BIGINT) else setLong(index, value)
    }

    private fun ResultSet.nullableLong(index: Int): Long? =
        getLong(index).let { if (wasNull()) null else it }

    private fun ResultSet.confidence(sourceIndex: Int, valueIndex: Int): Confidence =
        Confidence(SemanticSource.valueOf(getString(sourceIndex)), getFloat(valueIndex))

    companion object {
        private const val DB_USER = "sa"
        private const val DB_PASS = "sa"
        private val CHILD_TABLES = listOf(
            "semantic_scopes", "semantic_symbols", "semantic_occurrences", "semantic_relations", "semantic_types",
            "semantic_unresolved_types", "semantic_imports", "semantic_type_hints", "semantic_expression_types",
            "semantic_file_dependencies"
        )
        private val SCHEMA = listOf(
            "CREATE TABLE IF NOT EXISTS semantic_files(file_id BIGINT PRIMARY KEY,path VARCHAR(2048) UNIQUE,language_id VARCHAR(64),content_hash VARCHAR(64),parse_version BIGINT,semantic_version BIGINT,dependency_generation BIGINT,exported_surface_hash VARCHAR(64))",
            "CREATE TABLE IF NOT EXISTS semantic_scopes(scope_id BIGINT PRIMARY KEY,file_id BIGINT,parent_id BIGINT,owner_symbol_id BIGINT,kind VARCHAR(32),start_offset INT,end_offset INT)",
            "CREATE TABLE IF NOT EXISTS semantic_symbols(symbol_id BIGINT PRIMARY KEY,file_id BIGINT,name VARCHAR(512),qualified_name VARCHAR(2048),kind VARCHAR(32),declaration_start INT,declaration_end INT,name_start INT,name_end INT,scope_id BIGINT,owner_symbol_id BIGINT,declared_type_id BIGINT,inferred_type_id BIGINT,flags BIGINT,confidence_source VARCHAR(32),confidence_value REAL)",
            "CREATE TABLE IF NOT EXISTS semantic_occurrences(occurrence_id BIGINT PRIMARY KEY,file_id BIGINT,start_offset INT,end_offset INT,text_value VARCHAR(1024),kind VARCHAR(32),scope_id BIGINT,resolved_symbol_id BIGINT,receiver_occurrence_id BIGINT,confidence_source VARCHAR(32),confidence_value REAL)",
            "CREATE TABLE IF NOT EXISTS semantic_relations(file_id BIGINT,from_symbol BIGINT,to_symbol BIGINT,kind VARCHAR(32),confidence_source VARCHAR(32),confidence_value REAL,PRIMARY KEY(file_id,from_symbol,to_symbol,kind))",
            "CREATE TABLE IF NOT EXISTS semantic_types(type_id BIGINT,file_id BIGINT,kind VARCHAR(32),name VARCHAR(512),symbol_id BIGINT,confidence_source VARCHAR(32),confidence_value REAL,knowledge_level VARCHAR(32),PRIMARY KEY(type_id,file_id))",
            "CREATE TABLE IF NOT EXISTS semantic_unresolved_types(file_id BIGINT,owner_symbol_id BIGINT,scope_id BIGINT,name VARCHAR(1024),role VARCHAR(32),start_offset INT,end_offset INT,confidence_source VARCHAR(32),confidence_value REAL)",
            "CREATE TABLE IF NOT EXISTS semantic_imports(file_id BIGINT,scope_id BIGINT,path VARCHAR(2048),alias VARCHAR(512),wildcard BOOLEAN,start_offset INT,end_offset INT)",
            "CREATE TABLE IF NOT EXISTS semantic_type_hints(file_id BIGINT,target_symbol_id BIGINT,scope_id BIGINT,start_offset INT,end_offset INT,referenced_name VARCHAR(1024),kind VARCHAR(32),confidence_source VARCHAR(32),confidence_value REAL)",
            "CREATE TABLE IF NOT EXISTS semantic_expression_types(file_id BIGINT,start_offset INT,end_offset INT,type_kind VARCHAR(32),type_name VARCHAR(512),type_symbol_id BIGINT,confidence_source VARCHAR(32),confidence_value REAL,PRIMARY KEY(file_id,start_offset,end_offset))",
            "CREATE TABLE IF NOT EXISTS semantic_file_dependencies(file_id BIGINT,from_file_id BIGINT,to_file_id BIGINT,kind VARCHAR(32),generation BIGINT,PRIMARY KEY(file_id,to_file_id,kind))",
            "CREATE INDEX IF NOT EXISTS semantic_symbols_name_idx ON semantic_symbols(name)",
            "CREATE INDEX IF NOT EXISTS semantic_symbols_qualified_name_idx ON semantic_symbols(qualified_name)",
            "CREATE INDEX IF NOT EXISTS semantic_symbols_file_idx ON semantic_symbols(file_id)",
            "CREATE INDEX IF NOT EXISTS semantic_symbols_scope_idx ON semantic_symbols(scope_id)",
            "CREATE INDEX IF NOT EXISTS semantic_occurrences_file_offset_idx ON semantic_occurrences(file_id,start_offset)",
            "CREATE INDEX IF NOT EXISTS semantic_occurrences_symbol_idx ON semantic_occurrences(resolved_symbol_id)",
            "CREATE INDEX IF NOT EXISTS semantic_expression_types_range_idx ON semantic_expression_types(file_id,start_offset,end_offset)",
            "CREATE INDEX IF NOT EXISTS semantic_relations_from_idx ON semantic_relations(from_symbol,kind)",
            "CREATE INDEX IF NOT EXISTS semantic_relations_to_idx ON semantic_relations(to_symbol,kind)",
            "CREATE INDEX IF NOT EXISTS semantic_scopes_range_idx ON semantic_scopes(file_id,start_offset,end_offset)",
            "CREATE INDEX IF NOT EXISTS semantic_dependencies_to_idx ON semantic_file_dependencies(to_file_id)"
        )
    }
}
