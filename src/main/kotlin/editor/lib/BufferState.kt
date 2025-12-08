package editor.lib

import kotlinx.serialization.Serializable

@Serializable
data class PositionState(val line: Int, val column: Int)

@Serializable
data class BufferSnapshotState(
    val lines: List<String>,
    val cursor: PositionState,
    val anchor: PositionState? = null,
    val dirty: Boolean = false
)

@Serializable
data class EditOpState(
    val start: Int,
    val removed: String,
    val inserted: String,
    val beforeCursor: PositionState,
    val beforeAnchor: PositionState? = null,
    val beforeDirty: Boolean = false,
    val afterCursor: PositionState,
    val afterAnchor: PositionState? = null,
    val afterDirty: Boolean = false
)

@Serializable
data class BufferPersistState(
    val lines: List<String>,
    val cursor: PositionState,
    val anchor: PositionState? = null,
    val undoOps: List<EditOpState> = emptyList(),
    val redoOps: List<EditOpState> = emptyList(),
    // Legacy snapshot-based history for backward compatibility
    val undo: List<BufferSnapshotState> = emptyList(),
    val redo: List<BufferSnapshotState> = emptyList(),
    val dirty: Boolean = false
)
