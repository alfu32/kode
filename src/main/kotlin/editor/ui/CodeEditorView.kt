package editor.ui

import editor.codeintel.CodeIntelService
import editor.codeintel.CompletionRequest
import editor.codeintel.DefinitionRequest
import editor.codeintel.EditorIntelligenceService
import editor.codeintel.NavigationTarget
import editor.codeintel.ReferenceRequest
import editor.codeintel.TextPosition
import editor.codeintel.TokensRequest
import editor.lsp.LspService
import editor.lib.FoundToken
import editor.lib.Position
import editor.lib.SelectionRange
import editor.lib.TextBuffer
import editor.lib.handleKeyForBuffer
import editor.lib.handleMouseToBuffer
import editor.mime.MimeTypeResult
import editor.lib.PositionState
import editor.grammars.SyntaxProvider
import editor.app.EditorSessionState
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import java.io.File
import editor.ui.SearchReplaceBar.SearchCommand

class CodeEditorView(
    styleSheet: StyleSheet,
    private val buffer: TextBuffer = TextBuffer(),
    private val syntaxProvider: SyntaxProvider? = null,
    private val codeIntelIndexer: CodeIntelService? = null,
    private val codeIntel: EditorIntelligenceService? = null,
    private val lsp: LspService? = null,
    private val navigationHandler: ((String, Position) -> Unit)? = null
) : BaseComponent(styleSheet) {

    private var localStyleSheet: StyleSheet = styleSheet
    private var filePath: String = ""
    private var mime: String? = null
    private var language: String? = null
    private var grammarAvailable: Boolean = false
    private var grammarLanguage: String? = null
    private var scrollTop: Int = 0
    private var lastLayout: VisualLayout? = null
    private var dragging = false
    private var lastCols: Int = 0
    private var lastTotalCols: Int = 0
    private var lastRows: Int = 0
    private var searchVisible = false
    private var searchHasFocus = false
    private var searchBar: SearchReplaceBar = SearchReplaceBar(styleSheet, this::handleSearchAction)
    private var readOnly: Boolean = false
    private var lastIndexedVersion: Long = -1
    private var usagePopup: UsagePopup? = null
    private var renderedPopup: RenderedPopup? = null
    private var suggestionPopup: SuggestionPopup? = null
    private var renderedSuggestion: RenderedPopup? = null
    private var hoveredUsageIndex: Int = -1
    private var hoveredSuggestionIndex: Int = -1
    private var hoveredUsage: HoveredUsage? = null
    private var rerenderOnce: Boolean = false

    fun textContent(): String = buffer.text()

    fun loadTextContent(text: String) {
        buffer.loadText(text)
        scrollTop = 0
        lastIndexedVersion = -1
        triggerCodeIntel(force = true, immediate = true)
        syncLsp(open = true)
        rerenderOnce = true
    }

    private fun triggerCodeIntel(force: Boolean = false, immediate: Boolean = false) {
        val service = codeIntelIndexer ?: return
        if (filePath.isEmpty()) return
        val version = buffer.version()
        if (!force && version == lastIndexedVersion) return
        lastIndexedVersion = version
        val lang = grammarLanguage ?: language
        if (!lang.isNullOrBlank()) {
            if (immediate) {
                service.indexDocumentNow(filePath, lang, buffer.text(), version)
            } else {
                service.indexDocument(filePath, lang, buffer.text(), version)
            }
        }
        lsp?.changeDocument(filePath, buffer.text(), version.toInt())
    }

    private fun syncLsp(open: Boolean) {
        val lang = grammarLanguage ?: language
        if (filePath.isEmpty() || lang.isNullOrBlank()) return
        lsp?.startForLanguage(lang)
        if (open) {
            lsp?.openDocument(filePath, lang, buffer.text(), buffer.version().toInt())
        } else {
            lsp?.changeDocument(filePath, buffer.text(), buffer.version().toInt())
        }
    }

    fun captureState(lastModifiedMillis: Long? = null): EditorSessionState? {
        if (filePath.isEmpty()) return null
        return EditorSessionState(
            path = filePath,
            buffer = buffer.exportState(),
            scrollTop = scrollTop,
            mime = mime,
            language = language,
            grammarLanguage = grammarLanguage,
            grammarAvailable = grammarAvailable,
            lastModifiedMillis = lastModifiedMillis
        )
    }

    fun restoreState(state: EditorSessionState) {
        filePath = state.path
        mime = state.mime
        language = state.language
        grammarAvailable = state.grammarAvailable
        grammarLanguage = state.grammarLanguage ?: state.language
        if ((language.isNullOrBlank() || grammarLanguage.isNullOrBlank()) && filePath.isNotBlank()) {
            val ext = filePath.substringAfterLast('.', missingDelimiterValue = "").lowercase()
            val inferred = ext.takeIf { it.isNotEmpty() }?.let { syntaxProvider?.languageForExtension(it) }
            if (!inferred.isNullOrBlank()) {
                if (language.isNullOrBlank()) language = inferred
                if (grammarLanguage.isNullOrBlank()) grammarLanguage = inferred
                grammarAvailable = grammarAvailable || syntaxProvider?.languages()?.contains(inferred) == true
            }
        }
        buffer.restoreState(state.buffer)
        scrollTop = state.scrollTop.coerceAtLeast(0)
        lastIndexedVersion = -1
        triggerCodeIntel(force = true, immediate = true)
        syncLsp(open = true)
        rerenderOnce = true
    }

    fun loadVirtualContent(label: String, content: String, language: String? = null) {
        filePath = label
        mime = "text/plain"
        this.language = language
        grammarLanguage = language
        grammarAvailable = language != null && syntaxProvider?.languages()?.contains(language) == true
        buffer.loadText(content)
        scrollTop = 0
        lastIndexedVersion = -1
        triggerCodeIntel(force = true)
        syncLsp(open = true)
    }

    fun setReadOnly(value: Boolean) {
        readOnly = value
    }

    fun currentPath(): String = filePath

    fun openFile(
        path: String,
        detection: MimeTypeResult? = null,
        grammarAvailable: Boolean = false,
        grammarLanguage: String? = null
    ) {
        val content = try {
            File(path).readText()
        } catch (_: Exception) {
            ""
        }
        buffer.loadText(content)
        filePath = path
        this.mime = detection?.mime
        this.language = detection?.language
        this.grammarAvailable = grammarAvailable
        this.grammarLanguage = grammarLanguage ?: detection?.language
        scrollTop = 0
        lastIndexedVersion = -1
        triggerCodeIntel(force = true)
        syncLsp(open = true)
    }

    override fun render(canvas: CanvasRenderer) {
        val rows = canvas.rows().coerceAtLeast(1)
        val totalCols = canvas.cols().coerceAtLeast(1)
        val previewCols = computePreviewWidth(totalCols)
        val cols = (totalCols - previewCols).coerceAtLeast(1)
        lastCols = cols
        lastTotalCols = totalCols
        lastRows = rows
        val headerStyle = localStyleSheet.getStyle("code-header").withDefaults()
        val baseBody = localStyleSheet.getStyle("code-body").withDefaults()
        val gutterStyle = localStyleSheet.getStyle("code-gutter").withDefaults(baseBody.fg, baseBody.bg)
        val selectionStyle = localStyleSheet.getStyle("code-selection")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val cursorStyle = localStyleSheet.getStyle("code-cursor")
            .withDefaults(fg = baseBody.bg ?: gutterStyle.bg, bg = baseBody.fg ?: gutterStyle.fg)
        val searchMatchStyle = localStyleSheet.getStyle("code-search-match").withDefaults(baseBody.fg, baseBody.bg)
        val searchActiveMatchStyle =
            localStyleSheet.getStyle("code-search-active").withDefaults(searchMatchStyle.fg, searchMatchStyle.bg)
        val bodyStyle = baseBody
        val effectiveSearchVisible = searchVisible
        val searchHeight = if (effectiveSearchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyStartRow = 1 + searchHeight

        // Header bar with file path and mime
        canvas.withStyle(headerStyle) {
            val mimeLabel = mime?.let { "[$it]" } ?: "[unknown]"
            val grammarInfo = grammarLabel()
            val langLabel = language?.let { "· $it$grammarInfo" } ?: grammarInfo
            val saveMarker = if (buffer.isDirty()) "*" else " "
            val label = "$saveMarker${filePath.ifEmpty { "[no file]" }} $mimeLabel $langLabel"
                .take(totalCols)
            drawText(0, 0, label.padEnd(totalCols, ' '))
        }

        if (effectiveSearchVisible && searchHeight > 0) {
            val clipped = ClippedCanvasRenderer(
                base = canvas,
                offsetX = 0,
                offsetY = 1,
                width = cols,
                height = searchHeight
            )
            val state = buffer.searchState()
            searchBar.updateMatchLabel(state.activeIndex, state.matchCount)
            searchBar.render(clipped)
        }

        val bodyRows = (rows - bodyStartRow).coerceAtLeast(0)
        if (bodyRows == 0) return

        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(0)
        val lines = buffer.text().split("\n")
        val layout = buildLayout(lines, contentCols)
        lastLayout = layout

        val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
        scrollTop = scrollTop.coerceIn(0, maxOffset)

        val visibleRows = layout.wrapped.drop(scrollTop).take(bodyRows)
        if (visibleRows.isEmpty()) return

        val selection = buffer.selectionRange()
        val searchTokensByLine = buffer.foundTokens().groupBy { it.line }
        val firstVisibleLine = visibleRows.first().lineIndex
        val lastVisibleLine = visibleRows.last().lineIndex
        val tokensByLine = if (grammarAvailable && syntaxProvider != null && grammarLanguage != null) {
            val sliceEnd = (lastVisibleLine + 1).coerceAtMost(lines.size)
            val lineSlice = lines.subList(firstVisibleLine, sliceEnd)
            syntaxProvider.tokensForLines(firstVisibleLine, lineSlice, grammarLanguage!!).groupBy { it.line }
        } else {
            emptyMap()
        }
        val codeIntelTokensByLine = if (codeIntel != null && filePath.isNotEmpty()) {
            val sliceEnd = (lastVisibleLine + 1).coerceAtMost(lines.size)
            val lineSlice = lines.subList(firstVisibleLine, sliceEnd)
            codeIntel.tokens(
                TokensRequest(
                    filePath = filePath,
                    language = grammarLanguage ?: language,
                    startLine = firstVisibleLine,
                    lines = lineSlice,
                    version = buffer.version()
                )
            ).groupBy { it.line }
        } else {
            emptyMap()
        }

        canvas.withStyle(bodyStyle) {
            drawRect(0, bodyStartRow, cols, bodyRows)
            visibleRows.forEachIndexed { idx, wrapped ->
                val lineNumber = wrapped.lineIndex
                val y = bodyStartRow + idx
                val lineText = lines.getOrElse(lineNumber) { "" }

                canvas.withStyle(gutterStyle) {
                    val g = if (wrapped.startColumn == 0) {
                        (lineNumber + 1).toString().padStart(gutterWidth - 1, ' ') + " "
                    } else {
                        " ".repeat(gutterWidth)
                    }
                    drawText(0, y, g.take(gutterWidth))
                }

                if (contentCols <= 0) return@forEachIndexed
                val chunkText = lineText.substring(wrapped.startColumn, wrapped.endColumn)
                val tokens = tokensByLine[lineNumber] ?: emptyList()
                val chunkTokens = sliceTokens(tokens, wrapped.startColumn, wrapped.endColumn)
                val intelTokens = codeIntelTokensByLine[lineNumber] ?: emptyList()
                val chunkIntelTokens = sliceTokens(intelTokens, wrapped.startColumn, wrapped.endColumn)
                val selectionCols = selectionRangeForLine(selection, lineNumber, lineText)
                val chunkSelection = selectionCols?.let { trimSelectionToChunk(it, wrapped.startColumn, wrapped.endColumn) }
                val highlights = searchTokensByLine[lineNumber]?.mapNotNull {
                    trimHighlightToChunk(it, wrapped.startColumn, wrapped.endColumn)
                } ?: emptyList()
                val hoveredUsageRange = hoveredUsage?.takeIf { it.line == lineNumber }?.let { hu ->
                    val start = maxOf(hu.startColumn, wrapped.startColumn)
                    val end = minOf(hu.endColumn, wrapped.endColumn)
                    if (start < end) start - wrapped.startColumn until end - wrapped.startColumn else null
                }
                renderLineWithTokens(
                    canvas = this,
                    text = chunkText,
                    y = y,
                    startX = gutterWidth,
                    maxCols = contentCols,
                    tokens = chunkTokens,
                    baseStyle = bodyStyle,
                    overlayTokens = chunkIntelTokens,
                    selection = chunkSelection,
                    selectionStyle = selectionStyle,
                    highlights = highlights,
                    highlightStyle = searchMatchStyle,
                    activeHighlightStyle = searchActiveMatchStyle,
                    hoveredUsageRange = hoveredUsageRange
                )
            }
        }

        val cursor = buffer.cursorPosition()
        val cursorRow = visualRowForPosition(cursor, layout)
        val cursorChunk = chunkForPosition(cursor, layout)
        val cy = bodyStartRow + (cursorRow - scrollTop)
        if (cursorChunk != null && cy in bodyStartRow until rows) {
            val cursorCol = (cursor.column - cursorChunk.startColumn).coerceAtLeast(0)
            val cx = (gutterWidth + cursorCol).coerceAtMost(cols - 1)
            val lineText = lines.getOrElse(cursorChunk.lineIndex) { "" }
            val ch = lineText.getOrNull(cursor.column)?.toString() ?: " "
            canvas.withStyle(cursorStyle) {
                drawText(cx, cy, ch)
            }
        }

        renderUsagePopup(canvas, bodyStartRow, gutterWidth, cols, rows, layout)
        renderSuggestionPopup(canvas, bodyStartRow, gutterWidth, cols, rows, layout)

        if (previewCols > 0) {
            val previewCanvas = ClippedCanvasRenderer(
                base = canvas,
                offsetX = cols,
                offsetY = 0,
                width = previewCols,
                height = rows
            )
            val previewGutter = computeGutterWidth().coerceAtMost(previewCols)
            val firstVisibleLine = visibleRows.firstOrNull()?.lineIndex ?: 0
            val previewOffset = (firstVisibleLine / 4).coerceAtLeast(0)
            if (bodyStartRow > 1) {
                previewCanvas.withStyle(bodyStyle) {
                    drawRect(0, 1, previewCols, bodyStartRow - 1)
                }
            }
            renderBrailleBlocks(
                previewCanvas,
                lines,
                previewGutter,
                bodyStartRow,
                bodyRows,
                gutterStyle,
                bodyStyle,
                previewOffset
            )
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame" && rerenderOnce) {
            rerenderOnce = false
            lastLayout = null
            // Re-run code intel to ensure tokens are ready before the redraw.
            triggerCodeIntel(force = true, immediate = true)
            return true
        }
        val totalCols = (event.cols ?: lastTotalCols).coerceAtLeast(1)
        val previewCols = computePreviewWidth(totalCols)
        val cols = (totalCols - previewCols).coerceAtLeast(1)
        val rows = (event.rows ?: lastRows).coerceAtLeast(1)
        val gutterWidth = computeGutterWidth()
        val contentCols = (cols - gutterWidth).coerceAtLeast(0)
        val layout = buildLayout(buffer.text().split("\n"), contentCols).also { lastLayout = it }
        val effectiveSearchVisible = searchVisible
        val searchHeight = if (effectiveSearchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyRows = (rows - 1 - searchHeight).coerceAtLeast(0)
        val bodyStartRow = 1 + searchHeight

        if (event.kind == "key_down" && event.ctrl && event.key?.lowercase() == "f") {
            val selection = if (buffer.hasSelection()) buffer.selectionText() else ""
            openSearch(selection)
            return true
        }

        if (effectiveSearchVisible && event.kind.startsWith("mouse")) {
            val y = event.y ?: 0
            if (y in 1 until bodyStartRow) {
                val forwarded = event.alterCopy(
                    UIEvent(
                        kind = event.kind,
                        x = event.x,
                        y = (event.y ?: 0) - 1,
                        relX = event.relX,
                        relY = event.relY,
                        button = event.button,
                        scrollDelta = event.scrollDelta,
                        key = event.key,
                        ctrl = event.ctrl,
                        alt = event.alt,
                        shift = event.shift,
                        meta = event.meta,
                        focusId = event.focusId,
                        cols = event.cols,
                        rows = searchHeight,
                        raw = event.raw,
                        timeMs = event.timeMs
                    )
                )
                val handled = searchBar.dispatch(forwarded)
                if (handled) {
                    searchHasFocus = effectiveSearchVisible
                    syncSearchUiFromBuffer()
                    ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                    return true
                }
            }
        }

        if (effectiveSearchVisible && event.kind == "key_down" && searchHasFocus) {
            val handled = searchBar.dispatch(event)
            if (handled) {
                syncSearchUiFromBuffer()
                ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
            }
            return true
        }

        when (event.kind) {
            "mouse_scroll" -> {
                val delta = event.scrollDelta ?: return false
                val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
                val prev = scrollTop
                scrollTop = (scrollTop - delta).coerceIn(0, maxOffset)
                suggestionPopup = null
                renderedSuggestion = null
                return scrollTop != prev
            }
            "mouse_down" -> {
                val y = event.y ?: return false
                if (y == 0) return false // header
                if (effectiveSearchVisible && y in 1 until bodyStartRow) return true
                val ex = event.x ?: return false
                if (ex >= cols) {
                    if (y < bodyStartRow || y >= rows) return false
                    val firstVisibleLine = layout.wrapped.getOrNull(scrollTop)?.lineIndex ?: 0
                    val blockOffset = (firstVisibleLine / 4).coerceAtLeast(0)
                    val blockIdx = (y - bodyStartRow).coerceAtLeast(0)
                    val targetBlock = blockOffset + blockIdx
                    val targetLine = (targetBlock * 4).coerceAtMost(buffer.totalLines().coerceAtLeast(1) - 1)
                    val targetPos = Position(targetLine, 0)
                    val targetRow = visualRowForPosition(targetPos, layout)
                    val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
                    scrollTop = targetRow.coerceIn(0, maxOffset)
                    buffer.moveCursorTo(targetPos, expand = false)
                    return true
                }
                if (handleUsageClick(event)) return true
                if (handleSuggestionClick(event)) return true
                if (event.ctrl && handleCtrlClick(event, bodyStartRow, layout, gutterWidth)) {
                    return true
                }
                searchHasFocus = false
                dragging = true
                return handleMouse(
                    event = event,
                    bodyStartRow = bodyStartRow,
                    layout = layout,
                    gutterWidth = gutterWidth,
                    startSelection = true,
                    extendSelection = false
                )
            }
            "mouse_up" -> {
                dragging = false
                // hide popup on click release outside
                val rp = renderedPopup
                if (rp != null && event.x != null && event.y != null) {
                    if (event.x !in rp.x until (rp.x + rp.width) || event.y !in rp.y until (rp.y + rp.height)) {
                        usagePopup = null
                        renderedPopup = null
                    }
                }
                val rs = renderedSuggestion
                if (rs != null && event.x != null && event.y != null) {
                    if (event.x !in rs.x until (rs.x + rs.width) || event.y !in rs.y until (rs.y + rs.height)) {
                        suggestionPopup = null
                        renderedSuggestion = null
                    }
                }
                return true
            }
            "mouse_move" -> {
                if (!dragging) {
                    if ((event.x ?: 0) >= cols) return false
                    if (updatePopupHover(event)) return true
                    if (updateHoveredUsage(event, bodyStartRow, layout, gutterWidth)) return true
                    return false
                }
                hoveredUsage = null
                return handleMouse(
                    event = event,
                    bodyStartRow = bodyStartRow,
                    layout = layout,
                    gutterWidth = gutterWidth,
                    startSelection = false,
                    extendSelection = true
                )
            }
            "key_down" -> {
                val key = event.key?.lowercase()
                if (readOnly) {
                    val mutating = when (key) {
                        "backspace", "delete", "enter" -> true
                        else -> false
                    } || (!event.ctrl && !event.alt && (event.key?.length == 1)) ||
                        (event.ctrl && (key == "x" || key == "v"))
                    if (mutating) return true
                }
                if (event.ctrl && key == "s") {
                    if (filePath.isNotEmpty()) buffer.saveToFile(filePath)
                    return true
                }
                if (key == "pageup" || key == "pagedown") {
                    val delta = if (key == "pageup") -bodyRows else bodyRows
                    val newLine = (buffer.cursorPosition().line + delta).coerceIn(0, buffer.totalLines().coerceAtLeast(1) - 1)
                    buffer.moveCursorTo(Position(newLine, buffer.cursorPosition().column), expand = event.shift)
                    ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                    return true
                }
                if (handlePopupKeys(key)) return true
                if (event.ctrl && (key == " " || key == "space")) {
                    openSuggestions()
                    return true
                }
                if (key == "\u0000") {
                    openSuggestions()
                    return true
                }
                val beforeCursor = buffer.cursorPosition()
                val beforeSelection = if (buffer.hasSelection()) buffer.selectionText() else null
                val beforeText = buffer.text()
                val changed = handleKeyForBuffer(buffer, event, singleLine = false)
                val afterCursor = buffer.cursorPosition()
                val afterSelection = if (buffer.hasSelection()) buffer.selectionText() else null
                val moved = beforeCursor != afterCursor || beforeSelection != afterSelection
                val textChanged = beforeText != buffer.text()
                ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
                if (textChanged) {
                    triggerCodeIntel()
                    suggestionPopup = null
                    renderedSuggestion = null
                }
                return changed || moved || textChanged
            }
        }
        return true
    }

    private fun handleCtrlClick(
        event: UIEvent,
        bodyStartRow: Int,
        layout: VisualLayout,
        gutterWidth: Int
    ): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        val popupBox = renderedPopup
        if (popupBox != null && ex in popupBox.x until (popupBox.x + popupBox.width) &&
            ey in popupBox.y until (popupBox.y + popupBox.height)
        ) {
            val idx = ey - popupBox.y - 1
            val popup = usagePopup
            if (popup != null && idx in popup.entries.indices) {
                val entry = popup.entries[idx]
                navigationHandler?.invoke(entry.file, Position(entry.line, entry.column))
                usagePopup = null
                renderedPopup = null
            }
            return true
        }

        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: return false
        val line = wrapped.lineIndex
        val lineText = layout.lines.getOrElse(line) { "" }
        val col = (ex - gutterWidth + wrapped.startColumn).coerceAtMost(lineText.length)
        val token = identifyCodeIntelToken(line, col, lineText) ?: return false

        usagePopup = null
        renderedPopup = null
        suggestionPopup = null
        renderedSuggestion = null

        val name = token.text
        openDefinition(name, Position(line, col))

        val isDeclaration = token.scopes.any { it.contains("codeintel.declaration") }
        if (isDeclaration) {
            val refs = codeIntel?.references(
                ReferenceRequest(
                    filePath = filePath,
                    language = grammarLanguage ?: language,
                    position = TextPosition(line, col),
                    symbol = name
                )
            ).orEmpty()
            val usageEntries = refs.map {
                val label = "${java.io.File(it.filePath).name}:${it.range.start.line + 1}:${it.range.start.column + 1}"
                UsageEntry(it.filePath, it.range.start.line, it.range.start.column, label)
            }
            if (usageEntries.isNotEmpty()) {
                usagePopup = UsagePopup(Position(line, col), usageEntries)
                hoveredUsageIndex = 0
            }
        }
        return true
    }

    private fun identifyCodeIntelToken(line: Int, column: Int, lineText: String): editor.grammars.Token? {
        val service = codeIntel ?: return null
        if (filePath.isEmpty()) return null
        val tokens = service.tokens(
            TokensRequest(
                filePath = filePath,
                language = grammarLanguage ?: language,
                startLine = line,
                lines = listOf(lineText),
                version = buffer.version()
            )
        )
        return tokens.firstOrNull { column in it.start until it.end }
    }

    private fun openDefinition(name: String, position: Position) {
        val lang = grammarLanguage ?: language
        val hits = codeIntel?.definitions(
            DefinitionRequest(
                filePath = filePath,
                language = lang,
                position = TextPosition(position.line, position.column),
                symbol = name
            )
        ).orEmpty()
        val target = pickBestDefinition(hits, name) ?: return
        navigationHandler?.invoke(target.filePath, Position(target.range.start.line, target.range.start.column))
    }

    private fun pickBestDefinition(defs: List<NavigationTarget>, identifier: String): NavigationTarget? {
        if (defs.isEmpty()) return null
        val lower = identifier.lowercase()
        val currentFile = File(filePath).absoluteFile.normalize()
        fun score(def: NavigationTarget): Int {
            val file = File(def.filePath).absoluteFile.normalize()
            val fileName = file.nameWithoutExtension.lowercase()
            var s = 0
            if (!fileName.contains(lower)) s += 1
            if (file == currentFile) s += 1
            return s
        }
        return defs.minByOrNull { score(it) }
    }

    private fun handleSuggestionClick(event: UIEvent): Boolean {
        val rs = renderedSuggestion ?: return false
        val popup = suggestionPopup ?: return false
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ex !in rs.x until (rs.x + rs.width) || ey !in rs.y until (rs.y + rs.height)) return false
        val idx = ey - rs.y - 1
        if (idx !in popup.entries.indices) return true
        val entry = popup.entries[idx]
        applySuggestion(entry.name, popup.prefix)
        suggestionPopup = null
        renderedSuggestion = null
        return true
    }

    private fun handleUsageClick(event: UIEvent): Boolean {
        val rp = renderedPopup ?: return false
        val popup = usagePopup ?: return false
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ex !in rp.x until (rp.x + rp.width) || ey !in rp.y until (rp.y + rp.height)) return false
        val idx = ey - rp.y - 1
        if (idx !in popup.entries.indices) return true
        val entry = popup.entries[idx]
        navigationHandler?.invoke(entry.file, Position(entry.line, entry.column))
        usagePopup = null
        renderedPopup = null
        return true
    }

    private fun applySuggestion(suggestion: String, prefix: String) {
        val cursor = buffer.cursorPosition()
        val startCol = (cursor.column - prefix.length).coerceAtLeast(0)
        val startPos = Position(cursor.line, startCol)
        buffer.startSelection(startPos)
        buffer.selectTo(cursor)
        buffer.deleteBackspace()
        buffer.insertText(suggestion)
        val newCursor = Position(cursor.line, startCol + suggestion.length)
        buffer.moveCursorTo(newCursor, expand = false)
    }

    private fun openSuggestions() {
        val prefix = currentPrefix()
        val entries = suggestionEntries(prefix)
        if (entries.isEmpty()) {
            suggestionPopup = null
            renderedSuggestion = null
            return
        }
        hoveredSuggestionIndex = 0
        suggestionPopup = SuggestionPopup(buffer.cursorPosition(), prefix, entries)
    }

    private fun currentPrefix(): String {
        val cursor = buffer.cursorPosition()
        val lines = buffer.text().split("\n")
        val lineText = lines.getOrElse(cursor.line) { "" }
        if (lineText.isEmpty() || cursor.column == 0) return ""
        val start = lineText.take(cursor.column).takeLastWhile { it.isLetterOrDigit() || it == '_' }
        return start
    }

    private fun suggestionEntries(prefix: String): List<SuggestionEntry> {
        val names = mutableSetOf<String>()
        val results = mutableListOf<SuggestionEntry>()

        fun add(name: String, detail: String? = null) {
            if (name.isBlank()) return
            if (names.add(name)) results.add(SuggestionEntry(name, detail))
        }

        val lang = grammarLanguage ?: language
        val completions = codeIntel?.completions(
            CompletionRequest(
                filePath = filePath,
                language = lang,
                position = TextPosition(buffer.cursorPosition().line, buffer.cursorPosition().column),
                prefix = prefix
            )
        ).orEmpty()
        completions.forEach { add(it.label, it.detail) }

        val locals = buffer.text()
        val regex = Regex("\\b([A-Za-z_][A-Za-z0-9_]*)\\b")
        regex.findAll(locals).forEach { mr ->
            val name = mr.groupValues[1]
            if (prefix.isEmpty() || name.startsWith(prefix)) add(name, "local")
        }

        return results.take(50)
    }

    private fun updatePopupHover(event: UIEvent): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        val rp = renderedPopup
        val sp = renderedSuggestion
        var consumed = false
        if (rp != null && ey in rp.y until (rp.y + rp.height) && ex in rp.x until (rp.x + rp.width)) {
            hoveredUsageIndex = (ey - rp.y - 1).coerceIn(0, (usagePopup?.entries?.lastIndex ?: -1))
            consumed = true
        } else {
            hoveredUsageIndex = -1
        }
        if (sp != null && ey in sp.y until (sp.y + sp.height) && ex in sp.x until (sp.x + sp.width)) {
            hoveredSuggestionIndex = (ey - sp.y - 1).coerceIn(0, (suggestionPopup?.entries?.lastIndex ?: -1))
            consumed = true
        } else {
            hoveredSuggestionIndex = -1
        }
        return consumed
    }

    private fun updateHoveredUsage(event: UIEvent, bodyStartRow: Int, layout: VisualLayout, gutterWidth: Int): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return clearHoveredUsage()
        if (ey < bodyStartRow) return clearHoveredUsage()
        if (ex < gutterWidth) return clearHoveredUsage()
        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: return clearHoveredUsage()
        val lineText = layout.lines.getOrElse(wrapped.lineIndex) { "" }
        val relX = (ex - gutterWidth).coerceAtLeast(0)
        val col = (wrapped.startColumn + relX).coerceAtMost(lineText.length)
        val token = identifyCodeIntelToken(wrapped.lineIndex, col, lineText)
        val usageToken = token?.takeIf { t -> t.scopes.any { scope -> scope.contains("codeintel.usage") } }
        val newHover = usageToken?.let { HoveredUsage(wrapped.lineIndex, it.start, it.end) }
        if (newHover == hoveredUsage) return false
        hoveredUsage = newHover
        return true
    }

    private fun clearHoveredUsage(): Boolean {
        if (hoveredUsage == null) return false
        hoveredUsage = null
        return true
    }

    private fun handlePopupKeys(key: String?): Boolean {
        val k = key?.lowercase() ?: return false
        val hasUsage = usagePopup != null && renderedPopup != null
        val hasSuggestion = suggestionPopup != null && renderedSuggestion != null
        if (!hasUsage && !hasSuggestion) return false

        fun clampUsage(delta: Int) {
            val popup = usagePopup ?: return
            val max = popup.entries.lastIndex
            if (max < 0) return
            hoveredUsageIndex = (if (hoveredUsageIndex < 0) 0 else hoveredUsageIndex + delta).coerceIn(0, max)
        }

        fun clampSuggestion(delta: Int) {
            val popup = suggestionPopup ?: return
            val max = popup.entries.lastIndex
            if (max < 0) return
            hoveredSuggestionIndex = (if (hoveredSuggestionIndex < 0) 0 else hoveredSuggestionIndex + delta).coerceIn(0, max)
        }

        when (k) {
            "escape" -> {
                usagePopup = null
                renderedPopup = null
                suggestionPopup = null
                renderedSuggestion = null
                hoveredUsageIndex = -1
                hoveredSuggestionIndex = -1
                return true
            }
            "up" -> {
                if (hasUsage) clampUsage(-1)
                if (hasSuggestion) clampSuggestion(-1)
                return true
            }
            "down" -> {
                if (hasUsage) clampUsage(1)
                if (hasSuggestion) clampSuggestion(1)
                return true
            }
            "enter", "return" -> {
                if (hasUsage && hoveredUsageIndex >= 0) {
                    val popup = usagePopup
                    if (popup != null && hoveredUsageIndex in popup.entries.indices) {
                        val entry = popup.entries[hoveredUsageIndex]
                        navigationHandler?.invoke(entry.file, Position(entry.line, entry.column))
                        usagePopup = null
                        renderedPopup = null
                        return true
                    }
                }
                if (hasSuggestion && hoveredSuggestionIndex >= 0) {
                    val popup = suggestionPopup
                    if (popup != null && hoveredSuggestionIndex in popup.entries.indices) {
                        val entry = popup.entries[hoveredSuggestionIndex]
                        applySuggestion(entry.name, popup.prefix)
                        suggestionPopup = null
                        renderedSuggestion = null
                        return true
                    }
                }
            }
        }
        return false
    }

    private fun ensureCursorVisible(
        totalRows: Int,
        searchHeight: Int,
        layout: VisualLayout? = lastLayout,
        gutterWidth: Int = computeGutterWidth()
    ) {
        val bodyRows = (totalRows - 1 - searchHeight).coerceAtLeast(0)
        if (bodyRows == 0) return
        val contentCols = (lastCols - gutterWidth).coerceAtLeast(0)
        val activeLayout = layout ?: buildLayout(buffer.text().split("\n"), contentCols).also { lastLayout = it }
        if (activeLayout.wrapped.isEmpty()) return
        val cursorRow = visualRowForPosition(buffer.cursorPosition(), activeLayout)
        val maxOffset = (activeLayout.wrapped.size - bodyRows).coerceAtLeast(0)
        val newScroll = when {
            cursorRow < scrollTop -> cursorRow
            cursorRow >= scrollTop + bodyRows -> cursorRow - bodyRows + 1
            else -> scrollTop
        }.coerceIn(0, maxOffset)
        scrollTop = newScroll
    }

    private fun handleMouse(
        event: UIEvent,
        bodyStartRow: Int,
        layout: VisualLayout,
        gutterWidth: Int,
        startSelection: Boolean,
        extendSelection: Boolean
    ): Boolean {
        val ex = event.x ?: return false
        val ey = event.y ?: return false
        if (ey < bodyStartRow) return false
        if (layout.wrapped.isEmpty()) return false
        val relX = (ex - gutterWidth).coerceAtLeast(0)
        val relY = ey - bodyStartRow
        val visualIndex = (scrollTop + relY).coerceAtLeast(0)
        val wrapped = layout.wrapped.getOrNull(visualIndex) ?: layout.wrapped.last()
        val lineText = layout.lines.getOrElse(wrapped.lineIndex) { "" }
        val targetCol = (wrapped.startColumn + relX).coerceAtMost(lineText.length)
        val pos = Position(wrapped.lineIndex, targetCol)
        if (startSelection) {
            buffer.startSelection(pos)
        } else if (extendSelection) {
            buffer.selectTo(pos)
        }
        buffer.moveCursorTo(pos, expand = extendSelection)
        hoveredUsage = null
        ensureCursorVisible((event.rows ?: 0), bodyStartRow - 1, layout, gutterWidth)
        return true
    }

    private fun computeGutterWidth(): Int {
        val digits = buffer.totalLines().coerceAtLeast(1).toString().length
        return (digits + 2).coerceAtMost(12) // number + space; cap to avoid overrun
    }

    private fun grammarLabel(): String {
        val lang = language ?: return ""
        return if (grammarAvailable) " (grammar:${grammarLanguage ?: lang})" else " (grammar:none)"
    }

    private fun scopeToStyleId(scope: String): String =
        scope.replace(' ', '_').replace(":", "-").replace(",", "-")

    private fun styleForToken(token: editor.grammars.Token, base: StyleSet): StyleSet {
        val scoped = token.scopes.lastOrNull()?.let { cachedStyle(it).withDefaults(base.fg, base.bg) } ?: base
        token.fg?.let { color ->
            val copy = scoped.copy()
            copy.fg = color
            if (copy.bg == null) copy.bg = base.bg
            return copy
        }
        val copy = scoped.copy()
        if (copy.bg == null) copy.bg = base.bg
        return copy
    }

    private val styleCache = mutableMapOf<String, StyleSet>()

    private fun cachedStyle(scope: String): StyleSet =
        styleCache.getOrPut(scope) { localStyleSheet.getStyle(scopeToStyleId(scope)) }

    fun updateStyleSheet(styleSheet: StyleSheet) {
        this.localStyleSheet = styleSheet
        styleCache.clear()
        val searchState = buffer.searchState()
        searchBar = SearchReplaceBar(styleSheet, this::handleSearchAction).also {
            it.updateFromSearchState(searchState)
            it.setFocusEnabled(true)
        }
    }

    fun setSelection(start: Position, end: Position, center: Boolean = false) {
        buffer.startSelection(start)
        buffer.selectTo(end)
        val currentRows = lastRows.coerceAtLeast(1)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(currentRows - 1) else 0
        val gutterWidth = computeGutterWidth()
        val layout = lastLayout ?: buildLayout(
            buffer.text().split("\n"),
            (lastCols - gutterWidth).coerceAtLeast(0)
        ).also { lastLayout = it }
        if (center) {
            val bodyRows = (currentRows - 1 - searchHeight).coerceAtLeast(1)
            val targetRow = visualRowForPosition(start, layout)
            val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
            scrollTop = (targetRow - bodyRows / 2).coerceIn(0, maxOffset)
        }
        ensureCursorVisible(currentRows, searchHeight, layout, gutterWidth)
    }

    fun restoreViewport(cursor: PositionState, scroll: Int) {
        val lines = buffer.text().split("\n")
        val line = cursor.line.coerceIn(0, (lines.size - 1).coerceAtLeast(0))
        val col = cursor.column.coerceIn(0, lines.getOrElse(line) { "" }.length)
        val pos = Position(line, col)
        buffer.moveCursorTo(pos, expand = false)
        val cols = lastCols.coerceAtLeast(1)
        val rows = lastRows.coerceAtLeast(1)
        val gutterWidth = computeGutterWidth()
        val layout = buildLayout(lines, (cols - gutterWidth).coerceAtLeast(0)).also { lastLayout = it }
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(rows - 1) else 0
        val bodyRows = (rows - 1 - searchHeight).coerceAtLeast(1)
        val maxOffset = (layout.wrapped.size - bodyRows).coerceAtLeast(0)
        scrollTop = scroll.coerceIn(0, maxOffset)
        ensureCursorVisible(rows, searchHeight, layout, gutterWidth)
    }

    fun isDirty(): Boolean = buffer.isDirty()

    fun currentSelectionText(): String? = if (buffer.hasSelection()) buffer.selectionText() else null

    private fun openSearch(selectionText: String? = null) {
        searchVisible = true
        searchHasFocus = true
        if (!selectionText.isNullOrEmpty()) {
            buffer.updateSearch(Regex.escape(selectionText))
        }
        searchBar.updateFromSearchState(buffer.searchState())
    }

    private fun handleSearchAction(action: SearchCommand) {
        when (action) {
            is SearchCommand.Change -> buffer.updateSearch(action.query, action.replacement)
            SearchCommand.FindNext -> buffer.findNext()
            SearchCommand.FindAll -> buffer.findAll()
            SearchCommand.ReplaceOne -> buffer.replaceCurrent()
            SearchCommand.ReplaceAll -> buffer.replaceAll()
            SearchCommand.Close -> {
                searchVisible = false
                searchHasFocus = false
                return
            }
        }
        syncSearchUiFromBuffer()
        val currentRows = lastRows.coerceAtLeast(1)
        val searchHeight = if (searchVisible) searchBar.preferredHeight().coerceAtMost(currentRows - 1) else 0
        val gutterWidth = computeGutterWidth()
        val layout = lastLayout ?: buildLayout(
            buffer.text().split("\n"),
            (lastCols - gutterWidth).coerceAtLeast(0)
        ).also { lastLayout = it }
        triggerCodeIntel()
        ensureCursorVisible(currentRows, searchHeight, layout, gutterWidth)
    }

    private fun syncSearchUiFromBuffer() {
        val state = buffer.searchState()
        searchBar.updateFromSearchState(state)
    }

    private fun renderLineWithTokens(
        canvas: CanvasRenderer,
        text: String,
        y: Int,
        startX: Int,
        maxCols: Int,
        tokens: List<editor.grammars.Token>,
        overlayTokens: List<editor.grammars.Token> = emptyList(),
        baseStyle: StyleSet,
        selection: IntRange?,
        selectionStyle: StyleSet,
        highlights: List<FoundToken>,
        highlightStyle: StyleSet,
        activeHighlightStyle: StyleSet,
        hoveredUsageRange: IntRange? = null
    ) {
        if (maxCols <= 0) return
        val keywordTokens = tokens.filter { tok -> tok.scopes.any { it.contains("keyword") } }
        val nonKeywordTokens = if (keywordTokens.isEmpty()) tokens else tokens - keywordTokens.toSet()
        val usageTokens = overlayTokens.filter { tok ->
            tok.scopes.any { scope -> scope.contains("codeintel.usage") }
        }
        val declarationOverlays = if (usageTokens.isEmpty()) overlayTokens else overlayTokens - usageTokens.toSet()
        val methodFieldOverlays = declarationOverlays.filter { tok ->
            tok.scopes.any { scope -> scope.contains("codeintel.method") || scope.contains("codeintel.field") }
        }
        val otherOverlays = if (methodFieldOverlays.isEmpty()) declarationOverlays else declarationOverlays - methodFieldOverlays.toSet()

        val baseSegments = buildSegments(text, nonKeywordTokens, baseStyle)
        val withCodeIntel = applyTokenOverlays(baseSegments, otherOverlays, baseStyle)
        val withMethodFields = applyTokenOverlays(withCodeIntel, methodFieldOverlays, baseStyle)
        val withKeywords = applyTokenOverlays(withMethodFields, keywordTokens, baseStyle)
        val withHover = applyUsageHover(withKeywords, hoveredUsageRange, baseStyle)
        val withHighlights = applyHighlights(withHover, highlights, highlightStyle, activeHighlightStyle)
        val withSelection = applySelection(withHighlights, selection, selectionStyle)
        withSelection.forEach { seg ->
            if (seg.start >= maxCols) return
            val drawEnd = minOf(seg.end, maxCols, text.length)
            if (drawEnd <= seg.start) return@forEach
            val part = text.substring(seg.start, drawEnd)
            if (part.isNotEmpty()) {
                canvas.withStyle(seg.style) {
                    drawText(startX + seg.start, y, part)
                }
            }
        }
    }

    private fun selectionRangeForLine(selection: SelectionRange?, lineIdx: Int, lineText: String): IntRange? {
        selection ?: return null
        if (lineIdx < selection.start.line || lineIdx > selection.end.line) return null
        val lineLength = lineText.length
        val startCol = if (lineIdx == selection.start.line) selection.start.column else 0
        val endCol = if (lineIdx == selection.end.line) selection.end.column else lineLength
        val start = startCol.coerceIn(0, lineLength)
        val end = endCol.coerceIn(0, lineLength)
        if (start >= end) return null
        return start until end
    }

    private data class StyledSegment(val start: Int, val end: Int, val style: StyleSet)
    private data class HoveredUsage(val line: Int, val startColumn: Int, val endColumn: Int)

    private fun computePreviewWidth(totalCols: Int): Int {
        if (totalCols < 40) return 0
        return (totalCols / 3).coerceIn(8, 20)
    }

    private fun renderBrailleBlocks(
        canvas: CanvasRenderer,
        lines: List<String>,
        gutterWidth: Int,
        bodyStartRow: Int,
        bodyRows: Int,
        gutterStyle: StyleSet,
        bodyStyle: StyleSet,
        blockOffset: Int
    ) {
        val contentCols = (canvas.cols() - gutterWidth).coerceAtLeast(0)
        if (contentCols <= 0 || bodyRows <= 0) return
        // Each braille cell represents 2 columns (x) and 4 rows (y) of source.
        // Horizontal squashing: pack two source columns into one braille cell.
        val cellsPerRow = contentCols.coerceAtLeast(1)
        val maxBlocks = bodyRows
        val totalBlocks = (lines.size + 3) / 4
        val offset = blockOffset.coerceIn(0, (totalBlocks - maxBlocks).coerceAtLeast(0))

        canvas.withStyle(bodyStyle) {
            drawRect(0, bodyStartRow, canvas.cols(), bodyRows)
        }

        for (blockIdx in 0 until bodyRows) {
            val block = offset + blockIdx
            if (block >= totalBlocks) break
            val y = bodyStartRow + blockIdx
            val lineNumber = block * 4 + 1
            val number = lineNumber.toString().padStart(gutterWidth, ' ')
            canvas.withStyle(gutterStyle) {
                drawText(0, y, number.take(gutterWidth).padEnd(gutterWidth, ' '))
            }
            val sb = StringBuilder()
            for (cellX in 0 until cellsPerRow) {
                var mask = 0
                for (dy in 0 until 4) {
                    val srcLineIdx = block * 4 + dy
                    val srcLine = lines.getOrNull(srcLineIdx) ?: ""
                    val x0 = cellX * 2
                    val c1 = srcLine.getOrNull(x0) ?: ' '
                    val c2 = srcLine.getOrNull(x0 + 1) ?: ' '
                    if (c1 != ' ') mask = mask or dotMask(0, dy)
                    if (c2 != ' ') mask = mask or dotMask(1, dy)
                }
                sb.append((0x2800 + mask).toChar())
            }
            canvas.withStyle(bodyStyle) {
                drawText(gutterWidth, y, sb.toString())
            }
        }
    }

    private fun dotMask(dx: Int, dy: Int): Int {
        return when (dy) {
            0 -> if (dx == 0) 0x01 else 0x08   // dots 1,4
            1 -> if (dx == 0) 0x02 else 0x10   // dots 2,5
            2 -> if (dx == 0) 0x04 else 0x20   // dots 3,6
            else -> if (dx == 0) 0x40 else 0x80 // dots 7,8
        }
    }

    private fun buildSegments(
        text: String,
        tokens: List<editor.grammars.Token>,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        if (text.isEmpty()) return emptyList()
        if (tokens.isEmpty()) return listOf(StyledSegment(0, text.length, baseStyle))
        val segments = mutableListOf<StyledSegment>()
        var cursor = 0
        tokens.sortedBy { it.start }.forEach { tok ->
            val segStart = tok.start.coerceIn(0, text.length)
            val segEnd = tok.end.coerceIn(0, text.length)
            if (segStart > cursor) {
                segments.add(StyledSegment(cursor, segStart, baseStyle))
            }
            if (segEnd > segStart) {
                val style = styleForToken(tok, baseStyle)
                segments.add(StyledSegment(segStart, segEnd, style))
            }
            cursor = maxOf(cursor, segEnd)
            if (cursor >= text.length) return@forEach
        }
        if (cursor < text.length) {
            segments.add(StyledSegment(cursor, text.length, baseStyle))
        }
        return segments
    }

    private fun applySelection(
        segments: List<StyledSegment>,
        selection: IntRange?,
        selectionStyle: StyleSet
    ): List<StyledSegment> {
        selection ?: return segments
        if (segments.isEmpty()) return segments
        val selStart = selection.first
        val selEnd = selection.last + 1
        if (selStart >= selEnd) return segments

        val out = mutableListOf<StyledSegment>()
        segments.forEach { seg ->
            if (seg.end <= selStart || seg.start >= selEnd) {
                out.add(seg)
                return@forEach
            }
            if (seg.start < selStart) {
                out.add(StyledSegment(seg.start, selStart, seg.style))
            }
            val selectedStart = maxOf(seg.start, selStart)
            val selectedEnd = minOf(seg.end, selEnd)
            if (selectedStart < selectedEnd) {
                out.add(StyledSegment(selectedStart, selectedEnd, selectionStyle))
            }
            if (seg.end > selEnd) {
                out.add(StyledSegment(selEnd, seg.end, seg.style))
            }
        }
        return out
    }

    private fun applyHighlights(
        segments: List<StyledSegment>,
        highlights: List<FoundToken>,
        highlightStyle: StyleSet,
        activeHighlightStyle: StyleSet
    ): List<StyledSegment> {
        if (highlights.isEmpty()) return segments
        var current = segments
        highlights.sortedBy { it.startColumn }.forEach { token ->
            val style = if (token.active) activeHighlightStyle else highlightStyle
            current = overlayRange(current, token.startColumn, token.endColumn, style)
        }
        return current
    }

    private fun applyUsageHover(
        segments: List<StyledSegment>,
        hovered: IntRange?,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        hovered ?: return segments
        val underlineStyle = baseStyle.copy().also { style ->
            val existing = style.textDecoration
            val parts = (existing?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()).toMutableSet()
            parts += "underline"
            style.textDecoration = parts.joinToString(" ")
        }
        return overlayRange(segments, hovered.first, hovered.last + 1, underlineStyle)
    }

    private fun applyTokenOverlays(
        segments: List<StyledSegment>,
        overlays: List<editor.grammars.Token>,
        baseStyle: StyleSet
    ): List<StyledSegment> {
        if (overlays.isEmpty()) return segments
        var current = segments
        overlays.sortedBy { it.start }.forEach { tok ->
            val style = styleForToken(tok, baseStyle)
            current = overlayRange(current, tok.start, tok.end, style)
        }
        return current
    }

    private fun overlayRange(
        segments: List<StyledSegment>,
        start: Int,
        end: Int,
        style: StyleSet
    ): List<StyledSegment> {
        if (segments.isEmpty() || start >= end) return segments
        val out = mutableListOf<StyledSegment>()
        segments.forEach { seg ->
            if (seg.end <= start || seg.start >= end) {
                out.add(seg)
                return@forEach
            }
            if (seg.start < start) {
                out.add(StyledSegment(seg.start, start, seg.style))
            }
            val overlayStart = maxOf(seg.start, start)
            val overlayEnd = minOf(seg.end, end)
            if (overlayStart < overlayEnd) {
                out.add(StyledSegment(overlayStart, overlayEnd, style))
            }
            if (seg.end > end) {
                out.add(StyledSegment(end, seg.end, seg.style))
            }
        }
        return out
    }

    private fun trimSelectionToChunk(selection: IntRange, chunkStart: Int, chunkEnd: Int): IntRange? {
        val selStart = maxOf(selection.first, chunkStart)
        val selEndExclusive = minOf(selection.last + 1, chunkEnd)
        if (selStart >= selEndExclusive) return null
        return selStart - chunkStart until selEndExclusive - chunkStart
    }

    private fun sliceTokens(
        tokens: List<editor.grammars.Token>,
        chunkStart: Int,
        chunkEnd: Int
    ): List<editor.grammars.Token> =
        tokens.mapNotNull { token ->
            val start = maxOf(token.start, chunkStart)
            val end = minOf(token.end, chunkEnd)
            if (start >= end) return@mapNotNull null
            token.copy(start = start - chunkStart, end = end - chunkStart)
        }

    private fun trimHighlightToChunk(token: FoundToken, chunkStart: Int, chunkEnd: Int): FoundToken? {
        val start = maxOf(token.startColumn, chunkStart)
        val end = minOf(token.endColumn, chunkEnd)
        if (start >= end) return null
        return token.copy(startColumn = start - chunkStart, endColumn = end - chunkStart)
    }

    private fun buildLayout(lines: List<String>, contentCols: Int): VisualLayout {
        val width = contentCols.coerceAtLeast(1)
        val wrapped = mutableListOf<WrappedLine>()
        val lineOffsets = IntArray(lines.size)
        val wrapCounts = IntArray(lines.size)
        lines.forEachIndexed { idx, line ->
            lineOffsets[idx] = wrapped.size
            val len = line.length
            if (len == 0) {
                wrapped.add(WrappedLine(idx, 0, 0))
                wrapCounts[idx] = 1
            } else {
                var start = 0
                var count = 0
                while (start < len) {
                    val end = (start + width).coerceAtMost(len)
                    wrapped.add(WrappedLine(idx, start, end))
                    start = end
                    count++
                }
                wrapCounts[idx] = maxOf(1, count)
            }
        }
        if (lines.isEmpty()) {
            wrapped.add(WrappedLine(0, 0, 0))
        }
        return VisualLayout(lines, wrapped, lineOffsets, wrapCounts, width)
    }

    private fun visualRowForPosition(pos: Position, layout: VisualLayout): Int {
        if (layout.wrapped.isEmpty()) return 0
        val line = pos.line.coerceIn(0, layout.lines.lastIndex)
        val offset = layout.lineOffsets.getOrElse(line) { 0 }
        val width = layout.contentWidth.coerceAtLeast(1)
        val lineLength = layout.lines.getOrElse(line) { "" }.length
        val wraps = layout.wrapCounts.getOrElse(line) { 1 }.coerceAtLeast(1)
        val chunkIndex = (pos.column.coerceAtMost(lineLength) / width).coerceAtMost(wraps - 1)
        return offset + chunkIndex
    }

    private fun chunkForPosition(pos: Position, layout: VisualLayout): WrappedLine? {
        if (layout.wrapped.isEmpty()) return null
        val line = pos.line.coerceIn(0, layout.lines.lastIndex)
        val offset = layout.lineOffsets.getOrElse(line) { 0 }
        val width = layout.contentWidth.coerceAtLeast(1)
        val lineLength = layout.lines.getOrElse(line) { "" }.length
        val wraps = layout.wrapCounts.getOrElse(line) { 1 }.coerceAtLeast(1)
        val idxInLine = (pos.column.coerceAtMost(lineLength) / width).coerceAtMost(wraps - 1)
        val index = offset + idxInLine
        return layout.wrapped.getOrNull(index)
    }

    private fun renderUsagePopup(
        canvas: CanvasRenderer,
        bodyStartRow: Int,
        gutterWidth: Int,
        cols: Int,
        rows: Int,
        layout: VisualLayout
    ) {
        val popup = usagePopup ?: run {
            renderedPopup = null
            return
        }
        val anchorRow = visualRowForPosition(popup.anchor, layout)
        val screenRow = bodyStartRow + (anchorRow - scrollTop)
        val x = (gutterWidth + popup.anchor.column).coerceAtLeast(gutterWidth)
        val maxLabel = popup.entries.take(10).maxOfOrNull { it.label.length } ?: 0
        val width = (maxLabel + 2).coerceAtMost((cols - x).coerceAtLeast(12))
        val height = (popup.entries.size + 1).coerceAtMost((rows - screenRow - 1).coerceAtLeast(2))
        if (height < 2) {
            renderedPopup = null
            return
        }
        val finalX = x.coerceIn(0, (cols - width).coerceAtLeast(0))
        val finalY = screenRow.coerceIn(bodyStartRow, (rows - height).coerceAtLeast(bodyStartRow))
        val style = localStyleSheet.getStyle("code-search-bar").withDefaults()
        val hoverStyle = localStyleSheet.getStyle("code-search-active").withDefaults(style.fg, style.bg)
        canvas.withStyle(style) {
            drawRect(finalX, finalY, width, height)
            val entries = popup.entries.take(height - 1)
            entries.forEachIndexed { idx, entry ->
                val text = entry.label.take(width - 2).padEnd(width - 2, ' ')
                val rowStyle = if (idx == hoveredUsageIndex) hoverStyle else style
                canvas.withStyle(rowStyle) {
                    drawText(finalX + 1, finalY + idx + 1, text)
                }
            }
        }
        renderedPopup = RenderedPopup(finalX, finalY, width, height)
    }

    private fun renderSuggestionPopup(
        canvas: CanvasRenderer,
        bodyStartRow: Int,
        gutterWidth: Int,
        cols: Int,
        rows: Int,
        layout: VisualLayout
    ) {
        val popup = suggestionPopup ?: run {
            renderedSuggestion = null
            return
        }
        val anchorRow = visualRowForPosition(popup.anchor, layout)
        val screenRow = bodyStartRow + (anchorRow - scrollTop)
        val x = (gutterWidth + popup.anchor.column).coerceAtLeast(gutterWidth)
        val maxLabel = popup.entries.take(10).maxOfOrNull { it.name.length + (it.detail?.length ?: 0) + 3 } ?: 0
        val width = (maxLabel + 2).coerceAtMost((cols - x).coerceAtLeast(12))
        val height = (popup.entries.size + 1).coerceAtMost((rows - screenRow - 1).coerceAtLeast(2))
        if (height < 2) {
            renderedSuggestion = null
            return
        }
        val finalX = x.coerceIn(0, (cols - width).coerceAtLeast(0))
        val finalY = screenRow.coerceIn(bodyStartRow, (rows - height).coerceAtLeast(bodyStartRow))
        val style = localStyleSheet.getStyle("code-search-bar").withDefaults()
        val hoverStyle = localStyleSheet.getStyle("code-search-active").withDefaults(style.fg, style.bg)
        canvas.withStyle(style) {
            drawRect(finalX, finalY, width, height)
            val entries = popup.entries.take(height - 1)
            entries.forEachIndexed { idx, entry ->
                val label = buildString {
                    append(entry.name)
                    entry.detail?.let { append("  ").append(it) }
                }
                val text = label.take(width - 2).padEnd(width - 2, ' ')
                val rowStyle = if (idx == hoveredSuggestionIndex) hoverStyle else style
                canvas.withStyle(rowStyle) {
                    drawText(finalX + 1, finalY + idx + 1, text)
                }
            }
        }
        renderedSuggestion = RenderedPopup(finalX, finalY, width, height)
    }

    private data class WrappedLine(val lineIndex: Int, val startColumn: Int, val endColumn: Int)
    private data class VisualLayout(
        val lines: List<String>,
        val wrapped: List<WrappedLine>,
        val lineOffsets: IntArray,
        val wrapCounts: IntArray,
        val contentWidth: Int
    )

    private data class UsageEntry(val file: String, val line: Int, val column: Int, val label: String)
    private data class UsagePopup(val anchor: Position, val entries: List<UsageEntry>)
    private data class RenderedPopup(val x: Int, val y: Int, val width: Int, val height: Int)
    private data class SuggestionEntry(val name: String, val detail: String?)
    private data class SuggestionPopup(val anchor: Position, val prefix: String, val entries: List<SuggestionEntry>)
}
