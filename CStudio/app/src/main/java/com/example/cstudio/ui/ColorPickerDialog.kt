package com.example.cstudio.ui

import android.content.ClipData
import android.content.ClipboardManager
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.example.cstudio.color.ColorUtils
import kotlinx.coroutines.launch

@Composable
fun ColorPickerDialog(
    argb: Int,
    recentColors: List<Int>,
    onColorChanged: (Int) -> Unit,
    onSelectionFinished: (Int) -> Unit,
    onClearHistory: () -> Unit,
    onRequestEyedropper: () -> Unit,
    onDismiss: () -> Unit,
    allowAlpha: Boolean = true
) {
    var confirmClearHistory by remember { mutableStateOf(false) }
    var selectedMode by remember { mutableIntStateOf(0) }
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    val hexValue = ColorUtils.toHex(argb, includeAlpha = allowAlpha && (argb ushr 24) != 0xFF)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 640.dp).heightIn(max = 680.dp),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier.verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "Selector de color",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = onDismiss) { Text("Cerrar") }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 72.dp, height = 56.dp)
                            .clip(RoundedCornerShape(8.dp))
                            .background(Color.White)
                            .background(Color(argb))
                            .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(8.dp))
                    )
                    Column {
                        Text("Color actual", style = MaterialTheme.typography.labelLarge)
                        Text(hexValue, style = MaterialTheme.typography.titleMedium)
                    }
                    Button(
                        onClick = {
                            val clipboard = context.getSystemService(ClipboardManager::class.java)
                            clipboard.setPrimaryClip(ClipData.newPlainText("CStudio color", hexValue))
                            coroutineScope.launch { snackbarHostState.showSnackbar("Copiado") }
                        },
                        modifier = Modifier.defaultMinTouchTarget()
                    ) {
                        Text("Copiar")
                    }
                }
                TextButton(
                    onClick = onRequestEyedropper,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Cuentagotas: tomar color del lienzo")
                }

                PrimaryTabRow(selectedTabIndex = selectedMode) {
                    listOf("Rueda", "Cuadrado", "Valores").forEachIndexed { index, label ->
                        Tab(
                            selected = selectedMode == index,
                            onClick = { selectedMode = index },
                            text = { Text(label) }
                        )
                    }
                }
                when (selectedMode) {
                    0 -> ColorWheel(
                        argb = argb,
                        allowAlpha = allowAlpha,
                        onColorChanged = onColorChanged,
                        onSelectionFinished = onSelectionFinished,
                        modifier = Modifier.fillMaxWidth()
                    )
                    1 -> ColorSquare(
                        argb = argb,
                        allowAlpha = allowAlpha,
                        onColorChanged = onColorChanged,
                        onSelectionFinished = onSelectionFinished
                    )
                    else -> ColorValueInputs(
                        argb = argb,
                        allowAlpha = allowAlpha,
                        onColorChanged = onColorChanged,
                        onSelectionFinished = onSelectionFinished
                    )
                }

                Text("Colores recientes", style = MaterialTheme.typography.titleMedium)
                RecentColorsRow(
                    colors = recentColors,
                    selectedColor = argb,
                    onColorSelected = {
                        onColorChanged(it)
                        onSelectionFinished(it)
                    },
                    onClear = { confirmClearHistory = true },
                    modifier = Modifier.fillMaxWidth()
                )
                SnackbarHost(hostState = snackbarHostState)
            }
        }
    }

    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            title = { Text("Borrar historial") },
            text = { Text("¿Quieres borrar los colores recientes?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        onClearHistory()
                        confirmClearHistory = false
                    }
                ) {
                    Text("Borrar")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmClearHistory = false }) {
                    Text("Cancelar")
                }
            }
        )
    }
}

private fun Modifier.defaultMinTouchTarget(): Modifier =
    this.then(Modifier.heightIn(min = 48.dp))
