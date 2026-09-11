package editor.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.nio.file.Path

class ProjectSettingsView(
    styleSheet: StyleSheet,
    private val projectRootProvider: () -> Path,
    private val sourceRootsProvider: () -> List<String>,
    private val onRequestAdd: () -> Unit,
    private val onRemoveSource: (String) -> String?,
    private val exclusionsProvider: () -> List<String> = { emptyList() },
    private val onRequestAddExclusion: () -> Unit = {},
    private val onRemoveExclusion: (String) -> String? = { null },
) : BaseComponent(styleSheet) {

    private data class SettingEntry(val value: String, val exclusion: Boolean)
    private data class ButtonHit(val range: IntRange, val action: ButtonAction)
    private enum class ButtonAction { ADD_SOURCE, ADD_EXCLUSION, REMOVE }

    private var selectedIdx: Int = 0
    private var scrollTop: Int = 0
    private var lastMessage: String = ""
    private val buttonHits = mutableMapOf<Int, List<ButtonHit>>()

    override fun render(canvas: CanvasRenderer) {
        buttonHits.clear()
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val baseStyle = styleSheet.getStyle("content")
        canvas.withStyle(baseStyle) { drawRect(0, 0, cols, rows) }

        val header = headerLines()
        header.take(rows).forEachIndexed { idx, line ->
            canvas.withStyle(baseStyle) {
                drawText(0, idx, line.take(cols).padEnd(cols, ' '))
            }
        }

        val listStart = header.size
        renderEntries(canvas, cols, listStart, (rows - listStart).coerceAtLeast(0), baseStyle)
    }

    override fun dispatch(event: UIEvent): Boolean {
        val entries = settingEntries()
        val listStart = headerLines().size
        val listRows = ((event.rows ?: 0) - listStart).coerceAtLeast(0)
        selectedIdx = if (entries.isEmpty()) 0 else selectedIdx.coerceIn(entries.indices)

        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            val x = event.x ?: return false
            buttonHits[y]?.firstOrNull { x in it.range }?.let { hit ->
                handleAction(hit.action, entries)
                return true
            }
            if (y >= listStart) {
                val idx = scrollTop + y - listStart
                if (idx in entries.indices) {
                    selectedIdx = idx
                    ensureSelectionVisible(listRows, entries.size)
                    return true
                }
            }
            return false
        }

        if (event.kind == "mouse_scroll") {
            val delta = event.scrollDelta ?: return false
            val maxScroll = (entries.size - listRows).coerceAtLeast(0)
            val previous = scrollTop
            scrollTop = (scrollTop - delta).coerceIn(0, maxScroll)
            return previous != scrollTop
        }

        if (event.kind != "key_down") return false
        val key = event.key?.lowercase() ?: return false
        return when (key) {
            "up" -> moveSelection(entries, -1, listRows)
            "down" -> moveSelection(entries, 1, listRows)
            "pageup" -> moveSelection(entries, -listRows, listRows)
            "pagedown" -> moveSelection(entries, listRows, listRows)
            "home" -> moveSelection(entries, -selectedIdx, listRows)
            "end" -> moveSelection(entries, (entries.lastIndex - selectedIdx).coerceAtLeast(0), listRows)
            "a" -> {
                onRequestAdd()
                true
            }
            "x" -> {
                onRequestAddExclusion()
                true
            }
            "d", "backspace", "delete" -> {
                removeSelected(entries)
                true
            }
            else -> false
        }
    }

    fun setMessage(message: String) {
        lastMessage = message
    }

    private fun headerLines(): List<String> = listOf(
        "Project Settings",
        "Root: ${projectRootProvider()}",
        "Source roots: ${sourceRootsProvider().size}   Scan exclusions: ${exclusionsProvider().size}",
        lastMessage,
        "",
    )

    private fun settingEntries(): List<SettingEntry> =
        sourceRootsProvider().map { SettingEntry(it, exclusion = false) } +
            exclusionsProvider().map { SettingEntry(it, exclusion = true) }

    private fun renderEntries(
        canvas: CanvasRenderer,
        cols: Int,
        startRow: Int,
        rows: Int,
        baseStyle: StyleSet,
    ) {
        if (rows <= 0) return
        val entries = settingEntries()
        val listStyle = styleSheet.getStyle("file-entry")
        val selectedStyle = styleSheet.getStyle("file-entry:selected")
        val buttonStyle = styleSheet.getStyle("lsp-button")
        renderButtons(canvas, (startRow - 1).coerceAtLeast(0), cols, buttonStyle)

        selectedIdx = if (entries.isEmpty()) 0 else selectedIdx.coerceIn(entries.indices)
        val maxScroll = (entries.size - rows).coerceAtLeast(0)
        scrollTop = scrollTop.coerceIn(0, maxScroll)
        if (entries.isEmpty()) {
            canvas.withStyle(baseStyle) {
                drawText(0, startRow, "No source roots or scan exclusions. Use [add source] or [add exclusion].".take(cols).padEnd(cols, ' '))
            }
            return
        }

        entries.drop(scrollTop).take(rows).forEachIndexed { idx, entry ->
            val absIdx = scrollTop + idx
            val style = if (absIdx == selectedIdx) selectedStyle else listStyle
            val prefix = if (entry.exclusion) "[exclude] " else "[source]  "
            val label = prefix + formatPath(entry.value)
            canvas.withStyle(style) {
                drawText(0, startRow + idx, label.take(cols).padEnd(cols, ' '))
            }
        }
    }

    private fun renderButtons(canvas: CanvasRenderer, y: Int, cols: Int, buttonStyle: StyleSet) {
        if (y < 0) return
        val baseStyle = styleSheet.getStyle("content")
        val buttons = listOf(
            "[source(a)]" to ButtonAction.ADD_SOURCE,
            "[exclude(x)]" to ButtonAction.ADD_EXCLUSION,
            "[remove(d)]" to ButtonAction.REMOVE,
        )
        var cursor = 0
        val hits = mutableListOf<ButtonHit>()
        buttons.forEach { (label, action) ->
            val padded = " $label "
            if (cursor + padded.length > cols) return@forEach
            canvas.withStyle(buttonStyle) { drawText(cursor, y, padded) }
            hits += ButtonHit(cursor until cursor + padded.length, action)
            cursor += padded.length + 1
        }
        if (hits.isNotEmpty()) buttonHits[y] = hits
        if (cursor < cols) canvas.withStyle(baseStyle) { drawText(cursor, y, " ".repeat(cols - cursor)) }
    }

    private fun formatPath(value: String): String =
        when {
            value.isBlank() || value == "." -> "(project root)"
            value.startsWith("./") -> value.removePrefix("./")
            else -> value
        }

    private fun moveSelection(entries: List<SettingEntry>, delta: Int, listRows: Int): Boolean {
        if (entries.isEmpty()) return false
        val target = (selectedIdx + delta).coerceIn(entries.indices)
        if (target == selectedIdx) return false
        selectedIdx = target
        ensureSelectionVisible(listRows, entries.size)
        return true
    }

    private fun ensureSelectionVisible(listRows: Int, total: Int) {
        if (listRows <= 0) return
        if (selectedIdx < scrollTop) scrollTop = selectedIdx
        else if (selectedIdx >= scrollTop + listRows) scrollTop = selectedIdx - listRows + 1
        scrollTop = scrollTop.coerceIn(0, (total - listRows).coerceAtLeast(0))
    }

    private fun handleAction(action: ButtonAction, entries: List<SettingEntry>) {
        when (action) {
            ButtonAction.ADD_SOURCE -> onRequestAdd()
            ButtonAction.ADD_EXCLUSION -> onRequestAddExclusion()
            ButtonAction.REMOVE -> removeSelected(entries)
        }
    }

    private fun removeSelected(entries: List<SettingEntry>) {
        val target = entries.getOrNull(selectedIdx) ?: return
        lastMessage = if (target.exclusion) {
            onRemoveExclusion(target.value)
        } else {
            onRemoveSource(target.value)
        } ?: ""
    }
}
