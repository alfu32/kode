package editor.ui

import editor.lsp.InstallState
import editor.lsp.LspManager
import editor.lsp.LspService
import editor.lsp.LspServerStatus
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class SettingsView(
    styleSheet: StyleSheet,
    private val lspService: LspService,
    private val lspManager: LspManager
) : BaseComponent(styleSheet) {

    private var statuses: List<LspServerStatus> = lspService.statuses()
    private var selectedIdx: Int = 0
    private var lastMessage: String = ""
    private val cardHeight = 4

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val contentStyle = styleSheet.getStyle("content")
        val style = attachBackground(mergeStyles(styleSheet.getStyle("lsp-list"), contentStyle), contentStyle)
        val active = attachBackground(mergeStyles(styleSheet.getStyle("lsp-card-selected"), style), style)
        val headerLines = headerLines()
        canvas.withStyle(style) {
            drawRect(0, 0, cols, rows)
            headerLines.take(rows).forEachIndexed { idx, line ->
                drawText(0, idx, line.take(cols).padEnd(cols, ' '))
            }
        }
        val listStart = headerLines.size
        val visibleCards = ((rows - listStart) / cardHeight).coerceAtLeast(0)
        statuses.take(visibleCards).forEachIndexed { idx, status ->
            val row = listStart + (idx * cardHeight)
            val selected = idx == selectedIdx
            renderCard(canvas, cols, row, status, selected, style, active)
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            val idx = (y - headerLines().size) / cardHeight
            if (idx in statuses.indices) {
                selectedIdx = idx
                return true
            }
            return false
        }
        if (event.kind != "key_down") return false
        when (event.key?.lowercase()) {
            "up" -> {
                if (statuses.isNotEmpty()) {
                    selectedIdx = ((selectedIdx - 1) + statuses.size) % statuses.size
                }
                return true
            }
            "down" -> {
                if (statuses.isNotEmpty()) {
                    selectedIdx = (selectedIdx + 1) % statuses.size
                }
                return true
            }
            "backspace", "delete" -> {
                val status = statuses.getOrNull(selectedIdx) ?: return false
                val removed = lspManager.uninstall(status.entry.id)
                lastMessage = if (removed) "Removed ${status.entry.name}" else "Nothing to remove"
                refresh()
                return true
            }
            "s" -> {
                val status = statuses.getOrNull(selectedIdx) ?: return false
                val result = if (status.running) lspService.stopServer(status.entry.id) else lspService.startServer(status.entry.id)
                lastMessage = result.message
                refresh()
                return true
            }
            "r" -> {
                val status = statuses.getOrNull(selectedIdx) ?: return false
                val result = lspService.restartServer(status.entry.id)
                lastMessage = result.message
                refresh()
                return true
            }
            "y" -> {
                val status = statuses.getOrNull(selectedIdx) ?: return false
                val removed = lspManager.uninstall(status.entry.id)
                lastMessage = if (removed) "Uninstalled ${status.entry.name}" else "Nothing to uninstall"
                refresh()
                return true
            }
            "enter", " " -> {
                val status = statuses.getOrNull(selectedIdx) ?: return false
                val result = lspManager.install(status.entry.id)
                lastMessage = result.message
                refresh()
                return true
            }
            "f" -> {
                refresh()
                lastMessage = "Catalog refreshed"
                return true
            }
        }
        return false
    }

    private fun headerLines(): List<String> {
        val lines = mutableListOf<String>()
        lines += "LSP Servers (install root: ${lspManager.installLocation()})"
        lspManager.catalogLocation()?.let { lines += "Catalog: $it" } ?: lines.add("Catalog: missing")
        lines += "s=start/stop | r=restart | Enter/Space=install/update"
        lines += "y=uninstall | Backspace=remove | f=refresh"
        lines += lastMessage.takeIf { it.isNotBlank() } ?: ""
        lines += ""
        return lines
    }

    private fun refresh() {
        lspManager.reloadCatalog()
        statuses = lspService.statuses()
        selectedIdx = when {
            statuses.isEmpty() -> 0
            selectedIdx >= statuses.size -> statuses.lastIndex
            selectedIdx < 0 -> 0
            else -> selectedIdx
        }
    }

    private fun renderCard(
        canvas: CanvasRenderer,
        cols: Int,
        row: Int,
        status: LspServerStatus,
        selected: Boolean,
        baseStyle: StyleSet,
        activeStyle: StyleSet
    ) {
        val bgStyle = if (selected) activeStyle else baseStyle
        val indicator = if (selected) ">" else " "
        val languages = status.entry.languages.joinToString(",").ifBlank { status.entry.id }
        val installType = installationType(status)
        val name = status.entry.name
        val installStatus = installationStatus(status)

        val cardBg = bgStyle.bg
        val langStyle = bgStyle.copy().apply {
            val s = styleSheet.getStyle("lsp-lang")
            fg = s.fg ?: fg
            textDecoration = mergeDecorations(textDecoration, s.textDecoration)
            if (bg == null) bg = cardBg
        }
        val nameStyle = bgStyle.copy().apply {
            val s = styleSheet.getStyle("lsp-name")
            fg = s.fg ?: fg
            textDecoration = mergeDecorations(textDecoration, s.textDecoration)
            if (bg == null) bg = cardBg
        }
        val statusStyle = bgStyle.copy().apply {
            val s = statusStyleFor(status)
            fg = s.fg ?: fg
            textDecoration = mergeDecorations(textDecoration, s.textDecoration)
            if (bg == null) bg = cardBg
        }

        canvas.withStyle(bgStyle) {
            drawRect(0, row, cols, cardHeight)
        }
        // Line 1: indicator + languages (bold) on the left, install type on the right.
        val line1Y = row
        val left1 = "$indicator $languages"
        val right1 = installType
        if (line1Y < canvas.rows()) {
            canvas.withStyle(bgStyle) { drawText(0, line1Y, " ".repeat(cols)) }
            canvas.withStyle(langStyle) {
                drawText(0, line1Y, left1.take(cols).padEnd(cols, ' '))
            }
            val rightX = (cols - right1.length).coerceAtLeast(left1.length + 1)
            canvas.withStyle(bgStyle) {
                drawText(rightX, line1Y, right1.take(cols))
            }
        }
        // Line 2: name (italic) on the left, status on the right with color.
        val line2Y = row + 1
        if (line2Y < canvas.rows()) {
            canvas.withStyle(bgStyle) { drawText(0, line2Y, " ".repeat(cols)) }
            canvas.withStyle(nameStyle) {
                drawText(0, line2Y, "  ${name.take(cols)}".padEnd(cols, ' '))
            }
            val rightX = (cols - installStatus.length).coerceAtLeast(name.length + 3)
            canvas.withStyle(statusStyle) {
                drawText(rightX, line2Y, installStatus.take(cols))
            }
        }
        // Line 3: action buttons.
        val line3Y = row + 2
        if (line3Y < canvas.rows()) {
            canvas.withStyle(bgStyle) { drawText(0, line3Y, " ".repeat(cols)) }
            renderButtons(canvas, line3Y, cols, status, bgStyle)
        }
        // Line 4: error/message
        val line4Y = row + 3
        if (line4Y < canvas.rows()) {
            val msg = status.message.orEmpty()
            val msgStyle = mergeStyles(styleSheet.getStyle("lsp-error"), bgStyle)
            canvas.withStyle(if (msg.isNotBlank()) msgStyle else bgStyle) {
                drawText(0, line4Y, msg.take(cols).padEnd(cols, ' '))
            }
        }
    }

    private fun installationStatus(status: LspServerStatus): String {
        val latest = status.entry.versions.firstOrNull()?.version
        val installed = status.installedVersion
        val base = when (status.installState) {
            InstallState.INSTALLED -> installed?.let { "installed v$it" } ?: "installed"
            InstallState.UPDATE_AVAILABLE -> "update: have ${installed ?: "?"}, latest ${latest ?: "?"}"
            InstallState.MANUAL -> "manual install required"
            InstallState.MISSING -> "not installed"
            InstallState.ERROR -> status.message ?: "error"
        }
        return if (status.running) "$base (running)" else base
    }

    private fun installationType(status: LspServerStatus): String {
        if (status.entry.versions.isEmpty()) return "manual"
        return if (status.installState == InstallState.MANUAL) "manual" else "managed"
    }

    private fun statusStyleFor(status: LspServerStatus): StyleSet {
        val key = when (status.installState) {
            InstallState.INSTALLED -> "lsp-status-installed"
            InstallState.UPDATE_AVAILABLE -> "lsp-status-update"
            InstallState.MANUAL, InstallState.MISSING, InstallState.ERROR -> "lsp-status-missing"
        }
        return styleSheet.getStyle(key)
    }

    private fun mergeStyles(primary: StyleSet, fallback: StyleSet): StyleSet {
        val merged = fallback.copy()
        merged.mergeFrom(primary)
        return merged
    }

    private fun renderButtons(
        canvas: CanvasRenderer,
        y: Int,
        cols: Int,
        status: LspServerStatus,
        baseStyle: StyleSet
    ) {
        val buttonBase = styleSheet.getStyle("lsp-button")
        val buttonStyle = StyleSet(
            bg = buttonBase.bg ?: baseStyle.bg,
            fg = buttonBase.fg ?: baseStyle.fg,
            textDecoration = mergeDecorations(buttonBase.textDecoration, null)
        )
        val buttons = mutableListOf<String>()
        buttons += if (status.running) "[stop(s)]" else "[start(s)]"
        buttons += "[restart(r)]"
        buttons += "[${installLabel(status)}]"
        buttons += "[${uninstallLabel(status)}]"
        var cursor = 0
        buttons.forEach { label ->
            val padded = " $label "
            if (cursor + padded.length > cols) return
            canvas.withStyle(buttonStyle) { drawText(cursor, y, padded) }
            cursor += padded.length + 1
        }
        // fill rest of line with base style
        if (cursor < cols) {
            canvas.withStyle(baseStyle) { drawText(cursor, y, " ".repeat(cols - cursor)) }
        }
    }

    private fun installLabel(status: LspServerStatus): String = when (status.installState) {
        InstallState.UPDATE_AVAILABLE -> "update(enter)"
        InstallState.INSTALLED -> "reinstall(enter)"
        InstallState.MANUAL, InstallState.MISSING, InstallState.ERROR -> "install(enter)"
    }

    private fun uninstallLabel(status: LspServerStatus): String = when (status.installState) {
        InstallState.INSTALLED, InstallState.UPDATE_AVAILABLE -> "uninstall(y)"
        InstallState.MANUAL, InstallState.MISSING, InstallState.ERROR -> "remove(y)"
    }

    private fun attachBackground(style: StyleSet, fallback: StyleSet): StyleSet {
        val merged = style.copy()
        if (merged.bg == null) merged.bg = fallback.bg
        if (merged.fg == null) merged.fg = fallback.fg
        if (merged.textDecoration == null) merged.textDecoration = fallback.textDecoration
        return merged
    }

    private fun mergeDecorations(base: String?, extra: String?): String? {
        if (extra.isNullOrBlank()) return base
        val set = mutableSetOf<String>()
        base?.split(Regex("\\s+"))?.filter { it.isNotBlank() }?.let { set.addAll(it) }
        extra.split(Regex("\\s+")).filter { it.isNotBlank() }.forEach { set.add(it) }
        return if (set.isEmpty()) null else set.joinToString(" ")
    }
}
