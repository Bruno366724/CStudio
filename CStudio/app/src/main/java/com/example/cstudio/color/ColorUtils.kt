package com.example.cstudio.color

import kotlin.math.abs
import kotlin.math.roundToInt

data class RgbColor(
    val red: Int,
    val green: Int,
    val blue: Int,
    val alpha: Int = 255
) {
    init {
        require(red in 0..255 && green in 0..255 && blue in 0..255 && alpha in 0..255)
    }
}

data class HsvColor(
    val hue: Float,
    val saturation: Float,
    val value: Float,
    val alpha: Float = 1f
) {
    init {
        require(hue.isFinite() && hue in 0f..360f)
        require(saturation.isFinite() && saturation in 0f..1f)
        require(value.isFinite() && value in 0f..1f)
        require(alpha.isFinite() && alpha in 0f..1f)
    }
}

data class HslColor(
    val hue: Float,
    val saturation: Float,
    val lightness: Float,
    val alpha: Float = 1f
) {
    init {
        require(hue.isFinite() && hue in 0f..360f)
        require(saturation.isFinite() && saturation in 0f..1f)
        require(lightness.isFinite() && lightness in 0f..1f)
        require(alpha.isFinite() && alpha in 0f..1f)
    }
}

object ColorUtils {
    private const val HEX_DIGITS = "0123456789ABCDEF"

    fun parseHex(value: String): Int? {
        val hex = value.trim().removePrefix("#")
        if ((hex.length != 3 && hex.length != 4 && hex.length != 6 && hex.length != 8) ||
            hex.any { it.digitToIntOrNull(16) == null }
        ) {
            return null
        }

        val expanded = if (hex.length <= 4) {
            buildString(hex.length * 2) {
                hex.forEach {
                    append(it)
                    append(it)
                }
            }
        } else {
            hex
        }
        val red = expanded.substring(0, 2).toInt(16)
        val green = expanded.substring(2, 4).toInt(16)
        val blue = expanded.substring(4, 6).toInt(16)
        val alpha = if (expanded.length == 8) expanded.substring(6, 8).toInt(16) else 255
        return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
    }

    fun toHex(argb: Int, includeAlpha: Boolean = (argb ushr 24) != 0xFF): String = buildString {
        append('#')
        appendByte(argb ushr 16)
        appendByte(argb ushr 8)
        appendByte(argb)
        if (includeAlpha) appendByte(argb ushr 24)
    }

    fun argbToRgb(argb: Int) = RgbColor(
        red = argb ushr 16 and 0xFF,
        green = argb ushr 8 and 0xFF,
        blue = argb and 0xFF,
        alpha = argb ushr 24 and 0xFF
    )

    fun rgbToArgb(rgb: RgbColor): Int =
        (rgb.alpha shl 24) or (rgb.red shl 16) or (rgb.green shl 8) or rgb.blue

    fun argbToHsv(argb: Int): HsvColor {
        val rgb = argbToRgb(argb)
        val red = rgb.red / 255f
        val green = rgb.green / 255f
        val blue = rgb.blue / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        val hue = when {
            delta == 0f -> 0f
            max == red -> 60f * (((green - blue) / delta) % 6f)
            max == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        return HsvColor(
            hue = hue,
            saturation = if (max == 0f) 0f else delta / max,
            value = max,
            alpha = rgb.alpha / 255f
        )
    }

    fun hsvToArgb(hsv: HsvColor): Int {
        val hue = if (hsv.hue == 360f) 0f else hsv.hue
        val chroma = hsv.value * hsv.saturation
        val secondary = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val match = hsv.value - chroma
        val (red, green, blue) = when {
            hue < 60f -> Triple(chroma, secondary, 0f)
            hue < 120f -> Triple(secondary, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, secondary)
            hue < 240f -> Triple(0f, secondary, chroma)
            hue < 300f -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        return channelsToArgb(red + match, green + match, blue + match, hsv.alpha)
    }

    fun argbToHsl(argb: Int): HslColor {
        val rgb = argbToRgb(argb)
        val red = rgb.red / 255f
        val green = rgb.green / 255f
        val blue = rgb.blue / 255f
        val max = maxOf(red, green, blue)
        val min = minOf(red, green, blue)
        val delta = max - min
        val lightness = (max + min) / 2f
        val hue = when {
            delta == 0f -> 0f
            max == red -> 60f * (((green - blue) / delta) % 6f)
            max == green -> 60f * ((blue - red) / delta + 2f)
            else -> 60f * ((red - green) / delta + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val saturation = if (delta == 0f) {
            0f
        } else {
            delta / (1f - abs(2f * lightness - 1f))
        }
        return HslColor(
            hue = hue,
            saturation = saturation,
            lightness = lightness,
            alpha = rgb.alpha / 255f
        )
    }

    fun hslToArgb(hsl: HslColor): Int {
        val hue = if (hsl.hue == 360f) 0f else hsl.hue
        val chroma = (1f - abs(2f * hsl.lightness - 1f)) * hsl.saturation
        val secondary = chroma * (1f - abs((hue / 60f) % 2f - 1f))
        val match = hsl.lightness - chroma / 2f
        val (red, green, blue) = when {
            hue < 60f -> Triple(chroma, secondary, 0f)
            hue < 120f -> Triple(secondary, chroma, 0f)
            hue < 180f -> Triple(0f, chroma, secondary)
            hue < 240f -> Triple(0f, secondary, chroma)
            hue < 300f -> Triple(secondary, 0f, chroma)
            else -> Triple(chroma, 0f, secondary)
        }
        return channelsToArgb(red + match, green + match, blue + match, hsl.alpha)
    }

    private fun channelsToArgb(red: Float, green: Float, blue: Float, alpha: Float): Int {
        fun channel(value: Float) = (value.coerceIn(0f, 1f) * 255f).roundToInt()
        return (channel(alpha) shl 24) or
            (channel(red) shl 16) or
            (channel(green) shl 8) or
            channel(blue)
    }

    private fun StringBuilder.appendByte(value: Int) {
        val byte = value and 0xFF
        append(HEX_DIGITS[byte ushr 4])
        append(HEX_DIGITS[byte and 0x0F])
    }
}
