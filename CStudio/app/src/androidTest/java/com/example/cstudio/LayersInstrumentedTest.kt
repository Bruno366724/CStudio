package com.example.cstudio

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.SystemClock
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.cstudio.brush.BrushSettings
import com.example.cstudio.brush.Brushes
import com.example.cstudio.drawing.DrawingView
import com.example.cstudio.drawing.History
import com.example.cstudio.drawing.LayerStack
import com.example.cstudio.storage.ProjectLayerMetadata
import com.example.cstudio.storage.ProjectStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class LayersInstrumentedTest {
    @Test
    fun newLayerBecomesActiveAndReceivesStrokes() {
        val view = createView()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(view.addLayer())
                assertEquals(2, view.layerCount)
                assertEquals(1, view.activeLayerIndex)
                tap(view, 18f, 18f)
            }

            val bottom = view.snapshotLayerAt(0)
            val top = view.snapshotLayerAt(1)
            try {
                assertEquals(Color.TRANSPARENT, bottom.getPixel(18, 18))
                assertTrue(Color.alpha(top.getPixel(18, 18)) > 0)
            } finally {
                bottom.recycle()
                top.recycle()
            }
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun movingLayersChangesCompositeStackOrder() {
        val view = createView()
        val bottom = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val top = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.replaceLayers(
                listOf(bottom, top),
                listOf(
                    ProjectLayerMetadata("layer-0", "layer_0.png", true, 1f),
                    ProjectLayerMetadata("layer-test", "layer_test.png", true, 1f)
                ),
                takeOwnership = true
            )
        }
        try {
            val before = drawView(view)
            assertEquals(Color.BLUE, before.getPixel(32, 32))
            before.recycle()

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                assertTrue(view.moveLayer(1, -1))
            }
            val after = drawView(view)
            assertEquals(Color.RED, after.getPixel(32, 32))
            after.recycle()
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun hiddenActiveLayerBlocksDrawingAndIsNotRendered() {
        val view = createView()
        val bottom = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val top = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        var blocked = 0
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.replaceLayers(
                listOf(bottom, top),
                listOf(
                    ProjectLayerMetadata("layer-0", "layer_0.png", true, 1f),
                    ProjectLayerMetadata("layer-test", "layer_test.png", true, 1f)
                ),
                takeOwnership = true
            )
            view.onBlockedDrawing = { blocked++ }
            view.setLayerVisible(1, false)
            tap(view, 32f, 32f)
        }
        try {
            assertEquals(1, blocked)
            val composed = drawView(view)
            assertEquals(Color.RED, composed.getPixel(32, 32))
            composed.recycle()
            val hidden = view.snapshotLayerAt(1)
            try {
                assertEquals(Color.BLUE, hidden.getPixel(32, 32))
            } finally {
                hidden.recycle()
            }
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun layerOpacityAffectsCompositeAndEyedropper() {
        val view = createView()
        val base = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val top = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.replaceLayers(
                listOf(base, top),
                listOf(
                    ProjectLayerMetadata("layer-0", "layer_0.png", true, 1f),
                    ProjectLayerMetadata("layer-test", "layer_test.png", true, 0.5f)
                ),
                takeOwnership = true
            )
        }
        try {
            val composed = drawView(view)
            assertColorClose(Color.rgb(255, 127, 127), composed.getPixel(32, 32), 1)
            composed.recycle()

            val snapshot = view.snapshotComposite()
            try {
                assertEquals(128, Color.alpha(snapshot.getPixel(32, 32)))
                assertEquals(Color.RED, Color.rgb(
                    Color.red(snapshot.getPixel(32, 32)),
                    Color.green(snapshot.getPixel(32, 32)),
                    Color.blue(snapshot.getPixel(32, 32))
                ))
            } finally {
                snapshot.recycle()
            }

            val thumbnail = view.snapshotThumbnail()
            try {
                assertColorClose(Color.rgb(255, 127, 127), thumbnail.getPixel(128, 128), 1)
            } finally {
                thumbnail.recycle()
            }

            var sampled: Int? = null
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.onEyedropperResult = { sampled = it }
                view.eyedropperEnabled = true
                tap(view, 32f, 32f)
            }
            assertColorClose(Color.rgb(255, 127, 127), checkNotNull(sampled), 1)
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun eraserPreviewAndCommitOnlyAffectActiveLayer() {
        val view = createView()
        val bottom = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val top = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.replaceLayers(
                listOf(bottom, top),
                listOf(
                    ProjectLayerMetadata("layer-0", "layer_0.png", true, 1f),
                    ProjectLayerMetadata("layer-test", "layer_test.png", true, 1f)
                ),
                takeOwnership = true
            )
            view.brushSettings = BrushSettings(Color.BLACK, 4f, 1f, Brushes.ERASER)
            val now = SystemClock.uptimeMillis()
            dispatchTouch(view, now, now, MotionEvent.ACTION_DOWN, 32f, 32f)
        }

        val preview = drawView(view)
        assertEquals(Color.RED, preview.getPixel(32, 32))
        assertEquals(Color.BLUE, preview.getPixel(10, 10))
        preview.recycle()

        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            val now = SystemClock.uptimeMillis()
            dispatchTouch(view, now, now + 10, MotionEvent.ACTION_UP, 32f, 32f)
        }
        val committed = drawView(view)
        assertEquals(Color.RED, committed.getPixel(32, 32))
        committed.recycle()
        val lower = view.snapshotLayerAt(0)
        try {
            assertEquals(Color.RED, lower.getPixel(32, 32))
        } finally {
            lower.recycle()
            cleanUp(view)
        }
    }

    @Test
    fun brushPreviewMatchesCommittedPixelsWithLayerAndBrushOpacity() {
        val view = createView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.setLayerOpacity(0, 0.5f)
            view.brushSettings = BrushSettings(Color.RED, 4f, 0.5f, Brushes.PENCIL)
            val now = SystemClock.uptimeMillis()
            dispatchTouch(view, now, now, MotionEvent.ACTION_DOWN, 32f, 32f)
        }
        try {
            val preview = drawView(view)
            val previewPixel = preview.getPixel(32, 32)
            preview.recycle()

            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                val now = SystemClock.uptimeMillis()
                dispatchTouch(view, now, now + 10, MotionEvent.ACTION_UP, 32f, 32f)
            }
            val committed = drawView(view)
            try {
                assertColorClose(previewPixel, committed.getPixel(32, 32), 1)
            } finally {
                committed.recycle()
            }
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun undoRedoTargetsTheLayerThatCreatedEachEntry() {
        val view = createView()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.addLayer()
            view.selectLayer(0)
            tap(view, 16f, 16f)
            view.selectLayer(1)
            view.brushSettings = BrushSettings(Color.BLUE, 12f, 1f, Brushes.PENCIL)
            tap(view, 48f, 48f)
            view.moveLayer(1, -1)
            view.undo()
        }
        try {
            val top = view.snapshotLayerAt(0)
            val bottom = view.snapshotLayerAt(1)
            try {
                assertTrue(Color.alpha(bottom.getPixel(16, 16)) > 0)
                assertEquals(Color.TRANSPARENT, top.getPixel(48, 48))
            } finally {
                bottom.recycle()
                top.recycle()
            }
            InstrumentationRegistry.getInstrumentation().runOnMainSync { view.redo() }
            val redoneTop = view.snapshotLayerAt(0)
            try {
                assertEquals(Color.BLUE, redoneTop.getPixel(48, 48))
            } finally {
                redoneTop.recycle()
            }
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun historyRemovesEntriesForDeletedLayersAndKeepsByteAccounting() {
        val lower = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val upper = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        val history = History(lower)
        val bounds = android.graphics.Rect(2, 2, 10, 10)
        try {
            val lowerBefore = history.capture(bounds, lower)
            lower.setPixel(4, 4, Color.RED)
            history.commit(bounds, lowerBefore, lower)
            val upperBefore = history.capture(bounds, upper)
            upper.setPixel(4, 4, Color.BLUE)
            history.commit(bounds, upperBefore, upper)
            assertEquals(history.liveEntryBytesForTesting, history.trackedBytes)

            history.removeEntriesFor(upper)
            assertEquals(history.liveEntryBytesForTesting, history.trackedBytes)
            assertTrue(history.undo())
            assertEquals(Color.TRANSPARENT, lower.getPixel(4, 4))
            assertEquals(history.liveEntryBytesForTesting, history.trackedBytes)
        } finally {
            history.reset()
            lower.recycle()
            upper.recycle()
        }
    }

    @Test
    fun removingLayerDiscardsItsUndoHistoryAndKeepsOneLayer() {
        val view = createView()
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.addLayer()
                tap(view, 32f, 32f)
                assertTrue(view.removeActiveLayer())
                assertFalse(view.removeActiveLayer())
                view.undo()
            }
            assertEquals(1, view.layerCount)
            val remaining = view.snapshotLayerAt(0)
            try {
                assertEquals(Color.TRANSPARENT, remaining.getPixel(32, 32))
            } finally {
                remaining.recycle()
            }
        } finally {
            cleanUp(view)
        }
    }

    @Test
    fun layerLimitAndMemoryBudgetAreEnforced() {
        assertEquals(20, LayerStack.MAX_LAYERS)
        assertTrue(LayerStack.canFit(1920, 1080, 20))
        assertTrue(LayerStack.canFit(4096, 4096, 4))
        assertFalse(LayerStack.canFit(4096, 4096, 5))

        val initialBitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
        val stack = LayerStack(
            64,
            64,
            listOf(com.example.cstudio.drawing.DrawingLayer("layer-0", "layer_0.png", initialBitmap))
        )
        try {
            repeat(LayerStack.MAX_LAYERS - 1) { assertTrue(stack.addLayer()) }
            assertEquals(20, stack.layers.size)
            assertFalse(stack.addLayer())
        } finally {
            stack.layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        }
    }

    @Test
    fun largeCanvasThumbnailCompositionCompletes() {
        val view = createView()
        val first = Bitmap.createBitmap(4096, 4096, Bitmap.Config.ARGB_8888)
        val second = Bitmap.createBitmap(4096, 4096, Bitmap.Config.ARGB_8888)
        var thumbnail: Bitmap? = null
        try {
            InstrumentationRegistry.getInstrumentation().runOnMainSync {
                view.replaceLayers(
                    listOf(first, second),
                    listOf(
                        ProjectLayerMetadata("layer-0", "layer_0.png", true, 1f),
                        ProjectLayerMetadata("layer-measure", "layer_measure.png", true, 0.5f)
                    ),
                    takeOwnership = true
                )
                val started = SystemClock.elapsedRealtimeNanos()
                thumbnail = view.snapshotThumbnail()
                val durationMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f
                Log.i("LayerThumbnailTest", "4096x4096, 2 capas: ${durationMs}ms")
            }
            assertEquals(256, thumbnail?.width)
            assertNotNull(thumbnail)
        } finally {
            thumbnail?.recycle()
            cleanUp(view)
        }
    }

    @Test
    fun measureProjectStorageInitializationWithThirtyProjects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "storage-startup-${UUID.randomUUID()}")
        val setupStorage = ProjectStorage(root)
        try {
            repeat(30) { index ->
                setupStorage.createProject("Proyecto $index", 64, 64)
                    .layers
                    .forEach(Bitmap::recycle)
            }
            val started = SystemClock.elapsedRealtimeNanos()
            ProjectStorage(root)
            val durationMs = (SystemClock.elapsedRealtimeNanos() - started) / 1_000_000f
            Log.i("ProjectStorageStartupTest", "Constructor con 30 proyectos: ${durationMs}ms")
            assertEquals(30, File(root, "projects").listFiles()?.count { it.isDirectory })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun storageRoundTripsLayerMetadataAndRemovesDeletedLayerFiles() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "layers-${UUID.randomUUID()}")
        val storage = ProjectStorage(root)
        val project = storage.createProject("Capas", 64, 64)
        val first = project.layers.single()
        val second = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            setPixel(10, 11, Color.RED)
        }
        val third = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
            setPixel(20, 21, Color.BLUE)
        }
        val layers = listOf(
            project.metadata.layers.single(),
            ProjectLayerMetadata("layer-two", "layer_two.png", false, 0.25f),
            ProjectLayerMetadata("layer-three", "layer_three.png", true, 0.75f)
        )
        try {
            val metadata = project.metadata.copy(layers = layers)
            storage.saveProject(metadata, listOf(first, second, third))
            val restored = storage.loadProject(metadata.id)
            try {
                assertEquals(layers, restored.metadata.layers)
                assertEquals(Color.RED, restored.layers[1].getPixel(10, 11))
                assertEquals(Color.BLUE, restored.layers[2].getPixel(20, 21))
            } finally {
                restored.layers.forEach(Bitmap::recycle)
            }
            val gallery = storage.loadGalleryProjects()
            try {
                val thumbnailPixel = gallery.single().thumbnail!!.getPixel(128, 128)
                assertTrue(Color.blue(thumbnailPixel) > 240)
                assertTrue(Color.red(thumbnailPixel) in 60..70)
                assertTrue(Color.green(thumbnailPixel) in 60..70)
            } finally {
                gallery.forEach { it.thumbnail?.recycle() }
            }

            val thumbnail = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            try {
                storage.saveProject(
                    metadata.copy(layers = listOf(layers[0], layers[2])),
                    emptyMap(),
                    thumbnail
                )
            } finally {
                thumbnail.recycle()
            }
            val directory = File(root, "projects/${metadata.id}")
            assertFalse(File(directory, "layer_two.png").exists())
            assertTrue(File(directory, "layer_0.png").exists())
            assertTrue(File(directory, "layer_three.png").exists())

            val unchangedFile = File(directory, "layer_0.png")
            assertTrue(unchangedFile.setLastModified(1_000L))
            val unchangedThumbnail = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            try {
                storage.saveProject(
                    metadata.copy(layers = listOf(layers[0], layers[2])),
                    emptyMap(),
                    unchangedThumbnail
                )
            } finally {
                unchangedThumbnail.recycle()
            }
            assertEquals(1_000L, unchangedFile.lastModified())
            first.setPixel(3, 4, Color.GREEN)
            assertTrue(unchangedFile.setLastModified(1_000L))
            val changedThumbnail = Bitmap.createBitmap(256, 256, Bitmap.Config.ARGB_8888).apply {
                eraseColor(Color.WHITE)
            }
            try {
                storage.saveProject(
                    metadata.copy(layers = listOf(layers[0], layers[2])),
                    mapOf(layers[0].id to first),
                    changedThumbnail
                )
            } finally {
                changedThumbnail.recycle()
            }
            assertTrue(unchangedFile.lastModified() > 1_000L)
        } finally {
            project.layers.forEach { if (!it.isRecycled) it.recycle() }
            second.recycle()
            third.recycle()
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryFindsLayerFileWhenLayerZeroIsMissing() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "layer-recovery-${UUID.randomUUID()}")
        val storage = ProjectStorage(root)
        val project = storage.createProject("Recuperar", 64, 64)
        val id = project.metadata.id
        project.layers.forEach(Bitmap::recycle)
        val directory = File(root, "projects/$id")
        File(directory, "project.json").delete()
        File(directory, "project.json.bak").delete()
        assertTrue(File(directory, "layer_0.png").renameTo(File(directory, "layer_custom.png")))

        try {
            val recovered = ProjectStorage(root).loadProject(id)
            try {
                assertEquals("layer-custom", recovered.metadata.layers.single().id)
                assertEquals("layer_custom.png", recovered.metadata.layers.single().archivo)
            } finally {
                recovered.layers.forEach(Bitmap::recycle)
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryRestoresAllLayerFilesWhenMetadataIsMissing() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "layer-recovery-multiple-${UUID.randomUUID()}")
        val storage = ProjectStorage(root)
        val project = storage.createProject("Recuperar varias", 64, 64)
        val second = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.RED)
        }
        val third = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.BLUE)
        }
        val directory = File(root, "projects/${project.metadata.id}")
        try {
            val metadata = project.metadata.copy(
                layers = project.metadata.layers + listOf(
                    ProjectLayerMetadata("layer-second", "layer_second.png", true, 1f),
                    ProjectLayerMetadata("layer-third", "layer_third.png", true, 1f)
                )
            )
            storage.saveProject(metadata, listOf(project.layers.single(), second, third))
            val layerFiles = listOf("layer_0.png", "layer_second.png", "layer_third.png")
            File(directory, "project.json").delete()
            File(directory, "project.json.bak").delete()

            val recoveredStorage = ProjectStorage(root)
            val recovered = recoveredStorage.loadProject(project.metadata.id)
            try {
                assertEquals(3, recovered.layers.size)
                assertTrue(layerFiles.all { File(directory, it).isFile })
                recoveredStorage.saveProject(recovered.metadata, recovered.layers)
                assertTrue(layerFiles.all { File(directory, it).isFile })
            } finally {
                recovered.layers.forEach(Bitmap::recycle)
            }
        } finally {
            project.layers.forEach(Bitmap::recycle)
            second.recycle()
            third.recycle()
            root.deleteRecursively()
        }
    }

    @Test
    fun recoveryPreservesLayerFilesWithMismatchedDimensions() = runBlocking(Dispatchers.IO) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(context.cacheDir, "layer-recovery-mismatched-${UUID.randomUUID()}")
        val storage = ProjectStorage(root)
        val project = storage.createProject("Recuperar dimensiones", 64, 64)
        val directory = File(root, "projects/${project.metadata.id}")
        val mismatchedFile = File(directory, "layer_mismatched.png")
        val mismatched = Bitmap.createBitmap(128, 64, Bitmap.Config.ARGB_8888)
        try {
            FileOutputStream(mismatchedFile).use {
                assertTrue(mismatched.compress(Bitmap.CompressFormat.PNG, 100, it))
            }
            File(directory, "project.json").delete()
            File(directory, "project.json.bak").delete()

            val recoveredStorage = ProjectStorage(root)
            val recovered = recoveredStorage.loadProject(project.metadata.id)
            try {
                assertEquals(listOf("layer_0.png"), recovered.metadata.layers.map { it.archivo })
                assertTrue(mismatchedFile.isFile)
                recoveredStorage.saveProject(recovered.metadata, recovered.layers)
                assertTrue(mismatchedFile.isFile)
            } finally {
                recovered.layers.forEach(Bitmap::recycle)
            }
        } finally {
            project.layers.forEach(Bitmap::recycle)
            mismatched.recycle()
            root.deleteRecursively()
        }
    }

    private fun createView(): DrawingView {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        lateinit var view: DrawingView
        instrumentation.runOnMainSync {
            view = DrawingView(instrumentation.targetContext)
            view.layout(0, 0, 64, 64)
            view.replaceLayer(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), true)
        }
        return view
    }

    private fun cleanUp(view: DrawingView) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            view.replaceLayer(Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888), true)
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

    private fun tap(view: DrawingView, x: Float, y: Float) {
        val now = SystemClock.uptimeMillis()
        dispatchTouch(view, now, now, MotionEvent.ACTION_DOWN, x, y)
        dispatchTouch(view, now, now + 10, MotionEvent.ACTION_UP, x, y)
    }

    private fun dispatchTouch(
        view: DrawingView,
        downTime: Long,
        eventTime: Long,
        action: Int,
        x: Float,
        y: Float
    ) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply {
            id = 0
            toolType = MotionEvent.TOOL_TYPE_FINGER
        })
        val coordinates = arrayOf(MotionEvent.PointerCoords().apply {
            this.x = x
            this.y = y
            pressure = 1f
            size = 1f
        })
        val event = MotionEvent.obtain(
            downTime,
            eventTime,
            action,
            1,
            properties,
            coordinates,
            0,
            0,
            1f,
            1f,
            0,
            0,
            InputDevice.SOURCE_TOUCHSCREEN,
            0
        )
        view.dispatchTouchEvent(event)
        event.recycle()
    }

    private fun assertColorClose(expected: Int, actual: Int, tolerance: Int) {
        assertTrue(kotlin.math.abs(Color.red(expected) - Color.red(actual)) <= tolerance)
        assertTrue(kotlin.math.abs(Color.green(expected) - Color.green(actual)) <= tolerance)
        assertTrue(kotlin.math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance)
        assertEquals(Color.alpha(expected), Color.alpha(actual))
    }
}
