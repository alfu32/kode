package editor.codeintel.index

import editor.codeintel.model.FileDependencyRecord
import editor.codeintel.model.FileId

class DependencyGraph(dependencies: Collection<FileDependencyRecord>) {
    private val outgoing = dependencies.groupBy { it.fromFileId }
    private val incoming = dependencies.groupBy { it.toFileId }

    fun dependenciesOf(fileId: FileId): Set<FileId> =
        outgoing[fileId].orEmpty().mapTo(linkedSetOf()) { it.toFileId }

    fun directDependentsOf(fileId: FileId): Set<FileId> =
        incoming[fileId].orEmpty().mapTo(linkedSetOf()) { it.fromFileId }

    fun dependentClosure(files: Collection<FileId>): Set<FileId> {
        val visited = linkedSetOf<FileId>()
        val queue = ArrayDeque<FileId>()
        files.forEach {
            if (visited.add(it)) queue.addLast(it)
        }
        while (queue.isNotEmpty()) {
            directDependentsOf(queue.removeFirst()).forEach { dependent ->
                if (visited.add(dependent)) queue.addLast(dependent)
            }
        }
        return visited
    }
}
