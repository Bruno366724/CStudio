package com.example.cstudio.color

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ColorUtilsTest {
    @Test
    fun parsesHexFormatsWithAndWithoutAlpha() {
        assertEquals(0xFFFF0000.toInt(), ColorUtils.parseHex("#F00"))
        assertEquals(0xFFFF0000.toInt(), ColorUtils.parseHex("#F00F"))
        assertEquals(0xFFFF0000.toInt(), ColorUtils.parseHex("FF0000"))
        assertEquals(0x80FF6A3D.toInt(), ColorUtils.parseHex("#FF6A3D80"))
        assertEquals(0x80FF6A3D.toInt(), ColorUtils.parseHex("FF6A3D80"))
        assertEquals(0x00000000, ColorUtils.parseHex("#00000000"))
    }

    @Test
    fun rejectsMalformedHex() {
        listOf("", "#", "#12", "#12345", "#1234567", "#123456789", "#GGG", "12 3456")
            .forEach { assertNull("Expected invalid HEX: $it", ColorUtils.parseHex(it)) }
    }

    @Test
    fun formatsArgbAsHexAndPreservesAlphaWhenRequested() {
        assertEquals("#FF6A3D", ColorUtils.toHex(0xFFFF6A3D.toInt()))
        assertEquals("#FF6A3D80", ColorUtils.toHex(0x80FF6A3D.toInt()))
        assertEquals("#FF6A3DFF", ColorUtils.toHex(0xFFFF6A3D.toInt(), includeAlpha = true))
    }

    @Test
    fun convertsRgbAndArgbWithoutLosingChannels() {
        val rgb = RgbColor(red = 255, green = 106, blue = 61, alpha = 128)
        val argb = ColorUtils.rgbToArgb(rgb)

        assertEquals(rgb, ColorUtils.argbToRgb(argb))
        assertEquals(0x80FF6A3D.toInt(), argb)
    }

    @Test
    fun convertsPrimaryAndAchromaticColorsToHsv() {
        assertHsv(0xFFFF0000.toInt(), HsvColor(0f, 1f, 1f))
        assertHsv(0xFF00FF00.toInt(), HsvColor(120f, 1f, 1f))
        assertHsv(0xFF0000FF.toInt(), HsvColor(240f, 1f, 1f))
        assertHsv(0xFF000000.toInt(), HsvColor(0f, 0f, 0f))
        assertHsv(0xFFFFFFFF.toInt(), HsvColor(0f, 0f, 1f))
    }

    @Test
    fun convertsPrimaryAndAchromaticColorsToHsl() {
        assertHsl(0xFFFF0000.toInt(), HslColor(0f, 1f, 0.5f))
        assertHsl(0xFF00FF00.toInt(), HslColor(120f, 1f, 0.5f))
        assertHsl(0xFF0000FF.toInt(), HslColor(240f, 1f, 0.5f))
        assertHsl(0xFF000000.toInt(), HslColor(0f, 0f, 0f))
        assertHsl(0xFFFFFFFF.toInt(), HslColor(0f, 0f, 1f))
    }

    @Test
    fun hsvAndHslRoundTripAlphaAndBoundaryColors() {
        listOf(
            0xFF000000.toInt(),
            0xFFFFFFFF.toInt(),
            0xFFFF0000.toInt(),
            0xFF00FF00.toInt(),
            0xFF0000FF.toInt(),
            0x00000000,
            0x80FFFFFF.toInt(),
            0x8047A2D1.toInt()
        ).forEach { argb ->
            assertArgbClose(argb, ColorUtils.hsvToArgb(ColorUtils.argbToHsv(argb)))
            assertArgbClose(argb, ColorUtils.hslToArgb(ColorUtils.argbToHsl(argb)))
        }
    }

    @Test
    fun convertsExplicitHsvAndHslAlphaToArgb() {
        assertEquals(0x80FF0000.toInt(), ColorUtils.hsvToArgb(HsvColor(0f, 1f, 1f, 0.5f)))
        assertEquals(0x80FF0000.toInt(), ColorUtils.hslToArgb(HslColor(0f, 1f, 0.5f, 0.5f)))
    }

    private fun assertHsv(argb: Int, expected: HsvColor) {
        val actual = ColorUtils.argbToHsv(argb)
        assertFloatEquals(expected.hue, actual.hue)
        assertFloatEquals(expected.saturation, actual.saturation)
        assertFloatEquals(expected.value, actual.value)
    }

    private fun assertHsl(argb: Int, expected: HslColor) {
        val actual = ColorUtils.argbToHsl(argb)
        assertFloatEquals(expected.hue, actual.hue)
        assertFloatEquals(expected.saturation, actual.saturation)
        assertFloatEquals(expected.lightness, actual.lightness)
    }

    private fun assertFloatEquals(expected: Float, actual: Float) {
        assertEquals(expected.toDouble(), actual.toDouble(), 0.0001)
    }

    private fun assertArgbClose(expected: Int, actual: Int) {
        for (shift in 0..24 step 8) {
            val expectedChannel = expected ushr shift and 0xFF
            val actualChannel = actual ushr shift and 0xFF
            assertTrue(abs(expectedChannel - actualChannel) <= 1)
        }
    }
}
