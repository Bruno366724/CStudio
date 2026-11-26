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

data class StoredProject(val metadata: ProjectMetadata, val layers: List<Bitmap>)

data class GalleryProject(val metadata: ProjectMetadata, val thumbnail: Bitmap?)

data class ProjectLoadResult(val project: StoredProject, val recoveryError: IOException?)

class ProjectStorage(private val filesDir: File) {
    private val projectsDirectory = File(filesDir, PROJECTS_DIRECTORY)

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
        if (layers.size != metadata.layers.size || layers.isEmpty()) {
            throw IOException("La cantidad de capas no coincide con los metadatos.")
        }
        val directory = projectDirectory(metadata.id)
        ensureDirectory(directory)

        metadata.layers.zip(layers).forEach { (layerMetadata, bitmap) ->
            if (bitmap.width != metadata.canvasWidth || bitmap.height != metadata.canvasHeight) {
                throw IOException("El tamaño de la capa no coincide con el lienzo.")
            }
            writeBitmapAtomically(
                File(directory, safeFileName(layerMetadata.archivo)),
                bitmap
            )
        }
        val thumbnail = createThumbnail(layers.first())
        try {
            writeBitmapAtomically(File(directory, THUMBNAIL_FILE), thumbnail)
        } finally {
            thumbnail.recycle()
        }

        val updatedMetadata = metadata.copy(
            modifiedAt = maxOf(System.currentTimeMillis(), metadata.modifiedAt, metadata.createdAt)
        )
        writeTextAtomically(
            File(directory, METADATA_FILE),
            updatedMetadata.toJson().toString(2)
        )
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
                val bitmap = BitmapFactory.decodeFile(file.absolutePath)
                    ?: throw IOException("No se pudo leer una capa del proyecto.")
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
        if (!metadataFile.isFile) throw IOException("Falta project.json.")
        val json = try {
            JSONObject(metadataFile.readText())
        } catch (exception: JSONException) {
            throw IOException("project.json está dañado.", exception)
        }
        val metadata = ProjectMetadata.fromJson(json)
        if (metadata.id != directory.name) {
            throw IOException("El identificador del proyecto no coincide con su carpeta.")
        }
        if (migrateLegacy && json.optInt("version") == 1) {
            writeTextAtomically(metadataFile, metadata.toJson().toString(2))
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

    private fun createThumbnail(layer: Bitmap): Bitmap {
        val thumbnail = Bitmap.createBitmap(THUMBNAIL_SIZE, THUMBNAIL_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(thumbnail)
        canvas.drawColor(Color.WHITE)
        val scale = min(
            THUMBNAIL_SIZE.toFloat() / layer.width,
            THUMBNAIL_SIZE.toFloat() / layer.height
        )
        val width = layer.width * scale
        val height = layer.height * scale
        val destination = RectF(
            (THUMBNAIL_SIZE - width) / 2f,
            (THUMBNAIL_SIZE - height) / 2f,
            (THUMBNAIL_SIZE + width) / 2f,
            (THUMBNAIL_SIZE + height) / 2f
        )
        canvas.drawBitmap(layer, null, destination, Paint(Paint.FILTER_BITMAP_FLAG))
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

    private fun ensureDirectory(directory: File) {
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("No se pudo crear el directorio del proyecto.")
        }
    }

    private companion object {
        const val PROJECTS_DIRECTORY = "projects"
        const val METADATA_FILE = "project.json"
        const val THUMBNAIL_FILE = "thumbnail.png"
        const val THUMBNAIL_SIZE = 256
        const val DEFAULT_PROJECT_NAME = "Sin título"
        const val MAX_PROJECT_NAME_LENGTH = 40
        const val TAG = "ProjectStorage"
    }
}
