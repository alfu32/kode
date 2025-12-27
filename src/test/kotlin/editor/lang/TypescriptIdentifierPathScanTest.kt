package editor.lang

import org.treesitter.TreeSitterTypescript
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertTrue

class TypescriptIdentifierPathScanTest {

    @Test
    fun scansIdentifierPathsInTypescriptCorpus() {
        val parser = TreeSitterParser(TreeSitterTypescript())
        val paths = sortedSetOf<String>()
        val root = resolveCorpusRoot()
        val maxFiles = System.getenv("TS_SCAN_MAX_FILES")?.toIntOrNull() ?: 250
        Files.walk(root).use { stream ->
            val files = stream
                .filter { it.isRegularFile() && it.extension in setOf("ts", "tsx") }
                .toList()
            val preferred = files.filterNot { it.toString().contains("/lib/") }
            val secondary = files.filter { it.toString().contains("/lib/") }
            val ordered = (preferred + secondary)
            var scanned = 0
            ordered.forEach { file ->
                if (scanned >= maxFiles) return@forEach
                val text = Files.readString(file)
                val tsRoot = parser.parse(text) ?: return@forEach
                val linear = TreeSitterLinearizer.linearize(tsRoot)
                linear.forEach { ln ->
                    if (ln.node.type in identifierTypes) {
                        paths.add(buildPathWithTokens(ln.node, file))
                    }
                }
                scanned += 1
            }
        }

        writeReport(paths, Paths.get("build/reports/typescript-identifier-paths.txt"))
        assertTrue(paths.isNotEmpty())
    }

    private fun resolveCorpusRoot(): Path {
        val srcRoot = Paths.get("samples/.corpus/typescript/src")
        if (Files.exists(srcRoot)) return srcRoot
        return Paths.get("samples/.corpus/typescript")
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
            "program", "source_file" -> file.toString()
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
            "identifier",
            "type_identifier",
            "property_identifier",
            "private_property_identifier",
            "shorthand_property_identifier_pattern"
        )
        private val nameNodes = setOf(
            "class_declaration",
            "interface_declaration",
            "enum_declaration",
            "type_alias_declaration",
            "function_declaration",
            "method_definition",
            "method_signature",
            "abstract_method_signature",
            "property_signature",
            "public_field_definition",
            "private_field_definition",
            "protected_field_definition",
            "variable_declarator",
            "lexical_declaration",
            "variable_declaration",
            "import_clause",
            "namespace_declaration",
            "module_declaration",
            "type_parameter",
            "constructor",
            "parameter"
        )
        private val largeBodyNodes = setOf(
            "class_body",
            "statement_block",
            "object",
            "object_type",
            "interface_body",
            "enum_body",
            "module_body",
            "namespace_body",
            "function_body",
            "arrow_function"
        )
    }
}
