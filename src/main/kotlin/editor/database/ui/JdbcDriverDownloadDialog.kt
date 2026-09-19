package editor.database.ui

import editor.database.driver.JdbcDownloadProgress
import editor.database.driver.JdbcDriverDownloadCancelled
import editor.ui.ModalDialogFrame
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import react.BaseComponent
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

/** Modal, cancelable driver installation surface. It stays open until the user confirms the outcome. */
class JdbcDriverDownloadDialog(
    styleSheet: StyleSheet,
    private val driverName: String,
    private val download: ((JdbcDownloadProgress) -> Unit, () -> Boolean) -> Path,
    private val onDismiss: () -> Unit,
    private val onFinished: (success: Boolean, path: Path?, message: String?) -> Unit = { _, _, _ -> },
    private val onInvalidate: () -> Unit = {}
) : BaseComponent(styleSheet) {
    private val frame = ModalDialogFrame(styleSheet)
    private val cancelRequested = AtomicBoolean(false)
    @Volatile private var started = false
    @Volatile private var done = false
    @Volatile private var success = false
    @Volatile private var message = "Preparing driver download..."
    @Volatile private var progress = JdbcDownloadProgress("Preparing")
    private var buttonRow = -1
    private var buttonHit: IntRange = IntRange.EMPTY

    fun start() {
        if (started) return
        started = true
        Thread({
            try {
                val path = download(
                    { update ->
                        progress = update
                        message = update.phase
                        onInvalidate()
                    },
                    cancelRequested::get
                )
                success = true
                message = "Driver installed: ${path.fileName}"
                done = true
                onFinished(true, path, message)
            } catch (cancelled: JdbcDriverDownloadCancelled) {
                success = false
                message = "Download cancelled."
                done = true
                onFinished(false, null, message)
            } catch (error: Throwable) {
                success = false
                message = error.message ?: error::class.simpleName ?: "Driver download failed"
                done = true
                onFinished(false, null, message)
            } finally {
                onInvalidate()
            }
        }, "jdbc-driver-download").apply { isDaemon = true }.start()
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val bounds = frame.bounds(canvas, minOf(cols, 82).coerceAtLeast(34), minOf(rows, 12).coerceAtLeast(9))
        val width = bounds.width
        val height = bounds.height
        val x = bounds.x
        val y = bounds.y
        val panel = frame.panelStyle()
        val button = frame.buttonStyle()
        val text = styleSheet.getStyle("content").withDefaults(panel.fg, panel.bg)
        val current = progress
        val status = message.replace(Regex("\\s+"), " ")
        val percent = percentage(current)
        val barWidth = (width - 8).coerceAtLeast(12)
        val filled = (barWidth * percent / 100).coerceIn(0, barWidth)
        val bar = "[" + "#".repeat(filled) + "-".repeat(barWidth - filled) + "] $percent%"

        frame.render(canvas, bounds, "Install JDBC driver: $driverName")
        canvas.withStyle(panel) {
            drawText(x + 2, y + 3, status.take(width - 4))
            drawText(x + 2, y + 4, current.currentArtifact.take(width - 4))
        }
        canvas.withStyle(text) {
            drawText(x + 2, y + 6, bar.take(width - 4).padEnd(width - 4, ' '))
            if (done) drawText(x + 2, y + 7, (if (success) "Success." else "Error: $status").take(width - 4))
            else drawText(x + 2, y + 7, "Downloading runtime dependencies...".take(width - 4))
        }

        buttonRow = y + height - 2
        val label = if (done) " OK " else if (cancelRequested.get()) " Cancelling " else " Cancel "
        val buttonX = x + width - label.length - 2
        buttonHit = buttonX until (buttonX + label.length)
        canvas.withStyle(button) { drawText(buttonX, buttonRow, label) }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> if (!done) cancelRequested.set(true)
            }
            onInvalidate()
            return true
        }
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            if (y == buttonRow && x in buttonHit) {
                if (done) onDismiss() else cancelRequested.set(true)
                onInvalidate()
            }
            return true
        }
        return true
    }

    private fun percentage(value: JdbcDownloadProgress): Int {
        if (value.artifactCount <= 0) return 0
        val completed = (value.artifactIndex - 1).coerceAtLeast(0).toDouble()
        val current = if (value.totalBytes > 0) {
            (value.downloadedBytes.toDouble() / value.totalBytes.toDouble()).coerceIn(0.0, 1.0)
        } else 0.0
        return ((completed + current) / value.artifactCount * 100.0).toInt().coerceIn(0, 100)
    }

}
