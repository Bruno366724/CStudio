package com.example.cstudio.ui

// Pantalla de inicio con proyectos recientes y acciones de proyecto.
import androidx.compose.foundation.Image
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.cstudio.storage.GalleryProject
import java.text.DateFormat
import java.util.Date

@Composable
fun HomeScreen(
    projects: List<GalleryProject>,
    isLoading: Boolean,
    onNewProject: () -> Unit,
    onOpenProject: (String) -> Unit,
    onRenameProject: (String, String) -> Unit,
    onDeleteProject: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    var projectToDelete by remember { mutableStateOf<GalleryProject?>(null) }
    var projectToRename by remember { mutableStateOf<GalleryProject?>(null) }
    var renameText by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("CStudio", style = MaterialTheme.typography.headlineMedium)
            Spacer(modifier = Modifier.weight(1f))
            Button(onClick = onNewProject) { Text("Nuevo proyecto") }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator()
            }
            projects.isEmpty() -> Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    "Todavía no hay proyectos. Crea uno para empezar a dibujar.",
                    style = MaterialTheme.typography.bodyLarge
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 170.dp),
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(projects, key = { it.metadata.id }) { project ->
                    var menuExpanded by remember(project.metadata.id) { mutableStateOf(false) }
                    Card(
                        modifier = Modifier.combinedClickable(
                            onClick = { onOpenProject(project.metadata.id) },
                            onLongClick = { menuExpanded = true }
                        )
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(10.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp)
                        ) {
                            val thumbnail = project.thumbnail
                            if (thumbnail != null) {
                                val image = remember(thumbnail) { thumbnail.asImageBitmap() }
                                Image(
                                    bitmap = image,
                                    contentDescription = "Miniatura de ${project.metadata.name}",
                                    modifier = Modifier.fillMaxWidth().height(150.dp),
                                    contentScale = ContentScale.Fit
                                )
                            } else {
                                Box(
                                    modifier = Modifier.fillMaxWidth().height(150.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("Miniatura no disponible")
                                }
                            }
                            Text(
                                text = project.metadata.name,
                                style = MaterialTheme.typography.titleMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "${project.metadata.canvasWidth} × ${project.metadata.canvasHeight}",
                                style = MaterialTheme.typography.bodySmall
                            )
                            Text(
                                remember(project.metadata.modifiedAt) {
                                    DateFormat.getDateTimeInstance(
                                        DateFormat.SHORT,
                                        DateFormat.SHORT
                                    ).format(Date(project.metadata.modifiedAt))
                                },
                                style = MaterialTheme.typography.bodySmall
                            )
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { onOpenProject(project.metadata.id) }) {
                                    Text("Abrir")
                                }
                                Spacer(modifier = Modifier.weight(1f))
                                Box {
                                    TextButton(onClick = { menuExpanded = true }) {
                                        Text("Opciones")
                                    }
                                    DropdownMenu(
                                        expanded = menuExpanded,
                                        onDismissRequest = { menuExpanded = false }
                                    ) {
                                        DropdownMenuItem(
                                            text = { Text("Renombrar") },
                                            onClick = {
                                                menuExpanded = false
                                                renameText = project.metadata.name
                                                projectToRename = project
                                            }
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Borrar") },
                                            onClick = {
                                                menuExpanded = false
                                                projectToDelete = project
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    projectToDelete?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToDelete = null },
            title = { Text("Borrar proyecto") },
            text = { Text("¿Borrar \"${project.metadata.name}\"? Esta acción no se puede deshacer.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        projectToDelete = null
                        onDeleteProject(project.metadata.id)
                    }
                ) { Text("Borrar") }
            },
            dismissButton = {
                TextButton(onClick = { projectToDelete = null }) { Text("Cancelar") }
            }
        )
    }

    projectToRename?.let { project ->
        AlertDialog(
            onDismissRequest = { projectToRename = null },
            title = { Text("Renombrar proyecto") },
            text = {
                OutlinedTextField(
                    value = renameText,
                    onValueChange = { renameText = it.take(40) },
                    label = { Text("Nombre") },
                    singleLine = true,
                    supportingText = {
                        if (renameText.trim().isEmpty()) Text("El nombre no puede quedar vacío.")
                    }
                )
            },
            confirmButton = {
                TextButton(
                    enabled = renameText.trim().isNotEmpty(),
                    onClick = {
                        projectToRename = null
                        onRenameProject(project.metadata.id, renameText.trim())
                    }
                ) { Text("Guardar") }
            },
            dismissButton = {
                TextButton(onClick = { projectToRename = null }) { Text("Cancelar") }
            }
        )
    }
}
