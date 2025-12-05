package editor.lib

import java.net.URLConnection
import java.nio.file.Files
import java.nio.file.Path

/*
===============================================================
  FILE TREE INTERFACE
===============================================================
  Describes the required API for a generic line-based text buffer
  abstraction, independent of UI toolkit or rendering layer.
===============================================================
*/
interface IFileTree {
    val root: String

    fun toggle(path: String)
    fun flattened(): List<FileTreeEntry>
    fun refreshOpenNodes()
}

/*
===============================================================
  FILE TREE INTERFACE DATA TYPES
===============================================================
*/
data class FileTreeEntry(
    val name: String,
    val typ: String,      // "file" or "folder"
    val padding: Int,
    val fullPath: String,
    val isOpen: Boolean
) {

    fun detectType(): String? {
        // val path: Path = Path.of(fullPath)
        // // OS / JDK provider
        // val bySys = Files.probeContentType(path)?.let { return it }
//
        // // By name
        // val byName = URLConnection.guessContentTypeFromName(path.toString())?.let { return it }
        // val byBinary= Files.newInputStream(path).use { input ->
        //     URLConnection.guessContentTypeFromStream(input)
        // }?.let { return it }

        return null
    }
}

class FileTreeItem(
    val name: String,
    val fullPath: String,
    val isDir: Boolean,
    var isOpen: Boolean = false,
    var children: List<String> = emptyList()
)