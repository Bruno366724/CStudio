package com.example.cstudio.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.example.cstudio.color.ColorUtils
import com.example.cstudio.color.HsvColor
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val hueSpectrum = listOf(
    Color.Red,
    Color.Yellow,
    Color.Green,
    Color.Cyan,
    Color.Blue,
    Color.Magenta,
    Color.Red
)

@Composable
fun ColorWheel(
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
    val paddingPx = with(LocalDensity.current) { 12.dp.toPx() }
    val radius = 120.dp
    val selection = Modifier
        .size(radius * 2)
        .clipToBounds()
        .semantics {
            contentDescription = "Rueda de color"
            progressBarRangeInfo = ProgressBarRangeInfo(hsv.saturation, 0f..1f)
            stateDescription =
                "Tono ${hsv.hue.roundToInt()} grados, saturación ${(hsv.saturation * 100).roundToInt()} por ciento"
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
                    Key.DirectionLeft -> current.copy(hue = (current.hue - 1f + 360f) % 360f)
                    Key.DirectionRight -> current.copy(hue = (current.hue + 1f) % 360f)
                    Key.DirectionDown -> current.copy(saturation = (current.saturation - 0.01f).coerceAtLeast(0f))
                    Key.DirectionUp -> current.copy(saturation = (current.saturation + 0.01f).coerceAtMost(1f))
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
                val selected = wheelColorAt(
                    position,
                    Size(size.width.toFloat(), size.height.toFloat()),
                    paddingPx,
                    latestColor.value
                )
                onColorChangedState.value(selected)
                onFinishedState.value(selected)
            }
        }
        .pointerInput(Unit) {
            var lastSelected = latestColor.value
            detectDragGestures(
                onDragStart = { position ->
                    lastSelected = wheelColorAt(
                        position,
                        Size(size.width.toFloat(), size.height.toFloat()),
                        paddingPx,
                        latestColor.value
                    )
                    onColorChangedState.value(lastSelected)
                },
                onDragEnd = { onFinishedState.value(lastSelected) },
                onDrag = { change, _ ->
                    change.consume()
                    lastSelected = wheelColorAt(
                        change.position,
                        Size(size.width.toFloat(), size.height.toFloat()),
                        paddingPx,
                        lastSelected
                    )
                    onColorChangedState.value(lastSelected)
                }
            )
        }

    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(4.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Canvas(modifier = selection) {
            val center = Offset(size.width / 2f, size.height / 2f)
            val wheelRadius = min(size.width, size.height) / 2f - paddingPx
            drawCircle(
                brush = Brush.sweepGradient(hueSpectrum, center),
                radius = wheelRadius,
                center = center
            )
            drawCircle(
                brush = Brush.radialGradient(
                    colors = listOf(Color.White, Color.Transparent),
                    center = center,
                    radius = wheelRadius
                ),
                radius = wheelRadius,
                center = center
            )
            drawCircle(
                color = Color.DarkGray,
                radius = wheelRadius,
                center = center,
                style = Stroke(width = 1.dp.toPx())
            )
            val markerRadius = hsv.saturation * wheelRadius
            val marker = Offset(
                center.x + cos(Math.toRadians(hsv.hue.toDouble())).toFloat() * markerRadius,
                center.y + sin(Math.toRadians(hsv.hue.toDouble())).toFloat() * markerRadius
            )
            drawCircle(Color.Black, radius = 10.dp.toPx(), center = marker)
            drawCircle(Color.White, radius = 7.dp.toPx(), center = marker)
            drawCircle(Color(argb), radius = 5.dp.toPx(), center = marker)
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Brillo")
            Slider(
                value = hsv.value,
                onValueChange = { value ->
                    sliderColor = ColorUtils.hsvToArgb(
                        ColorUtils.argbToHsv(sliderColor).copy(value = value)
                    )
                    onColorChanged(sliderColor)
                },
                onValueChangeFinished = { onSelectionFinished(sliderColor) },
                valueRange = 0f..1f
            )
        }
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

private fun wheelColorAt(position: Offset, size: Size, padding: Float, sourceColor: Int): Int {
    val centerX = size.width / 2f
    val centerY = size.height / 2f
    val radius = min(centerX, centerY) - padding
    val dx = position.x - centerX
    val dy = position.y - centerY
    val distance = hypot(dx, dy).coerceAtMost(radius)
    val source = ColorUtils.argbToHsv(sourceColor)
    val hue = if (distance == 0f) {
        source.hue
    } else {
        ((Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())) + 360.0) % 360.0).toFloat()
    }
    return ColorUtils.hsvToArgb(
        HsvColor(
            hue = hue,
            saturation = (distance / radius).coerceIn(0f, 1f),
            value = source.value,
            alpha = source.alpha
        )
    )
}
