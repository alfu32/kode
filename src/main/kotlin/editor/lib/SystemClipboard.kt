package editor.lib

import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection

/** Best-effort JVM clipboard bridge with an in-process fallback for headless terminals. */
object SystemClipboard {
    private var fallback: String = ""

    fun setText(value: String) {
        fallback = value
        runCatching {
            Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(value), null)
        }
    }

    fun getText(): String {
        val system = runCatching {
            val clipboard = Toolkit.getDefaultToolkit().systemClipboard
            if (clipboard.isDataFlavorAvailable(DataFlavor.stringFlavor)) {
                clipboard.getData(DataFlavor.stringFlavor) as? String
            } else {
                null
            }
        }.getOrNull()
        return system ?: fallback
    }
}
