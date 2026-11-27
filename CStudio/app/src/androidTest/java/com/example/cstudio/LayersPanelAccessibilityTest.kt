package com.example.cstudio

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.cstudio.drawing.LayerInfo
import com.example.cstudio.ui.LayersPanel
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LayersPanelAccessibilityTest {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun layerRowsExposeSelectionAndVisibilityActions() {
        composeRule.setContent {
            MaterialTheme {
                LayersPanel(
                    layers = listOf(
                        LayerInfo("layer-bottom", visible = false, opacity = 1f),
                        LayerInfo("layer-top", visible = true, opacity = 1f)
                    ),
                    activeLayerIndex = 1,
                    onSelectLayer = {},
                    onToggleVisibility = { _, _ -> },
                    onOpacityChange = { _, _ -> },
                    onAdd = {},
                    onMove = { _, _ -> },
                    onDelete = {},
                    onDismiss = {}
                )
            }
        }

        composeRule.onNodeWithContentDescription("Capa 2").assertIsSelected()
        composeRule.onNodeWithContentDescription("Capa 1").assertIsNotSelected()
        composeRule.onNodeWithContentDescription("Ocultar capa 2").assertIsDisplayed()
        composeRule.onNodeWithContentDescription("Mostrar capa 1").assertIsDisplayed()
    }
}
