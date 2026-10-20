package dev.dshmobile.app.ui.chat

import kotlin.math.abs
import kotlin.math.min

internal data class ViewerOffset(val x: Float, val y: Float)
internal object ViewerGeometry {
    fun clamp(x: Float, y: Float, scale: Float, width: Float, height: Float, imageWidth: Int, imageHeight: Int): ViewerOffset {
        if (imageWidth <= 0 || imageHeight <= 0 || width <= 0 || height <= 0) return ViewerOffset(0f, 0f)
        val fit = min(width / imageWidth, height / imageHeight)
        val maxX = ((imageWidth * fit * scale - width) / 2).coerceAtLeast(0f)
        val maxY = ((imageHeight * fit * scale - height) / 2).coerceAtLeast(0f)
        return ViewerOffset(x.coerceIn(-maxX, maxX), y.coerceIn(-maxY, maxY))
    }
    fun zoom(x: Float, y: Float, oldScale: Float, newScale: Float, tapX: Float, tapY: Float, width: Float, height: Float, iw: Int, ih: Int): ViewerOffset {
        val ratio = newScale / oldScale
        return clamp((tapX - width / 2) * (1 - ratio) + x * ratio, (tapY - height / 2) * (1 - ratio) + y * ratio, newScale, width, height, iw, ih)
    }
}
