package com.bkc.screens.projects.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.currentOrThrow
import com.bkc.core.domain.Project
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.utils.toUserStatusTitle
import com.bkc.screens.pdf_viewer.PdfFilePicker
import com.bkc.screens.pdf_viewer.ProjectPdfViewerScreen
import com.bkc.screens.pdf_viewer.renderPdfFirstPage
import com.bkc.screens.projects.viewmodel.ProjectsEffect
import com.bkc.screens.projects.viewmodel.ProjectsIntent
import com.bkc.screens.projects.viewmodel.ProjectsViewModel
import com.bkc.screens.projects_user.ProjectsUserEffect
import org.koin.mp.KoinPlatform.getKoin

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
class ProjectsScreen : Screen {

    @Composable
    override fun Content() {
        val viewModel = getScreenModel<ProjectsViewModel>()

        var openPicker by remember { mutableStateOf(false) }
        var statusText by remember { mutableStateOf<String?>(null) }

        val navigetion = LocalNavigator.current
        val navigator = LocalNavigator.currentOrThrow



        LaunchedEffect(Unit) {
            val savedUser = getKoin().get<UserSessionStore>().getUserOrNull()
            statusText = savedUser?.status?.toUserStatusTitle()?.let { "Статус: $it" }
        }

        LaunchedEffect(Unit) {
            viewModel.effects.collect { effect ->
               when(effect){
                   is ProjectsEffect.NavigateToPdf ->
                       navigetion?.push(ProjectPdfViewerScreen(effect.title,effect.url))
                   is ProjectsEffect.ShowError -> {}
               }
            }
        }



        if (openPicker) {
            PdfFilePicker { fileName, bytes ->
                openPicker = false
                viewModel.process(
                    ProjectsIntent.AddProject(
                        title = fileName,
                        fileName = fileName,
                        file = bytes
                    )
                )
            }
        }

        val state = viewModel.state

        Scaffold(
            topBar = {
                TopAppBar(
                    title = {
                        Column {
                            Text("Проекты")
                            statusText?.takeIf { it.isNotBlank() }?.let {
                                Text(
                                    text = it,
                                    style = MaterialTheme.typography.labelMedium
                                )
                            }
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.secondary,
                        titleContentColor = MaterialTheme.colorScheme.onSecondary
                    )
                )
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { openPicker = true }
                ) {
                    Text("+")
                }
            }
        ) { padding ->

            if (state.projects.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentAlignment = Alignment.Center
                ) {
                    Text("Пока нет проектов")
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    contentPadding = PaddingValues(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(
                        items = state.projects,
                        key = { project -> project.id }
                    ) { project ->
                        ProjectPdfCard(
                            project = project,
                            onClick = {
                                viewModel.process(ProjectsIntent.OpenProject(project))
                            },
                            onDelete = {
                                viewModel.process(ProjectsIntent.DeleteProject(project))
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ProjectPdfCard(
    project: Project,
    onClick: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .clickable(onClick = onClick),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {

            Column(modifier = Modifier.fillMaxSize()) {
                PdfThumbnail(
                    url = project.fileUrl,
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                )

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(10.dp)
                ) {
                    Text(
                        text = project.title,
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )

                    Spacer(Modifier.height(4.dp))

                    Text(
                        text = project.createdBy,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            IconButton(
                onClick = onDelete,
                modifier = Modifier.align(Alignment.TopEnd)
            ) {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = "Удалить"
                )
            }
        }
    }
}

@Composable
fun PdfThumbnail(
    url: String,
    modifier: Modifier = Modifier
) {
    val thumbnailState by produceState<PdfThumbnailState>(
        initialValue = PdfThumbnailState.Loading,
        key1 = url
    ) {
        value = try {
            val image = renderPdfFirstPage(url)
            if (image != null) {
                PdfThumbnailState.Success(image)
            } else {
                PdfThumbnailState.Error("Не удалось отрисовать PDF")
            }
        } catch (t: Throwable) {
            PdfThumbnailState.Error(t.message ?: "Ошибка загрузки PDF")
        }
    }

    Box(
        modifier = modifier.background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        when (val state = thumbnailState) {
            is PdfThumbnailState.Loading -> {
                CircularProgressIndicator()
            }

            is PdfThumbnailState.Success -> {
                Image(
                    bitmap = state.bitmap,
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            is PdfThumbnailState.Error -> {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center,
                    modifier = Modifier.padding(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.PictureAsPdf,
                        contentDescription = null,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "PDF",
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }
        }
    }
}

data class ProjectUi(
    val id: String,
    val title: String,
    val createdBy: String,
    val pdfUrl: String
)
sealed interface PdfThumbnailState {
    data object Loading : PdfThumbnailState
    data class Success(val bitmap: ImageBitmap) : PdfThumbnailState
    data class Error(val message: String) : PdfThumbnailState
}
/*
@OptIn(ExperimentalMaterial3Api::class)
class ProjectsScreen : Screen {


    @Composable
    override fun Content() {

       val viewModel  = getScreenModel<ProjectsViewModel>()

        var openPicker by remember { mutableStateOf(false) }

        if (openPicker) {
            PdfFilePicker { fileName, bytes ->
                openPicker = false
                viewModel.process(
                    ProjectsIntent.AddProject(
                        title = fileName,
                        fileName = fileName,
                        file = bytes
                    )
                )
            }
        }

        Scaffold(
            topBar = {
                TopAppBar(title = { Text("Проекты") })
            },
            floatingActionButton = {
                FloatingActionButton(
                    onClick = { openPicker = true }
                ) {
                    Text("+")
                }
            }
        ) { padding ->

            LazyColumn(modifier = Modifier.padding(padding)) {
                items(viewModel.state.projects) { project ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                viewModel.process(
                                    ProjectsIntent.OpenProject(project)
                                )
                            }
                            .padding(16.dp)
                    ) {

                        Column(Modifier.weight(1f)) {
                            Text(project.title)
                            Text(project.createdBy)
                        }

                        IconButton(
                            onClick = {
                                viewModel.process(
                                    ProjectsIntent.DeleteProject(project)
                                )
                            }
                        ) {
                            Icon(Icons.Default.Delete, null)
                        }
                    }
                }
            }
        }
    }
}

@Composable
expect fun PdfFilePicker(
    onFilePicked: (fileName: String, bytes: ByteArray) -> Unit
)*/
