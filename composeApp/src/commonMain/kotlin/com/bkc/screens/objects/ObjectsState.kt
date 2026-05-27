package com.bkc.screens.objects

import cafe.adriel.voyager.core.model.screenModelScope
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.WorkObject
import com.bkc.core.domain.repository.ObjectRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.mvi.MviScreenModel
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.core.presentation.utils.containsIgnoreCase
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

data class ObjectsState(
    val searchQuery: String = "",
    val listState: UiListState<WorkObject> = UiListState.Loading,
    val canManageObjects: Boolean = false,
    val editor: ObjectEditorState? = null,
    val error: String? = null
)

data class ObjectEditorState(
    val objectId: String? = null,
    val name: String = "",
    val photoFileName: String? = null,
    val photoBytes: ByteArray? = null
) {
    val isEdit: Boolean get() = objectId != null
}

sealed interface ObjectsIntent {
    data object Load : ObjectsIntent
    data class SearchChanged(val value: String) : ObjectsIntent
    data object ClearSearch : ObjectsIntent
    data class ClickObject(val obj: WorkObject) : ObjectsIntent
    data object AddObject : ObjectsIntent
    data class EditObject(val obj: WorkObject) : ObjectsIntent
    data class DeleteObject(val obj: WorkObject) : ObjectsIntent
    data class ObjectNameChanged(val value: String) : ObjectsIntent
    data class ObjectPhotoPicked(val fileName: String, val bytes: ByteArray) : ObjectsIntent
    data object SaveObject : ObjectsIntent
    data object DismissEditor : ObjectsIntent
}

sealed interface ObjectsEffect {
    data object Selected : ObjectsEffect
}

class ObjectsScreenModel : MviScreenModel<ObjectsState, ObjectsIntent, ObjectsEffect>(ObjectsState()),
    KoinComponent {

    private val objectRepository: ObjectRepository by inject()
    private val appStateStore: AppStateStore by inject()
    private val userSessionStore: UserSessionStore by inject()

    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var all: List<WorkObject> = emptyList()

    init {
        screenModelScope.launch { loadPermissions() }
        screenModelScope.launch { onIntent(ObjectsIntent.Load) }
        screenModelScope.launch { queryFlow.debounce(300).collect { applyFilter(it) } }
    }

    override fun onIntent(intent: ObjectsIntent) {
        when (intent) {
            ObjectsIntent.Load -> load()
            is ObjectsIntent.SearchChanged -> {
                setState { it.copy(searchQuery = intent.value) }
                queryFlow.tryEmit(intent.value)
            }
            ObjectsIntent.ClearSearch -> {
                setState { it.copy(searchQuery = "") }
                queryFlow.tryEmit("")
            }
            is ObjectsIntent.ClickObject -> selectObject(intent.obj)
            ObjectsIntent.AddObject -> {
                if (state.value.canManageObjects) {
                    setState { it.copy(editor = ObjectEditorState(), error = null) }
                }
            }
            is ObjectsIntent.EditObject -> {
                if (state.value.canManageObjects) {
                    setState {
                        it.copy(
                            editor = ObjectEditorState(
                                objectId = intent.obj.id,
                                name = intent.obj.name
                            ),
                            error = null
                        )
                    }
                }
            }
            is ObjectsIntent.DeleteObject -> {
                if (state.value.canManageObjects) deleteObject(intent.obj)
            }
            is ObjectsIntent.ObjectNameChanged -> {
                setState { current ->
                    current.copy(editor = current.editor?.copy(name = intent.value), error = null)
                }
            }
            is ObjectsIntent.ObjectPhotoPicked -> {
                setState { current ->
                    current.copy(
                        editor = current.editor?.copy(
                            photoFileName = intent.fileName,
                            photoBytes = intent.bytes
                        ),
                        error = null
                    )
                }
            }
            ObjectsIntent.SaveObject -> saveObject()
            ObjectsIntent.DismissEditor -> setState { it.copy(editor = null, error = null) }
        }
    }

    private fun loadPermissions() {
        screenModelScope.launch {
            val user = userSessionStore.getUserOrNull()
            setState { it.copy(canManageObjects = user?.status == "ADMINISTRATOR") }
        }
    }

    private fun load() {
        screenModelScope.launch {
            setState { it.copy(listState = UiListState.Loading, error = null) }
            runCatching { objectRepository.getObjects() }
                .onSuccess {
                    all = it
                    applyFilter(state.value.searchQuery)
                }
                .onFailure { e ->
                    setState { it.copy(listState = UiListState.Error(e.message ?: "Ошибка")) }
                }
        }
    }

    private fun selectObject(obj: WorkObject) {
        screenModelScope.launch {
            val user = userSessionStore.getUserOrNull() ?: return@launch
            userSessionStore.saveUser(
                user.copy(
                    selectedObjectId = obj.id,
                    selectedObjectName = obj.name,
                    selectedObjectPhotoUrl = obj.photoUrl
                )
            )
            appStateStore.selectObject(obj)
            sendEffect(ObjectsEffect.Selected)
        }
    }

    private fun saveObject() {
        val editor = state.value.editor ?: return
        if (editor.name.isBlank()) {
            setState { it.copy(error = "Заполните название объекта") }
            return
        }

        screenModelScope.launch {
            runCatching {
                if (editor.isEdit) {
                    objectRepository.updateObject(
                        id = editor.objectId.orEmpty(),
                        name = editor.name.trim(),
                        photoFileName = editor.photoFileName,
                        photoBytes = editor.photoBytes
                    )
                } else {
                    objectRepository.addObject(
                        name = editor.name.trim(),
                        photoFileName = editor.photoFileName,
                        photoBytes = editor.photoBytes
                    )
                }
            }.onSuccess {
                setState { it.copy(editor = null, error = null) }
                load()
            }.onFailure { e ->
                setState { it.copy(error = e.message ?: "Ошибка сохранения объекта") }
            }
        }
    }

    private fun deleteObject(obj: WorkObject) {
        screenModelScope.launch {
            runCatching { objectRepository.deleteObject(obj.id) }
                .onSuccess { load() }
                .onFailure { e -> setState { it.copy(error = e.message ?: "Ошибка удаления объекта") } }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) all else all.filter { containsIgnoreCase(it.name, q) }

        setState {
            it.copy(
                listState = when {
                    all.isEmpty() -> UiListState.Empty("Список пуст")
                    filtered.isEmpty() -> UiListState.Empty("Ничего не найдено")
                    else -> UiListState.Content(filtered)
                }
            )
        }
    }
}
