package com.example.cstudio.ui

// Organiza la paleta, las acciones y los ajustes del pincel.
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.dp
import com.example.cstudio.brush.BrushSettings
import com.example.cstudio.brush.Brushes

@Composable
fun ToolBar(
    settings: BrushSettings,
    onSettingsChange: (BrushSettings) -> Unit,
    onClear: () -> Unit,
    onUndo: () -> Unit,
    onRedo: () -> Unit,
    onResetViewport: () -> Unit,
    onExport: () -> Unit,
    onHome: () -> Unit,
    onColorSelectionFinished: (Int) -> Unit,
    onOpenColorPicker: () -> Unit,
    onLayers: () -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp)) {
        ColorPalette(
            color = Color(settings.color),
            brush = settings.brush,
            onColor = { color ->
                onSettingsChange(
                    settings.copy(color = color.toArgb(), brush = Brushes.PENCIL)
                )
                onColorSelectionFinished(color.toArgb())
            },
            onOpenColorPicker = onOpenColorPicker
        )
        ActionButtons(
            selectedBrush = settings.brush,
            onBrushSelected = { brush -> onSettingsChange(settings.copy(brush = brush)) },
            onUndo = onUndo,
            onRedo = onRedo,
            onResetViewport = onResetViewport,
            onClear = onClear,
            onExport = onExport,
            onHome = onHome,
            onLayers = onLayers
        )
        BrushOptions(
            sizeDp = settings.sizeDp,
            onSize = { size -> onSettingsChange(settings.copy(sizeDp = size)) },
            opacity = settings.opacity,
            onOpacity = { opacity -> onSettingsChange(settings.copy(opacity = opacity)) }
        )
    }
}
