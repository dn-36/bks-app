package com.bkc.screens.objects

import androidx.compose.foundation.Image
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Image
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.WorkObject
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.media.ImageFilePicker
import com.bkc.core.presentation.media.loadImageBitmap
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.screens.user_panel_screen.UserPanelScreen
import org.koin.mp.KoinPlatform.getKoin

class ObjectsScreen(
    private val openMainOnSelect: Boolean = false,
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<ObjectsScreenModel>()
        val state by vm.state.collectAsState()

        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()

        LaunchedEffect(Unit) {
            vm.effects.collect { effect ->
                when (effect) {
                    ObjectsEffect.Selected -> {
                        if (openMainOnSelect) {
                            navigator?.replaceAll(UserPanelScreen())
                        } else {
                            navigator?.pop()
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
                    onSearchChange = { vm.onIntent(ObjectsIntent.SearchChanged(it)) },
                    onClear = { vm.onIntent(ObjectsIntent.ClearSearch) },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    }
                )
            },
            floatingActionButton = {
                if (state.canManageObjects) {
                    FloatingActionButton(onClick = { vm.onIntent(ObjectsIntent.AddObject) }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить объект")
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
                            contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(ls.items, key = { it.id }) { obj ->
                                ObjectCard(
                                    obj = obj,
                                    canManage = state.canManageObjects,
                                    onClick = { vm.onIntent(ObjectsIntent.ClickObject(obj)) },
                                    onEdit = { vm.onIntent(ObjectsIntent.EditObject(obj)) },
                                    onDelete = { vm.onIntent(ObjectsIntent.DeleteObject(obj)) }
                                )
                            }
                        }
                    }
                }
            }
        }

        state.editor?.let { editor ->
            ObjectEditorDialog(
                editor = editor,
                onNameChange = { vm.onIntent(ObjectsIntent.ObjectNameChanged(it)) },
                onPhotoPicked = { fileName, bytes -> vm.onIntent(ObjectsIntent.ObjectPhotoPicked(fileName, bytes)) },
                onDismiss = { vm.onIntent(ObjectsIntent.DismissEditor) },
                onSave = { vm.onIntent(ObjectsIntent.SaveObject) }
            )
        }
    }
}

@Composable
private fun ObjectCard(
    obj: WorkObject,
    canManage: Boolean,
    onClick: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(96.dp)
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
            ObjectPhoto(obj.photoUrl, Modifier.size(76.dp))
            Spacer(Modifier.width(12.dp))
            Text(
                text = obj.name,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (canManage) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Изменить объект")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить объект")
                }
            }
        }
    }
}

@Composable
private fun ObjectPhoto(
    photoUrl: String?,
    modifier: Modifier = Modifier
) {
    val bitmap by produceState<ImageBitmap?>(initialValue = null, key1 = photoUrl) {
        value = photoUrl?.let { loadImageBitmap(it) }
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
            Icon(
                imageVector = Icons.Default.Image,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ObjectEditorDialog(
    editor: ObjectEditorState,
    onNameChange: (String) -> Unit,
    onPhotoPicked: (String, ByteArray) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    var pickPhoto by remember { mutableStateOf(false) }

    if (pickPhoto) {
        ImageFilePicker { fileName, bytes ->
            pickPhoto = false
            onPhotoPicked(fileName, bytes)
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isEdit) "Изменить объект" else "Добавить объект") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = onNameChange,
                    label = { Text("Название") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedButton(
                    onClick = { pickPhoto = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Icon(Icons.Default.Image, contentDescription = null)
                    Spacer(Modifier.width(8.dp))
                    Text(editor.photoFileName ?: "Выбрать фото")
                }
                Text(
                    text = "Фото можно не указывать",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
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
