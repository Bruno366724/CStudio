package com.example.cstudio.drawing

// Dibuja trazos suavizados con presión, opacidad y modo borrador.
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF

internal class StrokeRenderer(
    private val canvasWidth: Int,
    private val canvasHeight: Int
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val segmentPath = Path()
    private val eraserMode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    private val layerBounds = RectF()

    fun saveLayer(canvas: Canvas, bounds: Rect): Int {
        setLayerBounds(bounds)
        return canvas.saveLayer(layerBounds.left, layerBounds.top, layerBounds.right, layerBounds.bottom, null)
    }

    fun preparePaint(color: Int, isEraser: Boolean) {
        if (isEraser) {
            paint.xfermode = eraserMode
            paint.color = Color.BLACK
        } else {
            paint.xfermode = null
            // El trazo se pinta opaco; la opacidad se aplica al renderizarlo completo.
            paint.color = Color.rgb(Color.red(color), Color.green(color), Color.blue(color))
        }
    }

    /** Dibuja el trazo con la opacidad elegida aplicada al trazo completo. */
    fun drawBrushStroke(
        canvas: Canvas,
        opacity: Float,
        pointsX: FloatArray,
        pointsY: FloatArray,
        pointsW: FloatArray,
        pointCount: Int,
        bounds: Rect
    ) {
        val alpha = (opacity.coerceIn(0f, 1f) * 255f).toInt()
        if (alpha >= 255) {
            replayStroke(canvas, pointsX, pointsY, pointsW, pointCount)
            return
        }
        setLayerBounds(bounds)
        val save = canvas.saveLayerAlpha(
            layerBounds.left,
            layerBounds.top,
            layerBounds.right,
            layerBounds.bottom,
            alpha
        )
        replayStroke(canvas, pointsX, pointsY, pointsW, pointCount)
        canvas.restoreToCount(save)
    }

    private fun setLayerBounds(bounds: Rect) {
        layerBounds.set(
            bounds.left.coerceIn(0, canvasWidth).toFloat(),
            bounds.top.coerceIn(0, canvasHeight).toFloat(),
            bounds.right.coerceIn(0, canvasWidth).toFloat(),
            bounds.bottom.coerceIn(0, canvasHeight).toFloat()
        )
    }

    /** Dibuja puntos conectados por curvas Bézier cuadráticas. */
    fun replayStroke(
        canvas: Canvas,
        pointsX: FloatArray,
        pointsY: FloatArray,
        pointsW: FloatArray,
        pointCount: Int
    ) {
        if (pointCount == 0) return

        // Un toque suelto deja un círculo gracias al cap redondo.
        paint.strokeWidth = pointsW[0]
        canvas.drawPoint(pointsX[0], pointsY[0], paint)

        var prevMidX = pointsX[0]
        var prevMidY = pointsY[0]
        for (i in 1 until pointCount) {
            val midX = (pointsX[i - 1] + pointsX[i]) / 2f
            val midY = (pointsY[i - 1] + pointsY[i]) / 2f

            segmentPath.reset()
            segmentPath.moveTo(prevMidX, prevMidY)
            segmentPath.quadTo(pointsX[i - 1], pointsY[i - 1], midX, midY)
            paint.strokeWidth = pointsW[i]
            canvas.drawPath(segmentPath, paint)

            prevMidX = midX
            prevMidY = midY
        }

        if (pointCount > 1) {
            // Cierra el último tramo hasta el punto exacto donde terminó el lápiz.
            segmentPath.reset()
            segmentPath.moveTo(prevMidX, prevMidY)
            segmentPath.lineTo(pointsX[pointCount - 1], pointsY[pointCount - 1])
            paint.strokeWidth = pointsW[pointCount - 1]
            canvas.drawPath(segmentPath, paint)
        }
    }
}
