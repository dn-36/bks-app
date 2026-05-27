package com.bkc.screens.projects_user

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Article
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.FolderZip
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.Project
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.media.ProjectFilePicker
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.pdf_viewer.ProjectPdfViewerScreen
import com.bkc.screens.pdf_viewer.renderPdfFirstPage
import org.koin.mp.KoinPlatform.getKoin

class ProjectsUserScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val vm = getScreenModel<ProjectsUserScreenModel>()
        val navigator = LocalNavigator.current
        val state by vm.state.collectAsState()

        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()

        LaunchedEffect(Unit) {
            vm.effects.collect { effect ->
                when (effect) {
                    is ProjectsUserEffect.OpenProject -> {
                        if (effect.project.fileExtension() == "pdf") {
                            navigator?.push(ProjectPdfViewerScreen(effect.project.title, effect.project.fileUrl))
                        }
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = state.searchQuery,
                    onSearchChange = { vm.onIntent(ProjectsUserIntent.SearchChanged(it)) },
                    onClear = { vm.onIntent(ProjectsUserIntent.ClearSearch) },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            },
            floatingActionButton = {
                if (state.canManageProjects) {
                    FloatingActionButton(onClick = { vm.onIntent(ProjectsUserIntent.AddProject) }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить проект")
                    }
                }
            }
        ) { padding ->
            Column(
                Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                state.error?.let {
                    Text(
                        text = it,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                    )
                }

                when (val ls = state.listState) {
                    UiListState.Loading -> LoadingState()
                    is UiListState.Empty -> EmptyState(ls.message)
                    is UiListState.Error -> EmptyState(ls.message)
                    is UiListState.Content -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(ls.items, key = { it.id }) { project ->
                                ProjectFileCard(
                                    project = project,
                                    canManage = state.canManageProjects,
                                    onClick = { vm.onIntent(ProjectsUserIntent.ClickProject(project)) },
                                    onDelete = { vm.onIntent(ProjectsUserIntent.DeleteProject(project)) }
                                )
                            }
                        }
                    }
                }
            }
        }

        state.editor?.let { editor ->
            ProjectEditorDialog(
                editor = editor,
                onTitleChange = { vm.onIntent(ProjectsUserIntent.ProjectTitleChanged(it)) },
                onFilePicked = { fileName, bytes -> vm.onIntent(ProjectsUserIntent.ProjectFilePicked(fileName, bytes)) },
                onDismiss = { vm.onIntent(ProjectsUserIntent.DismissEditor) },
                onSave = { vm.onIntent(ProjectsUserIntent.SaveProject) }
            )
        }
    }
}

@Composable
private fun ProjectFileCard(
    project: Project,
    canManage: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(112.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxSize().padding(10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            ProjectCover(project = project, modifier = Modifier.size(width = 78.dp, height = 92.dp))
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
                Text(
                    text = project.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    text = project.fileName.ifBlank { project.storagePath }.ifBlank { project.fileExtension().uppercase() },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            if (canManage) {
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить проект")
                }
            }
        }
    }
}

@Composable
private fun ProjectCover(
    project: Project,
    modifier: Modifier = Modifier
) {
    if (project.fileExtension() == "pdf") {
        val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = project.fileUrl) {
            value = runCatching { renderPdfFirstPage(project.fileUrl) }.getOrNull()
        }
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            if (bitmap != null) {
                Image(
                    bitmap = bitmap!!,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                FormatCover(extension = "PDF", icon = Icons.Default.PictureAsPdf, modifier = Modifier.fillMaxSize())
            }
        }
    } else {
        val ext = project.fileExtension()
        val icon = when (ext) {
            "fb2", "epub" -> Icons.Default.MenuBook
            "zip", "rar", "7z" -> Icons.Default.FolderZip
            "txt", "rtf" -> Icons.Default.Article
            else -> Icons.Default.Description
        }
        FormatCover(extension = ext.uppercase().ifBlank { "FILE" }, icon = icon, modifier = modifier)
    }
}

@Composable
private fun FormatCover(
    extension: String,
    icon: ImageVector,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            modifier = Modifier.size(30.dp),
            tint = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = extension,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1
        )
    }
}

@Composable
private fun ProjectEditorDialog(
    editor: ProjectEditorState,
    onTitleChange: (String) -> Unit,
    onFilePicked: (String, ByteArray) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var pickFile by remember { mutableStateOf(false) }

    if (pickFile) {
        ProjectFilePicker { fileName, bytes ->
            pickFile = false
            onFilePicked(fileName, bytes)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Добавить проект") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editor.title,
                    onValueChange = onTitleChange,
                    label = { Text("Название проекта") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { pickFile = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Description, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(editor.fileName ?: "Выбрать файл")
                }
            }
        },
        confirmButton = {
            Button(onClick = onSave) {
                Text("Сохранить")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Отмена")
            }
        }
    )
}

private fun Project.fileExtension(): String =
    fileName.substringAfterLast('.', "")
        .ifBlank { storagePath.substringAfterLast('.', "") }
        .lowercase()
