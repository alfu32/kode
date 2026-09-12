package editor.ui

import editor.grammars.Token

/** Move cached paint with unchanged source; discard spans touched by an edit. */
internal fun remapHighlightTokens(old: String, new: String, tokens: List<Token>): List<Token> {
    if (old == new || tokens.isEmpty()) return tokens
    var start = 0
    while (start < minOf(old.length, new.length) && old[start] == new[start]) start++
    var oldEnd = old.length
    var newEnd = new.length
    while (oldEnd > start && newEnd > start && old[oldEnd - 1] == new[newEnd - 1]) {
        oldEnd--; newEnd--
    }
    fun starts(text: String) = (listOf(0) + text.indices.filter { text[it] == '\n' }.map { it + 1 }).toIntArray()
    val before = starts(old)
    val after = starts(new)
    return tokens.mapNotNull { token ->
        val lineStart = before.getOrNull(token.line) ?: return@mapNotNull null
        val a = lineStart + token.start
        val b = lineStart + token.end
        val shift = when {
            b <= start -> 0
            a >= oldEnd -> newEnd - oldEnd
            else -> return@mapNotNull null
        }
        val offset = a + shift
        val end = b + shift
        if (offset < 0 || end > new.length || offset >= end) return@mapNotNull null
        // Never paint text that no longer matches the source behind the cached token.
        if (new.substring(offset, end) != token.text) return@mapNotNull null
        fun Char?.isNamePart() = this != null && (isLetterOrDigit() || this == '_' || this == '$')
        if (token.text.firstOrNull().isNamePart() &&
            old.getOrNull(a - 1).isNamePart() != new.getOrNull(offset - 1).isNamePart()) return@mapNotNull null
        if (token.text.lastOrNull().isNamePart() &&
            old.getOrNull(b).isNamePart() != new.getOrNull(end).isNamePart()) return@mapNotNull null
        val search = after.binarySearch(offset)
        val line = if (search >= 0) search else -search - 2
        token.copy(line = line, start = offset - after[line], end = end - after[line])
    }
}
