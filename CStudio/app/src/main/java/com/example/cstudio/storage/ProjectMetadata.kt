package com.example.cstudio.storage

// Define y serializa los metadatos versionados de un proyecto.
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import com.example.cstudio.CanvasDimensions
import com.example.cstudio.drawing.LayerStack
import java.io.IOException

data class ProjectLayerMetadata(
    val id: String,
    val archivo: String,
    val visible: Boolean,
    val opacidad: Float
)

data class ProjectMetadata(
    val id: String,
    val name: String,
    val createdAt: Long,
    val modifiedAt: Long,
    val canvasWidth: Int,
    val canvasHeight: Int,
    val layers: List<ProjectLayerMetadata>,
    val version: Int = CURRENT_VERSION
) {
    fun toJson(): JSONObject {
        val layerArray = JSONArray()
        layers.forEach { layer ->
            layerArray.put(
                JSONObject()
                    .put("id", layer.id)
                    .put("archivo", layer.archivo)
                    .put("visible", layer.visible)
                    .put("opacidad", layer.opacidad.toDouble())
            )
        }
        return JSONObject()
            .put("version", version)
            .put("id", id)
            .put("nombre", name)
            .put("fechaCreacion", createdAt)
            .put("fechaModificacion", modifiedAt)
            .put("canvasWidth", canvasWidth)
            .put("canvasHeight", canvasHeight)
            .put("layers", layerArray)
    }

    companion object {
        const val CURRENT_VERSION = 2

        @Throws(IOException::class)
        fun fromJson(json: JSONObject): ProjectMetadata {
            val version = try {
                json.getInt("version")
            } catch (exception: JSONException) {
                throw IOException("Falta la versión del proyecto.", exception)
            }
            if (version != 1 && version != CURRENT_VERSION) {
                throw UnsupportedProjectVersionException(version)
            }

            try {
                val canvasWidth: Int
                val canvasHeight: Int
                if (version == 1) {
                    val canvasSize = json.getInt("canvasSize")
                    canvasWidth = canvasSize
                    canvasHeight = canvasSize
                } else {
                    canvasWidth = json.getInt("canvasWidth")
                    canvasHeight = json.getInt("canvasHeight")
                }
                val jsonLayers = json.getJSONArray("layers")
                val layers = List(jsonLayers.length()) { index ->
                    val layer = jsonLayers.getJSONObject(index)
                    ProjectLayerMetadata(
                        id = layer.getString("id"),
                        archivo = layer.getString("archivo"),
                        visible = layer.getBoolean("visible"),
                        opacidad = layer.getDouble("opacidad").toFloat()
                    )
                }
                val metadata = ProjectMetadata(
                    id = json.getString("id"),
                    name = json.getString("nombre"),
                    createdAt = json.getLong("fechaCreacion"),
                    modifiedAt = json.getLong("fechaModificacion"),
                    canvasWidth = canvasWidth,
                    canvasHeight = canvasHeight,
                    layers = layers,
                    version = CURRENT_VERSION
                )
                metadata.validate()
                return metadata
            } catch (exception: JSONException) {
                throw IOException("Los metadatos del proyecto están dañados.", exception)
            } catch (exception: IllegalArgumentException) {
                throw IOException("Los metadatos del proyecto no son válidos.", exception)
            }
        }
    }

    internal fun validate() {
        require(id.isNotBlank())
        require(name.isNotBlank())
        require(createdAt > 0L && modifiedAt >= createdAt)
        require(CanvasDimensions.isValid(canvasWidth, canvasHeight))
        require(layers.isNotEmpty())
        require(LayerStack.canFit(canvasWidth, canvasHeight, layers.size))
        require(layers.map(ProjectLayerMetadata::id).toSet().size == layers.size)
        require(layers.map(ProjectLayerMetadata::archivo).toSet().size == layers.size)
        layers.forEach { layer ->
            require(layer.id.isNotBlank())
            require(layer.archivo.isNotBlank())
            require(layer.archivo != "." && layer.archivo != "..")
            require('/' !in layer.archivo && '\\' !in layer.archivo)
            require(layer.opacidad.isFinite() && layer.opacidad in 0f..1f)
        }
    }
}

class UnsupportedProjectVersionException(val unsupportedVersion: Int) :
    IOException("Versión de proyecto no compatible: $unsupportedVersion")
