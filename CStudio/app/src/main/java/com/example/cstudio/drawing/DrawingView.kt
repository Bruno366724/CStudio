package com.example.cstudio.drawing

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import com.example.cstudio.CanvasDimensions
import com.example.cstudio.brush.Brush
import com.example.cstudio.brush.BrushSettings
import com.example.cstudio.brush.Brushes
import com.example.cstudio.storage.ProjectLayerMetadata
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Vista clásica del lienzo; coordina entrada, viewport, renderizado e historial.
 *
 * Cómo funciona ahora:
 *  - Cada capa vive en un Bitmap transparente de tamaño fijo.
 *  - El trazo EN CURSO no se pinta en ese Bitmap: se guarda como lista de puntos
 *    (x, y, ancho) y se redibuja en cada onDraw directamente sobre el canvas de la
 *    pantalla (acelerado por GPU), usando las coordenadas del lienzo.
 *  - Al soltar el lápiz, el trazo se "confirma": se pinta una sola vez en el Bitmap.
 *
 * Por qué así: cada vez que un Bitmap cambia, Android tiene que volver a subirlo
 * entero a la GPU. Si lo modificáramos en cada evento del lápiz, en una tableta
 * grande se notaría lento. Así el Bitmap cambia una sola vez por trazo.
 * Además nos da la opacidad correcta (sin zonas más oscuras donde se superponen
 * los segmentos) y el trazo como unidad, que es lo que necesita "deshacer".
 */
