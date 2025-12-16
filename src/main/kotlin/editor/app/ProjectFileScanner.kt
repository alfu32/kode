package editor.app

import java.io.File
import java.nio.file.Files
import java.nio.file.Path

object ProjectFileScanner {
    fun listFilesForIndex(root: Path, ignoreGlobs: List<String>): List<Path> {
        val ignoreRegexes = ignoreGlobs.mapNotNull { glob ->
            glob.trim().takeIf { it.isNotEmpty() }?.let { globToRegex(it) }
        }
        val files = mutableListOf<Path>()
        Files.walk(root).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { path ->
                    val rel = root.relativize(path).toString()
                    val name = path.fileName.toString()
                    if (name.startsWith(".")) return@filter false
                    if (rel.contains("${File.separator}.git${File.separator}")) return@filter false
                    if (rel.contains("${File.separator}build${File.separator}")) return@filter false
                    ignoreRegexes.none { regex -> regex.matches(rel) }
                }
                .forEach { files.add(it) }
        }
        return files
    }

    fun globToRegex(glob: String): Regex {
        val sb = StringBuilder("^")
        glob.forEach { c ->
            when (c) {
                '*' -> sb.append(".*")
                '?' -> sb.append('.')
                '.' -> sb.append("\\.")
                '\\' -> sb.append("\\\\")
                else -> sb.append(c)
            }
        }
        sb.append('$')
        return Regex(sb.toString())
    }
}
