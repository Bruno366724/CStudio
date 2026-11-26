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
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.min

/**
 * Vista clásica del lienzo; coordina entrada, viewport, renderizado e historial.
 *
 * Cómo funciona ahora:
 *  - El dibujo vive en un Bitmap transparente de tamaño fijo (la futura "capa").
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

    private var currentCanvasWidth = CanvasDimensions.DEFAULT_WIDTH
    private var currentCanvasHeight = CanvasDimensions.DEFAULT_HEIGHT
    private var layerBitmap = Bitmap.createBitmap(
        currentCanvasWidth,
        currentCanvasHeight,
        Bitmap.Config.ARGB_8888
    )
    private var layerCanvas = Canvas(layerBitmap)
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

    private var history = History(layerBitmap)

    var onMemoryError: (() -> Unit)? = null
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
        canvas.concat(viewport.matrix)
        canvas.clipRect(0f, 0f, currentCanvasWidth.toFloat(), currentCanvasHeight.toFloat())
        canvas.drawRect(0f, 0f, currentCanvasWidth.toFloat(), currentCanvasHeight.toFloat(), paperPaint)

        // Sin trazo en curso: solo se muestra el dibujo terminado.
        if (pointCount == 0) {
            canvas.drawBitmap(layerBitmap, 0f, 0f, bitmapPaint)
            canvas.restore()
            return
        }

        strokeRenderer.preparePaint(strokeColor, strokeBrush.isEraser)
        if (strokeBrush.isEraser) {
            val bounds = strokeBounds()
            canvas.drawBitmap(layerBitmap, 0f, 0f, bitmapPaint)
            val clipSave = canvas.save()
            canvas.clipRect(bounds)
            canvas.drawRect(bounds, paperPaint)
            val save = strokeRenderer.saveLayer(canvas, bounds)
            canvas.drawBitmap(layerBitmap, 0f, 0f, bitmapPaint)
            strokeRenderer.replayStroke(canvas, pointsX, pointsY, pointsW, pointCount)
            canvas.restoreToCount(save)
            canvas.restoreToCount(clipSave)
        } else {
            canvas.drawBitmap(layerBitmap, 0f, 0f, bitmapPaint)
            strokeRenderer.drawBrushStroke(
                canvas, strokeOpacity, pointsX, pointsY, pointsW, pointCount, strokeBounds()
            )
        }
        canvas.restore()
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
                activeStylus = isStylus
                isTransforming = false
                activePointerId = MotionEvent.INVALID_POINTER_ID
                val point = viewport.mapToCanvas(event.x, event.y)
                if (!viewport.isInsideCanvas(point[0], point[1])) {
                    isDrawing = false
                    pointCount = 0
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
        try {
            history.clear()
        } catch (error: OutOfMemoryError) {
            onMemoryError?.invoke()
            return
        }
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
            invalidate()
            onStrokeCommitted?.invoke()
        }
    }

    fun snapshotLayer(): Bitmap {
        return layerBitmap.copy(Bitmap.Config.ARGB_8888, true)
            ?: throw IllegalStateException("No se pudo copiar la capa.")
    }

    fun replaceLayer(bitmap: Bitmap, takeOwnership: Boolean = false) {
        require(CanvasDimensions.isValid(bitmap.width, bitmap.height)) {
            "El lienzo debe medir entre 64 y 4096 píxeles por lado."
        }
        val replacement = if (takeOwnership) {
            bitmap
        } else {
            Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888).also {
                Canvas(it).drawBitmap(bitmap, 0f, 0f, null)
            }
        }
        val replacementCanvas = Canvas(replacement)
        val replacementViewport = Viewport(replacement.width, replacement.height)
        val replacementRenderer = StrokeRenderer(replacement.width, replacement.height)
        val replacementHistory = History(replacement)
        val previousBitmap = layerBitmap

        cancelStroke()
        history.reset()
        layerBitmap = replacement
        layerCanvas = replacementCanvas
        currentCanvasWidth = replacement.width
        currentCanvasHeight = replacement.height
        viewport = replacementViewport
        strokeRenderer = replacementRenderer
        history = replacementHistory
        if (previousBitmap !== replacement) previousBitmap.recycle()
        resetViewport()
        invalidate()
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
            var before: Bitmap? = null
            try {
                before = history.capture(bounds)
                strokeRenderer.preparePaint(strokeColor, strokeBrush.isEraser)
                if (strokeBrush.isEraser) {
                    strokeRenderer.replayStroke(layerCanvas, pointsX, pointsY, pointsW, pointCount)
                } else {
                    strokeRenderer.drawBrushStroke(
                        layerCanvas, strokeOpacity, pointsX, pointsY, pointsW, pointCount, bounds
                    )
                }
                history.commit(bounds, checkNotNull(before))
                before = null
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
}