class DrawingView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val density = resources.displayMetrics.density

    // Los ajustes se leen al iniciar cada trazo.
    var brushSettings = BrushSettings(Color.BLACK, 12f, 1f, Brushes.PENCIL)
    var onStrokeCommitted: (() -> Unit)? = null
    var onLayersChanged: (() -> Unit)? = null
    var onBlockedDrawing: (() -> Unit)? = null

    private var currentCanvasWidth = CanvasDimensions.DEFAULT_WIDTH
    private var currentCanvasHeight = CanvasDimensions.DEFAULT_HEIGHT
    private var layerStack = LayerStack(
        currentCanvasWidth,
        currentCanvasHeight,
        listOf(
            DrawingLayer(
                "layer-0",
                "layer_0.png",
                Bitmap.createBitmap(
                    currentCanvasWidth,
                    currentCanvasHeight,
                    Bitmap.Config.ARGB_8888
                )
            )
        )
    )
    private var viewport = Viewport(currentCanvasWidth, currentCanvasHeight)
    private var strokeRenderer = StrokeRenderer(currentCanvasWidth, currentCanvasHeight)

    private val bitmapPaint = Paint(Paint.FILTER_BITMAP_FLAG)

    private val paperPaint = Paint().apply { color = Color.WHITE }
    private val backgroundColor = Color.LTGRAY
    private val backgroundPaint = Paint().apply { color = backgroundColor }

    // Trazo en curso: puntos con su ancho ya suavizado
    private var isDrawing = false
    private var pointsX = FloatArray(256)
    private var pointsY = FloatArray(256)
    private var pointsW = FloatArray(256)
    private var pointCount = 0
    private var strokeMinX = 0f
    private var strokeMaxX = 0f
    private var strokeMinY = 0f
    private var strokeMaxY = 0f
    private var strokeMaxWidth = 0f

    // Ajustes "congelados" al empezar el trazo
    private var strokeColor = Color.BLACK
    private var strokeOpacity = 1f
    private var strokeBrush: Brush = Brushes.PENCIL
    private var strokeSizeDp = 12f
    private var strokeStartedWithFinger = false
    private var isTransforming = false

    private var history = History(layerStack.activeLayer.bitmap)

    var onMemoryError: (() -> Unit)? = null
    var onEyedropperResult: ((Int?) -> Unit)? = null
    var eyedropperEnabled = false
        set(value) {
            field = value
            if (value) cancelStroke()
        }
    var inputEnabled = true
        set(value) {
            field = value
            if (!value) cancelStroke()
        }

    private var viewportInitialized = false
    private var activeStylus = false
    private var activePointerId = MotionEvent.INVALID_POINTER_ID
    private val strokeBoundsRect = Rect()

    val canvasWidth: Int
        get() = currentCanvasWidth

    val canvasHeight: Int
        get() = currentCanvasHeight

    val layerCount: Int
        get() = layerStack.layers.size

    val activeLayerIndex: Int
        get() = layerStack.activeIndex

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (w <= 0 || h <= 0) return
        if (!viewportInitialized) {
            resetViewport()
            viewportInitialized = true
        } else if (oldw != w || oldh != h) {
            viewport.resize(oldw, oldh, w, h)
        }
    }

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, 0f, width.toFloat(), height.toFloat(), backgroundPaint)
        canvas.save()
        canvas.clipRect(0f, 0f, width.toFloat(), height.toFloat())
        canvas.concat(viewport.matrix)
        canvas.clipRect(0f, 0f, currentCanvasWidth.toFloat(), currentCanvasHeight.toFloat())
        canvas.drawRect(0f, 0f, currentCanvasWidth.toFloat(), currentCanvasHeight.toFloat(), paperPaint)

        layerStack.layers.forEachIndexed { index, layer ->
            if (!layer.visible) return@forEachIndexed
            if (pointCount > 0 && index == layerStack.activeIndex) {
                drawActiveLayerPreview(canvas, layer)
            } else {
                drawLayer(canvas, layer)
            }
        }
        canvas.restore()
    }

    private fun drawLayer(canvas: Canvas, layer: DrawingLayer) {
        bitmapPaint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
        canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
        bitmapPaint.alpha = 255
    }

    private fun drawActiveLayerPreview(canvas: Canvas, layer: DrawingLayer) {
        val bounds = strokeBounds()
        val layerAlpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
        val outside = canvas.save()
        canvas.clipOutRect(bounds)
        bitmapPaint.alpha = layerAlpha
        canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
        bitmapPaint.alpha = 255
        canvas.restoreToCount(outside)

        val previewClip = canvas.save()
        canvas.clipRect(bounds)
        if (strokeBrush.isEraser) {
            val opacitySave = canvas.saveLayerAlpha(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                layerAlpha
            )
            val groupSave = strokeRenderer.saveLayer(canvas, bounds)
            canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
            strokeRenderer.preparePaint(strokeColor, true)
            strokeRenderer.replayStroke(canvas, pointsX, pointsY, pointsW, pointCount)
            canvas.restoreToCount(groupSave)
            canvas.restoreToCount(opacitySave)
        } else if (layerAlpha < 255) {
            val groupSave = canvas.saveLayerAlpha(
                bounds.left.toFloat(),
                bounds.top.toFloat(),
                bounds.right.toFloat(),
                bounds.bottom.toFloat(),
                layerAlpha
            )
            canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
            strokeRenderer.preparePaint(strokeColor, false)
            strokeRenderer.drawBrushStroke(
                canvas,
                combinedStrokeOpacity(),
                pointsX,
                pointsY,
                pointsW,
                pointCount,
                bounds
            )
            canvas.restoreToCount(groupSave)
        } else {
            canvas.drawBitmap(layer.bitmap, 0f, 0f, bitmapPaint)
            strokeRenderer.preparePaint(strokeColor, false)
            strokeRenderer.drawBrushStroke(
                canvas,
                combinedStrokeOpacity(),
                pointsX,
                pointsY,
                pointsW,
                pointCount,
                bounds
            )
        }
        canvas.restoreToCount(previewClip)
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!inputEnabled) return true
        val actionIndex = event.actionIndex
        val actionToolType = if (actionIndex in 0 until event.pointerCount) {
            event.getToolType(actionIndex)
        } else {
            MotionEvent.TOOL_TYPE_UNKNOWN
        }
        val isStylus = actionToolType == MotionEvent.TOOL_TYPE_STYLUS

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (eyedropperEnabled) {
                    val sampledColor = sampleColorAtViewPoint(event.x, event.y)
                    if (sampledColor == null) {
                        onEyedropperResult?.invoke(null)
                    } else {
                        eyedropperEnabled = false
                        onEyedropperResult?.invoke(sampledColor)
                    }
                    return true
                }
                activeStylus = isStylus
                isTransforming = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                val point = viewport.mapToCanvas(event.x, event.y)
                if (!viewport.isInsideCanvas(point[0], point[1])) {
                    isDrawing = false
                    pointCount = 0
                    return true
                }
                if (!layerStack.activeLayer.visible) {
                    onBlockedDrawing?.invoke()
                    return true
                }
                activePointerId = event.getPointerId(0)
                startStroke(
                    point[0],
                    point[1],
                    event.pressure,
                    isStylus,
                    event.getToolType(0) == MotionEvent.TOOL_TYPE_FINGER
                )
                invalidate()
            }

            MotionEvent.ACTION_MOVE -> {
                if (isTransforming && event.pointerCount >= 2) {
                    viewport.updateGesture(event)
                    invalidate()
                    return true
                }
                if (!isDrawing) return true
                val pointerIndex = event.findPointerIndex(activePointerId)
                if (pointerIndex < 0) return true
                val pointerIsStylus =
                    event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS
                // Android agrupa varios puntos en un solo evento: los "históricos"
                // son los intermedios. Si se ignoran, las líneas salen quebradas.
                for (i in 0 until event.historySize) {
                    val point = viewport.mapToCanvas(
                        event.getHistoricalX(pointerIndex, i),
                        event.getHistoricalY(pointerIndex, i)
                    )
                    addPoint(
                        point[0],
                        point[1],
                        widthFor(
                            event.getHistoricalPressure(pointerIndex, i),
                            pointerIsStylus,
                            strokeBrush,
                            strokeSizeDp
                        )
                    )
                }
                val point = viewport.mapToCanvas(event.getX(pointerIndex), event.getY(pointerIndex))
                addPoint(
                    point[0],
                    point[1],
                    widthFor(
                        event.getPressure(pointerIndex),
                        pointerIsStylus,
                        strokeBrush,
                        strokeSizeDp
                    )
                )
                invalidate()
            }

            MotionEvent.ACTION_UP -> {
                if (isDrawing) {
                    val pointerIndex = event.findPointerIndex(activePointerId)
                        .takeIf { it >= 0 } ?: 0
                    val pointerIsStylus =
                        event.getToolType(pointerIndex) == MotionEvent.TOOL_TYPE_STYLUS
                    val point = viewport.mapToCanvas(
                        event.getX(pointerIndex),
                        event.getY(pointerIndex)
                    )
                    addPoint(
                        point[0],
                        point[1],
                        widthFor(
                            event.getPressure(pointerIndex),
                            pointerIsStylus,
                            strokeBrush,
                            strokeSizeDp
                        )
                    )
                    finishStroke()
                }
                activeStylus = false
                isTransforming = false
            }

            MotionEvent.ACTION_POINTER_DOWN -> {
                if (event.pointerCount >= 2) {
                    if (isStylus) {
                        activeStylus = true
                        if (strokeStartedWithFinger || isTransforming) cancelStroke()
                        val point = viewport.mapToCanvas(event.getX(actionIndex), event.getY(actionIndex))
                        if (viewport.isInsideCanvas(point[0], point[1])) {
                            if (!layerStack.activeLayer.visible) {
                                onBlockedDrawing?.invoke()
                                return true
                            }
                            activePointerId = event.getPointerId(actionIndex)
                            startStroke(point[0], point[1], event.getPressure(actionIndex),
                                true, false)
                            invalidate()
                            return true
                        }
                    }
                    if (activeStylus) return true
                    if (strokeStartedWithFinger) cancelStroke() else finishStroke()
                    isTransforming = true
                    viewport.beginGesture(event)
                }
            }

            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(actionIndex) == activePointerId && isDrawing) {
                    val pointerIsStylus = actionToolType == MotionEvent.TOOL_TYPE_STYLUS
                    val point = viewport.mapToCanvas(
                        event.getX(actionIndex),
                        event.getY(actionIndex)
                    )
                    addPoint(
                        point[0],
                        point[1],
                        widthFor(
                            event.getPressure(actionIndex),
                            pointerIsStylus,
                            strokeBrush,
                            strokeSizeDp
                        )
                    )
                    finishStroke()
                }
                if (actionToolType == MotionEvent.TOOL_TYPE_STYLUS) activeStylus = false
                isTransforming = false
            }

            // CANCEL suele ser la palma o el sistema: se descarta el trazo.
            MotionEvent.ACTION_CANCEL -> {
                cancelStroke()
                activeStylus = false
                isTransforming = false
            }
        }
        return true
    }

    /** Borra todo el dibujo. */
    fun clear() {
        cancelStroke()
        val layer = layerStack.activeLayer
        try {
            history.clear(layer.bitmap)
        } catch (error: OutOfMemoryError) {
            onMemoryError?.invoke()
            return
        }
        layer.pixelVersion++
        isDrawing = false
        pointCount = 0
        invalidate()
        onStrokeCommitted?.invoke()
    }

    fun undo() {
        cancelStroke()
        if (!history.canUndo) return
        try {
            if (history.undo()) {
                layerStack.layers.firstOrNull {
                    it.bitmap === history.lastChangedTarget
                }?.let { it.pixelVersion++ }
                invalidate()
                onStrokeCommitted?.invoke()
            } else {
                onMemoryError?.invoke()
            }
        } catch (error: OutOfMemoryError) {
            onMemoryError?.invoke()
        }
    }

    fun redo() {
        cancelStroke()
        if (history.redo()) {
            layerStack.layers.firstOrNull {
                it.bitmap === history.lastChangedTarget
            }?.let { it.pixelVersion++ }
            invalidate()
            onStrokeCommitted?.invoke()
        }
    }

    fun snapshotLayer(): Bitmap {
        return layerStack.activeLayer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("No se pudo copiar la capa.")
    }

    internal fun snapshotLayerAt(index: Int): Bitmap {
        val bitmap = layerStack.layers.getOrNull(index)?.bitmap
            ?: throw IndexOutOfBoundsException("No existe la capa $index.")
        return bitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("No se pudo copiar la capa.")
    }

    fun snapshotComposite(): Bitmap {
        val composite = Bitmap.createBitmap(
            currentCanvasWidth,
            currentCanvasHeight,
            Bitmap.Config.ARGB_8888
        )
        try {
            val canvas = Canvas(composite)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            layerStack.layers.forEach { layer ->
                if (layer.visible) {
                    paint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawBitmap(layer.bitmap, 0f, 0f, paint)
                }
            }
            return composite
        } catch (error: OutOfMemoryError) {
            composite.recycle()
            throw error
        } catch (exception: Exception) {
            composite.recycle()
            throw exception
        }
    }

    fun snapshotThumbnail(): Bitmap {
        val thumbnail = Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(thumbnail)
            canvas.drawColor(Color.WHITE)
            val scale = min(
                THUMBNAIL_SIZE.toFloat() / currentCanvasWidth,
                THUMBNAIL_SIZE.toFloat() / currentCanvasHeight
            )
            val drawnWidth = currentCanvasWidth * scale
            val drawnHeight = currentCanvasHeight * scale
            val left = (THUMBNAIL_SIZE - drawnWidth) / 2f
            val top = (THUMBNAIL_SIZE - drawnHeight) / 2f
            val right = left + drawnWidth
            val bottom = top + drawnHeight
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            val destination = android.graphics.RectF(left, top, right, bottom)
            layerStack.layers.forEach { layer ->
                if (layer.visible) {
                    paint.alpha = (layer.opacity * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawBitmap(layer.bitmap, null, destination, paint)
                }
            }
            return thumbnail
        } catch (error: OutOfMemoryError) {
            thumbnail.recycle()
            throw error
        } catch (exception: Exception) {
            thumbnail.recycle()
            throw exception
        }
    }

    internal fun snapshotChangedLayers(): List<LayerPixelSnapshot> {
        val snapshots = mutableListOf<LayerPixelSnapshot>()
        try {
            layerStack.layers.forEach { layer ->
                if (layer.pixelVersion != layer.savedPixelVersion) {
                    val bitmap = layer.bitmap.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw IllegalStateException("No se pudo copiar la capa.")
                    snapshots.add(LayerPixelSnapshot(layer.id, layer.pixelVersion, bitmap))
                }
            }
            return snapshots
        } catch (error: OutOfMemoryError) {
            snapshots.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            throw error
        } catch (exception: Exception) {
            snapshots.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            throw exception
        }
    }

    internal fun markLayerSnapshotsSaved(snapshots: List<LayerPixelSnapshot>) {
        snapshots.forEach { snapshot ->
            layerStack.layers.firstOrNull { it.id == snapshot.id }?.let { layer ->
                if (layer.pixelVersion == snapshot.version) {
                    layer.savedPixelVersion = snapshot.version
                }
            }
        }
    }

    fun layerInfos(): List<LayerInfo> = layerStack.layers.map {
        LayerInfo(it.id, it.visible, it.opacity)
    }

    fun layerMetadata(): List<ProjectLayerMetadata> = layerStack.layers.map(DrawingLayer::metadata)

    fun addLayer(): Boolean {
        cancelStroke()
        if (!layerStack.addLayer()) return false
        invalidate()
        onLayersChanged?.invoke()
        return true
    }

    fun removeActiveLayer(): Boolean {
        if (layerStack.layers.size <= 1) return false
        cancelStroke()
        val removed = layerStack.activeLayer
        history.removeEntriesFor(removed.bitmap)
        layerStack.remove(layerStack.activeIndex) ?: return false
        removed.bitmap.recycle()
        invalidate()
        onLayersChanged?.invoke()
        return true
    }

    fun moveLayer(index: Int, delta: Int): Boolean {
        cancelStroke()
        if (!layerStack.move(index, delta)) return false
        invalidate()
        onLayersChanged?.invoke()
        return true
    }

    fun selectLayer(index: Int) {
        if (layerStack.select(index)) {
            cancelStroke()
            invalidate()
            onLayersChanged?.invoke()
        }
    }

    fun setLayerVisible(index: Int, visible: Boolean) {
        if (layerStack.setVisible(index, visible)) {
            if (index == layerStack.activeIndex && !visible) cancelStroke()
            invalidate()
            onLayersChanged?.invoke()
        }
    }

    fun setLayerOpacity(index: Int, opacity: Float) {
        if (layerStack.setOpacity(index, opacity)) {
            invalidate()
            onLayersChanged?.invoke()
        }
    }

    fun replaceLayer(bitmap: Bitmap, takeOwnership: Boolean = false) {
        replaceLayers(
            listOf(bitmap),
            listOf(ProjectLayerMetadata("layer-0", "layer_0.png", true, 1f)),
            takeOwnership
        )
    }

    fun replaceLayers(
        bitmaps: List<Bitmap>,
        metadata: List<ProjectLayerMetadata>,
        takeOwnership: Boolean = false
    ) {
        require(bitmaps.isNotEmpty() && bitmaps.size == metadata.size)
        val width = bitmaps.first().width
        val height = bitmaps.first().height
        require(CanvasDimensions.isValid(width, height))
        require(LayerStack.canFit(width, height, bitmaps.size)) {
            "Las capas superan el límite de memoria del lienzo."
        }
        require(bitmaps.all { it.width == width && it.height == height })
        require(metadata.map(ProjectLayerMetadata::id).toSet().size == metadata.size)
        require(metadata.map(ProjectLayerMetadata::archivo).toSet().size == metadata.size)
        require(metadata.all {
            it.id.isNotBlank() && it.archivo.isNotBlank() &&
                '/' !in it.archivo && '\\' !in it.archivo &&
                it.opacidad.isFinite() && it.opacidad in 0f..1f
        })
        require(!takeOwnership || bitmaps.all {
            it.config == Bitmap.Config.ARGB_8888 && it.isMutable
        })

        val replacements = mutableListOf<DrawingLayer>()
        try {
            bitmaps.zip(metadata).forEach { (source, info) ->
                val bitmap = if (takeOwnership) source else {
                    source.copy(Bitmap.Config.ARGB_8888, true)
                        ?: throw IllegalStateException("No se pudo copiar la capa.")
                }
                replacements.add(
                    DrawingLayer(
                        id = info.id,
                        fileName = info.archivo,
                        bitmap = bitmap,
                        visible = info.visible,
                        opacity = info.opacidad.coerceIn(0f, 1f)
                    )
                )
            }
        } catch (error: OutOfMemoryError) {
            if (!takeOwnership) replacements.forEach { it.bitmap.recycle() }
            throw error
        } catch (exception: Exception) {
            if (!takeOwnership) replacements.forEach { it.bitmap.recycle() }
            throw exception
        }

        val newStack = LayerStack(width, height, replacements)
        val oldBitmaps = layerStack.layers.map { it.bitmap }
        cancelStroke()
        history.reset()
        layerStack = newStack
        currentCanvasWidth = width
        currentCanvasHeight = height
        viewport = Viewport(width, height)
        strokeRenderer = StrokeRenderer(width, height)
        history = History(layerStack.activeLayer.bitmap)
        oldBitmaps.forEach { old ->
            if (replacements.none { it.bitmap === old } && !old.isRecycled) old.recycle()
        }
        resetViewport()
        invalidate()
        onLayersChanged?.invoke()
    }

    fun resetViewport() {
        if (width <= 0 || height <= 0) return
        viewport.reset(width, height)
        invalidate()
    }

    // ---------------------------------------------------------------- trazo

    // Con el dedo no hay presión confiable: se usa el tamaño completo.
    // Con el lápiz el ancho varía entre el 15% y el 100% del tamaño elegido.
    private fun widthFor(pressure: Float, isStylus: Boolean, brush: Brush, sizeDp: Float): Float {
        val p = if (brush.usesPressure && isStylus) pressure.coerceIn(0f, 1f) else 1f
        return sizeDp * density / viewport.fitScale *
            (brush.minWidthRatio + (1f - brush.minWidthRatio) * p)
    }

    private fun startStroke(
        x: Float,
        y: Float,
        pressure: Float,
        isStylus: Boolean,
        isFinger: Boolean
    ) {
        isDrawing = true
        strokeBrush = brushSettings.brush
        strokeSizeDp = brushSettings.sizeDp
        strokeColor = brushSettings.color
        strokeOpacity = brushSettings.opacity
        strokeStartedWithFinger = isFinger
        pointCount = 0
        strokeMinX = x
        strokeMaxX = x
        strokeMinY = y
        strokeMaxY = y
        strokeMaxWidth = 0f
        recordPoint(x, y, widthFor(pressure, isStylus, strokeBrush, strokeSizeDp))
    }

    private fun addPoint(x: Float, y: Float, targetWidth: Float) {
        val last = pointCount - 1
        if (last < 0) return
        // Ignora movimientos mínimos (evita segmentos de largo casi cero).
        if (abs(x - pointsX[last]) < 0.5f && abs(y - pointsY[last]) < 0.5f) return

        // Suaviza los cambios bruscos de presión para que el ancho no "salte".
        val width = pointsW[last] + (targetWidth - pointsW[last]) * 0.35f
        recordPoint(x, y, width)
    }

    private fun recordPoint(x: Float, y: Float, w: Float) {
        if (pointCount == pointsX.size) {
            val newSize = pointCount * 2
            pointsX = pointsX.copyOf(newSize)
            pointsY = pointsY.copyOf(newSize)
            pointsW = pointsW.copyOf(newSize)
        }
        pointsX[pointCount] = x
        pointsY[pointCount] = y
        pointsW[pointCount] = w
        pointCount++
        if (pointCount == 1) {
            strokeMinX = x
            strokeMaxX = x
            strokeMinY = y
            strokeMaxY = y
            strokeMaxWidth = w
        } else {
            strokeMinX = min(strokeMinX, x)
            strokeMaxX = maxOf(strokeMaxX, x)
            strokeMinY = min(strokeMinY, y)
            strokeMaxY = maxOf(strokeMaxY, y)
            strokeMaxWidth = maxOf(strokeMaxWidth, w)
        }
    }

    /** Pasa el trazo en curso al Bitmap (se hace una sola vez por trazo). */
    private fun finishStroke() {
        if (!isDrawing) return
        isDrawing = false
        activePointerId = MotionEvent.INVALID_POINTER_ID
        if (pointCount > 0) {
            val bounds = strokeBounds()
            val layer = layerStack.activeLayer
            var before: Bitmap? = null
            try {
                before = history.capture(bounds, layer.bitmap)
                strokeRenderer.preparePaint(strokeColor, strokeBrush.isEraser)
                if (strokeBrush.isEraser) {
                    strokeRenderer.replayStroke(layer.canvas, pointsX, pointsY, pointsW, pointCount)
                } else {
                    strokeRenderer.drawBrushStroke(
                        layer.canvas,
                        combinedStrokeOpacity(),
                        pointsX,
                        pointsY,
                        pointsW,
                        pointCount,
                        bounds
                    )
                }
                history.commit(bounds, checkNotNull(before), layer.bitmap)
                before = null
                layer.pixelVersion++
                onStrokeCommitted?.invoke()
            } catch (error: OutOfMemoryError) {
                before?.recycle()
                onMemoryError?.invoke()
                onStrokeCommitted?.invoke()
            }
        }
        pointCount = 0
        invalidate()
    }

    private fun combinedStrokeOpacity(): Float =
        strokeOpacity * (Color.alpha(strokeColor) / 255f)

    private fun sampleColorAtViewPoint(x: Float, y: Float): Int? {
        val point = viewport.mapToCanvas(x, y)
        if (!viewport.isInsideCanvas(point[0], point[1])) return null

        val xPixel = point[0].toInt()
        val yPixel = point[1].toInt()
        var red = 255f
        var green = 255f
        var blue = 255f
        layerStack.layers.forEach { layer ->
            if (layer.visible) {
                val pixel = layer.bitmap.getPixel(xPixel, yPixel)
                val alpha = Color.alpha(pixel) / 255f * layer.opacity
                val inverseAlpha = 1f - alpha
                red = Color.red(pixel) * alpha + red * inverseAlpha
                green = Color.green(pixel) * alpha + green * inverseAlpha
                blue = Color.blue(pixel) * alpha + blue * inverseAlpha
            }
        }
        return Color.rgb(
            red.roundToInt().coerceIn(0, 255),
            green.roundToInt().coerceIn(0, 255),
            blue.roundToInt().coerceIn(0, 255)
        )
    }

    private fun strokeBounds(): Rect {
        val padding = strokeMaxWidth / 2f + 2f
        val left = floor(strokeMinX - padding).toInt().coerceIn(0, currentCanvasWidth - 1)
        val top = floor(strokeMinY - padding).toInt().coerceIn(0, currentCanvasHeight - 1)
        val right = ceil(strokeMaxX + padding).toInt().coerceIn(left + 1, currentCanvasWidth)
        val bottom = ceil(strokeMaxY + padding).toInt().coerceIn(top + 1, currentCanvasHeight)
        strokeBoundsRect.set(left, top, right, bottom)
        return strokeBoundsRect
    }

    private fun cancelStroke() {
        isDrawing = false
        pointCount = 0
        activePointerId = MotionEvent.INVALID_POINTER_ID
        invalidate()
    }

    private companion object {
        const val THUMBNAIL_SIZE = 256
    }
}