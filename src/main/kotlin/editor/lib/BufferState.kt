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
data class BufferPersistState(
    val lines: List<String>,
    val cursor: PositionState,
    val anchor: PositionState? = null,
    val undo: List<BufferSnapshotState> = emptyList(),
    val redo: List<BufferSnapshotState> = emptyList(),
    val dirty: Boolean = false
)
