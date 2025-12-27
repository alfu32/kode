package editor.lang

import org.treesitter.TreeSitterKotlin
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinIdentifierPathScanTest {

    @Test
    fun scansIdentifierPathsInProjectSources() {
        val parser = TreeSitterParser(TreeSitterKotlin())
        val paths = sortedSetOf<String>()
        val root = Paths.get("src/main/kotlin")
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "kt" }.forEach { file ->
                val text = Files.readString(file)
                val tsRoot = parser.parse(text) ?: return@forEach
                val linear = TreeSitterLinearizer.linearize(tsRoot)
                linear.forEach { ln ->
                    if (ln.node.type in identifierTypes) {
                        paths.add(buildPathWithTokens(ln.node, file))
                    }
                }
            }
        }

        writeReport(paths, Paths.get("build/reports/kotlin-identifier-paths.txt"))
        assertTrue(paths.isNotEmpty())
    }

    private fun writeReport(lines: Set<String>, path: Path) {
        Files.createDirectories(path.parent)
        Files.writeString(path, lines.joinToString("\n"))
    }

    private fun buildPathWithTokens(node: TsNode, file: Path): String {
        val chain = mutableListOf<TsNode>()
        var cursor: TsNode? = node
        while (cursor != null) {
            chain.add(cursor)
            cursor = cursor.parent()
        }
        chain.reverse()
        return chain.joinToString(" / ") { formatNode(it, file) }
    }

    private fun formatNode(node: TsNode, file: Path): String {
        val token = when (node.type) {
            "source_file" -> file.toString()
            in largeBodyNodes -> "{...}"
            in nameNodes -> pickNameToken(node)
            else -> safeToken(node.text)
        }
        val field = node.fieldName()
        val suffix = if (field.isNullOrBlank()) "" else "[field:$field]"
        return "${node.type}($token)$suffix"
    }

    private fun pickNameToken(node: TsNode): String {
        if (node.type in identifierTypes) return safeToken(node.text)
        val stack = ArrayDeque<TsNode>()
        stack.add(node)
        while (stack.isNotEmpty()) {
            val next = stack.removeLast()
            if (next.type in identifierTypes) return safeToken(next.text)
            next.children().reversed().forEach { stack.add(it) }
        }
        return safeToken(node.text)
    }

    private fun safeToken(text: String): String {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return "{...}"
        if (trimmed.contains('\n')) return "{...}"
        if (trimmed.length > 40) return "{...}"
        return trimmed
    }

    companion object {
        private val identifierTypes = setOf(
            "simple_identifier",
            "identifier",
            "type_identifier",
            "package_identifier"
        )
        private val nameNodes = setOf(
            "class_declaration",
            "object_declaration",
            "companion_object",
            "interface_declaration",
            "enum_class_declaration",
            "enum_declaration",
            "type_alias",
            "function_declaration",
            "property_declaration",
            "value_parameter",
            "parameter",
            "class_parameter",
            "enum_entry",
            "variable_declaration"
        )
        private val largeBodyNodes = setOf(
            "class_body",
            "function_body",
            "block",
            "lambda_literal",
            "statements",
            "control_structure_body"
        )
    }
}
