package editor.ui

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class AboutView(
    styleSheet: StyleSheet,
    private val version: String
) : BaseComponent(styleSheet) {

    private data class Credit(
        val name: String,
        val license: String,
        val licenseUrl: String,
        val sourceUrl: String
    )

    private val credits = listOf(
        Credit(
            name = "JGit 7.4.0",
            license = "EDL-1.0 / EPL-2.0",
            licenseUrl = "https://www.eclipse.org/legal/epl-2.0/",
            sourceUrl = "https://www.eclipse.org/jgit/"
        ),
        Credit(
            name = "Korim / Korio 4.0.10",
            license = "MIT",
            licenseUrl = "https://opensource.org/licenses/MIT",
            sourceUrl = "https://github.com/korlibs/korlibs"
        ),
        Credit(
            name = "kotlinx-coroutines 1.10.2",
            license = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://github.com/Kotlin/kotlinx.coroutines"
        ),
        Credit(
            name = "kotlinx-serialization-json 1.7.3",
            license = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://github.com/Kotlin/kotlinx.serialization"
        ),
        Credit(
            name = "H2 Database 2.2.224",
            license = "MPL-2.0 / EPL-1.0",
            licenseUrl = "https://h2database.com/html/license.html",
            sourceUrl = "https://h2database.com"
        ),
        Credit(
            name = "JNA 5.14.0",
            license = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://github.com/java-native-access/jna"
        ),
        Credit(
            name = "Tree-sitter core 0.25.3",
            license = "MIT",
            licenseUrl = "https://opensource.org/licenses/MIT",
            sourceUrl = "https://github.com/tree-sitter/tree-sitter"
        ),
        Credit(
            name = "Tree-sitter grammars (bonede): TS/JS, Python, JSON, C, Kotlin, SQL, PHP, CSS, HTML, Zig, Markdown, Swift, Lua, C++, Svelte, Bash, Go, Perl, Nim, D, YAML, Pascal, Ruby, OCaml, C#",
            license = "MIT",
            licenseUrl = "https://opensource.org/licenses/MIT",
            sourceUrl = "https://github.com/bonede"
        )
    )

    private val languages: List<String> = loadLanguages()
    private var selectedLanguage: String? = null
    private var creditsScroll: Int = 0
    private var scrollOffset: Int = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val style = styleSheet.getStyle("content")

        canvas.withStyle(style) {
            drawRect(0, 0, cols, rows)
            val textWidth = (cols - 2).coerceAtLeast(0)
            val headerLines = listOf(
                "Kode version: $version",
                "License: MIT (see LICENSE.md)",
                ""
            )

            headerLines.forEachIndexed { idx, line ->
                val content = line.take(textWidth).padEnd(textWidth, ' ')
                drawText(1, idx, content)
            }

            val creditHeaderRow = headerLines.size
            val langMinRows = 5
            val creditStart = creditHeaderRow + 1
            val creditHeight = (rows - creditStart - langMinRows).coerceAtLeast(3)
            val langHeaderRow = creditStart + creditHeight
            val langStartRow = langHeaderRow + 1
            val langHeight = (rows - langStartRow).coerceAtLeast(0)

            val creditLines = buildCreditsLines()
            val creditMaxScroll = (creditLines.size - creditHeight).coerceAtLeast(0)
            creditsScroll = creditsScroll.coerceIn(0, creditMaxScroll)
            val langMaxScroll = (languages.size - langHeight).coerceAtLeast(0)
            scrollOffset = scrollOffset.coerceIn(0, langMaxScroll)

            val creditHeader = "Credits (license | source):"
            if (creditHeaderRow < rows) {
                drawText(1, creditHeaderRow, creditHeader.take(textWidth).padEnd(textWidth, ' '))
            }
            creditLines.drop(creditsScroll).take(creditHeight).forEachIndexed { idx, line ->
                val y = creditStart + idx
                if (y >= rows) return@forEachIndexed
                val content = line.take(textWidth).padEnd(textWidth, ' ')
                drawText(1, y, content)
            }

            if (langHeaderRow < rows) {
                val header = "Supported languages:"
                drawText(1, langHeaderRow, header.take(textWidth).padEnd(textWidth, ' '))
            }
            if (langHeight > 0) {
                val selectedStyle = style.copy().apply {
                    val fgOld = fg
                    fg = bg
                    bg = fgOld
                }
                languages.drop(scrollOffset).take(langHeight).forEachIndexed { idx, lang ->
                    val y = langStartRow + idx
                    if (y >= rows) return@forEachIndexed
                    val content = "- $lang".take(textWidth).padEnd(textWidth, ' ')
                    val isSelected = lang == selectedLanguage
                    canvas.withStyle(if (isSelected) selectedStyle else style) {
                        drawText(1, y, content)
                    }
                }
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        val cols = event.cols?.coerceAtLeast(1) ?: return false
        val rows = event.rows?.coerceAtLeast(1) ?: return false
        val headerLines = 3
        val creditHeaderRow = headerLines
        val creditStart = creditHeaderRow + 1
        val langMinRows = 5
        val creditHeight = (rows - creditStart - langMinRows).coerceAtLeast(3)
        val langHeaderRow = creditStart + creditHeight
        val langStartRow = langHeaderRow + 1
        val langHeight = (rows - langStartRow).coerceAtLeast(0)

        val creditLines = buildCreditsLines()
        val creditMaxScroll = (creditLines.size - creditHeight).coerceAtLeast(0)
        val langMaxScroll = (languages.size - langHeight).coerceAtLeast(0)
        creditsScroll = creditsScroll.coerceIn(0, creditMaxScroll)
        scrollOffset = scrollOffset.coerceIn(0, langMaxScroll)

        if (event.kind == "mouse_scroll") {
            val delta = event.scrollDelta ?: 0
            val y = event.y ?: 0
            if (y in creditStart until (creditStart + creditHeight)) {
                creditsScroll = (creditsScroll - delta.sign()).coerceIn(0, creditMaxScroll)
            } else if (y >= langStartRow) {
                scrollOffset = (scrollOffset - delta.sign()).coerceIn(0, langMaxScroll)
            }
            return true
        }
        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            if (y in langStartRow until (langStartRow + langHeight)) {
                val idx = scrollOffset + (y - langStartRow)
                languages.getOrNull(idx)?.let {
                    selectedLanguage = it
                    return true
                }
            }
        }
        return false
    }

    private fun buildCreditsLines(): List<String> {
        val lines = mutableListOf<String>()
        credits.forEach { credit ->
            lines += "- ${credit.name}"
            lines += "  ${credit.license} | ${credit.licenseUrl}"
            lines += "  source: ${credit.sourceUrl}"
        }
        return lines
    }

    private fun loadLanguages(): List<String> {
        val path = Paths.get("codeintel/definitions.json")
        if (!Files.exists(path)) return emptyList()
        val text = runCatching { Files.readString(path) }.getOrNull() ?: return emptyList()
        val regex = Regex("\\\"language\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        return regex.findAll(text)
            .map { it.groupValues[1] }
            .distinct()
            .sorted()
            .toList()
    }

    private fun Int.sign(): Int = when {
        this > 0 -> 1
        this < 0 -> -1
        else -> 0
    }
}
