package editor.codeintel.semantic

import editor.codeintel.index.SemanticSnapshot
import editor.codeintel.model.FileId
import editor.codeintel.model.OccurrenceRecord
import editor.codeintel.model.RelationRecord
import editor.codeintel.model.SymbolRecord
import editor.codeintel.model.TypeRecord

data class SemanticEnrichment(
    val symbols: List<SymbolRecord> = emptyList(),
    val occurrences: List<OccurrenceRecord> = emptyList(),
    val relations: List<RelationRecord> = emptyList(),
    val types: List<TypeRecord> = emptyList()
)

interface SemanticEnricher {
    suspend fun enrich(files: Set<FileId>, snapshot: SemanticSnapshot): SemanticEnrichment
}

interface LspSemanticEnricher : SemanticEnricher

interface CompilerSemanticEnricher : SemanticEnricher

interface ScipSemanticEnricher : SemanticEnricher

interface MachineLearningEnricher : SemanticEnricher
