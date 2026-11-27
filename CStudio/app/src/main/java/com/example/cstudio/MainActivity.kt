package com.example.cstudio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import com.example.cstudio.brush.BrushSettings
import com.example.cstudio.brush.Brushes
import com.example.cstudio.color.RecentColorsRepository
import com.example.cstudio.drawing.DrawingView
import com.example.cstudio.drawing.LayerInfo
import com.example.cstudio.drawing.LayerStack
import com.example.cstudio.drawing.LayerPixelSnapshot
import com.example.cstudio.storage.AutoSaveDebouncer
import com.example.cstudio.storage.ExportFormat
import com.example.cstudio.storage.ExportOptions
import com.example.cstudio.storage.GalleryProject
import com.example.cstudio.storage.ImageExporter
import com.example.cstudio.storage.ProjectMetadata
import com.example.cstudio.storage.ProjectStorage
import com.example.cstudio.storage.StoredProject
import com.example.cstudio.ui.ColorPickerDialog
import com.example.cstudio.ui.ExportDialog
import com.example.cstudio.ui.HomeScreen
import com.example.cstudio.ui.LayersPanel
import com.example.cstudio.ui.NewProjectDialog
import com.example.cstudio.ui.ToolBar
import com.example.cstudio.ui.theme.CStudioTheme
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.IOException

private enum class MainScreen {
    HOME,
    EDITOR
}

private data class ProjectSaveSnapshot(
    val metadata: ProjectMetadata,
    val layers: List<LayerPixelSnapshot>,
    val thumbnail: Bitmap,
    val changeVersion: Long
) {
    val changedBitmaps: Map<String, Bitmap>
        get() = layers.associate { it.id to it.bitmap }

    fun recycle() {
        layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
        if (!thumbnail.isRecycled) thumbnail.recycle()
    }
}

class MainActivity : ComponentActivity() {
    private lateinit var drawingView: DrawingView
    private lateinit var projectStorage: ProjectStorage
    private lateinit var autoSaveDebouncer: AutoSaveDebouncer
    private lateinit var recentColorsRepository: RecentColorsRepository
    private var currentProject: ProjectMetadata? = null
    private var projectReady = false
    private var restoredProjectId: String? = null
    private var currentScreen by mutableStateOf(MainScreen.HOME)
    private var homeProjects by mutableStateOf<List<GalleryProject>>(emptyList())
    private var homeLoading by mutableStateOf(true)
    private var newProjectDialogVisible by mutableStateOf(false)
    private var exportDialogVisible by mutableStateOf(false)
    private var colorPickerDialogVisible by mutableStateOf(false)
    private var layersPanelVisible by mutableStateOf(false)
    private var layerInfos by mutableStateOf<List<LayerInfo>>(emptyList())
    private var activeLayerIndex by mutableStateOf(0)
    private var eyedropperMode by mutableStateOf(false)
    private var pendingExportOptions: ExportOptions? = null
    private var lastBlockedToastMillis: Long? = null
    private var isBusy = false
    private var dirty = false
    private var replacingProject = false
    private var changeVersion = 0L
    private val projectSaveMutex = PROCESS_PROJECT_MUTEX

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        currentScreen = savedInstanceState?.getString(STATE_SCREEN)
            ?.let { runCatching { MainScreen.valueOf(it) }.getOrNull() }
            ?: MainScreen.HOME
        restoredProjectId = savedInstanceState?.getString(STATE_PROJECT_ID)
        if (currentScreen == MainScreen.EDITOR && restoredProjectId == null) {
            currentScreen = MainScreen.HOME
        }

