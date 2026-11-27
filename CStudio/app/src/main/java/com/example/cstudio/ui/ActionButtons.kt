package com.example.cstudio.ui

// Mantiene las acciones del lienzo en una fila separada de la paleta.
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cstudio.brush.Brush
import com.example.cstudio.brush.Brushes

@Composable
fun ActionButtons(
    selectedBrush: Brush,
    onBrushSelected: (Brush) -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetViewport: () -> Unit,
    onClear: () -> Unit,
    onExport: () -> Unit,
    onHome: () -> Unit,
    onLayers: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Brushes.available.forEach { brush ->
            Button(onClick = { onBrushSelected(brush) }) {
                Text(if (brush == selectedBrush) "• ${brush.name}" else brush.name)
            }
        }
        Button(onClick = onUndo) { Text("Deshacer") }
        Button(onClick = onRedo) { Text("Rehacer") }
        Button(onClick = onResetViewport) { Text("Ajustar") }
        Button(onClick = onClear) { Text("Limpiar") }
        Button(onClick = onLayers) { Text("Capas") }
        Button(onClick = onExport) { Text("Exportar") }
        Button(onClick = onHome) { Text("Inicio") }
    }
}
