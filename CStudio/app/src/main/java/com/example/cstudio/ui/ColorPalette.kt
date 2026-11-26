package com.example.cstudio.ui

// Muestra y permite seleccionar un color de la paleta rápida.
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.example.cstudio.brush.Brush
import com.example.cstudio.color.ColorUtils

private val palette = listOf(
    Color(0xFF000000), Color(0xFF9E9E9E), Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
    Color(0xFFEC407A), Color(0xFF6D4C41), Color(0xFFFFFFFF)
)

@Composable
fun ColorPalette(
    color: Color,
    brush: Brush,
    onColor: (Color) -> Unit,
    onOpenColorPicker: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        palette.forEach { swatch ->
            val isSelectedColor = swatch == color && !brush.isEraser
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (isSelectedColor) 3.dp else 1.dp,
                        color = if (isSelectedColor) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape
                    )
                    .clickable { onColor(swatch) }
                    .semantics {
                        contentDescription = "Color ${ColorUtils.toHex(swatch.toArgb())}"
                        selected = isSelectedColor
                        role = Role.Button
                    }
            )
        }
        TextButton(onClick = onOpenColorPicker) {
            Text("Más colores")
        }
    }
}
