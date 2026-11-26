package com.example.cstudio.ui

// Contiene los controles de tamaño y opacidad del trazo.
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun BrushOptions(
    sizeDp: Float,
    onSize: (Float) -> Unit,
    opacity: Float,
    onOpacity: (Float) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Tamaño", modifier = Modifier.width(80.dp))
        Slider(
            value = sizeDp,
            onValueChange = onSize,
            valueRange = 1f..60f,
            modifier = Modifier.weight(1f)
        )
    }

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Opacidad", modifier = Modifier.width(80.dp))
        Slider(
            value = opacity,
            onValueChange = onOpacity,
            valueRange = 0.05f..1f,
            modifier = Modifier.weight(1f)
        )
    }
}
