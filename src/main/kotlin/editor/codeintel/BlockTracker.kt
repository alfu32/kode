package editor.codeintel

internal class BlockTracker(
    private val mode: BlockMode,
    beginRegex: String? = null,
    endRegex: String? = null
) {

    private val beginPattern = beginRegex?.let { Regex(it, setOf(RegexOption.IGNORE_CASE)) }
    private val endPattern = endRegex?.let { Regex(it, setOf(RegexOption.IGNORE_CASE)) }
    private var indentStack = ArrayDeque<Int>()
    private var depth = 0
    private var parenBalance = 0

    fun reset() {
        indentStack.clear()
        depth = 0
        parenBalance = 0
    }

    fun update(line: String) {
        when (mode) {
            BlockMode.BRACE -> updateBrace(line)
            BlockMode.PAREN -> updateParen(line)
            BlockMode.INDENT -> updateIndent(line)
            BlockMode.REGEX -> updateRegex(line)
            BlockMode.SQL -> updateSql(line)
            BlockMode.NONE -> {}
        }
    }

    fun currentDepth(): Int = when (mode) {
        BlockMode.PAREN -> parenBalance
        else -> depth
    }

    private fun updateBrace(line: String) {
        depth += line.count { it == '{' }
        depth -= line.count { it == '}' }
        if (depth < 0) depth = 0
    }

    private fun updateParen(line: String) {
        parenBalance += line.count { it == '(' }
        parenBalance -= line.count { it == ')' }
        if (parenBalance < 0) parenBalance = 0
    }

    private fun updateIndent(line: String) {
        val spaces = line.takeWhile { it == ' ' }.length
        val tabs = line.takeWhile { it == '\t' }.length
        val indent = spaces + tabs * 4
        if (indentStack.isEmpty()) {
            indentStack.addLast(indent)
            depth = 0
            return
        }
        val current = indentStack.last()
        when {
            indent > current -> {
                indentStack.addLast(indent)
                depth++
            }
            indent < current -> {
                while (indentStack.isNotEmpty() && indentStack.last() > indent) {
                    indentStack.removeLast()
                    depth = (depth - 1).coerceAtLeast(0)
                }
            }
        }
    }

    private fun updateRegex(line: String) {
        if (beginPattern?.containsMatchIn(line) == true) depth++
        if (endPattern?.containsMatchIn(line) == true) depth = (depth - 1).coerceAtLeast(0)
    }

    private fun updateSql(line: String) {
        val upper = line.uppercase()
        if (upper.contains("BEGIN")) depth++
        if (upper.contains("END")) depth = (depth - 1).coerceAtLeast(0)
    }
}
