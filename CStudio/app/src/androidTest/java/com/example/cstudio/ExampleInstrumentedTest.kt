package com.example.cstudio

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.os.Build
import android.os.SystemClock
import android.provider.MediaStore
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cstudio.drawing.DrawingView
import com.example.cstudio.drawing.History
import com.example.cstudio.storage.AutoSaveDebouncer
import com.example.cstudio.storage.ExportBackground
import com.example.cstudio.storage.ExportFormat
import com.example.cstudio.storage.ExportOptions
import com.example.cstudio.storage.ImageExporter
import com.example.cstudio.storage.ProjectMetadata
import com.example.cstudio.storage.ProjectStorage
import com.example.cstudio.storage.UnsupportedProjectVersionException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONObject
import java.io.File
import java.util.UUID

/**
 * Instrumented test, which will execute on an Android device.
 *
 * See [testing documentation](http://d.android.com/tools/testing).
 */
@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun useAppContext() {
        // Context of the app under test.
        val appContext = InstrumentationRegistry.getInstrumentation().targetContext
        assertEquals("com.example.cstudio", appContext.packageName)
    }

    @Test
    fun pinchAndPanMoveCanvasWithoutDrawingAnAccidentalDot() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 500, 500)

            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(
                view, downTime, downTime, MotionEvent.ACTION_DOWN,
                floatArrayOf(200f), floatArrayOf(250f)
            )
            dispatchTouch(
                view, downTime, downTime + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(200f, 300f), floatArrayOf(250f, 250f)
            )
            dispatchTouch(
                view, downTime, downTime + 20, MotionEvent.ACTION_MOVE,
                floatArrayOf(225f, 275f), floatArrayOf(250f, 250f)
            )
            dispatchTouch(
                view, downTime, downTime + 30, MotionEvent.ACTION_MOVE,
                floatArrayOf(275f, 325f), floatArrayOf(250f, 250f)
            )
            dispatchTouch(
                view, downTime, downTime + 40,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(275f, 325f), floatArrayOf(250f, 250f)
            )
            dispatchTouch(view, downTime, downTime + 50, MotionEvent.ACTION_UP, floatArrayOf(275f), floatArrayOf(250f))
        }

        val transformed = drawView(view)
        assertEquals(Color.LTGRAY, transformed.getPixel(20, 250))
        assertEquals(Color.WHITE, transformed.getPixel(275, 250))

        instrumentation.runOnMainSync {
            view.resetViewport()
            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(250f))
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(250f))
        }
        val drawn = drawView(view)
        assertEquals(Color.BLACK, drawn.getPixel(250, 250))
    }

    @Test
    fun strokesStayInsideTheCanvasAndTouchesOutsideAreIgnored() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 600, 500)

            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(20f))
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(20f))

            dispatchTouch(view, downTime + 20, downTime + 20, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(250f))
            dispatchTouch(view, downTime + 20, downTime + 30, MotionEvent.ACTION_MOVE, floatArrayOf(250f), floatArrayOf(230f))
        }

        val preview = drawView(view)
        assertEquals(Color.LTGRAY, preview.getPixel(250, 45))
        assertEquals(Color.WHITE, preview.getPixel(250, 200))
        assertEquals(Color.BLACK, preview.getPixel(250, 245))

        instrumentation.runOnMainSync {
            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(230f))
        }
        val committed = drawView(view)
        assertEquals(Color.LTGRAY, committed.getPixel(250, 45))
        assertEquals(Color.WHITE, committed.getPixel(250, 200))
        assertEquals(Color.BLACK, committed.getPixel(250, 245))
    }

    @Test
    fun stylusUsesItsPointerWhenPalmTouchesFirstAndCommitsOnStylusUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 500, 500)
            val downTime = SystemClock.uptimeMillis()

            dispatchTouch(
                view, downTime, downTime, MotionEvent.ACTION_DOWN,
                floatArrayOf(90f), floatArrayOf(200f)
            )
            dispatchTouch(
                view, downTime, downTime + 10,
                MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(90f, 320f), floatArrayOf(200f, 200f),
                intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS),
                intArrayOf(3, 7)
            )
            dispatchTouch(
                view, downTime, downTime + 20, MotionEvent.ACTION_MOVE,
                floatArrayOf(120f, 340f), floatArrayOf(210f, 205f),
                intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS),
                intArrayOf(3, 7)
            )
            dispatchTouch(
                view, downTime, downTime + 30, MotionEvent.ACTION_MOVE,
                floatArrayOf(170f, 360f), floatArrayOf(220f, 210f),
                intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS),
                intArrayOf(3, 7)
            )
            dispatchTouch(
                view, downTime, downTime + 40,
                MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
                floatArrayOf(170f, 360f), floatArrayOf(220f, 210f),
                intArrayOf(MotionEvent.TOOL_TYPE_FINGER, MotionEvent.TOOL_TYPE_STYLUS),
                intArrayOf(3, 7)
            )
            dispatchTouch(
                view, downTime, downTime + 50, MotionEvent.ACTION_UP,
                floatArrayOf(170f), floatArrayOf(220f),
                intArrayOf(MotionEvent.TOOL_TYPE_FINGER), intArrayOf(3)
            )
        }

        val drawn = view.snapshotLayer()
        try {
            assertTrue(hasOpaquePixel(drawn, 1_100, 1_500, 250, 500))
            assertFalse(hasOpaquePixel(drawn, 250, 750, 250, 500))
        } finally {
            drawn.recycle()
        }

        instrumentation.runOnMainSync { view.undo() }
        val undone = view.snapshotLayer()
        try {
            assertFalse(hasOpaquePixel(undone, 0, undone.width, 0, undone.height))
        } finally {
            undone.recycle()
        }
    }

    @Test
    fun undoRedoAndClearKeepTheirBehavior() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 500, 500)
            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(250f))
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(250f))
        }
        assertEquals(Color.BLACK, drawView(view).getPixel(250, 250))

        instrumentation.runOnMainSync { view.undo() }
        assertEquals(Color.WHITE, drawView(view).getPixel(250, 250))

        instrumentation.runOnMainSync { view.redo() }
        assertEquals(Color.BLACK, drawView(view).getPixel(250, 250))

        instrumentation.runOnMainSync { view.clear() }
        assertEquals(Color.WHITE, drawView(view).getPixel(250, 250))

        instrumentation.runOnMainSync { view.undo() }
        assertEquals(Color.BLACK, drawView(view).getPixel(250, 250))
    }

    @Test
    fun undoWithoutHistoryDoesNotReportMemoryErrorOrChangeBitmap() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        var memoryErrors = 0
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 500, 500)
            view.onMemoryError = { memoryErrors++ }
            view.undo()
        }

        assertEquals(0, memoryErrors)
        val snapshot = view.snapshotLayer()
        try {
            assertFalse(hasOpaquePixel(snapshot, 0, snapshot.width, 0, snapshot.height))
        } finally {
            snapshot.recycle()
        }
    }

    @Test
    fun projectLayerRoundTripsWithoutChangingPixels() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "storage-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        var source: Bitmap? = null
        var restored: Bitmap? = null
        try {
            val project = storage.createProject()
            val sourceBitmap = project.layers.single()
            source = sourceBitmap
            sourceBitmap.setPixel(17, 29, Color.RED)
            sourceBitmap.setPixel(1919, 1079, Color.BLUE)
            val updatedMetadata = storage.saveProject(project.metadata, listOf(sourceBitmap))

            restored = storage.loadProject(updatedMetadata.id).layers.single()
            assertTrue(sourceBitmap.sameAs(restored))
        } finally {
            source?.recycle()
            restored?.recycle()
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun unsupportedProjectVersionIsRejected() = runBlocking(Dispatchers.IO) {
        val json = JSONObject().put("version", ProjectMetadata.CURRENT_VERSION + 1)
        val exception = try {
            ProjectMetadata.fromJson(json)
            null
        } catch (expected: UnsupportedProjectVersionException) {
            expected
        }
        assertNotNull(exception)
    }

    @Test
    fun unsupportedSavedVersionRecoversByCreatingANewProject() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "storage-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        try {
            val damagedProject = storage.createProject()
            damagedProject.layers.forEach(Bitmap::recycle)
            val metadataFile = File(
                testFiles,
                "projects/${damagedProject.metadata.id}/project.json"
            )
            val json = JSONObject(metadataFile.readText())
                .put("version", ProjectMetadata.CURRENT_VERSION + 1)
            metadataFile.writeText(json.toString())

            val result = storage.loadLatestOrCreate()
            try {
                assertNotNull(result.recoveryError)
                assertNotEquals(damagedProject.metadata.id, result.project.metadata.id)
                assertTrue(storage.listProjects().any { it.id == result.project.metadata.id })
            } finally {
                result.project.layers.forEach(Bitmap::recycle)
            }
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun versionOneProjectsMigrateWithoutChangingTheirLayer() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "storage-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        try {
            val project = storage.createProject("Proyecto antiguo", 2048, 2048)
            val source = project.layers.single()
            source.setPixel(100, 200, Color.GREEN)
            storage.saveProject(project.metadata, listOf(source))
            source.recycle()

            val metadataFile = File(testFiles, "projects/${project.metadata.id}/project.json")
            val legacyJson = JSONObject(metadataFile.readText())
                .put("version", 1)
                .put("canvasSize", 2048)
                .apply {
                    remove("canvasWidth")
                    remove("canvasHeight")
                }
            metadataFile.writeText(legacyJson.toString())

            val migrated = storage.loadProject(project.metadata.id)
            try {
                assertEquals(2, migrated.metadata.version)
                assertEquals(2048, migrated.metadata.canvasWidth)
                assertEquals(2048, migrated.metadata.canvasHeight)
                assertEquals(Color.GREEN, migrated.layers.single().getPixel(100, 200))
                val rewrittenJson = JSONObject(metadataFile.readText())
                assertEquals(2, rewrittenJson.getInt("version"))
                assertEquals(2048, rewrittenJson.getInt("canvasWidth"))
                assertEquals(2048, rewrittenJson.getInt("canvasHeight"))
            } finally {
                migrated.layers.forEach(Bitmap::recycle)
            }
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun supportedCanvasDimensionsSaveAndReloadPixels() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "canvas-size-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        val dimensions = listOf(1080 to 1920, 3840 to 2160, 4096 to 4096)
        try {
            dimensions.forEachIndexed { index, (width, height) ->
                val project = storage.createProject("Tamaño $index", width, height)
                val source = project.layers.single()
                try {
                    source.setPixel(width / 2, height / 2, Color.MAGENTA)
                    source.setPixel(width - 1, height - 1, Color.CYAN)
                    val updated = storage.saveProject(project.metadata, listOf(source))
                    val restored = storage.loadProject(updated.id)
                    try {
                        assertEquals(width, restored.metadata.canvasWidth)
                        assertEquals(height, restored.metadata.canvasHeight)
                        assertTrue(source.sameAs(restored.layers.single()))
                    } finally {
                        restored.layers.forEach(Bitmap::recycle)
                    }
                } finally {
                    source.recycle()
                }
            }
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun clearOnMaximumCanvasRemainsUndoableOverHistoryLimit() {
        val bitmap = Bitmap.createBitmap(4096, 4096, Bitmap.Config.ARGB_8888)
        val history = History(bitmap)
        try {
            bitmap.setPixel(4095, 4095, Color.RED)
            history.clear()
            assertTrue(history.undo())
            assertEquals(Color.RED, bitmap.getPixel(4095, 4095))
            assertTrue(history.redo())
            assertEquals(Color.TRANSPARENT, bitmap.getPixel(4095, 4095))
        } finally {
            history.reset()
            bitmap.recycle()
        }
    }

    @Test
    fun replacingLayerClearsUndoHistory() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 500, 500)
            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(250f))
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(250f))

            val blankLayer = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
            view.replaceLayer(blankLayer)
            blankLayer.recycle()
            view.undo()
        }
        assertEquals(Color.WHITE, drawView(view).getPixel(250, 250))
    }

    @Test
    fun damagedProjectLayerIsReported() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "storage-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        val project = storage.createProject()
        project.layers.forEach(Bitmap::recycle)
        val layerFile = File(testFiles, "projects/${project.metadata.id}/layer_0.png")
        layerFile.writeBytes(byteArrayOf(1, 2, 3, 4))

        try {
            val recovered = storage.loadLatestOrCreate()
            try {
                assertNotNull(recovered.recoveryError)
                assertNotEquals(project.metadata.id, recovered.project.metadata.id)
            } finally {
                recovered.project.layers.forEach(Bitmap::recycle)
            }
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun projectsAreOrderedByModificationAndCanBeDeleted() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "storage-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        try {
            val olderProject = storage.createProject("Primero")
            olderProject.layers.forEach(Bitmap::recycle)
            delay(30L)
            val newerProject = storage.createProject("Segundo")
            newerProject.layers.forEach(Bitmap::recycle)

            assertEquals(
                listOf(newerProject.metadata.id, olderProject.metadata.id),
                storage.listProjects().map(ProjectMetadata::id)
            )
            storage.deleteProject(newerProject.metadata.id)
            assertEquals(listOf(olderProject.metadata.id), storage.listProjects().map(ProjectMetadata::id))
            assertEquals(olderProject.metadata.id, storage.loadLatestProject()?.metadata?.id)
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun projectCanBeRenamedAndRemainsListed() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "rename-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        try {
            val project = storage.createProject("Anterior")
            project.layers.forEach(Bitmap::recycle)
            val renamed = storage.renameProject(project.metadata.id, "Nuevo nombre")
            assertEquals("Nuevo nombre", renamed.name)
            assertEquals(
                "Nuevo nombre",
                storage.listProjects().single().name
            )
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun autosaveDebounceWaitsUntilChangesStop() = runBlocking {
        var saves = 0
        val debouncer = AutoSaveDebouncer(this, 300L) { saves++ }
        debouncer.schedule()
        delay(200L)
        debouncer.schedule()
        delay(200L)
        assertEquals(0, saves)
        delay(200L)
        assertEquals(1, saves)
        debouncer.cancelAndJoin()
    }

    @Test
    fun autosaveSchedulesOneMoreRunWhenChangedDuringSave() = runBlocking {
        val saveStarted = CompletableDeferred<Unit>()
        val finishFirstSave = CompletableDeferred<Unit>()
        var saves = 0
        var firstSaveCompleted = false
        val debouncer = AutoSaveDebouncer(this, 10L) {
            saves++
            if (saves == 1) {
                saveStarted.complete(Unit)
                finishFirstSave.await()
                firstSaveCompleted = true
            }
        }

        debouncer.schedule()
        saveStarted.await()
        debouncer.schedule()
        finishFirstSave.complete(Unit)
        delay(100L)

        assertTrue(firstSaveCompleted)
        assertEquals(2, saves)
        debouncer.cancelAndJoin()
    }

    @Test
    fun cancellingAutosaveDuringDebouncePreventsSave() = runBlocking {
        var saves = 0
        val debouncer = AutoSaveDebouncer(this, 100L) { saves++ }
        debouncer.schedule()
        delay(20L)
        debouncer.cancel()
        delay(150L)

        assertEquals(0, saves)
        debouncer.cancelAndJoin()
    }

    @Test
    fun exportCompositionUsesOpaqueWhitePaper() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val layer = Bitmap.createBitmap(2048, 2048, Bitmap.Config.ARGB_8888)
        val exporter = ImageExporter(context.contentResolver)
        layer.setPixel(50, 60, Color.RED)
        val composed = exporter.composeOnWhite(layer)
        try {
            assertEquals(2048, composed.width)
            assertEquals(2048, composed.height)
            assertEquals(Color.WHITE, composed.getPixel(0, 0))
            assertEquals(Color.RED, composed.getPixel(50, 60))
        } finally {
            composed.recycle()
        }
    }

    @Test
    fun inputDisabledRejectsTouchAndEraserPreviewKeepsOtherPixels() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 500, 500)
            view.inputEnabled = false
            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(250f))
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(250f))
        }
        assertEquals(Color.WHITE, drawView(view).getPixel(250, 250))

        instrumentation.runOnMainSync {
            view.inputEnabled = true
            view.brushSettings = com.example.cstudio.brush.BrushSettings(
                Color.BLACK, 12f, 1f, com.example.cstudio.brush.Brushes.PENCIL
            )
            val downTime = SystemClock.uptimeMillis()
            dispatchTouch(view, downTime, downTime, MotionEvent.ACTION_DOWN, floatArrayOf(250f), floatArrayOf(250f))
            dispatchTouch(view, downTime, downTime + 10, MotionEvent.ACTION_UP, floatArrayOf(250f), floatArrayOf(250f))
            view.brushSettings = com.example.cstudio.brush.BrushSettings(
                Color.BLACK, 30f, 1f, com.example.cstudio.brush.Brushes.ERASER
            )
            dispatchTouch(view, downTime + 20, downTime + 20, MotionEvent.ACTION_DOWN, floatArrayOf(300f), floatArrayOf(300f))
        }
        val preview = drawView(view)
        val snapshot = view.snapshotLayer()
        try {
            var foundBlack = false
            for (x in 0 until snapshot.width) {
                for (y in 0 until snapshot.height) {
                    if (snapshot.getPixel(x, y) == Color.BLACK) {
                        foundBlack = true
                        break
                    }
                }
                if (foundBlack) break
            }
            assertTrue(foundBlack)
        } finally {
            snapshot.recycle()
        }
    }

    @Test
    fun loadedLayerIsMutableAndCorruptMetadataCanRecover() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val testFiles = File(context.cacheDir, "recover-test-${UUID.randomUUID()}")
        val storage = ProjectStorage(testFiles)
        try {
            val project = storage.createProject("Original", 256, 256)
            val source = project.layers.single()
            source.setPixel(25, 30, Color.RED)
            storage.saveProject(project.metadata, listOf(source))
            source.recycle()

            val directory = File(testFiles, "projects/${project.metadata.id}")
            File(directory, "project.json.bak").delete()
            File(directory, "project.json").writeText("{malformado")
            val recovered = storage.loadProject(project.metadata.id)
            try {
                assertEquals("Recuperado", recovered.metadata.name)
                assertEquals(256, recovered.metadata.canvasWidth)
                assertEquals(256, recovered.metadata.canvasHeight)
                assertEquals(Color.RED, recovered.layers.single().getPixel(25, 30))
                val mutableView = DrawingView(context)
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    mutableView.layout(0, 0, 256, 256)
                    mutableView.replaceLayer(recovered.layers.single(), takeOwnership = true)
                }
            } finally {
                if (recovered.layers.single().isRecycled.not()) recovered.layers.forEach(Bitmap::recycle)
            }
        } finally {
            testFiles.deleteRecursively()
        }
    }

    @Test
    fun exportsSupportedFormatsWithExpectedMimeDimensionsAndBackground() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val resolver = context.contentResolver
        val exporter = ImageExporter(resolver)
        val layer = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        layer.setPixel(10, 12, Color.RED)
        val scenarios = listOf(
            ExportOptions(ExportFormat.PNG, background = ExportBackground.TRANSPARENT),
            ExportOptions(ExportFormat.PNG, background = ExportBackground.WHITE),
            ExportOptions(ExportFormat.JPG, background = ExportBackground.TRANSPARENT),
            ExportOptions(ExportFormat.WEBP, background = ExportBackground.TRANSPARENT),
            ExportOptions(ExportFormat.WEBP, background = ExportBackground.WHITE),
            ExportOptions(ExportFormat.PDF)
        )
        try {
            scenarios.forEach { options ->
                val result = exporter.export(layer, "Mi:Dibujo", options)
                try {
                    resolver.query(
                        result.uri,
                        arrayOf(
                            MediaStore.MediaColumns.MIME_TYPE,
                            MediaStore.MediaColumns.DISPLAY_NAME
                        ),
                        null,
                        null,
                        null
                    )!!.use { cursor ->
                        assertTrue(cursor.moveToFirst())
                        assertEquals(
                            options.format.mimeType,
                            cursor.getString(cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.MIME_TYPE))
                        )
                        assertFalse(
                            cursor.getString(
                                cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                            ).contains(':')
                        )
                    }

                    if (options.format == ExportFormat.PDF) {
                        val descriptor = resolver.openFileDescriptor(result.uri, "r")
                            ?: throw AssertionError("El PDF no se pudo abrir.")
                        PdfRenderer(descriptor).use { renderer ->
                            assertEquals(1, renderer.pageCount)
                            renderer.openPage(0).use { page ->
                                assertEquals(64, page.width)
                                assertEquals(64, page.height)
                                val rendered = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                                try {
                                    page.render(rendered, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                                    assertEquals(Color.WHITE, rendered.getPixel(0, 0))
                                    assertEquals(Color.RED, rendered.getPixel(10, 12))
                                } finally {
                                    rendered.recycle()
                                }
                            }
                        }
                    } else {
                        val decoded = resolver.openInputStream(result.uri)!!.use { stream ->
                            BitmapFactory.decodeStream(stream)
                        } ?: throw AssertionError("La imagen exportada no se pudo decodificar.")
                        try {
                            assertEquals(64, decoded.width)
                            assertEquals(64, decoded.height)
                            when {
                                options.format == ExportFormat.PNG &&
                                    options.background == ExportBackground.TRANSPARENT ->
                                    assertEquals(0, Color.alpha(decoded.getPixel(0, 0)))

                                options.format == ExportFormat.JPG ||
                                    options.background == ExportBackground.WHITE ->
                                    assertTrue(Color.red(decoded.getPixel(0, 0)) > 245)
                            }
                        } finally {
                            decoded.recycle()
                        }
                    }
                } finally {
                    resolver.delete(result.uri, null, null)
                }
            }
        } finally {
            layer.recycle()
        }
    }

    private fun drawView(view: DrawingView): Bitmap {
        lateinit var result: Bitmap
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            result = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(result))
        }
        return result
    }

    private fun dispatchTouch(
        view: DrawingView,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: FloatArray,
        y: FloatArray
    ) {
        dispatchTouch(
            view, downTime, eventTime, action, x, y,
            IntArray(x.size) { MotionEvent.TOOL_TYPE_FINGER },
            IntArray(x.size) { it }
        )
    }

    private fun dispatchTouch(
        view: DrawingView,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: FloatArray,
        y: FloatArray,
        toolTypes: IntArray,
        ids: IntArray
    ) {
        require(x.size == y.size && x.size == toolTypes.size && x.size == ids.size)
        val properties = Array(x.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = ids[index]
                toolType = toolTypes[index]
            }
        }
        val coords = Array(x.size) { index ->
            MotionEvent.PointerCoords().apply {
                this.x = x[index]
                this.y = y[index]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            downTime, eventTime, action, x.size, properties, coords,
            0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun hasOpaquePixel(bitmap: Bitmap, left: Int, right: Int, top: Int, bottom: Int): Boolean {
        for (x in left.coerceAtLeast(0) until right.coerceAtMost(bitmap.width)) {
            for (y in top.coerceAtLeast(0) until bottom.coerceAtMost(bitmap.height)) {
                if (Color.alpha(bitmap.getPixel(x, y)) > 0) return true
            }
        }
        return false
    }
}