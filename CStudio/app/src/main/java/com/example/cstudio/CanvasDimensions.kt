package com.example.cstudio

// Centraliza límites y valores predeterminados del lienzo.
object CanvasDimensions {
    const val MIN_SIDE = 64
    const val MAX_SIDE = 4096
    const val DEFAULT_WIDTH = 1920
    const val DEFAULT_HEIGHT = 1080

    fun isValid(width: Int, height: Int): Boolean {
        return width in MIN_SIDE..MAX_SIDE && height in MIN_SIDE..MAX_SIDE
    }
}
