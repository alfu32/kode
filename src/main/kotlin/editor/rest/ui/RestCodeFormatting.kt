package editor.rest.ui

import java.util.Locale
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement

internal fun restLanguageForContentType(contentType: String?): String? {
    val type = contentType.orEmpty().lowercase(Locale.ROOT).substringBefore(';').trim()
    return when {
        type == "application/json" || type.endsWith("+json") -> "json"
        type == "application/yaml" || type == "text/yaml" || type.endsWith("+yaml") -> "yaml"
        type == "application/javascript" || type == "text/javascript" || type == "application/ecmascript" -> "javascript"
        type == "text/html" || type == "application/xhtml+xml" -> "html"
        type == "application/xml" || type == "text/xml" || type.endsWith("+xml") -> "xml"
        type == "application/graphql" -> "graphql"
        else -> null
    }
}

internal fun restPrettyPrint(content: String, contentType: String?, enabled: Boolean): String {
    if (!enabled || content.isBlank()) return content
    return when (restLanguageForContentType(contentType)) {
        "json", "graphql" -> runCatching {
            val formatter = Json { prettyPrint = true }
            formatter.encodeToString(JsonElement.serializer(), formatter.parseToJsonElement(content))
        }.getOrDefault(content)
        "xml", "html" -> prettyMarkup(content)
        "javascript" -> prettyJavaScript(content)
        "yaml" -> content.lines().joinToString("\n") { it.trimEnd() }
        else -> content
    }
}

private fun prettyMarkup(content: String): String {
    val tokens = content.replace("><", ">\n<").lines().map { it.trim() }.filter { it.isNotEmpty() }
    var depth = 0
    return tokens.joinToString("\n") { token ->
        if (token.startsWith("</")) depth = (depth - 1).coerceAtLeast(0)
        val line = "  ".repeat(depth) + token
        if (token.startsWith("<") && !token.startsWith("</") && !token.startsWith("<?") && !token.startsWith("<!") && !token.endsWith("/>") && !token.contains("</")) depth++
        line
    }
}

private fun prettyJavaScript(content: String): String {
    val output = StringBuilder()
    var indent = 0
    var quote: Char? = null
    var escaped = false
    fun newline() {
        while (output.endsWith(" ") || output.endsWith("\n")) output.deleteCharAt(output.lastIndex)
        output.append('\n').append("  ".repeat(indent))
    }
    content.forEach { char ->
        if (quote != null) {
            output.append(char)
            if (escaped) escaped = false else if (char == '\\') escaped = true else if (char == quote) quote = null
            return@forEach
        }
        when (char) {
            '\'', '"', '`' -> { quote = char; output.append(char) }
            '{' -> { output.append(" {"); indent++; newline() }
            '}' -> { indent = (indent - 1).coerceAtLeast(0); newline(); output.append('}') }
            ';' -> { output.append(';'); newline() }
            ',' -> { output.append(','); output.append(' ') }
            else -> output.append(char)
        }
    }
    return output.toString().trim()
}
