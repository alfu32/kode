package editor.database.ui

import editor.database.model.DataSourceDefinition
import editor.database.model.DatabaseObject
import editor.database.model.DatabaseObjectType
import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class DatabaseObjectMenu(
    styleSheet: StyleSheet,
    private val dataSource: DataSourceDefinition,
    private val objectInfo: DatabaseObject,
    private val onAction: (String) -> Unit,
    private val onDismiss: () -> Unit
) : BaseComponent(styleSheet) {
    private data class Hit(val index: Int, val y: Int, val x: IntRange)
    private var hits: List<Hit> = emptyList()
    private var menuX = 0
    private var menuY = 0
    private var cancelY = -1

    private val actions: List<Pair<String, String>> = buildList {
        add("Copy DDL" to ddl())
        when (objectInfo.objectType) {
            DatabaseObjectType.TABLE, DatabaseObjectType.VIEW -> add("Copy SELECT" to select())
            DatabaseObjectType.FUNCTION -> add("Copy CALL / SELECT" to call())
            DatabaseObjectType.PROCEDURE -> add("Copy CALL" to call())
            DatabaseObjectType.TYPE, DatabaseObjectType.PACKAGE -> add("Copy name" to objectInfo.qualifiedName)
            else -> Unit
        }
    }

    override fun render(canvas: CanvasRenderer) {
        val cols = canvas.cols().coerceAtLeast(1)
        val rows = canvas.rows().coerceAtLeast(1)
        val width = minOf(cols - 2, 44).coerceAtLeast(24)
        val height = (actions.size + 4).coerceAtMost(rows).coerceAtLeast(6)
        menuX = ((cols - width) / 2).coerceAtLeast(0)
        menuY = ((rows - height) / 2).coerceAtLeast(0)
        val panel = styleSheet.getStyle("project-search-dialog").withDefaults()
        val border = styleSheet.getStyle("project-search-dialog-border").withDefaults(panel.fg, panel.bg)
        val active = styleSheet.getStyle("file-entry:selected").withDefaults(panel.fg, panel.bg)
        val button = styleSheet.getStyle("lsp-button").withDefaults(panel.fg, panel.bg)
        canvas.withStyle(panel) { drawRect(menuX, menuY, width, height) }
        drawBorder(canvas, border, menuX, menuY, width, height)
        canvas.withStyle(panel) {
            drawText(menuX + 2, menuY + 1, "${objectInfo.objectType}: ${objectInfo.name}".take(width - 4))
            drawText(menuX + 2, menuY + 2, dataSource.name.take(width - 4))
        }
        val rendered = mutableListOf<Hit>()
        actions.forEachIndexed { index, (label, _) ->
            val y = menuY + 3 + index
            val text = " $label "
            canvas.withStyle(if (index == 0) active else button) {
                drawText(menuX + 2, y, text.take(width - 4).padEnd(width - 4, ' '))
            }
            rendered += Hit(index, y, (menuX + 2) until (menuX + width - 2))
        }
        cancelY = menuY + height - 1
        canvas.withStyle(button) { drawText(menuX + width - 11, cancelY, " cancel ") }
        hits = rendered
    }

    override fun dispatch(event: UIEvent): Boolean {
        if (event.kind == "animation_frame") return true
        if (event.kind == "mouse_down") {
            val x = event.x ?: return true
            val y = event.y ?: return true
            hits.firstOrNull { it.y == y && x in it.x }?.let {
                onAction(actions[it.index].second)
                onDismiss()
                return true
            }
            if (y == cancelY) {
                onDismiss()
            }
            return true
        }
        if (event.kind == "key_down") {
            when (event.key?.lowercase()) {
                "escape", "esc" -> onDismiss()
                "enter", "return" -> actions.firstOrNull()?.second?.let {
                    onAction(it)
                    onDismiss()
                }
            }
            return true
        }
        return true
    }

    private fun select(): String = "SELECT * FROM ${objectInfo.qualifiedName};"

    private fun call(): String = when (objectInfo.objectType) {
        DatabaseObjectType.FUNCTION -> "SELECT ${objectInfo.qualifiedName}();"
        else -> "CALL ${objectInfo.qualifiedName}();"
    }

    private fun ddl(): String = when (objectInfo.objectType) {
        DatabaseObjectType.TABLE, DatabaseObjectType.VIEW -> when (objectInfo.attributes["driverId"]) {
            "mysql", "mariadb" -> "SHOW CREATE TABLE ${objectInfo.qualifiedName};"
            "h2" -> "SCRIPT NODATA TABLE ${objectInfo.qualifiedName};"
            else -> "-- DDL extraction is driver-specific for ${objectInfo.qualifiedName}"
        }
        DatabaseObjectType.FUNCTION, DatabaseObjectType.PROCEDURE -> "-- DDL extraction is driver-specific for ${objectInfo.qualifiedName}"
        else -> "-- DDL extraction is driver-specific for ${objectInfo.qualifiedName}"
    }

    private fun drawBorder(canvas: CanvasRenderer, style: StyleSet, x: Int, y: Int, width: Int, height: Int) {
        val right = x + width - 1
        val bottom = y + height - 1
        canvas.withStyle(style) {
            for (px in x..right) {
                drawText(px, y, if (px == x || px == right) "+" else "-")
                drawText(px, bottom, if (px == x || px == right) "+" else "-")
            }
            for (py in (y + 1) until bottom) {
                drawText(x, py, "|")
                drawText(right, py, "|")
            }
        }
    }
}
