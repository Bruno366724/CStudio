package com.example.cstudio.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cstudio.drawing.LayerInfo

@Composable
fun LayersPanel(
    layers: List<LayerInfo>,
    activeLayerIndex: Int,
    onSelectLayer: (Int) -> Unit,
    onToggleVisibility: (Int, Boolean) -> Unit,
    onOpacityChange: (Int, Float) -> Unit,
    onAdd: () -> Unit,
    onMove: (Int, Int) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier
) {
    var pendingDeleteIndex by remember { mutableIntStateOf(-1) }
    val activeInfo = layers.getOrNull(activeLayerIndex)
    val deleteLayerNumber = pendingDeleteIndex + 1

    Card(modifier = modifier.widthIn(max = 300.dp).fillMaxWidth().heightIn(max = 620.dp)) {
        Column(
            modifier = Modifier
                .padding(12.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Capas", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) { Text("Cerrar") }
            }

            layers.indices.reversed().forEach { index ->
                val info = layers[index]
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 48.dp)
                        .clickable { onSelectLayer(index) }
                        .semantics {
                            contentDescription = "Capa ${index + 1}"
                            selected = index == activeLayerIndex
                            role = Role.Button
                        }
                        .padding(horizontal = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = (if (index == activeLayerIndex) "● " else "") + "Capa ${index + 1}",
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyLarge
                    )
                    TextButton(
                        onClick = { onToggleVisibility(index, !info.visible) },
                        modifier = Modifier
                            .heightIn(min = 48.dp)
                            .semantics {
                                contentDescription = if (info.visible) {
                                    "Ocultar capa ${index + 1}"
                                } else {
                                    "Mostrar capa ${index + 1}"
                                }
                            }
                    ) {
                        Text(if (info.visible) "Ocultar" else "Mostrar")
                    }
                }
            }

            Text("Opacidad: ${((activeInfo?.opacity ?: 1f) * 100).toInt()}%")
            Slider(
                value = activeInfo?.opacity ?: 1f,
                onValueChange = { opacity -> onOpacityChange(activeLayerIndex, opacity) },
                enabled = activeInfo != null
            )

            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(onClick = onAdd, modifier = Modifier.weight(1f)) { Text("Agregar") }
                Button(
                    onClick = { onMove(activeLayerIndex, 1) },
                    enabled = activeLayerIndex < layers.lastIndex,
                    modifier = Modifier.weight(1f)
                ) { Text("Subir") }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Button(
                    onClick = { onMove(activeLayerIndex, -1) },
                    enabled = activeLayerIndex > 0,
                    modifier = Modifier.weight(1f)
                ) { Text("Bajar") }
                Button(
                    onClick = { pendingDeleteIndex = activeLayerIndex },
                    enabled = layers.size > 1,
                    modifier = Modifier.weight(1f)
                ) { Text("Borrar") }
            }
        }
    }

    if (pendingDeleteIndex >= 0) {
        AlertDialog(
            onDismissRequest = { pendingDeleteIndex = -1 },
            title = { Text("Borrar capa") },
            text = {
                Text("¿Borrar la capa $deleteLayerNumber? Sus trazos dejarán de estar en el historial de deshacer.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        pendingDeleteIndex = -1
                        onDelete()
                    }
                ) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDeleteIndex = -1 }) { Text("Cancelar") }
            }
        )
    }
}
