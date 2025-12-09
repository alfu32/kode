package editor.ui

import editor.grammars.SyntaxProvider
import editor.lib.Position
import editor.app.EditorSessionState
import editor.lib.SearchState
import editor.lib.ProjectSearcher
import editor.lib.ProjectSearchMatch
import react.BaseComponent
import react.ClippedCanvasRenderer
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer
import kotlin.math.max
import java.nio.file.Path
import java.nio.file.Paths

class ProjectSearchDialog(
    styleSheet: StyleSheet,
    private val onDismiss: () -> Unit,
    syntaxProvider: SyntaxProvider? = null,
    private val onDirtyFile: (String, EditorSessionState?) -> Unit = { _, _ -> },
    private val projectRoot: Path = Paths.get(System.getProperty("user.dir")),
    private val searcher: ProjectSearcher = ProjectSearcher()
) : BaseComponent(styleSheet) {

    data class MatchLine(
        val filePath: String,
        val lineNumber: Int,
        val lineText: String,
        val matchRange: IntRange
    )

    private data class Bounds(val x: Int, val y: Int, val width: Int, val height: Int) {
        fun contains(px: Int, py: Int): Boolean {
            if (width <= 0 || height <= 0) return false
            return px in x until (x + width) && py in y until (y + height)
        }
    }

    private class InputState(var text: String = "", var cursor: Int = 0) {
        fun clampCursor() {
            cursor = cursor.coerceIn(0, text.length)
        }

        fun handleKey(key: String, ev: UIEvent): Boolean {
            val normalized = key.lowercase()
            when (normalized) {
                "left" -> {
                    if (cursor > 0) {
                        cursor--
                        return true
                    }
                }
                "right" -> {
                    if (cursor < text.length) {
                        cursor++
                        return true
                    }
                }
                "home" -> {
                    if (cursor != 0) {
                        cursor = 0
                        return true
                    }
                }
                "end" -> {
                    val end = text.length
                    if (cursor != end) {
                        cursor = end
                        return true
                    }
                }
                "backspace" -> {
                    if (cursor > 0) {
                        text = text.removeRange(cursor - 1, cursor)
                        cursor--
                        return true
                    }
                }
                "delete" -> {
                    if (cursor < text.length) {
                        text = text.removeRange(cursor, cursor + 1)
                        return true
                    }
                }
                else -> if (!ev.ctrl && !ev.alt && !ev.meta && key.length == 1) {
                    text = text.substring(0, cursor) + key + text.substring(cursor)
                    cursor++
                    return true
                }
            }
            return false
        }
    }

    private enum class FocusTarget { SEARCH, FILTER, LIST, EDITOR }

    private var bounds: Bounds = Bounds(0, 0, 0, 0)
    private var searchBar: SearchReplaceBar = SearchReplaceBar(styleSheet, this::handleSearchAction)
    private var filterState = InputState()
    private var focus: FocusTarget = FocusTarget.SEARCH
    private var matches: List<MatchLine> = emptyList()
    private var selectedIndex: Int = 0
    private var listScroll: Int = 0
    private var dividerOffset: Int = 4
    private var dividerDragging = false
    private val codeEditor = CodeEditorView(styleSheet, syntaxProvider = syntaxProvider)
    private var contentWidth = 0
    private var contentHeight = 0
    private var contentX = 0
    private var contentY = 0
    private var currentQuery: String = ""
    private var currentFilter: String = ""
    private var lastPatternError: String? = null

    init {
        setFocus(FocusTarget.SEARCH)
    }

    fun setInitialInputs(searchQuery: String?, fileFilter: String?) {
        val query = searchQuery ?: ""
        currentQuery = query
        currentFilter = fileFilter ?: ""
        searchBar.updateFromSearchState(SearchState(query, "", 0, -1, null))
        filterState.text = currentFilter
        filterState.cursor = filterState.text.length
        filterState.clampCursor()
        setFocus(FocusTarget.SEARCH)
        runSearch()
    }

    fun setMatches(newMatches: List<MatchLine>, patternError: String? = null) {
        matches = newMatches
        selectedIndex = if (matches.isNotEmpty()) 0 else -1
        listScroll = 0
        lastPatternError = patternError
        loadSelectedMatch()
        updateSearchStatus()
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val dialogWidth = max(10, cols / 2).coerceAtMost(cols)
        val dialogHeight = (rows - 2).coerceAtLeast(4).coerceAtMost(rows)
        val startX = ((cols - dialogWidth) / 2).coerceAtLeast(0)
        val startY = ((rows - dialogHeight) / 2).coerceAtLeast(0)
        val dialogStyle = styleSheet.getStyle("project-search-dialog").withDefaults()
        val borderStyle = styleSheet.getStyle("project-search-dialog-border").withDefaults(dialogStyle.fg, dialogStyle.bg)

        bounds = Bounds(startX, startY, dialogWidth, dialogHeight)
        contentX = startX + 1
        contentY = startY + 1
        contentWidth = (dialogWidth - 2).coerceAtLeast(1)
        contentHeight = (dialogHeight - 2).coerceAtLeast(1)
        dividerOffset = dividerOffset.coerceIn(1, contentHeight - 2)

        // Fill background
        canvas.withStyle(dialogStyle) {
            drawRect(startX, startY, dialogWidth, dialogHeight)
        }

        // Simple border hint
        canvas.withStyle(borderStyle) {
            val endX = (startX + dialogWidth - 1).coerceAtLeast(startX)
            val endY = (startY + dialogHeight - 1).coerceAtLeast(startY)
            for (x in startX..endX) {
                drawText(x, startY, "-")
                drawText(x, endY, "-")
            }
            for (y in startY..endY) {
                drawText(startX, y, "|")
                drawText(endX, y, "|")
            }
            drawText(startX, startY, "+")
            drawText(endX, startY, "+")
            drawText(startX, endY, "+")
            drawText(endX, endY, "+")
        }

        renderContent(canvas)
    }

    private fun renderContent(canvas: CanvasRenderer) {
        val headerHeight = searchBar.preferredHeight().coerceAtMost(contentHeight)
        val filterHeight = 1
        val bodyY = contentY + headerHeight + filterHeight
        val bodyHeight = (contentHeight - headerHeight - filterHeight).coerceAtLeast(1)
        val listHeight = dividerOffset.coerceIn(1, bodyHeight - 1)
        val editorHeight = (bodyHeight - listHeight - 1).coerceAtLeast(1)

        val searchClip = ClippedCanvasRenderer(
            base = canvas,
            offsetX = contentX,
            offsetY = contentY,
            width = contentWidth,
            height = headerHeight
        )
        searchBar.render(searchClip)

        renderFilter(canvas, contentX, contentY + headerHeight, contentWidth)

        renderMatchList(canvas, contentX, bodyY, contentWidth, listHeight)

        renderDivider(canvas, contentX, bodyY + listHeight, contentWidth)

        val editorClip = ClippedCanvasRenderer(
            base = canvas,
            offsetX = contentX,
            offsetY = bodyY + listHeight + 1,
            width = contentWidth,
            height = editorHeight
        )
        codeEditor.render(editorClip)
    }

    private fun renderFilter(canvas: CanvasRenderer, x: Int, y: Int, width: Int) {
        val label = "File regex: "
        val labelStyle = styleSheet.getStyle("code-search-label").withDefaults()
        val fieldStyle = styleSheet.getStyle("code-search-field").withDefaults(labelStyle.fg, labelStyle.bg)
        val activeFieldStyle = styleSheet.getStyle("code-search-field-active").withDefaults(fieldStyle.fg, fieldStyle.bg)
        val cursorStyle = styleSheet.getStyle("code-search-cursor").withDefaults(fieldStyle.bg, fieldStyle.fg)
        val isActive = focus == FocusTarget.FILTER

        canvas.withStyle(labelStyle) {
            drawText(x, y, label.take(width).padEnd(label.length.coerceAtMost(width), ' '))
        }
        val startX = x + label.length
        val available = (width - label.length).coerceAtLeast(1)
        val cursor = filterState.cursor.coerceIn(0, filterState.text.length)
        val windowStart = (cursor - available + 1).coerceAtLeast(0)
        val visibleText = filterState.text.substring(windowStart).take(available)
        val padText = visibleText.padEnd(available, ' ')

        val fieldStyleToUse = if (isActive) activeFieldStyle else fieldStyle
        canvas.withStyle(fieldStyleToUse) {
            drawText(startX, y, padText)
        }
        if (isActive) {
            val cursorX = startX + (cursor - windowStart).coerceAtLeast(0).coerceAtMost(available - 1)
            canvas.withStyle(cursorStyle) {
                val ch = padText.getOrElse(cursorX - startX) { ' ' }
                drawText(cursorX, y, ch.toString())
            }
        }
    }

    private fun renderMatchList(canvas: CanvasRenderer, x: Int, y: Int, width: Int, height: Int) {
        val listStyle = styleSheet.getStyle("project-search-list").withDefaults()
        val selectedStyle = styleSheet.getStyle("project-search-list-selected").withDefaults(listStyle.bg, listStyle.fg)
        val pathStyle = styleSheet.getStyle("project-search-list-path").withDefaults(listStyle.fg, listStyle.bg)
        val markerStyle = styleSheet.getStyle("project-search-list-marker").withDefaults(listStyle.fg, listStyle.bg)
        val highlightStyle = styleSheet.getStyle("project-search-list-highlight").withDefaults(listStyle.fg, listStyle.bg)

        val visibleMatches = matches.drop(listScroll).take(height)
        canvas.withStyle(listStyle) {
            drawRect(x, y, width, height)
        }
        if (visibleMatches.isEmpty()) {
            canvas.withStyle(listStyle) {
                drawText(x, y, "(no matches)".take(width))
            }
            return
        }
        visibleMatches.forEachIndexed { idx, match ->
            val isSelected = (listScroll + idx) == selectedIndex
            val rowY = y + idx
            val marker = if (isSelected) "[*]" else "[ ]"
            canvas.withStyle(markerStyle) {
                drawText(x, rowY, marker.take(width.coerceAtLeast(0)))
            }
            val textStart = x + marker.length + 1
            val available = (width - (textStart - x)).coerceAtLeast(0)
            val matchText = match.lineText
            val clippedText = if (matchText.length > available) matchText.take(available) else matchText
            val highlightStart = match.matchRange.first.coerceIn(0, clippedText.length)
            val highlightEnd = (match.matchRange.last + 1).coerceIn(highlightStart, clippedText.length)
            if (highlightStart < highlightEnd) {
                val before = clippedText.substring(0, highlightStart)
                val highlight = clippedText.substring(highlightStart, highlightEnd)
                val after = clippedText.substring(highlightEnd, clippedText.length.coerceAtLeast(highlightEnd))
                canvas.withStyle(if (isSelected) selectedStyle else listStyle) {
                    drawText(textStart, rowY, before)
                }
                canvas.withStyle(highlightStyle) {
                    drawText(textStart + before.length, rowY, highlight)
                }
                val trailingX = textStart + before.length + highlight.length
                val remaining = available - (before.length + highlight.length)
                val afterText = after.take(remaining).padEnd(remaining, ' ')
                canvas.withStyle(if (isSelected) selectedStyle else listStyle) {
                    drawText(trailingX, rowY, afterText)
                }
            } else {
                val pad = clippedText.padEnd(available, ' ')
                canvas.withStyle(if (isSelected) selectedStyle else listStyle) {
                    drawText(textStart, rowY, pad)
                }
            }
            val fileLabel = " ${match.filePath.substringAfterLast('/')}"
            val pathStart = (x + width - fileLabel.length).coerceAtLeast(textStart)
            canvas.withStyle(pathStyle) {
                bold(true)
                drawText(pathStart, rowY, fileLabel.take(width - (pathStart - x)))
                bold(false)
            }
        }
    }

    private fun renderDivider(canvas: CanvasRenderer, x: Int, y: Int, width: Int) {
        val dividerStyle = styleSheet.getStyle("project-search-divider").withDefaults()
        canvas.withStyle(dividerStyle) {
            drawRect(x, y, width, 1)
            if (width > 2) {
                drawText(x + width / 2, y, "─")
            }
        }
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "mouse_down") {
            val x = event.x ?: return false
            val y = event.y ?: return false
            val inside = bounds.contains(x, y)
            if (!inside) {
                onDismiss()
                return true
            }
            if (isOnDivider(y)) {
                dividerDragging = true
                return true
            }
        }
        if (event.kind == "mouse_up") {
            dividerDragging = false
        }
        if (event.kind == "mouse_move" && dividerDragging) {
            val y = event.y ?: return false
            dividerOffset = (y - contentY - searchBar.preferredHeight() - 1).coerceIn(1, contentHeight - 2)
            return true
        }

        val localEvent = toLocal(event) ?: return false
        val headerHeight = searchBar.preferredHeight().coerceAtMost(contentHeight)
        val filterHeight = 1
        val bodyY = headerHeight + filterHeight
        val listHeight = dividerOffset.coerceIn(1, (contentHeight - headerHeight - filterHeight - 1).coerceAtLeast(1))

        if (localEvent.kind == "mouse_down") {
            when {
                localEvent.y in 0 until headerHeight -> setFocus(FocusTarget.SEARCH)
                localEvent.y in headerHeight until (headerHeight + filterHeight) -> setFocus(FocusTarget.FILTER)
                localEvent.y in (headerHeight + filterHeight) until (headerHeight + filterHeight + listHeight) -> setFocus(FocusTarget.LIST)
                else -> setFocus(FocusTarget.EDITOR)
            }
        }

        if (localEvent.kind == "key_down" && localEvent.ctrl && localEvent.key.equals("s", ignoreCase = true)) {
            val editorEvent = localEvent.alterCopy(
                UIEvent(
                    kind = localEvent.kind,
                    x = localEvent.x,
                    y = localEvent.y?.minus(bodyY + listHeight + 1),
                    relX = localEvent.relX,
                    relY = localEvent.relY,
                    button = localEvent.button,
                    scrollDelta = localEvent.scrollDelta,
                    key = localEvent.key,
                    ctrl = localEvent.ctrl,
                    alt = localEvent.alt,
                    shift = localEvent.shift,
                    meta = localEvent.meta,
                    focusId = localEvent.focusId,
                    cols = localEvent.cols,
                    rows = localEvent.rows,
                    raw = localEvent.raw
                )
            )
            if (codeEditor.dispatch(editorEvent)) {
                maybeRecordRecent()
                return true
            }
        }

        if (focus == FocusTarget.SEARCH) {
            val handled = searchBar.dispatch(localEvent)
            if (handled) return true
        }

        if (focus == FocusTarget.FILTER) {
            val handled = handleFilterEvent(localEvent)
            if (handled) return true
        }

        if (focus == FocusTarget.LIST) {
            val handled = handleListEvent(localEvent, listHeight, headerHeight + filterHeight)
            if (handled) return true
        }

        if (focus == FocusTarget.EDITOR) {
            val editorEvent = localEvent.alterCopy(
                UIEvent(
                    kind = localEvent.kind,
                    x = localEvent.x,
                    y = localEvent.y?.minus(bodyY + listHeight + 1),
                    relX = localEvent.relX,
                    relY = localEvent.relY,
                    button = localEvent.button,
                    scrollDelta = localEvent.scrollDelta,
                    key = localEvent.key,
                    ctrl = localEvent.ctrl,
                    alt = localEvent.alt,
                    shift = localEvent.shift,
                    meta = localEvent.meta,
                    focusId = localEvent.focusId,
                    cols = localEvent.cols,
                    rows = localEvent.rows,
                    raw = localEvent.raw
                )
            )
            val handled = codeEditor.dispatch(editorEvent)
            if (handled) {
                maybeRecordRecent()
                return true
            }
        }
        return true
    }

    private fun setFocus(target: FocusTarget) {
        focus = target
        searchBar.setFocusEnabled(target == FocusTarget.SEARCH)
    }

    private fun handleSearchAction(action: SearchReplaceBar.SearchCommand) {
        when (action) {
            is SearchReplaceBar.SearchCommand.Change -> {
                currentQuery = action.query
                runSearch()
            }
            SearchReplaceBar.SearchCommand.FindNext -> {
                moveSelection(1)
            }
            SearchReplaceBar.SearchCommand.FindAll -> {
                runSearch()
            }
            else -> {
                // Replace actions are ignored for project-wide search for now
            }
        }
    }

    private fun runSearch() {
        val result = searcher.search(projectRoot, currentQuery, currentFilter)
        val mapped = result.matches.map { toMatchLine(it) }
        setMatches(mapped, result.patternError)
        val activeIndex = if (selectedIndex in mapped.indices) selectedIndex else -1
        searchBar.updateFromSearchState(
            SearchState(
                query = currentQuery,
                replacement = "",
                matchCount = mapped.size,
                activeIndex = activeIndex,
                patternError = result.patternError
            )
        )
    }

    private fun toMatchLine(match: ProjectSearchMatch): MatchLine =
        MatchLine(
            filePath = match.filePath,
            lineNumber = match.lineNumber,
            lineText = match.lineText,
            matchRange = match.matchRange
        )

    private fun handleFilterEvent(event: UIEvent): Boolean {
        if (event.kind != "key_down") return false
        val key = event.key ?: return true
        val before = filterState.text
        filterState.handleKey(key, event)
        currentFilter = filterState.text
        if (before != currentFilter) {
            runSearch()
        }
        return true // consume all key events while filter is focused
    }

    private fun handleListEvent(event: UIEvent, listHeight: Int, listStartY: Int): Boolean {
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "up" -> {
                    moveSelection(-1)
                    return true
                }
                "down" -> {
                    moveSelection(1)
                    return true
                }
                "pageup" -> {
                    moveSelection(-listHeight)
                    return true
                }
                "pagedown" -> {
                    moveSelection(listHeight)
                    return true
                }
                "enter" -> {
                    loadSelectedMatch()
                    return true
                }
            }
        }
        if (event.kind == "mouse_down") {
            val y = event.y ?: return false
            val localY = (y - listStartY).coerceAtLeast(0)
            val idx = listScroll + localY
            if (idx in matches.indices) {
                selectedIndex = idx
                loadSelectedMatch()
            }
            return true
        }
        if (event.kind == "mouse_scroll") {
            val delta = event.scrollDelta ?: return false
            val maxScroll = (matches.size - listHeight).coerceAtLeast(0)
            val prev = listScroll
            listScroll = (listScroll - delta).coerceIn(0, maxScroll)
            return listScroll != prev
        }
        return false
    }

    private fun moveSelection(delta: Int) {
        if (matches.isEmpty()) return
        val newIndex = (selectedIndex + delta).coerceIn(0, matches.lastIndex)
        selectedIndex = newIndex
        ensureSelectionVisible()
        loadSelectedMatch()
        updateSearchStatus()
    }

    private fun ensureSelectionVisible() {
        val headerHeight = searchBar.preferredHeight().coerceAtMost(contentHeight)
        val filterHeight = 1
        val listVisible = (contentHeight - headerHeight - filterHeight - 1).coerceAtLeast(1)
        val listHeight = dividerOffset.coerceIn(1, listVisible)
        val maxScroll = (matches.size - listHeight).coerceAtLeast(0)
        if (selectedIndex < listScroll) listScroll = selectedIndex
        if (selectedIndex >= listScroll + listHeight) listScroll = (selectedIndex - listHeight + 1).coerceIn(0, maxScroll)
    }

    private fun loadSelectedMatch() {
        val match = matches.getOrNull(selectedIndex) ?: return
        try {
            codeEditor.openFile(match.filePath)
            val start = Position(match.lineNumber, match.matchRange.first)
            val end = Position(match.lineNumber, (match.matchRange.last + 1).coerceAtLeast(match.matchRange.first))
            codeEditor.setSelection(start, end, center = true)
        } catch (_: Exception) {
            // ignore missing files for now
        }
    }

    private fun updateSearchStatus() {
        val activeIndex = if (selectedIndex in matches.indices) selectedIndex else -1
        searchBar.updateMatchLabel(activeIndex, matches.size, lastPatternError)
    }

    private fun maybeRecordRecent() {
        val path = codeEditor.currentPath()
        if (path.isEmpty()) return
        if (!codeEditor.isDirty()) return
        onDirtyFile(path, codeEditor.captureState())
    }

    private fun toLocal(event: UIEvent): UIEvent? {
        val x = event.x ?: 0
        val y = event.y ?: 0
        if (event.kind.startsWith("mouse") && !bounds.contains(x, y)) return null
        return event.alterCopy(
            UIEvent(
                kind = event.kind,
                x = x - contentX,
                y = y - contentY,
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
                cols = contentWidth,
                rows = contentHeight,
                raw = event.raw
            )
        )
    }

    private fun isOnDivider(globalY: Int): Boolean {
        val dividerY = contentY + searchBar.preferredHeight() + 1 + dividerOffset
        return globalY == dividerY
    }
}
