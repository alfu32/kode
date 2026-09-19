package editor.rest.model

/** Text format used by the environment editor: one escaped key=value pair per line. */
object RestEnvironmentCodec {
    fun parse(text: String): List<RestEnvironmentValue> = text.lineSequence()
        .map(String::trim)
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val separator = findSeparator(line)
            if (separator <= 0) return@mapNotNull null
            val key = unescape(line.substring(0, separator).trim())
            if (key.isBlank()) return@mapNotNull null
            RestEnvironmentValue(key, unescape(line.substring(separator + 1).trim()))
        }
        .toList()

    fun format(values: List<RestEnvironmentValue>): String = values.joinToString("\n") { value ->
        escape(value.key) + "=" + escape(value.value)
    }

    private fun findSeparator(value: String): Int {
        var escaped = false
        value.forEachIndexed { index, character ->
            when {
                escaped -> escaped = false
                character == '\\' -> escaped = true
                character == '=' -> return index
            }
        }
        return -1
    }

    private fun escape(value: String): String = buildString {
        value.forEach { character ->
            if (character == '\\' || character == '=') append('\\')
            append(character)
        }
    }

    private fun unescape(value: String): String = buildString {
        var escaped = false
        value.forEach { character ->
            if (escaped) {
                if (character == '=' || character == '\\') append(character) else append('\\').append(character)
                escaped = false
            } else if (character == '\\') {
                escaped = true
            } else {
                append(character)
            }
        }
        if (escaped) append('\\')
    }
}
