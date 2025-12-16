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
        )
    )

    private val languages: List<String> = loadLanguages()
    private var selectedLanguage: String? = null
    private var scrollOffset: Int = 0

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val style = styleSheet.getStyle("content")

        canvas.withStyle(style) {
            drawRect(0, 0, cols, rows)
            val textWidth = (cols - 2).coerceAtLeast(0)
            val lines = mutableListOf<String>()
            lines += "Kode version: $version"
            lines += "License: MIT (see LICENSE.md)"
            lines += ""
            lines += "Credits (license | source):"
            credits.forEach { credit ->
                lines += "- ${credit.name}"
                lines += "  ${credit.license} | ${credit.licenseUrl}"
                lines += "  source: ${credit.sourceUrl}"
            }
            val langHeaderRow = lines.size + 1
            val langStartRow = langHeaderRow + 1
            val langHeight = (rows - langStartRow).coerceAtLeast(0)
            val maxScroll = (languages.size - langHeight).coerceAtLeast(0)
            scrollOffset = scrollOffset.coerceIn(0, maxScroll)

            lines.take(rows).forEachIndexed { idx, line ->
                val content = line.take(textWidth).padEnd(textWidth, ' ')
                drawText(1, idx, content)
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
        val introRows = 4 + credits.size * 3
        val langHeaderRow = introRows + 1
        val langStartRow = langHeaderRow + 1
        val langHeight = (rows - langStartRow).coerceAtLeast(0)
        val maxScroll = (languages.size - langHeight).coerceAtLeast(0)
        scrollOffset = scrollOffset.coerceIn(0, maxScroll)

        if (event.kind == "mouse_scroll") {
            val delta = event.scrollDelta ?: 0
            scrollOffset = (scrollOffset - delta.sign()).coerceIn(0, maxScroll)
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
