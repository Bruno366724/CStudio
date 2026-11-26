package com.example.cstudio

// Verifica que la pantalla de inicio se presenta al arrancar.
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cstudio.storage.ProjectStorage
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.Assert.assertTrue
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenInstrumentedTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun appStartsAtHomeWithCreateProjectAction() {
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Nuevo proyecto").fetchSemanticsNodes().isNotEmpty()
        }
        assertTrue(composeRule.onAllNodesWithText("CStudio").fetchSemanticsNodes().isNotEmpty())
        assertTrue(composeRule.onAllNodesWithText("Nuevo proyecto").fetchSemanticsNodes().isNotEmpty())
    }

    @Test
    fun newProjectDialogUsesHdAsTheDefaultCanvas() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val storage = ProjectStorage(context.filesDir)
        val existingProjectIds = storage.listProjects().map { it.id }.toSet()
        composeRule.waitUntil(timeoutMillis = 10_000) {
            composeRule.onAllNodesWithText("Nuevo proyecto").fetchSemanticsNodes().isNotEmpty()
        }
        try {
            composeRule.onNodeWithText("Nuevo proyecto").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Intercambiar ancho y alto")
                    .fetchSemanticsNodes().isNotEmpty()
            }

            assertTrue(composeRule.onAllNodesWithText("HD 1920 × 1080").fetchSemanticsNodes().isNotEmpty())
            assertTrue(composeRule.onAllNodesWithText("1920").fetchSemanticsNodes().isNotEmpty())
            assertTrue(composeRule.onAllNodesWithText("1080").fetchSemanticsNodes().isNotEmpty())
            composeRule.onNodeWithText("Crear").performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Inicio").fetchSemanticsNodes().isNotEmpty()
            }
            composeRule.onNodeWithText("Inicio").performScrollTo().performClick()
            composeRule.waitUntil(timeoutMillis = 10_000) {
                composeRule.onAllNodesWithText("Nuevo proyecto").fetchSemanticsNodes().isNotEmpty()
            }
            val createdProject = storage.listProjects().firstOrNull { it.id !in existingProjectIds }
            assertTrue(createdProject != null)
            assertEquals(1920, createdProject?.canvasWidth)
            assertEquals(1080, createdProject?.canvasHeight)
        } finally {
            storage.listProjects()
                .filter { it.id !in existingProjectIds }
                .forEach { storage.deleteProject(it.id) }
        }
    }
}
