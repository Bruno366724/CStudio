package com.example.cstudio.drawing

// Controla la matriz de vista, los gestos y la conversión de coordenadas.
import android.graphics.Matrix
import android.view.MotionEvent
import kotlin.math.hypot
import kotlin.math.min

internal class Viewport(
    private val canvasWidth: Int,
    private val canvasHeight: Int
) {
    val matrix = Matrix()
    private val inverseMatrix = Matrix()
    private val matrixValues = FloatArray(9)
    private val mappedPoint = FloatArray(2)
    var fitScale = 1f
        private set

    private var lastFocusX = 0f
    private var lastFocusY = 0f
    private var lastSpan = 0f
    private var viewWidth = 0
    private var viewHeight = 0

    fun reset(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return
        viewWidth = width
        viewHeight = height
        fitScale = min(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight)
        val offsetX = (width - canvasWidth * fitScale) / 2f
        val offsetY = (height - canvasHeight * fitScale) / 2f
        matrix.setValues(
            floatArrayOf(fitScale, 0f, offsetX, 0f, fitScale, offsetY, 0f, 0f, 1f)
        )
        updateInverse()
    }

    fun resize(oldWidth: Int, oldHeight: Int, width: Int, height: Int) {
        if (oldWidth <= 0 || oldHeight <= 0 || width <= 0 || height <= 0) return
        val centerX = mapToCanvas(oldWidth / 2f, oldHeight / 2f)[0]
        val centerY = mappedPoint[1]
        val previousFitScale = fitScale
        if (previousFitScale <= 0f) return

        matrix.getValues(matrixValues)
        val zoom = matrixValues[Matrix.MSCALE_X] / previousFitScale
        fitScale = min(width.toFloat() / canvasWidth, height.toFloat() / canvasHeight)
        val scale = fitScale * zoom
        viewWidth = width
        viewHeight = height
        matrix.setValues(
            floatArrayOf(
                scale, 0f, width / 2f - centerX * scale,
                0f, scale, height / 2f - centerY * scale,
                0f, 0f, 1f
            )
        )
        limitPan(width, height)
        updateInverse()
    }

    fun mapToCanvas(x: Float, y: Float): FloatArray {
        mappedPoint[0] = x
        mappedPoint[1] = y
        inverseMatrix.mapPoints(mappedPoint)
        return mappedPoint
    }

    fun isInsideCanvas(x: Float, y: Float): Boolean {
        return x >= 0f && y >= 0f && x < canvasWidth && y < canvasHeight
    }

    fun beginGesture(event: MotionEvent) {
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        lastFocusX = (x0 + x1) / 2f
        lastFocusY = (y0 + y1) / 2f
        lastSpan = hypot(x1 - x0, y1 - y0)
    }

    fun updateGesture(event: MotionEvent) {
        val x0 = event.getX(0)
        val y0 = event.getY(0)
        val x1 = event.getX(1)
        val y1 = event.getY(1)
        val focusX = (x0 + x1) / 2f
        val focusY = (y0 + y1) / 2f
        val span = hypot(x1 - x0, y1 - y0)

        if (lastSpan > 0f && span > 0f) {
            matrix.getValues(matrixValues)
            val currentScale = matrixValues[Matrix.MSCALE_X]
            val minimumScale = fitScale * MIN_ZOOM
            val maximumScale = maxOf(fitScale * MAX_ZOOM, 8f)
            val requestedScale = currentScale * span / lastSpan
            val newScale = requestedScale.coerceIn(minimumScale, maximumScale)
            val scaleFactor = newScale / currentScale
            matrix.postScale(scaleFactor, scaleFactor, lastFocusX, lastFocusY)
            matrix.postTranslate(focusX - lastFocusX, focusY - lastFocusY)
            if (viewWidth > 0 && viewHeight > 0) limitPan(viewWidth, viewHeight)
            updateInverse()
        }

        lastFocusX = focusX
        lastFocusY = focusY
        lastSpan = span
    }

    private fun limitPan(width: Int, height: Int) {
        matrix.getValues(matrixValues)
        val scale = matrixValues[Matrix.MSCALE_X]
        val canvasScreenWidth = canvasWidth * scale
        val canvasScreenHeight = canvasHeight * scale
        val minVisible = 64f

        var tx = matrixValues[Matrix.MTRANS_X]
        var ty = matrixValues[Matrix.MTRANS_Y]
        tx = tx.coerceIn(minVisible - canvasScreenWidth, width - minVisible)
        ty = ty.coerceIn(minVisible - canvasScreenHeight, height - minVisible)
        matrixValues[Matrix.MTRANS_X] = tx
        matrixValues[Matrix.MTRANS_Y] = ty
        matrix.setValues(matrixValues)
    }

    private fun updateInverse() {
        matrix.invert(inverseMatrix)
    }

    private companion object {
        const val MIN_ZOOM = 0.5f
        const val MAX_ZOOM = 8f
    }
}
