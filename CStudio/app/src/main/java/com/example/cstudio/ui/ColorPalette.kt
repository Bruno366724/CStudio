package com.example.cstudio.ui

// Muestra y permite seleccionar un color de la paleta rápida.
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.example.cstudio.brush.Brush

private val palette = listOf(
    Color(0xFF000000), Color(0xFF9E9E9E), Color(0xFFE53935), Color(0xFFFB8C00),
    Color(0xFFFDD835), Color(0xFF43A047), Color(0xFF1E88E5), Color(0xFF8E24AA),
    Color(0xFFEC407A), Color(0xFF6D4C41), Color(0xFFFFFFFF)
)

@Composable
fun ColorPalette(
    color: Color,
    brush: Brush,
    onColor: (Color) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        palette.forEach { swatch ->
            val selected = swatch == color && !brush.isEraser
            Row(
                modifier = Modifier
                    .size(34.dp)
                    .clip(CircleShape)
                    .background(swatch)
                    .border(
                        width = if (selected) 3.dp else 1.dp,
                        color = if (selected) MaterialTheme.colorScheme.primary else Color.Gray,
                        shape = CircleShape
                    )
                    .clickable {
                        onColor(swatch)
                    }
            ) {}
        }
    }
}
