package com.example.cstudio.brush

// Agrupa los parámetros que se aplican al siguiente trazo.
data class BrushSettings(
    val color: Int,
    val sizeDp: Float,
    val opacity: Float,
    val brush: Brush
)
