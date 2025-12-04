package org.github.alfu32.ktx.lib
import org.github.alfu32.ktx.FileTreeEntry
import org.github.alfu32.ktx.FileTreeItem
import org.github.alfu32.ktx.IFileTree
import java.io.File
import kotlin.collections.plusAssign


// Implementation hidden behind the interface
class FileTree private constructor(
    override val root: String,
    val nodes: MutableMap<String, FileTreeItem>
) : IFileTree {

    companion object {
        fun newFileTree(root: String): FileTree {
            val absolute = File(root).canonicalFile.path

            val nodes = mutableMapOf<String, FileTreeItem>()
            val rootItem = FileTreeItem(
                name = File(absolute).name,
                fullPath = absolute,
                isDir = true,
                isOpen = true
            )
            nodes[absolute] = rootItem

            val tree = FileTree(root = absolute, nodes = nodes)
            tree.ensureChildren(absolute)
            return tree
        }
    }

    override fun toggle(path: String) {
        val item = nodes[path] ?: return
        if (!item.isDir) return

        item.isOpen = !item.isOpen
        if (item.isOpen) {
            ensureChildren(path)
        }
    }

    override fun flattened(): List<FileTreeEntry> {
        ensureChildren(root)
        val rootItem = nodes[root] ?: return emptyList()

        val entries = mutableListOf<FileTreeEntry>()
        for (childPath in rootItem.children) {
            collect(childPath, 0, entries)
        }
        return entries
    }

    override fun refreshOpenNodes() {
        refreshRecursive(root)
    }

    private fun refreshRecursive(path: String) {
        ensureChildren(path)
        val item = nodes[path] ?: return

        for (child in item.children) {
            val childItem = nodes[child] ?: continue
            if (childItem.isDir && childItem.isOpen) {
                refreshRecursive(child)
            }
        }
    }

    private fun collect(path: String, depth: Int, entries: MutableList<FileTreeEntry>) {
        val item = nodes[path] ?: return

        val entryType = if (item.isDir) "folder" else "file"
        entries += FileTreeEntry(
            name = item.name,
            typ = entryType,
            padding = depth,
            fullPath = item.fullPath,
            isOpen = item.isOpen
        )

        if (!item.isDir || !item.isOpen) return

        ensureChildren(path)
        for (child in item.children) {
            collect(child, depth + 1, entries)
        }
    }

    private fun ensureChildren(path: String) {
        val item = nodes[path] ?: return
        if (!item.isDir) return

        val dir = File(path)
        val listed = dir.listFiles()
        if (!dir.exists() || !dir.isDirectory || listed == null) {
            item.children = emptyList()
            return
        }

        val directories = mutableListOf<String>()
        val files = mutableListOf<String>()

        for (entry in listed) {
            val full = entry.canonicalFile.path
            if (entry.isDirectory) {
                directories += full
            } else {
                files += full
            }
        }

        directories.sort()
        files.sort()

        val children = mutableListOf<String>()

        for (full in directories) {
            nodes.getOrPut(full) {
                FileTreeItem(
                    name = File(full).name,
                    fullPath = full,
                    isDir = true
                )
            }
            children += full
        }

        for (full in files) {
            nodes.getOrPut(full) {
                FileTreeItem(
                    name = File(full).name,
                    fullPath = full,
                    isDir = false
                )
            }
            children += full
        }

        item.children = children
    }
}
