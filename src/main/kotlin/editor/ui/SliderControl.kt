package editor.ui

import react.BaseComponent
import react.StyleSet
import react.StyleSheet
import react.UIEvent
import react.renderer.CanvasRenderer

class SliderControl(
    styleSheet: StyleSheet,
    val label: String,
    private val minVal: Double,
    private val maxVal: Double,
    private val onChange: (Double) -> Unit,
    private val onRelease: (Double) -> Unit
) : BaseComponent(styleSheet) {
    var value: Double = minVal
    private var row: Int = 0
    private var startX: Int = 0
    private var endX: Int = 0
    private var dragging: Boolean = false
    private val trackStyle: StyleSet = styleSheet.getStyle("slider-track").withDefaults()
    private val indicatorStyle: StyleSet = styleSheet.getStyle("slider-indicator").withDefaults()

    fun renderAt(canvas: CanvasRenderer, row: Int, cols: Int) {
        this.row = row
        val trackLen = (cols - label.length - 8).coerceAtLeast(10)
        startX = label.length + 2
        endX = startX + trackLen - 1
        val ratio = ((value - minVal) / (maxVal - minVal)).coerceIn(0.0, 1.0)
        val indicatorPos = startX + (ratio * (trackLen - 1)).toInt().coerceIn(0, trackLen - 1)
        val track = CharArray(trackLen) { '─' }
        if (indicatorPos in startX..endX) {
            track[indicatorPos - startX] = '█'
        }
        val text = "$label: ".padEnd(startX, ' ') + "[" + String(track) + "]"
        canvas.applyStyle(trackStyle) {
            drawText(0, row, text.take(cols))
        }
        // Overlay indicator with its style if it sits inside the track.
        if (indicatorPos in startX..endX) {
            canvas.applyStyle(indicatorStyle) {
                val idx = indicatorPos - startX
                if (idx in track.indices) {
                    drawText(startX + idx + 1, row, String(charArrayOf(track[idx])))
                }
            }
        }
    }

    fun onMouseDown(x: Int?, y: Int?): Boolean {
        if (x == null || y == null) return false
        if (y != row) return false
        updateValueFromX(x)
        dragging = true
        return true
    }

    fun onMouseMove(x: Int?, y: Int?): Boolean {
        if (!dragging) return false
        if (x == null) return false
        updateValueFromX(x)
        onChange(value)
        return true
    }

    fun onMouseUp(): Boolean {
        val wasDragging = dragging
        dragging = false
        if (wasDragging) {
            onRelease(value)
        }
        return wasDragging
    }

    fun isDragging(): Boolean = dragging

    fun setValueSilently(v: Double) {
        value = v.coerceIn(minVal, maxVal)
    }

    override fun render(canvas: CanvasRenderer) {
        // Rendering is driven by renderAt with explicit row/cols from parent.
    }

    override fun dispatch(event: UIEvent): Boolean = false

    private fun updateValueFromX(x: Int) {
        val clamped = x.coerceIn(startX, endX)
        val len = (endX - startX).coerceAtLeast(1)
        val ratio = (clamped - startX).toDouble() / len.toDouble()
        val newValue = minVal + (maxVal - minVal) * ratio
        if (newValue != value) {
            value = newValue
            onChange(value)
        }
    }
}