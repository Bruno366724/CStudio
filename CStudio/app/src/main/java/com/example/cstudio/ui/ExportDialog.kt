package com.example.cstudio.ui

// Permite elegir formato, calidad y fondo para la exportación.
import android.os.Build
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cstudio.storage.ExportBackground
import com.example.cstudio.storage.ExportFormat
import com.example.cstudio.storage.ExportOptions
import kotlin.math.roundToInt

@Composable
fun ExportDialog(
    onDismiss: () -> Unit,
    onExport: (ExportOptions) -> Unit
) {
    var selectedFormatName by rememberSaveable { mutableStateOf(ExportFormat.PNG.name) }
    var selectedBackgroundName by rememberSaveable {
        mutableStateOf(ExportBackground.TRANSPARENT.name)
    }
    var quality by rememberSaveable { mutableFloatStateOf(90f) }
    val selectedFormat = ExportFormat.valueOf(selectedFormatName)
    val selectedBackground = ExportBackground.valueOf(selectedBackgroundName)
    val showQuality = selectedFormat == ExportFormat.JPG ||
        (selectedFormat == ExportFormat.WEBP && Build.VERSION.SDK_INT < Build.VERSION_CODES.R)
    val supportsBackground = selectedFormat == ExportFormat.PNG ||
        selectedFormat == ExportFormat.WEBP
    val formats = remember { ExportFormat.entries.toList() }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Exportar") },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text("Formato")
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    formats.forEach { format ->
                        FilterChip(
                            selected = selectedFormat == format,
                            onClick = { selectedFormatName = format.name },
                            label = { Text(format.name) }
                        )
                    }
                }
                if (showQuality) {
                    Text("Calidad: ${quality.toInt()}")
                    Slider(
                        value = quality,
                        onValueChange = { quality = it.coerceIn(1f, 100f).roundToInt().toFloat() },
                        valueRange = 1f..100f,
                        steps = 0
                    )
                }
                if (supportsBackground) {
                    Text("Fondo")
                    ExportBackground.entries.forEach { background ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(
                                selected = selectedBackground == background,
                                onClick = { selectedBackgroundName = background.name }
                            )
                            Text(
                                text = if (background == ExportBackground.TRANSPARENT) {
                                    "Fondo transparente"
                                } else {
                                    "Fondo blanco"
                                }
                            )
                        }
                    }
                } else {
                    Text("JPG y PDF siempre se exportan con fondo blanco.")
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onExport(
                        ExportOptions(
                            format = selectedFormat,
                            quality = quality.toInt(),
                            background = selectedBackground
                        )
                    )
                }
            ) { Text("Guardar") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancelar") }
        }
    )
}
