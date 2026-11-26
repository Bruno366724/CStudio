package com.example.cstudio.brush

// Describe el comportamiento que comparte cada tipo de pincel.
data class Brush(
    val name: String,
    val minWidthRatio: Float,
    val usesPressure: Boolean,
    val isEraser: Boolean
)
