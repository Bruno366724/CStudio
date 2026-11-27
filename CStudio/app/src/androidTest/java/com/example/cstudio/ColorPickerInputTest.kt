package com.example.cstudio

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.click
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.cstudio.ui.ColorValueInputs
import com.example.cstudio.ui.ColorSquare
import com.example.cstudio.ui.ColorWheel
import com.example.cstudio.ui.ColorPickerDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ColorPickerInputTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun validHexUpdatesColorAndInvalidHexDoesNotApply() {
        val selectedColor = mutableStateOf(0xFF000000.toInt())
        composeRule.setContent {
            MaterialTheme {
                ColorValueInputs(
                    argb = selectedColor.value,
                    allowAlpha = true,
                    onColorChanged = { selectedColor.value = it },
                    onSelectionFinished = {}
                )
            }
        }

        composeRule.onNodeWithTag("color-hex-input").performTextClearance()
        composeRule.onNodeWithTag("color-hex-input").performTextInput("#00FF0080")
        composeRule.runOnIdle {
            assertEquals(0x8000FF00.toInt(), selectedColor.value)
        }

        composeRule.onNodeWithTag("color-hex-input").performTextClearance()
        composeRule.onNodeWithTag("color-hex-input").performTextInput("#GGG")
        composeRule.runOnIdle {
            assertEquals(0x8000FF00.toInt(), selectedColor.value)
        }
        composeRule.onNodeWithText("HEX no válido").assertIsDisplayed()
        composeRule.onNodeWithTag("color-hex-input").assertTextContains("#GGG")
    }

    @Test
    fun validHexPrefixDoesNotResetInputWhileTyping() {
        val selectedColor = mutableStateOf(0xFF000000.toInt())
        composeRule.setContent {
            MaterialTheme {
                ColorValueInputs(
                    argb = selectedColor.value,
                    allowAlpha = true,
                    onColorChanged = { selectedColor.value = it },
                    onSelectionFinished = {}
                )
            }
        }

        val hexField = composeRule.onNodeWithTag("color-hex-input")
        hexField.performTextClearance()
        hexField.performTextInput("#")
        hexField.performTextInput("F")
        hexField.performTextInput("F")
        hexField.performTextInput("0")
        hexField.assertTextContains("#FF0")
        hexField.performTextInput("0")
        hexField.performTextInput("0")
        hexField.performTextInput("0")

        composeRule.runOnIdle {
            assertEquals(0xFFFF0000.toInt(), selectedColor.value)
        }
        hexField.assertTextContains("#FF0000")
    }

    @Test
    fun tappingHueWheelSelectsColorAndFinishesInteraction() {
        val selectedColor = mutableStateOf(0xFFFFFFFF.toInt())
        val finishedColor = mutableStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                ColorWheel(
                    argb = selectedColor.value,
                    allowAlpha = false,
                    onColorChanged = { selectedColor.value = it },
                    onSelectionFinished = { finishedColor.value = it }
                )
            }
        }

        val wheel = composeRule.onNodeWithContentDescription("Rueda de color")
        val wheelBounds = wheel.getBoundsInRoot()
        val wheelSize = with(composeRule.density) {
            Offset(
                (wheelBounds.right - wheelBounds.left).toPx(),
                (wheelBounds.bottom - wheelBounds.top).toPx()
            )
        }
        val wheelPadding = with(composeRule.density) { 12.dp.toPx() }
        wheel.performTouchInput {
            click(Offset(wheelSize.x - wheelPadding, wheelSize.y / 2f))
        }
        composeRule.runOnIdle {
            assertEquals(0xFFFF0000.toInt(), selectedColor.value)
            assertEquals(selectedColor.value, finishedColor.value)
        }
    }

    @Test
    fun tappingColorSquareUpdatesSaturationAndBrightness() {
        val selectedColor = mutableStateOf(0xFFFFFFFF.toInt())
        val finishedColor = mutableStateOf(0)
        composeRule.setContent {
            MaterialTheme {
                ColorSquare(
                    argb = selectedColor.value,
                    allowAlpha = false,
                    onColorChanged = { selectedColor.value = it },
                    onSelectionFinished = { finishedColor.value = it }
                )
            }
        }

        val square = composeRule.onNodeWithContentDescription("Selector cuadrado de color")
        val squareBounds = square.getBoundsInRoot()
        val squareSize = with(composeRule.density) {
            Offset(
                (squareBounds.right - squareBounds.left).toPx(),
                (squareBounds.bottom - squareBounds.top).toPx()
            )
        }
        square.performTouchInput {
            click(Offset(squareSize.x - 1f, squareSize.y - 1f))
        }
        composeRule.runOnIdle {
            assertEquals(0xFF000000.toInt(), selectedColor.value)
            assertEquals(selectedColor.value, finishedColor.value)
        }
    }

    @Test
    fun numericRgbInputUpdatesColorAndHexValue() {
        val selectedColor = mutableStateOf(0xFF000000.toInt())
        composeRule.setContent {
            MaterialTheme {
                ColorValueInputs(
                    argb = selectedColor.value,
                    allowAlpha = true,
                    onColorChanged = { selectedColor.value = it },
                    onSelectionFinished = {}
                )
            }
        }

        composeRule.onNodeWithTag("color-rgb-red-input").performTextClearance()
        composeRule.onNodeWithTag("color-rgb-red-input").performTextInput("128")
        composeRule.onNodeWithTag("color-rgb-red-input").performImeAction()

        composeRule.runOnIdle {
            assertEquals(0xFF800000.toInt(), selectedColor.value)
        }
        composeRule.onNodeWithTag("color-hex-input").assertTextContains("#800000")
    }

    @Test
    fun dialogSwitchesBetweenWheelAndSquareModes() {
        val eyedropperRequested = mutableStateOf(false)
        val dialogVisible = mutableStateOf(true)
        composeRule.setContent {
            MaterialTheme {
                if (dialogVisible.value) {
                    ColorPickerDialog(
                        argb = 0xFFFF0000.toInt(),
                        recentColors = emptyList(),
                        onColorChanged = {},
                        onSelectionFinished = {},
                        onClearHistory = {},
                        onRequestEyedropper = {
                            eyedropperRequested.value = true
                            dialogVisible.value = false
                        },
                        onDismiss = { dialogVisible.value = false }
                    )
                }
            }
        }

        composeRule.onNodeWithText("Selector de color").assertIsDisplayed()
        composeRule.onNodeWithText("Cuadrado").performClick()
        composeRule.onNodeWithContentDescription("Selector cuadrado de color").assertIsDisplayed()
        composeRule.onNodeWithText("Cuentagotas: tomar color del lienzo").performClick()
        composeRule.runOnIdle { assertTrue(eyedropperRequested.value) }
    }
}
