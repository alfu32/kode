package editor.ui

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
            name = "pty4j 0.13.1",
            license = "Apache-2.0",
            licenseUrl = "https://www.apache.org/licenses/LICENSE-2.0",
            sourceUrl = "https://github.com/JetBrains/pty4j"
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
            lines.take(rows).forEachIndexed { idx, line ->
                val content = line.take(textWidth).padEnd(textWidth, ' ')
                drawText(1, idx, content)
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean = false
}
