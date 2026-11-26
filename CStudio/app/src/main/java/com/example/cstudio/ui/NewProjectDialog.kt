package com.example.cstudio.ui

// Configura el nombre y tamaño del proyecto antes de crearlo.
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.cstudio.CanvasDimensions
import java.util.Locale

@Composable
fun NewProjectDialog(
    onDismiss: () -> Unit,
    onCreate: (name: String, width: Int, height: Int) -> Unit
) {
    val presets = remember {
        listOf(
            CanvasPreset("Cuadrado 2048 × 2048", 2048, 2048),
            CanvasPreset("Cuadrado 1080 × 1080", 1080, 1080),
            CanvasPreset("HD 1920 × 1080", 1920, 1080),
            CanvasPreset("Vertical 1080 × 1920", 1080, 1920),
            CanvasPreset("A4 vertical 2480 × 3508", 2480, 3508),
            CanvasPreset("4K UHD 3840 × 2160", 3840, 2160),
            CanvasPreset("Máximo 4096 × 4096", 4096, 4096)
        )
    }
    var name by rememberSaveable { mutableStateOf("Sin título") }
    var widthText by rememberSaveable { mutableStateOf(CanvasDimensions.DEFAULT_WIDTH.toString()) }
    var heightText by rememberSaveable { mutableStateOf(CanvasDimensions.DEFAULT_HEIGHT.toString()) }
    val defaultPreset = presets.first { it.width == CanvasDimensions.DEFAULT_WIDTH &&
        it.height == CanvasDimensions.DEFAULT_HEIGHT }
    var selectedPreset by rememberSaveable { mutableStateOf<String?>(defaultPreset.label) }

    val width = widthText.toIntOrNull()
    val height = heightText.toIntOrNull()
    val widthError = dimensionError(widthText, width)
    val heightError = dimensionError(heightText, height)
    val valid = name.trim().isNotEmpty() && widthError == null && heightError == null
    val estimatedMegabytes = if (valid) {
        width!!.toLong() * height!!.toLong() * 4.0 / 1_000_000.0
    } else {
        null
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Nuevo proyecto") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 480.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it.take(40) },
                    label = { Text("Nombre del proyecto") },
                    singleLine = true,
                    supportingText = {
                        if (name.trim().isEmpty()) Text("El nombre no puede quedar vacío.")
                    },
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Tamaño del lienzo")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    presets.forEach { preset ->
                        FilterChip(
                            selected = selectedPreset == preset.label,
                            onClick = {
                                selectedPreset = preset.label
                                widthText = preset.width.toString()
                                heightText = preset.height.toString()
                            },
                            label = { Text(preset.label) }
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = widthText,
                        onValueChange = {
                            widthText = it.filter(Char::isDigit).take(4)
                            selectedPreset = null
                        },
                        label = { Text("Ancho") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = widthError != null,
                        supportingText = { if (widthError != null) Text(widthError) },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = heightText,
                        onValueChange = {
                            heightText = it.filter(Char::isDigit).take(4)
                            selectedPreset = null
                        },
                        label = { Text("Alto") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = heightError != null,
                        supportingText = { if (heightError != null) Text(heightError) },
                        modifier = Modifier.weight(1f)
                    )
                }
                TextButton(
                    onClick = {
                        val oldWidth = widthText
                        widthText = heightText
                        heightText = oldWidth
                        selectedPreset = null
                    }
                ) { Text("Intercambiar ancho y alto") }
                estimatedMegabytes?.let { estimate ->
                    Text(
                        "Memoria aproximada de la capa: ${
                            String.format(Locale.getDefault(), "%.1f MB", estimate)
                        }"
                    )
                    if (estimate > 32.0) {
                        Text("Los lienzos muy grandes pueden ir más lento en algunos dispositivos")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = valid,
                onClick = { onCreate(name.trim(), width!!, height!!) }
            ) { Text("Crear") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}

private data class CanvasPreset(val label: String, val width: Int, val height: Int)

private fun dimensionError(text: String, value: Int?): String? {
    if (text.isEmpty() || value == null) return "Ingresa una dimensión."
    if (!CanvasDimensions.isValidSide(value)) {
        return "Debe estar entre 64 y 4096 px."
    }
    return null
}
