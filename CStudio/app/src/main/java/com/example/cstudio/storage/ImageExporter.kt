package com.example.cstudio.storage

// Exporta el lienzo en formatos de imagen o PDF mediante MediaStore.
import android.content.ContentResolver
import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import com.example.cstudio.CanvasDimensions
import java.io.File
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

enum class ExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
    PDF("pdf", "application/pdf")
}

enum class ExportBackground {
    TRANSPARENT,
    WHITE
}

data class ExportOptions(
    val format: ExportFormat,
    val quality: Int = 90,
    val background: ExportBackground = ExportBackground.TRANSPARENT
)

data class ExportResult(val uri: Uri, val folder: String)

class ImageExporter(private val contentResolver: ContentResolver) {
    fun export(layer: Bitmap, projectName: String, options: ExportOptions): ExportResult {
        if (!CanvasDimensions.isValid(layer.width, layer.height)) {
            throw IOException("El lienzo tiene dimensiones inesperadas.")
        }
        if (options.quality !in 1..100) {
            throw IOException("La calidad debe estar entre 1 y 100.")
        }

        val folder = if (options.format == ExportFormat.PDF) {
            "${Environment.DIRECTORY_DOCUMENTS}/CStudio"
        } else {
            "${Environment.DIRECTORY_PICTURES}/CStudio"
        }
        val displayName = buildDisplayName(projectName, options.format)
        val outputBitmap = if (
            options.format != ExportFormat.PDF &&
            (options.format == ExportFormat.JPG || options.background == ExportBackground.WHITE)
        ) {
            composeOnWhite(layer)
        } else {
            layer
        }

        var uri: Uri? = null
        try {
            val collection = if (options.format == ExportFormat.PDF) {
                MediaStore.Files.getContentUri("external")
            } else {
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            }
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, options.format.mimeType)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    put(MediaStore.MediaColumns.RELATIVE_PATH, folder)
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                } else {
                    put(MediaStore.MediaColumns.DATA, legacyFile(folder, displayName).absolutePath)
                }
            }
            uri = contentResolver.insert(collection, values)
                ?: throw IOException("MediaStore no pudo crear el archivo.")
            val output = contentResolver.openOutputStream(uri, "w")
                ?: throw IOException("No se pudo abrir el archivo de exportación.")
            output.use { stream ->
                when (options.format) {
                    ExportFormat.PDF -> writePdf(layer, stream)
                    else -> writeImage(outputBitmap, options, stream)
                }
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val published = contentResolver.update(
                    uri,
                    ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) },
                    null,
                    null
                )
                if (published == 0) throw IOException("No se pudo publicar el archivo.")
            }
            return ExportResult(uri, folder)
        } catch (exception: Exception) {
            uri?.let { insertedUri ->
                try {
                    contentResolver.delete(insertedUri, null, null)
                } catch (cleanupException: Exception) {
                    exception.addSuppressed(cleanupException)
                }
            }
            if (exception is IOException) throw exception
            throw IOException("No se pudo exportar el archivo.", exception)
        } finally {
            if (outputBitmap !== layer) outputBitmap.recycle()
        }
    }

    internal fun composeOnWhite(layer: Bitmap): Bitmap {
        val result = Bitmap.createBitmap(layer.width, layer.height, Bitmap.Config.ARGB_8888)
        Canvas(result).apply {
            drawColor(Color.WHITE)
            drawBitmap(layer, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
        }
        return result
    }

    private fun writeImage(bitmap: Bitmap, options: ExportOptions, output: java.io.OutputStream) {
        val compression = when (options.format) {
            ExportFormat.PNG -> Bitmap.CompressFormat.PNG
            ExportFormat.JPG -> Bitmap.CompressFormat.JPEG
            ExportFormat.WEBP -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Bitmap.CompressFormat.WEBP_LOSSLESS
            } else {
                @Suppress("DEPRECATION")
                Bitmap.CompressFormat.WEBP
            }
            ExportFormat.PDF -> error("PDF no se codifica como imagen.")
        }
        val quality = if (options.format == ExportFormat.PNG ||
            (options.format == ExportFormat.WEBP && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
        ) {
            100
        } else {
            options.quality
        }
        if (!bitmap.compress(compression, quality, output)) {
            throw IOException("No se pudo codificar el archivo de imagen.")
        }
    }

    private fun writePdf(bitmap: Bitmap, output: java.io.OutputStream) {
        val document = PdfDocument()
        try {
            val page = document.startPage(
                PdfDocument.PageInfo.Builder(bitmap.width, bitmap.height, 1).create()
            )
            page.canvas.drawColor(Color.WHITE)
            page.canvas.drawBitmap(bitmap, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            document.finishPage(page)
            document.writeTo(output)
        } finally {
            document.close()
        }
    }

    private fun buildDisplayName(projectName: String, format: ExportFormat): String {
        val cleanedName = projectName
            .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
            .replace(Regex("\\s+"), "_")
            .trim('_', '.')
            .take(40)
            .ifBlank { "Sin_titulo" }
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmm", Locale.US).format(Date())
        return "${cleanedName}_${timestamp}.${format.extension}"
    }

    @Suppress("DEPRECATION")
    private fun legacyFile(folder: String, displayName: String): File {
        val directoryType = folder.substringBefore('/')
        val directory = File(
            Environment.getExternalStoragePublicDirectory(directoryType),
            "CStudio"
        )
        if (!directory.isDirectory && !directory.mkdirs()) {
            throw IOException("No se pudo crear $folder.")
        }
        return File(directory, displayName)
    }
}
