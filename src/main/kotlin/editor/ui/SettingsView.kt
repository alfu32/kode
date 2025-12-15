package editor.ui

import editor.lsp.InstallState
import editor.lsp.LspManager
import editor.lsp.LspService
import editor.lsp.LspServerStatus
import editor.db.DbServerManager
import editor.db.DbStatus
import editor.codeintel.CodeIntelService
import java.nio.file.Path
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class SettingsView(
    styleSheet: StyleSheet,
    private val lspService: LspService,
    private val lspManager: LspManager,
    private val dbManager: DbServerManager,
    private val codeIntel: CodeIntelService,
    private val projectRootProvider: () -> Path
) : BaseComponent(styleSheet) {

    private var statuses: List<LspServerStatus> = lspService.statuses()
    private var selectedIdx: Int = 0
    private var lastMessage: String = ""
    private var dbMessage: String = ""
    private var lspScrollTop: Int = 0
    private var dbFocused: Boolean = false
    private val cardHeight = 4
    private val buttonHits = mutableMapOf<Int, List<ButtonHit>>()

    override fun render(canvas: CanvasRenderer) {
        buttonHits.clear()
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
        val lspAreaRows = ((rows - listStart) / 2).coerceAtLeast(0)
        renderLspList(canvas, cols, listStart, lspAreaRows, style, active)

        val dbStart = (listStart + lspAreaRows).coerceAtMost(rows)
        val dbRows = (rows - dbStart).coerceAtLeast(0)
        renderDbPanel(canvas, cols, dbStart, dbRows, style)
    }

    override fun dispatch(event: UIEvent): Boolean {
        val cols = event.cols ?: 0
        val rows = event.rows ?: 0
        val headerSize = headerLines().size
        val lspAreaRows = ((rows - headerSize) / 2).coerceAtLeast(0)
        val lspStart = headerSize
        val dbStart = (lspStart + lspAreaRows).coerceAtMost(rows)

        if (event.kind == "mouse_scroll") {
            val delta = event.scrollDelta ?: return false
            val visibleCards = (lspAreaRows / cardHeight).coerceAtLeast(1)
            val maxScroll = (statuses.size - visibleCards).coerceAtLeast(0)
            val prev = lspScrollTop
            lspScrollTop = (lspScrollTop - delta).coerceIn(0, maxScroll)
            return lspScrollTop != prev
        }
        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            if (y in lspStart until dbStart) {
                dbFocused = false
                val idx = lspScrollTop + ((y - lspStart) / cardHeight)
                if (idx in statuses.indices) {
                    selectedIdx = idx
                    val hit = event.x?.let { x -> buttonHits[y]?.firstOrNull { x in it.range } }
                    if (hit != null) {
                        handleButtonAction(idx, hit.action)
                        return true
                    }
                    return true
                }
                return false
            }
            if (y >= dbStart) {
                dbFocused = true
                val hit = event.x?.let { x -> buttonHits[y]?.firstOrNull { x in it.range } }
                if (hit != null) {
                    handleDbAction(hit.action)
                    return true
                }
                return false
            }
            return false
        }
        if (event.kind != "key_down") return false
        val key = event.key?.lowercase()
        if (dbFocused) {
            when (key) {
                "s" -> {
                    val status = dbManager.status()
                    val root = projectRootProvider()
                    val result = if (status.state == DbStatus.State.RUNNING || status.state == DbStatus.State.STARTING) {
                        dbManager.stop()
                    } else dbManager.start(root)
                    dbMessage = result.message
                    return true
                }
                "r" -> {
                    val root = projectRootProvider()
                    val result = dbManager.restart(root)
                    dbMessage = result.message
                    return true
                }
                "c" -> {
                    val cleared = dbManager.clearIndex()
                    codeIntel.clear()
                    dbMessage = if (cleared) "Cleared index" else "Failed to clear index"
                    return true
                }
            }
        }
        when (key) {
            "up" -> {
                if (statuses.isNotEmpty()) {
                    selectedIdx = ((selectedIdx - 1) + statuses.size) % statuses.size
                    ensureSelectionVisible(lspAreaRows)
                }
                return true
            }
            "down" -> {
                if (statuses.isNotEmpty()) {
                    selectedIdx = (selectedIdx + 1) % statuses.size
                    ensureSelectionVisible(lspAreaRows)
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

    private fun renderLspList(
        canvas: CanvasRenderer,
        cols: Int,
        startRow: Int,
        rows: Int,
        style: StyleSet,
        active: StyleSet
    ) {
        if (rows <= 0) return
        val visibleCards = (rows / cardHeight).coerceAtLeast(0)
        val maxScroll = (statuses.size - visibleCards).coerceAtLeast(0)
        lspScrollTop = lspScrollTop.coerceIn(0, maxScroll)
        val slice = statuses.drop(lspScrollTop).take(visibleCards)
        slice.forEachIndexed { idx, status ->
            val absoluteIdx = lspScrollTop + idx
            val row = startRow + (idx * cardHeight)
            val selected = absoluteIdx == selectedIdx
            renderCard(canvas, cols, row, status, selected, style, active)
        }
    }

    private fun renderDbPanel(canvas: CanvasRenderer, cols: Int, startRow: Int, rows: Int, baseStyle: StyleSet) {
        if (rows <= 0) return
        val status = dbManager.status()
        val stats = codeIntel.stats()
        val header = "Database / Processes"
        val jar = status.jarPath?.toString() ?: "jar: missing"
        val baseDir = status.baseDir?.toString() ?: "data: unset"
        val stateLabel = when (status.state) {
            DbStatus.State.RUNNING -> "running"
            DbStatus.State.STARTING -> "starting"
            DbStatus.State.ERROR -> "error"
            DbStatus.State.STOPPED -> "stopped"
        }
        val stateStyle = when (status.state) {
            DbStatus.State.RUNNING -> styleSheet.getStyle("db-status-ok")
            DbStatus.State.STARTING -> styleSheet.getStyle("db-status-warn")
            DbStatus.State.ERROR -> styleSheet.getStyle("db-status-err")
            DbStatus.State.STOPPED -> styleSheet.getStyle("db-status-warn")
        }
        val panelStyle = attachBackground(styleSheet.getStyle("db-panel"), baseStyle)
        val msg = status.message.ifBlank { dbMessage }
        canvas.withStyle(panelStyle) {
            drawRect(0, startRow, cols, rows)
            drawText(0, startRow, header.take(cols).padEnd(cols, ' '))
            if (rows > 1) drawText(0, startRow + 1, " state: ".take(cols))
            canvas.withStyle(attachBackground(stateStyle, panelStyle)) {
                if (rows > 1) drawText(8, startRow + 1, stateLabel.take((cols - 8).coerceAtLeast(0)))
            }
            if (rows > 2) drawText(0, startRow + 2, " jar: ${jar.take(cols - 5)}".padEnd(cols, ' '))
            if (rows > 3) drawText(0, startRow + 3, " dir: ${baseDir.take(cols - 6)}".padEnd(cols, ' '))
            if (rows > 4) drawText(0, startRow + 4, " index: ${stats.files} files, ${stats.symbols} symbols".take(cols).padEnd(cols, ' '))
            if (rows > 5 && msg.isNotBlank()) {
                val msgStyle = attachBackground(styleSheet.getStyle("db-status-err"), panelStyle)
                canvas.withStyle(msgStyle) {
                    drawText(0, startRow + 5, msg.take(cols).padEnd(cols, ' '))
                }
            }
            if (rows > 6) {
                renderDbButtons(canvas, startRow + 6, cols, panelStyle)
            }
        }
    }

    private fun renderDbButtons(canvas: CanvasRenderer, y: Int, cols: Int, baseStyle: StyleSet) {
        val buttonBase = styleSheet.getStyle("lsp-button")
        val buttonStyle = StyleSet(
            bg = buttonBase.bg ?: baseStyle.bg,
            fg = buttonBase.fg ?: baseStyle.fg,
            textDecoration = mergeDecorations(buttonBase.textDecoration, null)
        )
        val buttons = listOf(
            "[start/stop(s)]" to ButtonAction.DB_START_STOP,
            "[restart(r)]" to ButtonAction.DB_RESTART,
            "[clear index(c)]" to ButtonAction.DB_CLEAR
        )
        var cursor = 0
        val hits = mutableListOf<ButtonHit>()
        buttons.forEach { (label, action) ->
            val padded = " $label "
            if (cursor + padded.length > cols) return
            canvas.withStyle(buttonStyle) { drawText(cursor, y, padded) }
            hits += ButtonHit(cursor until (cursor + padded.length), action)
            cursor += padded.length + 1
        }
        if (hits.isNotEmpty()) {
            buttonHits[y] = hits
        }
        if (cursor < cols) {
            canvas.withStyle(baseStyle) { drawText(cursor, y, " ".repeat(cols - cursor)) }
        }
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
        val maxScroll = (statuses.size - 1).coerceAtLeast(0)
        lspScrollTop = lspScrollTop.coerceIn(0, maxScroll)
    }

    private fun ensureSelectionVisible(lspAreaRows: Int) {
        val visibleCards = (lspAreaRows / cardHeight).coerceAtLeast(1)
        val maxScroll = (statuses.size - visibleCards).coerceAtLeast(0)
        if (selectedIdx < lspScrollTop) {
            lspScrollTop = selectedIdx.coerceIn(0, maxScroll)
        } else if (selectedIdx >= lspScrollTop + visibleCards) {
            lspScrollTop = (selectedIdx - visibleCards + 1).coerceIn(0, maxScroll)
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
            val msgStyle = attachBackground(mergeStyles(styleSheet.getStyle("lsp-error"), bgStyle), bgStyle)
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
        val hits = mutableListOf<ButtonHit>()
        buttons.forEachIndexed { idx, label ->
            val padded = " $label "
            if (cursor + padded.length > cols) return
            canvas.withStyle(buttonStyle) { drawText(cursor, y, padded) }
            hits += ButtonHit(cursor until (cursor + padded.length), buttonAction(idx))
            cursor += padded.length + 1
        }
        buttonHits[y] = hits
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

    private fun buttonAction(idx: Int): ButtonAction = when (idx) {
        0 -> ButtonAction.START_STOP
        1 -> ButtonAction.RESTART
        2 -> ButtonAction.INSTALL
        else -> ButtonAction.UNINSTALL
    }

    private fun handleButtonAction(selected: Int, action: ButtonAction) {
        val status = statuses.getOrNull(selected) ?: return
        when (action) {
            ButtonAction.START_STOP -> {
                val result = if (status.running) lspService.stopServer(status.entry.id) else lspService.startServer(status.entry.id)
                lastMessage = result.message
            }
            ButtonAction.RESTART -> {
                val result = lspService.restartServer(status.entry.id)
                lastMessage = result.message
            }
            ButtonAction.INSTALL -> {
                val result = lspManager.install(status.entry.id)
                lastMessage = result.message
            }
            ButtonAction.UNINSTALL -> {
                val removed = lspManager.uninstall(status.entry.id)
                lastMessage = if (removed) "Uninstalled ${status.entry.name}" else "Nothing to uninstall"
            }
            else -> {}
        }
        refresh()
    }

    private fun handleDbAction(action: ButtonAction) {
        val root = projectRootProvider()
        when (action) {
            ButtonAction.DB_START_STOP -> {
                val status = dbManager.status()
                val result = if (status.state == DbStatus.State.RUNNING || status.state == DbStatus.State.STARTING) {
                    dbManager.stop()
                } else dbManager.start(root)
                dbMessage = result.message
            }
            ButtonAction.DB_RESTART -> {
                val result = dbManager.restart(root)
                dbMessage = result.message
            }
            ButtonAction.DB_CLEAR -> {
                val cleared = dbManager.clearIndex()
                codeIntel.clear()
                dbMessage = if (cleared) "Cleared index" else "Failed to clear index"
            }
            else -> {}
        }
    }

    private data class ButtonHit(val range: IntRange, val action: ButtonAction)
    private enum class ButtonAction { START_STOP, RESTART, INSTALL, UNINSTALL, DB_START_STOP, DB_RESTART, DB_CLEAR }

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
