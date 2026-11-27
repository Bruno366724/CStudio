package com.example.cstudio.storage

// Gestiona proyectos privados, imágenes de capas y miniaturas en disco.
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.Log
import com.example.cstudio.CanvasDimensions
import com.example.cstudio.drawing.LayerStack
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

data class StoredProject(val metadata: ProjectMetadata, val layers: List<Bitmap>)

data class GalleryProject(val metadata: ProjectMetadata, val thumbnail: Bitmap?)

data class ProjectLoadResult(val project: StoredProject, val recoveryError: IOException?)

class ProjectStorage(private val filesDir: File) {
    private val projectsDirectory = File(filesDir, PROJECTS_DIRECTORY)

    init {
        cleanupTemporaryFilesAndIncompleteProjects()
    }

    fun createProject(
        name: String = DEFAULT_PROJECT_NAME,
        canvasWidth: Int = CanvasDimensions.DEFAULT_WIDTH,
        canvasHeight: Int = CanvasDimensions.DEFAULT_HEIGHT
    ): StoredProject {
        if (!CanvasDimensions.isValid(canvasWidth, canvasHeight)) {
            throw IOException("El tamaño del lienzo debe estar entre 64 y 4096 píxeles por lado.")
        }
        ensureDirectory(projectsDirectory)
        val projectId = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        val metadata = ProjectMetadata(
            id = projectId,
            name = name.trim().ifEmpty { DEFAULT_PROJECT_NAME },
            createdAt = now,
            modifiedAt = now,
            canvasWidth = canvasWidth,
            canvasHeight = canvasHeight,
            layers = listOf(
                ProjectLayerMetadata(
                    id = "layer-0",
                    archivo = "layer_0.png",
                    visible = true,
                    opacidad = 1f
                )
            )
        )
        val blankLayer = Bitmap.createBitmap(
            metadata.canvasWidth,
            metadata.canvasHeight,
            Bitmap.Config.ARGB_8888
        )
        return try {
            val savedMetadata = saveProject(metadata, listOf(blankLayer))
            StoredProject(savedMetadata, listOf(blankLayer))
        } catch (error: OutOfMemoryError) {
            blankLayer.recycle()
            throw error
        } catch (exception: Exception) {
            blankLayer.recycle()
            throw exception
        }
    }

    fun saveProject(metadata: ProjectMetadata, layers: List<Bitmap>): ProjectMetadata {
        validateMetadata(metadata)
        if (layers.size != metadata.layers.size || layers.isEmpty()) {
            throw IOException("La cantidad de capas no coincide con los metadatos.")
        }
        val changedLayers = metadata.layers.zip(layers).associate { (layer, bitmap) ->
            layer.id to bitmap
        }
        val thumbnail = createThumbnail(metadata, layers)
        return try {
            saveProject(metadata, changedLayers, thumbnail)
        } finally {
            thumbnail.recycle()
        }
    }

