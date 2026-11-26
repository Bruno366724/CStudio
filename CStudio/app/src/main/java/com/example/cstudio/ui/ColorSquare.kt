package com.example.cstudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.cstudio.color.ColorUtils
import com.example.cstudio.color.HsvColor
import kotlin.math.roundToInt

@Composable
fun ColorSquare(
    argb: Int,
    allowAlpha: Boolean,
    onColorChanged: (Int) -> Unit,
    onSelectionFinished: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val hsv = ColorUtils.argbToHsv(argb)
    val latestColor = rememberUpdatedState(argb)
    val onColorChangedState = rememberUpdatedState(onColorChanged)
    val onFinishedState = rememberUpdatedState(onSelectionFinished)
    var sliderColor by remember { mutableIntStateOf(argb) }
    SideEffect { sliderColor = argb }
    val selection = Modifier
        .fillMaxWidth()
        .widthIn(max = 320.dp)
        .aspectRatio(1f)
        .semantics {
            contentDescription = "Selector cuadrado de color"
            progressBarRangeInfo = ProgressBarRangeInfo(hsv.saturation, 0f..1f)
            stateDescription =
                "Saturación ${(hsv.saturation * 100).roundToInt()} por ciento, brillo ${(hsv.value * 100).roundToInt()} por ciento"
            setProgress { requested ->
                val current = ColorUtils.argbToHsv(latestColor.value)
                val updated = ColorUtils.hsvToArgb(current.copy(saturation = requested.coerceIn(0f, 1f)))
                onColorChangedState.value(updated)
                onFinishedState.value(updated)
                true
            }
        }
        .focusable()
        .onKeyEvent { event ->
            if (event.type != KeyEventType.KeyUp) {
                false
            } else {
                val current = ColorUtils.argbToHsv(latestColor.value)
                val updated = when (event.key) {
                    Key.DirectionLeft -> current.copy(saturation = (current.saturation - 0.01f).coerceAtLeast(0f))
                    Key.DirectionRight -> current.copy(saturation = (current.saturation + 0.01f).coerceAtMost(1f))
                    Key.DirectionDown -> current.copy(value = (current.value - 0.01f).coerceAtLeast(0f))
                    Key.DirectionUp -> current.copy(value = (current.value + 0.01f).coerceAtMost(1f))
                    else -> return@onKeyEvent false
                }
                ColorUtils.hsvToArgb(updated).also {
                    onColorChangedState.value(it)
                    onFinishedState.value(it)
                }
                true
            }
        }
        .pointerInput(Unit) {
            detectTapGestures { position ->
                val selected = squareColorAt(position.x, position.y, size.width.toFloat(), size.height.toFloat(), latestColor.value)
                onColorChangedState.value(selected)
                onFinishedState.value(selected)
            }
        }
        .pointerInput(Unit) {
            var lastSelected = latestColor.value
            detectDragGestures(
                onDragStart = { position ->
                    lastSelected = squareColorAt(position.x, position.y, size.width.toFloat(), size.height.toFloat(), latestColor.value)
                    onColorChangedState.value(lastSelected)
                },
                onDragEnd = { onFinishedState.value(lastSelected) },
                onDrag = { change, _ ->
                    change.consume()
                    lastSelected = squareColorAt(
                        change.position.x,
                        change.position.y,
                        size.width.toFloat(),
                        size.height.toFloat(),
                        lastSelected
                    )
                    onColorChangedState.value(lastSelected)
                }
            )
        }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Canvas(modifier = selection) {
            val hueColor = ColorUtils.hsvToArgb(hsv.copy(saturation = 1f, value = 1f, alpha = 1f))
            drawRect(Brush.horizontalGradient(listOf(Color.White, Color(hueColor))))
            drawRect(Brush.verticalGradient(listOf(Color.Transparent, Color.Black)))

            val marker = Offset(
                x = hsv.saturation * size.width,
                y = (1f - hsv.value) * size.height
            )
            drawCircle(Color.Black, radius = 10.dp.toPx(), center = marker)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = marker)
            drawCircle(Color(argb), radius = 5.dp.toPx(), center = marker)
            drawRect(Color.DarkGray, style = Stroke(width = 1.dp.toPx()))
        }

        HueSlider(
            hue = hsv.hue,
            currentColor = Color(argb),
            onValueChange = { hue ->
                sliderColor = ColorUtils.hsvToArgb(
                    ColorUtils.argbToHsv(sliderColor).copy(hue = hue)
                )
                onColorChanged(sliderColor)
            },
            onValueChangeFinished = { onSelectionFinished(sliderColor) }
        )
        if (allowAlpha) {
            AlphaSlider(
                alpha = hsv.alpha,
                onValueChange = { alpha ->
                    sliderColor = ColorUtils.hsvToArgb(
                        ColorUtils.argbToHsv(sliderColor).copy(alpha = alpha)
                    )
                    onColorChanged(sliderColor)
                },
                onValueChangeFinished = { onSelectionFinished(sliderColor) }
            )
        }
    }
}

private fun squareColorAt(x: Float, y: Float, width: Float, height: Float, sourceColor: Int): Int {
    val source = ColorUtils.argbToHsv(sourceColor)
    val saturation = (x / width).coerceIn(0f, 1f)
    val value = (1f - y / height).coerceIn(0f, 1f)
    return ColorUtils.hsvToArgb(source.copy(saturation = saturation, value = value))
}
