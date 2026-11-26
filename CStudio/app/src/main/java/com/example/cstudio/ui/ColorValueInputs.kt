package com.example.cstudio.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cstudio.color.ColorUtils
import com.example.cstudio.color.HsvColor
import com.example.cstudio.color.RgbColor

@Composable
fun ColorValueInputs(
    argb: Int,
    allowAlpha: Boolean,
    onColorChanged: (Int) -> Unit,
    onSelectionFinished: (Int) -> Unit,
    modifier: Modifier = Modifier
) {
    val focusManager = LocalFocusManager.current
    var hexInput by remember(argb, allowAlpha) {
        mutableStateOf(ColorUtils.toHex(argb, includeAlpha = (argb ushr 24) != 0xFF))
    }
    val parsedHex = ColorUtils.parseHex(hexInput)
    val rgb = ColorUtils.argbToRgb(argb)
    val hsv = ColorUtils.argbToHsv(argb)

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = hexInput,
            onValueChange = { input ->
                hexInput = input
                ColorUtils.parseHex(input)?.let(onColorChanged)
            },
            modifier = Modifier.fillMaxWidth().testTag("color-hex-input"),
            label = { Text("HEX") },
            singleLine = true,
            isError = parsedHex == null,
            supportingText = {
                if (parsedHex == null) Text("HEX no válido")
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Ascii,
                imeAction = ImeAction.Done
            ),
            keyboardActions = KeyboardActions(
                onDone = {
                    parsedHex?.let {
                        onColorChanged(it)
                        onSelectionFinished(it)
                    }
                    focusManager.clearFocus()
                }
            )
        )

        Text("RGB")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumericColorField(
                label = "R",
                initialValue = rgb.red.toString(),
                range = 0f..255f,
                modifier = Modifier.weight(1f).testTag("color-rgb-red-input")
            ) { red ->
                val color = ColorUtils.rgbToArgb(rgb.copy(red = red.toInt()))
                onColorChanged(color)
                onSelectionFinished(color)
            }
            NumericColorField(
                label = "G",
                initialValue = rgb.green.toString(),
                range = 0f..255f,
                modifier = Modifier.weight(1f).testTag("color-rgb-green-input")
            ) { green ->
                val color = ColorUtils.rgbToArgb(rgb.copy(green = green.toInt()))
                onColorChanged(color)
                onSelectionFinished(color)
            }
            NumericColorField(
                label = "B",
                initialValue = rgb.blue.toString(),
                range = 0f..255f,
                modifier = Modifier.weight(1f).testTag("color-rgb-blue-input")
            ) { blue ->
                val color = ColorUtils.rgbToArgb(rgb.copy(blue = blue.toInt()))
                onColorChanged(color)
                onSelectionFinished(color)
            }
        }

        Text("HSB")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumericColorField(
                label = "Tono",
                initialValue = hsv.hue.toString(),
                range = 0f..360f,
                modifier = Modifier.weight(1f).testTag("color-hsb-hue-input")
            ) { hue ->
                val color = ColorUtils.hsvToArgb(hsv.copy(hue = hue))
                onColorChanged(color)
                onSelectionFinished(color)
            }
            NumericColorField(
                label = "Saturación %",
                initialValue = (hsv.saturation * 100f).toString(),
                range = 0f..100f,
                modifier = Modifier.weight(1f)
            ) { saturation ->
                val color = ColorUtils.hsvToArgb(hsv.copy(saturation = saturation / 100f))
                onColorChanged(color)
                onSelectionFinished(color)
            }
            NumericColorField(
                label = "Brillo %",
                initialValue = (hsv.value * 100f).toString(),
                range = 0f..100f,
                modifier = Modifier.weight(1f)
            ) { value ->
                val color = ColorUtils.hsvToArgb(hsv.copy(value = value / 100f))
                onColorChanged(color)
                onSelectionFinished(color)
            }
        }

        if (allowAlpha) {
            NumericColorField(
                label = "Alfa %",
                initialValue = (hsv.alpha * 100f).toString(),
                range = 0f..100f,
                modifier = Modifier.fillMaxWidth()
            ) { alpha ->
                val color = ColorUtils.hsvToArgb(hsv.copy(alpha = alpha / 100f))
                onColorChanged(color)
                onSelectionFinished(color)
            }
        }
    }
}

@Composable
private fun NumericColorField(
    label: String,
    initialValue: String,
    range: ClosedFloatingPointRange<Float>,
    modifier: Modifier = Modifier,
    onCommit: (Float) -> Unit
) {
    var value by remember(initialValue) { mutableStateOf(initialValue) }
    var isFocused by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val parsedValue = value.toFloatOrNull()
        ?.takeIf { it.isFinite() && it in range }

    fun commit() {
        parsedValue?.let(onCommit)
    }

    OutlinedTextField(
        value = value,
        onValueChange = { value = it },
        modifier = modifier.onFocusChanged { state ->
            if (isFocused && !state.isFocused) commit()
            isFocused = state.isFocused
        },
        label = { Text(label) },
        singleLine = true,
        isError = parsedValue == null,
        supportingText = {
            if (parsedValue == null) Text("Usa ${range.start}–${range.endInclusive}")
        },
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Decimal,
            imeAction = ImeAction.Done
        ),
        keyboardActions = KeyboardActions(
            onDone = {
                commit()
                focusManager.clearFocus()
            }
        )
    )
}