    fun saveProject(
        metadata: ProjectMetadata,
        changedLayers: Map<String, Bitmap>,
        thumbnail: Bitmap
    ): ProjectMetadata {
        validateMetadata(metadata)
        if (metadata.layers.isEmpty() || metadata.layers.size > LayerStack.MAX_LAYERS) {
            throw IOException("La cantidad de capas del proyecto no es válida.")
        }
        if (thumbnail.width != THUMBNAIL_SIZE || thumbnail.height != THUMBNAIL_SIZE) {
            throw IOException("La miniatura tiene dimensiones incorrectas.")
        }
        val metadataIds = metadata.layers.map(ProjectLayerMetadata::id).toSet()
        if (changedLayers.keys.any { it !in metadataIds }) {
            throw IOException("Se intentó guardar una capa ajena al proyecto.")
        }
        val directory = projectDirectory(metadata.id)
        ensureDirectory(directory)
        val preservedRecoveryFiles = readPreservedRecoveryLayerFiles(directory)

        metadata.layers.forEach { layerMetadata ->
            val target = File(directory, safeFileName(layerMetadata.archivo))
            val bitmap = changedLayers[layerMetadata.id]
            if (bitmap != null) {
                if (bitmap.width != metadata.canvasWidth || bitmap.height != metadata.canvasHeight) {
                    throw IOException("El tamaño de la capa no coincide con el lienzo.")
                }
                writeBitmapAtomically(target, bitmap)
            } else if (!target.isFile) {
                throw IOException("Falta el archivo de una capa sin cambios.")
            }
        }
        writeBitmapAtomically(File(directory, THUMBNAIL_FILE), thumbnail)

        val updatedMetadata = metadata.copy(
            modifiedAt = maxOf(System.currentTimeMillis(), metadata.modifiedAt, metadata.createdAt)
        )
        writeTextAtomically(
            File(directory, METADATA_FILE),
            updatedMetadata.toJson().toString(2)
        )
        val referencedFiles = metadata.layers.map { safeFileName(it.archivo) }.toSet() +
            preservedRecoveryFiles
        directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("layer_") && it.name.endsWith(".png") }
            ?.filter { it.name !in referencedFiles }
            ?.forEach { orphan ->
                if (!orphan.delete()) {
                    Log.w(TAG, "No se pudo borrar la capa huérfana ${orphan.name}.")
                }
            }
        directory.setLastModified(updatedMetadata.modifiedAt)
        return updatedMetadata
    }

    fun loadProject(projectId: String): StoredProject {
        val directory = projectDirectory(projectId)
        val metadata = readMetadata(directory, migrateLegacy = true)
        val layers = mutableListOf<Bitmap>()
        try {
            metadata.layers.forEach { layer ->
                val file = File(directory, safeFileName(layer.archivo))
                if (!file.isFile) throw IOException("Falta el archivo de una capa.")
                val options = BitmapFactory.Options().apply {
                    inMutable = true
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val decoded = BitmapFactory.decodeFile(file.absolutePath, options)
                    ?: throw IOException("No se pudo leer una capa del proyecto.")
                val bitmap = if (decoded.config != Bitmap.Config.ARGB_8888) {
                    val converted = decoded.copy(Bitmap.Config.ARGB_8888, true)
                    decoded.recycle()
                    converted
                } else {
                    decoded
                }
                    ?: throw IOException("No se pudo convertir una capa a ARGB_8888.")
                if (bitmap.width != metadata.canvasWidth || bitmap.height != metadata.canvasHeight) {
                    bitmap.recycle()
                    throw IOException("Una capa tiene dimensiones inesperadas.")
                }
                layers.add(bitmap)
            }
        } catch (exception: Exception) {
            layers.forEach(Bitmap::recycle)
            throw exception
        } catch (error: OutOfMemoryError) {
            layers.forEach(Bitmap::recycle)
            throw error
        }
        return StoredProject(metadata, layers)
    }

    fun loadLatestProject(): StoredProject? {
        val latestDirectory = projectDirectories().maxByOrNull { directory ->
            File(directory, METADATA_FILE).lastModified().takeIf { it > 0L }
                ?: directory.lastModified()
        } ?: return null
        return loadProject(latestDirectory.name)
    }

    fun loadLatestOrCreate(): ProjectLoadResult {
        return try {
            ProjectLoadResult(loadLatestProject() ?: createProject(), null)
        } catch (exception: IOException) {
            try {
                ProjectLoadResult(createProject(), exception)
            } catch (creationException: IOException) {
                creationException.addSuppressed(exception)
                throw creationException
            }
        }
    }

    fun listProjects(): List<ProjectMetadata> {
        return projectDirectories().mapNotNull { directory ->
            try {
                readMetadata(directory)
            } catch (exception: IOException) {
                Log.w(TAG, "Se omite un proyecto con metadatos inválidos: ${directory.name}", exception)
                null
            }
        }.sortedByDescending(ProjectMetadata::modifiedAt)
    }

    fun loadGalleryProjects(): List<GalleryProject> {
        return listProjects().map { metadata ->
            val thumbnail = try {
                loadThumbnail(metadata.id)
            } catch (exception: IOException) {
                Log.w(TAG, "No se pudo leer la miniatura de ${metadata.id}.", exception)
                null
            }
            GalleryProject(metadata, thumbnail)
        }
    }

    fun deleteProject(projectId: String) {
        val directory = projectDirectory(projectId)
        if (directory.exists() && !directory.deleteRecursively()) {
            throw IOException("No se pudo borrar el proyecto.")
        }
    }

    fun renameProject(projectId: String, newName: String): ProjectMetadata {
        val name = newName.trim()
        if (name.isEmpty() || name.length > MAX_PROJECT_NAME_LENGTH) {
            throw IOException("El nombre debe tener entre 1 y 40 caracteres.")
        }
        val directory = projectDirectory(projectId)
        val metadata = readMetadata(directory, migrateLegacy = true)
        val updated = metadata.copy(
            name = name,
            modifiedAt = maxOf(System.currentTimeMillis(), metadata.modifiedAt, metadata.createdAt)
        )
        writeTextAtomically(File(directory, METADATA_FILE), updated.toJson().toString(2))
        directory.setLastModified(updated.modifiedAt)
        return updated
    }

    private fun loadThumbnail(projectId: String): Bitmap? {
        val file = File(projectDirectory(projectId), THUMBNAIL_FILE)
        if (!file.isFile) return null
        return BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IOException("La miniatura del proyecto está dañada.")
    }

    private fun readMetadata(directory: File, migrateLegacy: Boolean = false): ProjectMetadata {
        val metadataFile = File(directory, METADATA_FILE)
        if (!metadataFile.isFile) {
            return readBackupOrRecover(directory)
        }
        val metadata = try {
            val json = try {
                JSONObject(metadataFile.readText())
            } catch (exception: JSONException) {
                throw IOException("project.json está dañado.", exception)
            }
            ProjectMetadata.fromJson(json).also {
                if (migrateLegacy && json.optInt("version") == 1) {
                    writeTextAtomically(metadataFile, it.toJson().toString(2))
                }
            }
        } catch (exception: UnsupportedProjectVersionException) {
            throw exception
        } catch (exception: IOException) {
            readBackupOrRecover(directory)
        }
        if (metadata.id != directory.name) {
            throw IOException("El identificador del proyecto no coincide con su carpeta.")
        }
        return metadata
    }

    private fun projectDirectories(): List<File> {
        return projectsDirectory.listFiles()?.filter(File::isDirectory).orEmpty()
    }

    private fun projectDirectory(projectId: String): File {
        val canonicalId = try {
            UUID.fromString(projectId).toString()
        } catch (exception: IllegalArgumentException) {
            throw IOException("El identificador del proyecto no es válido.", exception)
        }
        return File(projectsDirectory, canonicalId)
    }

    private fun safeFileName(fileName: String): String {
        if (
            fileName.isBlank() || fileName == "." || fileName == ".." ||
            '/' in fileName || '\\' in fileName
        ) {
            throw IOException("El nombre de archivo de capa no es válido.")
        }
        return fileName
    }

    private fun createThumbnail(metadata: ProjectMetadata, layers: List<Bitmap>): Bitmap {
        val thumbnail = Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
        try {
            val canvas = Canvas(thumbnail)
            canvas.drawColor(Color.WHITE)
            val scale = min(
                THUMBNAIL_SIZE.toFloat() / metadata.canvasWidth,
                THUMBNAIL_SIZE.toFloat() / metadata.canvasHeight
            )
            val width = metadata.canvasWidth * scale
            val height = metadata.canvasHeight * scale
            val destination = RectF(
                (THUMBNAIL_SIZE - width) / 2f,
                (THUMBNAIL_SIZE - height) / 2f,
                (THUMBNAIL_SIZE + width) / 2f,
                (THUMBNAIL_SIZE + height) / 2f
            )
            val paint = Paint(Paint.FILTER_BITMAP_FLAG)
            metadata.layers.zip(layers).forEach { (info, layer) ->
                if (info.visible) {
                    paint.alpha = (info.opacidad * 255f).roundToInt().coerceIn(0, 255)
                    canvas.drawBitmap(layer, null, destination, paint)
                }
            }
        } catch (error: OutOfMemoryError) {
            thumbnail.recycle()
            throw error
        } catch (exception: Exception) {
            thumbnail.recycle()
            throw exception
        }
        return thumbnail
    }

    private fun writeBitmapAtomically(target: File, bitmap: Bitmap) {
        val temporary = temporaryFile(target)
        try {
            FileOutputStream(temporary).use { output ->
                if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
                    throw IOException("No se pudo codificar el PNG.")
                }
                output.fd.sync()
            }
            moveAtomically(temporary, target)
        } finally {
            deleteTemporaryFile(temporary)
        }
    }

    private fun writeTextAtomically(target: File, content: String) {
        val temporary = temporaryFile(target)
        try {
            if (target.name == METADATA_FILE && target.isFile) {
                Files.copy(
                    target.toPath(),
                    File(target.parentFile, "$METADATA_FILE.bak").toPath(),
                    StandardCopyOption.REPLACE_EXISTING
                )
            }
            FileOutputStream(temporary).use { output ->
                output.write(content.toByteArray(Charsets.UTF_8))
                output.fd.sync()
            }
            moveAtomically(temporary, target)
        } finally {
            deleteTemporaryFile(temporary)
        }
    }

    private fun deleteTemporaryFile(file: File) {
        if (file.exists() && !file.delete()) {
            Log.w(TAG, "No se pudo borrar el archivo temporal ${file.name}.")
        }
    }

    private fun moveAtomically(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (exception: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun temporaryFile(target: File): File {
        return File(target.parentFile, "${target.name}.${UUID.randomUUID()}.tmp")
    }

    private fun readBackupOrRecover(directory: File): ProjectMetadata {
        val metadataFile = File(directory, METADATA_FILE)
        val backupFile = File(directory, "$METADATA_FILE.bak")
        if (backupFile.isFile) {
            try {
                val backup = ProjectMetadata.fromJson(JSONObject(backupFile.readText()))
                if (backup.id == directory.name) {
                    writeTextAtomically(metadataFile, backup.toJson().toString(2))
                    return backup
                }
            } catch (exception: Exception) {
                if (exception is UnsupportedProjectVersionException) throw exception
            }
        }
        // The original stack order cannot be reconstructed without project metadata.
        val layerFiles = directory.listFiles()
            ?.filter { it.isFile && it.name.startsWith("layer_") && it.name.endsWith(".png") }
            ?.sortedBy(File::getName)
            .orEmpty()
        val recoveredLayers = mutableListOf<ProjectLayerMetadata>()
        val preservedFiles = mutableListOf<String>()
        var canvasWidth: Int? = null
        var canvasHeight: Int? = null
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }

        layerFiles.forEach { layerFile ->
            bounds.outWidth = 0
            bounds.outHeight = 0
            BitmapFactory.decodeFile(layerFile.absolutePath, bounds)
            val width = bounds.outWidth
            val height = bounds.outHeight
            if (width !in 64..4096 || height !in 64..4096) {
                preservedFiles += layerFile.name
                return@forEach
            }

            if (canvasWidth == null) {
                canvasWidth = width
                canvasHeight = height
            }
            if (width != canvasWidth || height != canvasHeight ||
                recoveredLayers.size >= LayerStack.MAX_LAYERS ||
                !LayerStack.canFit(width, height, recoveredLayers.size + 1)
            ) {
                preservedFiles += layerFile.name
                return@forEach
            }

            val layerSuffix = layerFile.name.removePrefix("layer_").removeSuffix(".png")
            recoveredLayers += ProjectLayerMetadata(
                "layer-$layerSuffix",
                layerFile.name,
                true,
                1f
            )
        }

        if (recoveredLayers.isEmpty()) {
            throw IOException("No hay metadatos utilizables ni una capa recuperable.")
        }
        val now = System.currentTimeMillis()
        val recovered = ProjectMetadata(
            id = directory.name,
            name = "Recuperado",
            createdAt = now,
            modifiedAt = now,
            canvasWidth = requireNotNull(canvasWidth),
            canvasHeight = requireNotNull(canvasHeight),
            layers = recoveredLayers
        )
        if (preservedFiles.isNotEmpty()) {
            val preservedJson = JSONArray().apply { preservedFiles.forEach(::put) }
            writeTextAtomically(
                File(directory, RECOVERY_PRESERVED_LAYERS_FILE),
                preservedJson.toString()
            )
        }
        writeTextAtomically(metadataFile, recovered.toJson().toString(2))
        return recovered
    }

    private fun readPreservedRecoveryLayerFiles(directory: File): Set<String> {
        val marker = File(directory, RECOVERY_PRESERVED_LAYERS_FILE)
        if (!marker.isFile) return emptySet()
        val files = try {
            JSONArray(marker.readText())
        } catch (exception: JSONException) {
            throw IOException("La lista de capas preservadas está dañada.", exception)
        }
        return buildSet {
            repeat(files.length()) { index ->
                val fileName = try {
                    files.getString(index)
                } catch (exception: JSONException) {
                    throw IOException("La lista de capas preservadas está dañada.", exception)
                }
                safeFileName(fileName)
                if (!fileName.startsWith("layer_") || !fileName.endsWith(".png")) {
                    throw IOException("La lista de capas preservadas no es válida.")
                }
                add(fileName)
            }
        }
    }

    private fun cleanupTemporaryFilesAndIncompleteProjects() {
        if (!projectsDirectory.isDirectory) return
        projectsDirectory.listFiles()?.forEach { entry ->
            if (entry.isDirectory) {
                entry.listFiles()?.forEach { file ->
                    if (file.name.endsWith(".tmp") && file.isFile) file.delete()
                }
                val metadata = File(entry, METADATA_FILE)
                val backup = File(entry, "$METADATA_FILE.bak")
                val hasLayer = entry.listFiles()?.any {
                    it.isFile && it.name.startsWith("layer_") && it.name.endsWith(".png")
                } == true
                val hasUnknownVersion = try {
                    val source = if (metadata.isFile) metadata else if (backup.isFile) backup else null
                    source?.let { JSONObject(it.readText()).optInt("version", 0) }?.let { it > 2 } == true
                } catch (_: Exception) {
                    false
                }
                if (!hasUnknownVersion && !metadata.isFile && !backup.isFile && !hasLayer) {
                    entry.deleteRecursively()
                }
            } else if (entry.name.endsWith(".tmp")) {
                entry.delete()
            }
        }
    }

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("No se pudo crear el directorio del proyecto.")
        }
    }

    private fun validateMetadata(metadata: ProjectMetadata) {
        try {
            metadata.validate()
        } catch (exception: IllegalArgumentException) {
            throw IOException("Los metadatos del proyecto no son válidos.", exception)
        }
    }

    private companion object {
        const val PROJECTS_DIRECTORY = "projects"
        const val METADATA_FILE = "project.json"
        const val THUMBNAIL_FILE = "thumbnail.png"
        const val RECOVERY_PRESERVED_LAYERS_FILE = "recovery_preserved_layers.json"
        const val THUMBNAIL_SIZE = 256
        const val DEFAULT_PROJECT_NAME = "Sin título"
        const val MAX_PROJECT_NAME_LENGTH = 40
        const val TAG = "ProjectStorage"
    }
}
