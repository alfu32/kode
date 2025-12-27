package editor.lang

import editor.lang.kotlin.KotlinIdentifierExtractor
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import kotlin.io.path.extension
import kotlin.io.path.isRegularFile
import kotlin.test.Test
import kotlin.test.assertTrue

class KotlinIdentifierOccurrenceIntegrationTest {

    @Test
    fun extractsOccurrencesFromProjectSources() {
        val extractor = KotlinIdentifierExtractor()
        val occurrences = mutableListOf<IdentifierOccurrence>()
        val root = Paths.get("src/main/kotlin")
        Files.walk(root).use { stream ->
            stream.filter { it.isRegularFile() && it.extension == "kt" }.forEach { file ->
                val text = Files.readString(file)
                occurrences += extractor.extract(text, file.toString())
            }
        }

        writeReport(occurrences, Paths.get("build/reports/kotlin-identifier-occurrences.txt"))
        assertTrue(occurrences.isNotEmpty())
    }

    private fun writeReport(list: List<IdentifierOccurrence>, path: Path) {
        Files.createDirectories(path.parent)
        val lines = list
            .sortedWith(
                compareBy<IdentifierOccurrence> { it.fileName }
                    .thenBy { it.lineNumber }
                    .thenBy { it.charPosition }
            )
            .joinToString("\n") { occ ->
                "${occ.fileName}:${occ.lineNumber}:${occ.charPosition} ${occ.kind} ${occ.type} ${occ.identifier}"
            }
        Files.writeString(path, lines)
    }
}
