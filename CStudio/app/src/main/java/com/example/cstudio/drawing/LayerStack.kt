package com.example.cstudio.drawing

import android.graphics.Bitmap
import android.graphics.Canvas
import com.example.cstudio.storage.ProjectLayerMetadata
import java.util.UUID

internal data class DrawingLayer(
    val id: String,
    val fileName: String,
    val bitmap: Bitmap,
    val canvas: Canvas = Canvas(bitmap),
    var visible: Boolean = true,
    var opacity: Float = 1f,
    var pixelVersion: Long = 0L,
    var savedPixelVersion: Long = 0L
) {
    fun metadata() = ProjectLayerMetadata(id, fileName, visible, opacity)
}

data class LayerInfo(
    val id: String,
    val visible: Boolean,
    val opacity: Float
)

internal data class LayerPixelSnapshot(
    val id: String,
    val version: Long,
    val bitmap: Bitmap
)

internal class LayerStack(
    val width: Int,
    val height: Int,
    initialLayers: List<DrawingLayer>,
    activeIndex: Int = initialLayers.lastIndex
) {
    val layers: MutableList<DrawingLayer> = initialLayers.toMutableList()
    var activeIndex: Int = activeIndex
        private set

    val activeLayer: DrawingLayer
        get() = layers[activeIndex]

    init {
        require(layers.isNotEmpty())
        require(layers.size <= MAX_LAYERS)
        require(activeIndex in layers.indices)
        require(canFit(width, height, layers.size))
        require(layers.all {
            it.bitmap.width == width && it.bitmap.height == height &&
                it.bitmap.config == Bitmap.Config.ARGB_8888 && it.bitmap.isMutable
        })
    }

    fun addLayer(): Boolean {
        if (layers.size >= MAX_LAYERS || !canFit(width, height, layers.size + 1)) return false
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        try {
            val suffix = UUID.randomUUID().toString()
            val layer = DrawingLayer(
                id = "layer-$suffix",
                fileName = "layer_$suffix.png",
                bitmap = bitmap,
                pixelVersion = 1L
            )
            val insertAt = activeIndex + 1
            layers.add(insertAt, layer)
            activeIndex = insertAt
            return true
        } catch (error: OutOfMemoryError) {
            bitmap.recycle()
            throw error
        }
    }

    fun remove(index: Int): DrawingLayer? {
        if (layers.size <= 1 || index !in layers.indices) return null
        val removed = layers.removeAt(index)
        activeIndex = when {
            index < activeIndex -> activeIndex - 1
            index == activeIndex -> minOf(index, layers.lastIndex)
            else -> activeIndex
        }
        return removed
    }

    fun move(index: Int, delta: Int): Boolean {
        val destination = index + delta
        if (index !in layers.indices || destination !in layers.indices || delta !in -1..1 || delta == 0) {
            return false
        }
        val selected = activeLayer
        val moved = layers.removeAt(index)
        layers.add(destination, moved)
        activeIndex = layers.indexOf(selected)
        return true
    }

    fun select(index: Int): Boolean {
        if (index !in layers.indices) return false
        val changed = activeIndex != index
        activeIndex = index
        return changed
    }

    fun setVisible(index: Int, visible: Boolean): Boolean {
        val layer = layers.getOrNull(index) ?: return false
        if (layer.visible == visible) return false
        layer.visible = visible
        return true
    }

    fun setOpacity(index: Int, opacity: Float): Boolean {
        val layer = layers.getOrNull(index) ?: return false
        val value = opacity.coerceIn(0f, 1f)
        if (layer.opacity == value) return false
        layer.opacity = value
        return true
    }

    companion object {
        internal const val MAX_LAYERS = 20
        internal const val LAYER_MEMORY_BUDGET = 256L * 1024L * 1024L

        internal fun canFit(width: Int, height: Int, count: Int): Boolean {
            if (width <= 0 || height <= 0 || count < 1 || count > MAX_LAYERS) return false
            return width.toLong() * height.toLong() * 4L * count <= LAYER_MEMORY_BUDGET
        }
    }
}