        drawingView = DrawingView(this)
        drawingView.onMemoryError = ::showMemoryError
        projectStorage = ProjectStorage(filesDir)
        recentColorsRepository = RecentColorsRepository(
            getSharedPreferences(COLOR_PREFERENCES_NAME, MODE_PRIVATE)
        )
        autoSaveDebouncer = AutoSaveDebouncer(lifecycleScope, AUTO_SAVE_DELAY_MS) {
            saveCurrentProject()
        }
        drawingView.onStrokeCommitted = {
            if (projectReady) {
                changeVersion++
                dirty = true
                autoSaveDebouncer.schedule()
            }
        }
        drawingView.onLayersChanged = {
            layerInfos = drawingView.layerInfos()
            activeLayerIndex = drawingView.activeLayerIndex
            if (projectReady && !replacingProject) {
                changeVersion++
                dirty = true
                autoSaveDebouncer.schedule()
            }
        }
        drawingView.onBlockedDrawing = {
            val now = SystemClock.elapsedRealtime()
            val lastShown = lastBlockedToastMillis
            if (lastShown == null || now - lastShown >= BLOCKED_DRAWING_TOAST_INTERVAL_MS) {
                lastBlockedToastMillis = now
                Toast.makeText(this, "La capa está oculta.", Toast.LENGTH_SHORT).show()
            }
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (newProjectDialogVisible) {
                    newProjectDialogVisible = false
                } else if (exportDialogVisible) {
                    exportDialogVisible = false
                } else if (colorPickerDialogVisible) {
                    colorPickerDialogVisible = false
                } else if (layersPanelVisible) {
                    layersPanelVisible = false
                } else if (eyedropperMode) {
                    eyedropperMode = false
                    drawingView.eyedropperEnabled = false
                } else if (currentScreen == MainScreen.EDITOR) {
                    lifecycleScope.launch { navigateHome() }
                } else {
                    finish()
                }
            }
        })

        setContent {
            CStudioTheme {
                val exportPermissionLauncher = rememberLauncherForActivityResult(
                    ActivityResultContracts.RequestPermission()
                ) { granted ->
                    val options = pendingExportOptions
                    pendingExportOptions = null
                    if (granted && options != null) {
                        exportDrawing(options)
                    } else if (!granted) {
                        Toast.makeText(this, "Se necesita permiso para guardar el archivo.", Toast.LENGTH_LONG).show()
                    }
                }
                var recentColors by remember {
                    mutableStateOf(recentColorsRepository.getRecentColors())
                }
                var brushSettings by remember {
                    mutableStateOf(
                    BrushSettings(
                        color = recentColorsRepository.getLastColor(Color.Black.toArgb()),
                        sizeDp = 12f,
                        opacity = 1f,
                        brush = Brushes.PENCIL
                    )
                    )
                }
                val onColorSelectionFinished: (Int) -> Unit = { selectedColor ->
                    recentColorsRepository.addColor(selectedColor)
                    recentColorsRepository.saveLastColor(selectedColor)
                    recentColors = recentColorsRepository.getRecentColors()
                }
                androidx.compose.runtime.SideEffect {
                    drawingView.onEyedropperResult = { color ->
                        if (color == null) {
                            Toast.makeText(
                                this@MainActivity,
                                "Toca dentro del lienzo para tomar un color.",
                                Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            eyedropperMode = false
                            brushSettings = brushSettings.copy(
                                color = color,
                                brush = Brushes.PENCIL
                            )
                            onColorSelectionFinished(color)
                        }
                    }
                }

                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    if (currentScreen == MainScreen.HOME) {
                        HomeScreen(
                            projects = homeProjects,
                            isLoading = homeLoading,
                            modifier = Modifier.padding(innerPadding),
                            onNewProject = { newProjectDialogVisible = true },
                            onOpenProject = ::openProject,
                            onRenameProject = ::renameProject,
                            onDeleteProject = ::deleteProject
                        )
                    } else {
                        Column(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
                            ToolBar(
                                settings = brushSettings,
                                onSettingsChange = { brushSettings = it },
                                onClear = { drawingView.clear() },
                                onUndo = { drawingView.undo() },
                                onRedo = { drawingView.redo() },
                                onResetViewport = { drawingView.resetViewport() },
                                onExport = { exportDialogVisible = true },
                                onHome = {
                                    eyedropperMode = false
                                    drawingView.eyedropperEnabled = false
                                    lifecycleScope.launch { navigateHome() }
                                },
                                onColorSelectionFinished = onColorSelectionFinished,
                                onOpenColorPicker = { colorPickerDialogVisible = true },
                                onLayers = { layersPanelVisible = !layersPanelVisible }
                            )
                            Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
                                AndroidView(
                                    modifier = Modifier.fillMaxSize().clipToBounds(),
                                    factory = { drawingView },
                                    update = { view -> view.brushSettings = brushSettings }
                                )
                                if (eyedropperMode) {
                                    Row(
                                        modifier = Modifier
                                            .align(Alignment.TopCenter)
                                            .fillMaxWidth()
                                            .background(MaterialTheme.colorScheme.secondaryContainer)
                                            .padding(horizontal = 12.dp, vertical = 4.dp),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text(
                                            "Toca el lienzo para tomar un color.",
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.weight(1f)
                                        )
                                        TextButton(
                                            onClick = {
                                                eyedropperMode = false
                                                drawingView.eyedropperEnabled = false
                                            }
                                        ) {
                                            Text("Cancelar")
                                        }
                                    }
                                }
                                if (layersPanelVisible) {
                                    LayersPanel(
                                        layers = layerInfos,
                                        activeLayerIndex = activeLayerIndex,
                                        onSelectLayer = drawingView::selectLayer,
                                        onToggleVisibility = drawingView::setLayerVisible,
                                        onOpacityChange = drawingView::setLayerOpacity,
                                        onAdd = {
                                            try {
                                                if (drawingView.layerCount >= LayerStack.MAX_LAYERS) {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "Máximo de ${LayerStack.MAX_LAYERS} capas.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                } else if (!drawingView.addLayer()) {
                                                    Toast.makeText(
                                                        this@MainActivity,
                                                        "No hay memoria para más capas en este lienzo.",
                                                        Toast.LENGTH_SHORT
                                                    ).show()
                                                }
                                            } catch (error: OutOfMemoryError) {
                                                showMemoryError()
                                            }
                                        },
                                        onMove = drawingView::moveLayer,
                                        onDelete = { drawingView.removeActiveLayer() },
                                        onDismiss = { layersPanelVisible = false },
                                        modifier = Modifier
                                            .align(Alignment.CenterEnd)
                                            .padding(8.dp)
                                    )
                                }
                            }
                        }
                    }
                    if (newProjectDialogVisible) {
                        NewProjectDialog(
                            onDismiss = { newProjectDialogVisible = false },
                            onCreate = { name, width, height ->
                                newProjectDialogVisible = false
                                createNewProject(name, width, height)
                            }
                        )
                    }
                    if (exportDialogVisible) {
                        ExportDialog(
                            onDismiss = { exportDialogVisible = false },
                            onExport = { options ->
                                exportDialogVisible = false
                                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q &&
                                    checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) !=
                                    PackageManager.PERMISSION_GRANTED
                                ) {
                                    pendingExportOptions = options
                                    exportPermissionLauncher.launch(
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE
                                    )
                                } else {
                                    exportDrawing(options)
                                }
                            }
                        )
                    }
                    if (colorPickerDialogVisible && currentScreen == MainScreen.EDITOR) {
                        ColorPickerDialog(
                            argb = brushSettings.color,
                            recentColors = recentColors,
                            onColorChanged = { color ->
                                brushSettings = brushSettings.copy(
                                    color = color,
                                    brush = Brushes.PENCIL
                                )
                            },
                            onSelectionFinished = onColorSelectionFinished,
                            onClearHistory = {
                                recentColorsRepository.clearHistory()
                                recentColors = emptyList()
                            },
                            onRequestEyedropper = {
                                colorPickerDialogVisible = false
                                eyedropperMode = true
                                drawingView.eyedropperEnabled = true
                                Toast.makeText(
                                    this@MainActivity,
                                    "Toca el lienzo para tomar un color.",
                                    Toast.LENGTH_SHORT
                                ).show()
                            },
                            onDismiss = {
                                onColorSelectionFinished(brushSettings.color)
                                colorPickerDialogVisible = false
                            }
                        )
                    }
                }
            }
        }

        lifecycleScope.launch { restoreInitialScreen() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putString(STATE_SCREEN, currentScreen.name)
        outState.putString(STATE_PROJECT_ID, currentProject?.id)
        super.onSaveInstanceState(outState)
    }

    override fun onPause() {
        if (projectReady && dirty) {
            autoSaveDebouncer.cancel()
            val snapshot = createSaveSnapshot()
            if (snapshot != null) {
                    lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        var writeCompleted = false
                        var completedMetadata: ProjectMetadata? = null
                        try {
                            val updatedMetadata = writeProjectSnapshot(snapshot) {
                                completedMetadata = it
                                writeCompleted = true
                            }
                            if (currentProject?.id == updatedMetadata.id) {
                                currentProject = updatedMetadata
                                drawingView.markLayerSnapshotsSaved(snapshot.layers)
                                dirty = changeVersion != snapshot.changeVersion
                            }
                        } catch (exception: CancellationException) {
                            if (writeCompleted) {
                                completedMetadata?.let {
                                    if (currentProject?.id == it.id) {
                                        currentProject = it
                                        drawingView.markLayerSnapshotsSaved(snapshot.layers)
                                    }
                                }
                                dirty = changeVersion != snapshot.changeVersion
                            } else {
                                dirty = true
                            }
                            throw exception
                        } catch (exception: IOException) {
                            dirty = true
                            Log.e(TAG, "No se pudo guardar el proyecto al pausar.", exception)
                        } catch (exception: Exception) {
                            dirty = true
                            Log.e(TAG, "Error inesperado al guardar al pausar.", exception)
                        } catch (error: OutOfMemoryError) {
                            dirty = true
                            Log.e(TAG, "Memoria insuficiente para guardar el lienzo.", error)
                            showMemoryError()
                        } finally {
                            snapshot.recycle()
                        }
                    }
            }
        }
        super.onPause()
    }

    private suspend fun restoreInitialScreen() {
        if (currentScreen == MainScreen.EDITOR) {
            val projectId = restoredProjectId
            if (projectId == null) {
                currentScreen = MainScreen.HOME
                refreshHome()
                return
            }
            try {
                val project = withContext(Dispatchers.IO) {
                    projectSaveMutex.withLock { projectStorage.loadProject(projectId) }
                }
                displayProject(project)
            } catch (exception: IOException) {
                Log.e(TAG, "No se pudo recuperar el proyecto abierto.", exception)
                try {
                    val replacement = withContext(Dispatchers.IO) {
                        projectStorage.createProject()
                    }
                    displayProject(replacement)
                    Toast.makeText(this, "El proyecto anterior estaba dañado; se creó uno nuevo.", Toast.LENGTH_LONG).show()
                } catch (creationException: IOException) {
                    currentScreen = MainScreen.HOME
                    showStorageError("No se pudo cargar el proyecto.", creationException)
                    refreshHome()
                } catch (error: OutOfMemoryError) {
                    currentScreen = MainScreen.HOME
                    showMemoryError()
                    refreshHome()
                }
            } catch (error: OutOfMemoryError) {
                currentScreen = MainScreen.HOME
                showMemoryError()
                refreshHome()
            }
        } else {
            currentScreen = MainScreen.HOME
            restoredProjectId = null
            refreshHome()
        }
    }

    private suspend fun refreshHome() {
        homeLoading = true
        try {
            homeProjects = withContext(Dispatchers.IO) {
                projectStorage.loadGalleryProjects()
            }
        } catch (exception: IOException) {
            showStorageError("No se pudieron cargar los proyectos.", exception)
        } catch (error: OutOfMemoryError) {
            showMemoryError()
        } finally {
            homeLoading = false
        }
    }

    private fun createNewProject(
        name: String = "Sin título",
        width: Int = CanvasDimensions.DEFAULT_WIDTH,
        height: Int = CanvasDimensions.DEFAULT_HEIGHT
    ) {
        lifecycleScope.launch {
            if (isBusy) return@launch
            isBusy = true
            drawingView.inputEnabled = false
            try {
                if (!saveCurrentProject()) return@launch
                val project = withContext(Dispatchers.IO) {
                    projectSaveMutex.withLock {
                        projectStorage.createProject(name, width, height)
                    }
                }
                displayProject(project)
            } catch (exception: IOException) {
                showStorageError("No se pudo crear el proyecto.", exception)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Error inesperado al crear el proyecto.", exception)
                showStorageError("No se pudo crear el proyecto.", IOException(exception.message, exception))
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            } finally {
                isBusy = false
                drawingView.inputEnabled = projectReady
            }
        }
    }

    private fun openProject(projectId: String) {
        lifecycleScope.launch {
            if (isBusy) return@launch
            isBusy = true
            drawingView.inputEnabled = false
            try {
                if (!saveCurrentProject()) return@launch
                val project = withContext(Dispatchers.IO) {
                    projectSaveMutex.withLock { projectStorage.loadProject(projectId) }
                }
                displayProject(project)
            } catch (exception: IOException) {
                showStorageError("No se pudo abrir el proyecto.", exception)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Error inesperado al abrir el proyecto.", exception)
                showStorageError("No se pudo abrir el proyecto.", IOException(exception.message, exception))
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            } finally {
                isBusy = false
                drawingView.inputEnabled = projectReady
            }
        }
    }

    private fun renameProject(projectId: String, name: String) {
        lifecycleScope.launch {
            if (isBusy) return@launch
            isBusy = true
            try {
                withContext(Dispatchers.IO) {
                    projectSaveMutex.withLock { projectStorage.renameProject(projectId, name) }
                }
                refreshHome()
            } catch (exception: IOException) {
                showStorageError("No se pudo renombrar el proyecto.", exception)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Error inesperado al renombrar.", exception)
                showStorageError("No se pudo renombrar el proyecto.", IOException(exception.message, exception))
            } finally {
                isBusy = false
            }
        }
    }

    private fun deleteProject(projectId: String) {
        lifecycleScope.launch {
            if (isBusy) return@launch
            isBusy = true
            try {
                withContext(Dispatchers.IO) {
                    projectSaveMutex.withLock { projectStorage.deleteProject(projectId) }
                }
                refreshHome()
            } catch (exception: IOException) {
                showStorageError("No se pudo borrar el proyecto.", exception)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Error inesperado al borrar.", exception)
                showStorageError("No se pudo borrar el proyecto.", IOException(exception.message, exception))
            } finally {
                isBusy = false
            }
        }
    }

    private suspend fun navigateHome() {
        if (isBusy) return
        isBusy = true
        drawingView.inputEnabled = false
        try {
            if (!saveCurrentProject()) {
                Toast.makeText(this, "No se pudo guardar. Reintenta o revisa el almacenamiento.", Toast.LENGTH_LONG).show()
                return
            }
            autoSaveDebouncer.cancel()
            try {
                val emptyLayer = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                replacingProject = true
                projectReady = false
                try {
                    drawingView.replaceLayer(emptyLayer, takeOwnership = true)
                } finally {
                    replacingProject = false
                }
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            }
            currentProject = null
            projectReady = false
            restoredProjectId = null
            currentScreen = MainScreen.HOME
            homeProjects = emptyList()
            refreshHome()
        } finally {
            isBusy = false
            drawingView.inputEnabled = projectReady
        }
    }

    private fun displayProject(project: StoredProject) {
        var ownershipTransferred = false
        val previousProjectReady = projectReady
        replacingProject = true
        projectReady = false
        try {
            drawingView.replaceLayers(
                project.layers,
                project.metadata.layers,
                takeOwnership = true
            )
            ownershipTransferred = true
            currentProject = project.metadata
            projectReady = true
            dirty = false
            restoredProjectId = project.metadata.id
            currentScreen = MainScreen.EDITOR
            homeProjects = emptyList()
            layersPanelVisible = false
            layerInfos = drawingView.layerInfos()
            activeLayerIndex = drawingView.activeLayerIndex
            autoSaveDebouncer.cancel()
        } catch (exception: Exception) {
            projectReady = previousProjectReady
            throw exception
        } catch (error: OutOfMemoryError) {
            projectReady = previousProjectReady
            throw error
        } finally {
            replacingProject = false
            if (!ownershipTransferred) project.layers.forEach { it.recycle() }
        }
    }

    private fun createSaveSnapshot(): ProjectSaveSnapshot? {
        val metadata = currentProject ?: return null
        val snapshotVersion = changeVersion
        var layers: List<LayerPixelSnapshot> = emptyList()
        var thumbnail: Bitmap? = null
        try {
            val savedMetadata = metadata.copy(layers = drawingView.layerMetadata())
            layers = drawingView.snapshotChangedLayers()
            val composedThumbnail = drawingView.snapshotThumbnail()
            thumbnail = composedThumbnail
            return ProjectSaveSnapshot(savedMetadata, layers, composedThumbnail, snapshotVersion)
        } catch (error: OutOfMemoryError) {
            layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            thumbnail?.recycle()
            showMemoryError()
            return null
        } catch (exception: Exception) {
            layers.forEach { if (!it.bitmap.isRecycled) it.bitmap.recycle() }
            thumbnail?.recycle()
            showStorageError(
                "No se pudo preparar el guardado del proyecto.",
                IOException(exception.message, exception)
            )
            return null
        }
    }

    private suspend fun writeProjectSnapshot(
        snapshot: ProjectSaveSnapshot,
        onCompleted: (ProjectMetadata) -> Unit
    ): ProjectMetadata = withContext(NonCancellable + Dispatchers.IO) {
        projectSaveMutex.withLock {
            projectStorage.saveProject(
                snapshot.metadata,
                snapshot.changedBitmaps,
                snapshot.thumbnail
            )
        }.also(onCompleted)
    }

    private suspend fun saveCurrentProject(): Boolean {
        if (currentProject == null) return true
        if (!projectReady) return true
        if (!dirty) return true

        val snapshot = createSaveSnapshot() ?: return false
        var writeCompleted = false
        var completedMetadata: ProjectMetadata? = null
        try {
            val updatedMetadata = writeProjectSnapshot(snapshot) {
                completedMetadata = it
                writeCompleted = true
            }
            if (currentProject?.id == updatedMetadata.id) {
                currentProject = updatedMetadata
                drawingView.markLayerSnapshotsSaved(snapshot.layers)
                dirty = changeVersion != snapshot.changeVersion
            }
            return true
        } catch (exception: IOException) {
            dirty = true
            showStorageError("No se pudo guardar el proyecto.", exception)
            return false
        } catch (exception: CancellationException) {
            if (writeCompleted) {
                completedMetadata?.let {
                    if (currentProject?.id == it.id) {
                        currentProject = it
                        drawingView.markLayerSnapshotsSaved(snapshot.layers)
                    }
                }
                dirty = changeVersion != snapshot.changeVersion
            } else {
                dirty = true
            }
            throw exception
        } catch (exception: Exception) {
            dirty = true
            Log.e(TAG, "Error inesperado al guardar.", exception)
            showStorageError("No se pudo guardar el proyecto.", IOException(exception.message, exception))
            return false
        } catch (error: OutOfMemoryError) {
            dirty = true
            showMemoryError()
            return false
        } finally {
            snapshot.recycle()
        }
    }

    private fun exportDrawing(options: ExportOptions) {
        if (!projectReady) {
            Toast.makeText(this, "El dibujo todavía se está cargando.", Toast.LENGTH_SHORT).show()
            return
        }
        val projectName = currentProject?.name ?: "Sin título"
        val snapshot = try {
            drawingView.snapshotComposite()
        } catch (error: OutOfMemoryError) {
            showMemoryError()
            return
        }
        lifecycleScope.launch {
            try {
                val result = withContext(NonCancellable + Dispatchers.IO) {
                    try {
                        ImageExporter(contentResolver).export(snapshot, projectName, options)
                    } finally {
                        snapshot.recycle()
                    }
                }
                Toast.makeText(
                    this@MainActivity,
                    "Archivo guardado en ${result.folder}/",
                    Toast.LENGTH_LONG
                ).show()
            } catch (exception: IOException) {
                showStorageError("No se pudo guardar el archivo.", exception)
            } catch (exception: CancellationException) {
                throw exception
            } catch (exception: Exception) {
                Log.e(TAG, "Error inesperado al exportar.", exception)
                showStorageError("No se pudo guardar el archivo.", IOException(exception.message, exception))
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            }
        }
    }

    private fun showStorageError(message: String, exception: IOException) {
        Log.e(TAG, message, exception)
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
    }

    private fun showMemoryError() {
        Toast.makeText(this, "Memoria insuficiente para este tamaño.", Toast.LENGTH_LONG).show()
    }

    private companion object {
        private val PROCESS_PROJECT_MUTEX = Mutex()
        const val AUTO_SAVE_DELAY_MS = 2_000L
        const val BLOCKED_DRAWING_TOAST_INTERVAL_MS = 2_000L
        const val STATE_SCREEN = "screen"
        const val STATE_PROJECT_ID = "project_id"
        const val COLOR_PREFERENCES_NAME = "cstudio_color_picker"
        const val TAG = "MainActivity"
    }
}
