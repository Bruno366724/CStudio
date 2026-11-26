package com.example.cstudio.brush

// Centraliza los pinceles disponibles para la aplicación.
object Brushes {
    val PENCIL = Brush(
        name = "Pincel",
        minWidthRatio = 0.15f,
        usesPressure = true,
        isEraser = false
    )
    val ERASER = Brush(
        name = "Borrador",
        minWidthRatio = 0.15f,
        usesPressure = true,
        isEraser = true
    )

    val available = listOf(PENCIL, ERASER)
}
