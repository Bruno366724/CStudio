package com.example.cstudio

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Build
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.lifecycleScope
import com.example.cstudio.brush.BrushSettings
import com.example.cstudio.brush.Brushes
import com.example.cstudio.drawing.DrawingView
import com.example.cstudio.storage.AutoSaveDebouncer
import com.example.cstudio.storage.ExportFormat
import com.example.cstudio.storage.ExportOptions
import com.example.cstudio.storage.GalleryProject
import com.example.cstudio.storage.ImageExporter
import com.example.cstudio.storage.ProjectMetadata
import com.example.cstudio.storage.ProjectStorage
import com.example.cstudio.storage.StoredProject
import com.example.cstudio.ui.ExportDialog
import com.example.cstudio.ui.HomeScreen
import com.example.cstudio.ui.NewProjectDialog
import com.example.cstudio.ui.ToolBar
import com.example.cstudio.ui.theme.CStudioTheme
import kotlinx.coroutines.CoroutineStart
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

class MainActivity : ComponentActivity() {
    private lateinit var drawingView: DrawingView
    private lateinit var projectStorage: ProjectStorage
    private lateinit var autoSaveDebouncer: AutoSaveDebouncer
    private var currentProject: ProjectMetadata? = null
    private var projectReady = false
    private var restoredProjectId: String? = null
    private var currentScreen by mutableStateOf(MainScreen.HOME)
    private var homeProjects by mutableStateOf<List<GalleryProject>>(emptyList())
    private var homeLoading by mutableStateOf(true)
    private var newProjectDialogVisible by mutableStateOf(false)
    private var exportDialogVisible by mutableStateOf(false)
    private var pendingExportOptions: ExportOptions? = null
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
        autoSaveDebouncer = AutoSaveDebouncer(lifecycleScope, AUTO_SAVE_DELAY_MS) {
            saveCurrentProject()
        }
        drawingView.onStrokeCommitted = {
            if (projectReady) autoSaveDebouncer.schedule()
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (newProjectDialogVisible) {
                    newProjectDialogVisible = false
                } else if (exportDialogVisible) {
                    exportDialogVisible = false
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
                var brushSettings by remember {
                    mutableStateOf(
                    BrushSettings(
                        color = Color.Black.toArgb(),
                        sizeDp = 12f,
                        opacity = 1f,
                        brush = Brushes.PENCIL
                    )
                    )
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
                                onHome = { lifecycleScope.launch { navigateHome() } }
                            )
                            AndroidView(
                                modifier = Modifier.fillMaxWidth().weight(1f),
                                factory = { drawingView },
                                update = { view -> view.brushSettings = brushSettings }
                            )
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
        if (projectReady) {
            autoSaveDebouncer.cancel()
            val metadata = currentProject
            if (metadata != null) {
                val snapshot = try {
                    drawingView.snapshotLayer()
                } catch (error: OutOfMemoryError) {
                    showMemoryError()
                    null
                }
                if (snapshot != null) {
                    lifecycleScope.launch(start = CoroutineStart.UNDISPATCHED) {
                        try {
                            val updatedMetadata = withContext(NonCancellable + Dispatchers.IO) {
                                projectSaveMutex.withLock {
                                    projectStorage.saveProject(metadata, listOf(snapshot))
                                }
                            }
                            if (currentProject?.id == updatedMetadata.id) currentProject = updatedMetadata
                        } catch (exception: IOException) {
                            Log.e(TAG, "No se pudo guardar el proyecto al pausar.", exception)
                        } catch (error: OutOfMemoryError) {
                            Log.e(TAG, "Memoria insuficiente para guardar el lienzo.", error)
                            showMemoryError()
                        } finally {
                            snapshot.recycle()
                        }
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
            if (!saveCurrentProject()) return@launch
            try {
                val project = withContext(Dispatchers.IO) {
                    projectStorage.createProject(name, width, height)
                }
                displayProject(project)
            } catch (exception: IOException) {
                showStorageError("No se pudo crear el proyecto.", exception)
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            }
        }
    }

    private fun openProject(projectId: String) {
        lifecycleScope.launch {
            if (!saveCurrentProject()) return@launch
            try {
                val project = withContext(Dispatchers.IO) {
                    projectSaveMutex.withLock { projectStorage.loadProject(projectId) }
                }
                displayProject(project)
            } catch (exception: IOException) {
                showStorageError("No se pudo abrir el proyecto.", exception)
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            }
        }
    }

    private fun renameProject(projectId: String, name: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { projectStorage.renameProject(projectId, name) }
                refreshHome()
            } catch (exception: IOException) {
                showStorageError("No se pudo renombrar el proyecto.", exception)
            }
        }
    }

    private fun deleteProject(projectId: String) {
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { projectStorage.deleteProject(projectId) }
                refreshHome()
            } catch (exception: IOException) {
                showStorageError("No se pudo borrar el proyecto.", exception)
            }
        }
    }

    private suspend fun navigateHome() {
        if (!saveCurrentProject()) return
        autoSaveDebouncer.cancel()
        try {
            val emptyLayer = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
            drawingView.replaceLayer(emptyLayer, takeOwnership = true)
        } catch (error: OutOfMemoryError) {
            showMemoryError()
        }
        currentProject = null
        projectReady = false
        restoredProjectId = null
        currentScreen = MainScreen.HOME
        homeProjects = emptyList()
        refreshHome()
    }

    private fun displayProject(project: StoredProject) {
        var ownershipTransferred = false
        try {
            if (project.layers.size != 1) {
                throw IOException("Este proyecto contiene capas todavía no compatibles.")
            }
            drawingView.replaceLayer(project.layers.single(), takeOwnership = true)
            ownershipTransferred = true
            currentProject = project.metadata
            projectReady = true
            restoredProjectId = project.metadata.id
            currentScreen = MainScreen.EDITOR
            homeProjects = emptyList()
            autoSaveDebouncer.cancel()
        } finally {
            if (!ownershipTransferred) project.layers.forEach { it.recycle() }
        }
    }

    private suspend fun saveCurrentProject(): Boolean {
        val metadata = currentProject ?: return true
        if (!projectReady) return true

        val snapshot = try {
            drawingView.snapshotLayer()
        } catch (error: OutOfMemoryError) {
            showMemoryError()
            return false
        }
        try {
            val updatedMetadata = withContext(Dispatchers.IO) {
                projectSaveMutex.withLock {
                    projectStorage.saveProject(metadata, listOf(snapshot))
                }
            }
            if (currentProject?.id == updatedMetadata.id) currentProject = updatedMetadata
            return true
        } catch (exception: IOException) {
            showStorageError("No se pudo guardar el proyecto.", exception)
            return false
        } catch (error: OutOfMemoryError) {
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
            drawingView.snapshotLayer()
        } catch (error: OutOfMemoryError) {
            showMemoryError()
            return
        }
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    ImageExporter(contentResolver).export(snapshot, projectName, options)
                }
                Toast.makeText(
                    this@MainActivity,
                    "Archivo guardado en ${
                        if (options.format == ExportFormat.PDF) "Documents/CStudio" else "Pictures/CStudio"
                    }",
                    Toast.LENGTH_LONG
                ).show()
            } catch (exception: IOException) {
                showStorageError("No se pudo guardar el archivo.", exception)
            } catch (error: OutOfMemoryError) {
                showMemoryError()
            } finally {
                snapshot.recycle()
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
        const val STATE_SCREEN = "screen"
        const val STATE_PROJECT_ID = "project_id"
        const val TAG = "MainActivity"
    }
}
