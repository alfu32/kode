package react

import java.io.File
import kotlin.collections.iterator

/* =====================================================================
   STYLE SYSTEM (as provided by Uuu)
   ===================================================================== */
class StyleSheet(
    val defaultStyle: StyleSet = StyleSet(),
    val rules: Map<String, StyleSet> = emptyMap()
) {

    fun getStyle(styleId: String): StyleSet {
        return rules.filter { (key,style) ->
            key==styleId
        }.values.fold(defaultStyle.copy().apply { styleName="default" }) {
            acc: StyleSet,stl: StyleSet ->
            acc.merged(stl)
        }
    }

    fun getStyle0(styleId: String): StyleSet {
        val matchingKeys = rules.keys.filter { key ->
            val path = splitPathKey(key)
            path.isNotEmpty() && path.last() == styleId
        }
        if (matchingKeys.isEmpty()) return defaultStyle.copy().apply { styleName="default" }

        val bestKey = matchingKeys.maxByOrNull { splitPathKey(it).size }!!
        val bestPath = splitPathKey(bestKey)

        val result = defaultStyle.copy()
        val prefix = mutableListOf<String>()
        for (segment in bestPath) {
            prefix += segment
            val pk = prefix.joinToString(".")
            rules[pk]?.let { result.mergeFrom(it) }
        }
        return result
    }

    companion object {
        fun loadFromFiles(files: List<String>): StyleSheet {
            val aggregateDefault = StyleSet()
            val aggregateRules = mutableMapOf<String, StyleSet>()

            for (path in files) {
                val css = try {
                    File(path).takeIf { it.exists() }?.readText()
                } catch (_: Exception) { null } ?: continue

                val sheet = parse(css)
                aggregateDefault.mergeFrom(sheet.defaultStyle)
                for ((k, v) in sheet.rules) {
                    val target = aggregateRules.getOrPut(k) { StyleSet() }
                    target.mergeFrom(v)
                }
            }

            return StyleSheet(aggregateDefault, aggregateRules)
        }

        fun parse(css: String): StyleSheet {
            val defaultStyle = StyleSet()
            val rules = mutableMapOf<String, StyleSet>()

            for (chunk in css.split('}')) {
                val parts = chunk.split('{', limit = 2)
                if (parts.size < 2) continue

                val selectorRaw = parts[0].trim()
                if (selectorRaw.isEmpty()) continue

                val body = parts[1]
                val style = StyleSet.parse(body)

                if (selectorRaw == "default") {
                    defaultStyle.mergeFrom(style)
                } else {
                    val segments = selectorRaw
                        .replace(">", " ")
                        .split(Regex("\\s+"))
                        .filter { it.isNotEmpty() }

                    if (segments.isNotEmpty()) {
                        val key = segments.joinToString(".")
                        val existing = rules.getOrPut(key) { StyleSet() }
                        existing.mergeFrom(style)
                    }
                }
            }

            return StyleSheet(defaultStyle, rules)
        }

        private fun splitPathKey(key: String): List<String> =
            if (key.isBlank()) emptyList() else key.split('.')
    }
}
