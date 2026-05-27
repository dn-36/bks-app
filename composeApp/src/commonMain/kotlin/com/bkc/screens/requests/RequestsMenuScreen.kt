package com.bkc.screens.requests

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.MaterialRequest
import com.bkc.core.domain.MaterialRequestItemInput
import com.bkc.core.domain.Specification
import com.bkc.core.domain.repository.SpecificationRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.ListCard
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.mvi.MviScreenModel
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.core.presentation.requests.MaterialRequestsRealtimeStore
import com.bkc.core.presentation.utils.containsIgnoreCase
import com.bkc.screens.objects.ObjectsScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatform.getKoin

data class RequestsState(
    val searchQuery: String = "",
    val specificationsState: UiListState<Specification> = UiListState.Loading,
    val requestsState: UiListState<MaterialRequest> = UiListState.Loading,
    val canManageSpecifications: Boolean = false,
    val canCreateRequests: Boolean = false,
    val recipientEmail: String = "",
    val requestLines: List<RequestLineEditorState> = listOf(RequestLineEditorState()),
    val specificationEditor: SpecificationEditorState? = null,
    val error: String? = null,
    val success: String? = null
)

data class RequestLineEditorState(
    val specificationId: String = "",
    val quantity: String = ""
)

data class SpecificationEditorState(
    val specificationId: String? = null,
    val name: String = "",
    val unit: String = "",
    val initialQuantity: String = "",
    val remainingQuantity: String = ""
) {
    val isEdit: Boolean get() = specificationId != null
}

sealed interface RequestsIntent {
    data object LoadSpecifications : RequestsIntent
    data object LoadRequests : RequestsIntent
    data class SearchChanged(val value: String) : RequestsIntent
    data object ClearSearch : RequestsIntent
    data class RecipientEmailChanged(val value: String) : RequestsIntent
    data object AddRequestLine : RequestsIntent
    data class RemoveRequestLine(val index: Int) : RequestsIntent
    data class RequestLineSpecificationChanged(val index: Int, val specificationId: String) : RequestsIntent
    data class RequestLineQuantityChanged(val index: Int, val value: String) : RequestsIntent
    data object SubmitRequest : RequestsIntent
    data class DeleteMaterialRequest(val request: MaterialRequest) : RequestsIntent
    data object AddSpecification : RequestsIntent
    data class EditSpecification(val specification: Specification) : RequestsIntent
    data class DeleteSpecification(val specification: Specification) : RequestsIntent
    data class SpecificationNameChanged(val value: String) : RequestsIntent
    data class SpecificationUnitChanged(val value: String) : RequestsIntent
    data class SpecificationInitialQuantityChanged(val value: String) : RequestsIntent
    data class SpecificationRemainingQuantityChanged(val value: String) : RequestsIntent
    data object SaveSpecification : RequestsIntent
    data object DismissSpecificationEditor : RequestsIntent
}

class RequestsScreenModel :
    MviScreenModel<RequestsState, RequestsIntent, Unit>(RequestsState()),
    KoinComponent {

    private val specificationRepository: SpecificationRepository by inject()
    private val userSessionStore: UserSessionStore by inject()
    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var allSpecifications: List<Specification> = emptyList()
    private var allRequests: List<MaterialRequest> = emptyList()

    init {
        screenModelScope.launch { loadSession() }
        screenModelScope.launch { queryFlow.debounce(300).collect { applyFilters(it) } }
        screenModelScope.launch {
            MaterialRequestsRealtimeStore.changedObjectIds.collect { objectId ->
                if (objectId == userSessionStore.getUserOrNull()?.selectedObjectId.orEmpty()) {
                    loadSpecifications()
                    loadRequests()
                }
            }
        }
    }

    override fun onIntent(intent: RequestsIntent) {
        when (intent) {
            RequestsIntent.LoadSpecifications -> loadSpecifications()
            RequestsIntent.LoadRequests -> loadRequests()
            is RequestsIntent.SearchChanged -> {
                setState { it.copy(searchQuery = intent.value) }
                queryFlow.tryEmit(intent.value)
            }
            RequestsIntent.ClearSearch -> {
                setState { it.copy(searchQuery = "") }
                queryFlow.tryEmit("")
            }
            is RequestsIntent.RecipientEmailChanged -> setState {
                it.copy(recipientEmail = intent.value, error = null, success = null)
            }
            RequestsIntent.AddRequestLine -> setState {
                it.copy(requestLines = it.requestLines + RequestLineEditorState(), error = null, success = null)
            }
            is RequestsIntent.RemoveRequestLine -> removeRequestLine(intent.index)
            is RequestsIntent.RequestLineSpecificationChanged -> updateRequestLine(intent.index) {
                it.copy(specificationId = intent.specificationId)
            }
            is RequestsIntent.RequestLineQuantityChanged -> updateRequestLine(intent.index) {
                it.copy(quantity = intent.value)
            }
            RequestsIntent.SubmitRequest -> submitRequest()
            is RequestsIntent.DeleteMaterialRequest -> deleteMaterialRequest(intent.request)
            RequestsIntent.AddSpecification -> {
                if (state.value.canManageSpecifications) {
                    setState { it.copy(specificationEditor = SpecificationEditorState(), error = null, success = null) }
                }
            }
            is RequestsIntent.EditSpecification -> {
                if (state.value.canManageSpecifications) {
                    setState {
                        it.copy(
                            specificationEditor = SpecificationEditorState(
                                specificationId = intent.specification.id,
                                name = intent.specification.name,
                                unit = intent.specification.unit,
                                initialQuantity = intent.specification.initialQuantity.quantityText(),
                                remainingQuantity = intent.specification.remainingQuantity.quantityText()
                            ),
                            error = null,
                            success = null
                        )
                    }
                }
            }
            is RequestsIntent.DeleteSpecification -> {
                if (state.value.canManageSpecifications) deleteSpecification(intent.specification)
            }
            is RequestsIntent.SpecificationNameChanged -> setState { current ->
                current.copy(specificationEditor = current.specificationEditor?.copy(name = intent.value), error = null)
            }
            is RequestsIntent.SpecificationUnitChanged -> setState { current ->
                current.copy(specificationEditor = current.specificationEditor?.copy(unit = intent.value), error = null)
            }
            is RequestsIntent.SpecificationInitialQuantityChanged -> setState { current ->
                current.copy(specificationEditor = current.specificationEditor?.copy(initialQuantity = intent.value), error = null)
            }
            is RequestsIntent.SpecificationRemainingQuantityChanged -> setState { current ->
                current.copy(specificationEditor = current.specificationEditor?.copy(remainingQuantity = intent.value), error = null)
            }
            RequestsIntent.SaveSpecification -> saveSpecification()
            RequestsIntent.DismissSpecificationEditor -> setState {
                it.copy(specificationEditor = null, error = null)
            }
        }
    }

    private suspend fun loadSession() {
        val user = userSessionStore.getUserOrNull()
        setState {
            it.copy(
                canManageSpecifications = user?.status == "ADMINISTRATOR",
                canCreateRequests = user?.status == "ADMINISTRATOR" || user?.status == "FOREMAN",
                recipientEmail = user?.materialRequestEmail.orEmpty()
            )
        }
    }

    private fun loadSpecifications() {
        screenModelScope.launch {
            setState { it.copy(specificationsState = UiListState.Loading, error = null) }
            runCatching { specificationRepository.getSpecifications() }
                .onSuccess {
                    allSpecifications = it
                    applySpecificationFilter(state.value.searchQuery)
                }
                .onFailure { e ->
                    setState { it.copy(specificationsState = UiListState.Error(e.message ?: "Ошибка загрузки спецификации")) }
                }
        }
    }

    private fun loadRequests() {
        screenModelScope.launch {
            setState { it.copy(requestsState = UiListState.Loading, error = null) }
            runCatching { specificationRepository.getMaterialRequests() }
                .onSuccess {
                    allRequests = it
                    applyRequestsFilter(state.value.searchQuery)
                }
                .onFailure { e ->
                    setState { it.copy(requestsState = UiListState.Error(e.message ?: "Ошибка загрузки заявок")) }
                }
        }
    }

    private fun submitRequest() {
        if (!state.value.canCreateRequests) return
        val email = state.value.recipientEmail.trim()
        if (!email.matches(Regex("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$"))) {
            setState { it.copy(error = "Введите корректный email получателя", success = null) }
            return
        }

        val items = mutableListOf<MaterialRequestItemInput>()
        state.value.requestLines.forEach { line ->
            val hasAnyValue = line.specificationId.isNotBlank() || line.quantity.isNotBlank()
            if (hasAnyValue) {
                val quantity = line.quantity.toQuantityOrNull()
                if (line.specificationId.isBlank() || quantity == null || quantity <= 0.0) {
                    setState { it.copy(error = "Выберите материал и укажите количество больше нуля", success = null) }
                    return
                }
                items += MaterialRequestItemInput(line.specificationId, quantity)
            }
        }
        if (items.isEmpty()) {
            setState { it.copy(error = "Добавьте материалы в заявку", success = null) }
            return
        }

        screenModelScope.launch {
            runCatching { specificationRepository.submitMaterialRequest(email, items) }
                .onSuccess { request ->
                    saveMaterialRequestEmail(email)
                    allRequests = listOf(request) + allRequests
                    loadSpecifications()
                    setState {
                        it.copy(
                            requestLines = listOf(RequestLineEditorState()),
                            error = null,
                            success = request.emailStatus.successMessage()
                        )
                    }
                }
                .onFailure { e ->
                    setState { it.copy(error = e.message ?: "Ошибка отправки заявки", success = null) }
                }
        }
    }

    private suspend fun saveMaterialRequestEmail(email: String) {
        val user = userSessionStore.getUserOrNull() ?: return
        userSessionStore.saveUser(user.copy(materialRequestEmail = email))
    }

    private fun saveSpecification() {
        val editor = state.value.specificationEditor ?: return
        val initialQuantity = editor.initialQuantity.toQuantityOrNull()
        val remainingQuantity = editor.remainingQuantity.toQuantityOrNull() ?: initialQuantity

        if (editor.name.trim().isBlank() || initialQuantity == null || remainingQuantity == null) {
            setState { it.copy(error = "Заполните наименование и количество", success = null) }
            return
        }
        if (initialQuantity <= 0.0 || remainingQuantity < 0.0 || remainingQuantity > initialQuantity) {
            setState { it.copy(error = "Остаток должен быть от 0 до планового количества", success = null) }
            return
        }

        screenModelScope.launch {
            runCatching {
                if (editor.isEdit) {
                    specificationRepository.updateSpecification(
                        id = editor.specificationId.orEmpty(),
                        name = editor.name.trim(),
                        unit = editor.unit.trim(),
                        initialQuantity = initialQuantity,
                        remainingQuantity = remainingQuantity
                    )
                } else {
                    specificationRepository.addSpecification(
                        name = editor.name.trim(),
                        unit = editor.unit.trim(),
                        initialQuantity = initialQuantity,
                        remainingQuantity = remainingQuantity
                    )
                }
            }.onSuccess {
                setState { it.copy(specificationEditor = null, error = null, success = null) }
                loadSpecifications()
            }.onFailure { e ->
                setState { it.copy(error = e.message ?: "Ошибка сохранения материала", success = null) }
            }
        }
    }

    private fun deleteSpecification(specification: Specification) {
        screenModelScope.launch {
            runCatching { specificationRepository.deleteSpecification(specification.id) }
                .onSuccess { loadSpecifications() }
                .onFailure { e -> setState { it.copy(error = e.message ?: "Ошибка удаления материала") } }
        }
    }

    private fun deleteMaterialRequest(request: MaterialRequest) {
        screenModelScope.launch {
            runCatching { specificationRepository.deleteMaterialRequest(request.id) }
                .onSuccess {
                    allRequests = allRequests.filterNot { it.id == request.id }
                    applyRequestsFilter(state.value.searchQuery)
                    setState { it.copy(error = null, success = "Заявка удалена") }
                }
                .onFailure { e ->
                    setState { it.copy(error = e.message ?: "Ошибка удаления заявки", success = null) }
                }
        }
    }

    private fun removeRequestLine(index: Int) {
        val current = state.value.requestLines
        if (current.size <= 1 || index !in current.indices) return
        setState { it.copy(requestLines = current.filterIndexed { i, _ -> i != index }, error = null, success = null) }
    }

    private fun updateRequestLine(index: Int, transform: (RequestLineEditorState) -> RequestLineEditorState) {
        val current = state.value.requestLines
        if (index !in current.indices) return
        setState {
            it.copy(
                requestLines = current.mapIndexed { i, line -> if (i == index) transform(line) else line },
                error = null,
                success = null
            )
        }
    }

    private fun applyFilters(query: String) {
        applySpecificationFilter(query)
        applyRequestsFilter(query)
    }

    private fun applySpecificationFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) allSpecifications else allSpecifications.filter {
            containsIgnoreCase(it.name, q) || containsIgnoreCase(it.unit, q)
        }
        setState {
            it.copy(
                specificationsState = when {
                    allSpecifications.isEmpty() -> UiListState.Empty("Спецификация пока пустая")
                    filtered.isEmpty() -> UiListState.Empty("Ничего не найдено")
                    else -> UiListState.Content(filtered)
                }
            )
        }
    }

    private fun applyRequestsFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) allRequests else allRequests.filter { request ->
            containsIgnoreCase(request.senderName, q) ||
                containsIgnoreCase(request.senderEmail, q) ||
                containsIgnoreCase(request.recipientEmail, q) ||
                request.items.any { containsIgnoreCase(it.specificationName, q) }
        }
        setState {
            it.copy(
                requestsState = when {
                    allRequests.isEmpty() -> UiListState.Empty("Заявок пока нет")
                    filtered.isEmpty() -> UiListState.Empty("Ничего не найдено")
                    else -> UiListState.Content(filtered)
                }
            )
        }
    }
}

class RequestsMenuScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()
        var query by remember { mutableStateOf("") }

        val all = listOf("Создать заявку", "Предыдущие заявки")
        val items = all.filter {
            val q = query.trim()
            q.isEmpty() || it.lowercase().contains(q.lowercase())
        }

        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                organization = appState.organizationName,
                objectName = appState.selectedObjectName,
                searchQuery = query,
                onSearchChange = { query = it },
                onClear = { query = "" },
                onBackClick = if (showBackButton) {
                    { navigator?.pop() }
                } else {
                    null
                },
                onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
            )
            Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (items.isEmpty()) {
                    EmptyState("Ничего не найдено")
                } else {
                    items.forEach { title ->
                        ListCard(
                            title = title,
                            onClick = {
                                when (title) {
                                    "Создать заявку" -> navigator?.push(CreateMaterialRequestScreen(showBackButton = true))
                                    "Предыдущие заявки" -> navigator?.push(MaterialRequestsHistoryScreen(showBackButton = true))
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

class SpecificationsScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<RequestsScreenModel>()
        val state by vm.state.collectAsState()
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()
        var requestToDelete by remember { mutableStateOf<MaterialRequest?>(null) }

        LaunchedEffect(Unit) {
            vm.onIntent(RequestsIntent.LoadSpecifications)
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = state.searchQuery,
                    onSearchChange = { vm.onIntent(RequestsIntent.SearchChanged(it)) },
                    onClear = { vm.onIntent(RequestsIntent.ClearSearch) },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            },
            floatingActionButton = {
                if (state.canManageSpecifications) {
                    FloatingActionButton(onClick = { vm.onIntent(RequestsIntent.AddSpecification) }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить материал")
                    }
                }
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScreenMessages(state)
                Text(
                    text = "Спецификация",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                when (val ls = state.specificationsState) {
                    UiListState.Loading -> LoadingState()
                    is UiListState.Empty -> EmptyState(ls.message)
                    is UiListState.Error -> EmptyState(ls.message)
                    is UiListState.Content -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ls.items, key = { it.id }) { specification ->
                            SpecificationCard(
                                specification = specification,
                                canManage = state.canManageSpecifications,
                                onEdit = { vm.onIntent(RequestsIntent.EditSpecification(specification)) },
                                onDelete = { vm.onIntent(RequestsIntent.DeleteSpecification(specification)) }
                            )
                        }
                    }
                }
            }
        }

        state.specificationEditor?.let { editor ->
            SpecificationEditorDialog(
                editor = editor,
                onNameChange = { vm.onIntent(RequestsIntent.SpecificationNameChanged(it)) },
                onUnitChange = { vm.onIntent(RequestsIntent.SpecificationUnitChanged(it)) },
                onInitialQuantityChange = { vm.onIntent(RequestsIntent.SpecificationInitialQuantityChanged(it)) },
                onRemainingQuantityChange = { vm.onIntent(RequestsIntent.SpecificationRemainingQuantityChanged(it)) },
                onDismiss = { vm.onIntent(RequestsIntent.DismissSpecificationEditor) },
                onSave = { vm.onIntent(RequestsIntent.SaveSpecification) }
            )
        }
    }
}

class CreateMaterialRequestScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<RequestsScreenModel>()
        val state by vm.state.collectAsState()
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()

        LaunchedEffect(Unit) {
            vm.onIntent(RequestsIntent.LoadSpecifications)
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = state.searchQuery,
                    onSearchChange = { vm.onIntent(RequestsIntent.SearchChanged(it)) },
                    onClear = { vm.onIntent(RequestsIntent.ClearSearch) },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScreenMessages(state)
                Text(
                    text = "Создать заявку",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                if (!state.canCreateRequests) {
                    EmptyState("Создавать заявки может только прораб или администратор")
                    return@Column
                }

                val specifications = (state.specificationsState as? UiListState.Content)?.items.orEmpty()
                when (state.specificationsState) {
                    UiListState.Loading -> LoadingState()
                    is UiListState.Empty -> EmptyState("Сначала администратор должен заполнить спецификацию")
                    is UiListState.Error -> EmptyState((state.specificationsState as UiListState.Error).message)
                    is UiListState.Content -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        item {
                            OutlinedTextField(
                                value = state.recipientEmail,
                                onValueChange = { vm.onIntent(RequestsIntent.RecipientEmailChanged(it)) },
                                label = { Text("Email получателя") },
                                singleLine = true,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                        itemsIndexed(state.requestLines) { index, line ->
                            MaterialRequestLineEditor(
                                index = index,
                                line = line,
                                specifications = specifications,
                                canRemove = state.requestLines.size > 1,
                                onSpecificationChange = {
                                    vm.onIntent(RequestsIntent.RequestLineSpecificationChanged(index, it))
                                },
                                onQuantityChange = {
                                    vm.onIntent(RequestsIntent.RequestLineQuantityChanged(index, it))
                                },
                                onRemove = { vm.onIntent(RequestsIntent.RemoveRequestLine(index)) }
                            )
                        }
                        item {
                            OutlinedButton(
                                onClick = { vm.onIntent(RequestsIntent.AddRequestLine) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Icon(Icons.Default.Add, contentDescription = null)
                                Spacer(Modifier.width(8.dp))
                                Text("Добавить материал")
                            }
                        }
                        item {
                            Button(
                                onClick = { vm.onIntent(RequestsIntent.SubmitRequest) },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Отправить заявку")
                            }
                        }
                    }
                }
            }
        }
    }
}

class MaterialRequestsHistoryScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<RequestsScreenModel>()
        val state by vm.state.collectAsState()
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()
        var requestToDelete by remember { mutableStateOf<MaterialRequest?>(null) }

        LaunchedEffect(Unit) {
            vm.onIntent(RequestsIntent.LoadRequests)
        }

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = state.searchQuery,
                    onSearchChange = { vm.onIntent(RequestsIntent.SearchChanged(it)) },
                    onClear = { vm.onIntent(RequestsIntent.ClearSearch) },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().padding(padding)) {
                ScreenMessages(state)
                Text(
                    text = "Предыдущие заявки",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
                )
                when (val ls = state.requestsState) {
                    UiListState.Loading -> LoadingState()
                    is UiListState.Empty -> EmptyState(ls.message)
                    is UiListState.Error -> EmptyState(ls.message)
                    is UiListState.Content -> LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(ls.items, key = { it.id }) { request ->
                            MaterialRequestCard(
                                request = request,
                                onDelete = { requestToDelete = request }
                            )
                        }
                    }
                }
            }
        }

        requestToDelete?.let { request ->
            AlertDialog(
                onDismissRequest = { requestToDelete = null },
                title = { Text("Удалить заявку?") },
                text = { Text("Заявка будет удалена из истории.") },
                confirmButton = {
                    Button(
                        onClick = {
                            requestToDelete = null
                            vm.onIntent(RequestsIntent.DeleteMaterialRequest(request))
                        }
                    ) {
                        Text("Удалить")
                    }
                },
                dismissButton = {
                    TextButton(onClick = { requestToDelete = null }) {
                        Text("Отмена")
                    }
                }
            )
        }
    }
}

@Composable
private fun ScreenMessages(state: RequestsState) {
    state.error?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
    state.success?.let {
        Text(
            text = it,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
    }
}

@Composable
private fun SpecificationCard(
    specification: Specification,
    canManage: Boolean,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    val progress = (specification.remainingQuantity / specification.initialQuantity).coerceIn(0.0, 1.0)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        text = specification.name,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${specification.remainingQuantity.quantityText()}/${specification.initialQuantity.quantityText()} ${specification.unit}".trimEnd(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (canManage) {
                    IconButton(onClick = onEdit) {
                        Icon(Icons.Default.Edit, contentDescription = "Изменить материал")
                    }
                    IconButton(onClick = onDelete) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить материал")
                    }
                }
            }
            LinearProgressIndicator(
                progress = { progress.toFloat() },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MaterialRequestLineEditor(
    index: Int,
    line: RequestLineEditorState,
    specifications: List<Specification>,
    canRemove: Boolean,
    onSpecificationChange: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selected = specifications.firstOrNull { it.id == line.specificationId }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Позиция ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить позицию")
                    }
                }
            }
            Box {
                OutlinedButton(
                    onClick = { expanded = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = selected?.let {
                            "${it.name} · ${it.remainingQuantity.quantityText()} ${it.unit}".trimEnd()
                        } ?: "Выберите материал",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                DropdownMenu(
                    expanded = expanded,
                    onDismissRequest = { expanded = false }
                ) {
                    specifications.forEach { specification ->
                        DropdownMenuItem(
                            text = {
                                Text(
                                    text = "${specification.name} · остаток ${specification.remainingQuantity.quantityText()} ${specification.unit}".trimEnd(),
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            },
                            onClick = {
                                expanded = false
                                onSpecificationChange(specification.id)
                            }
                        )
                    }
                }
            }
            OutlinedTextField(
                value = line.quantity,
                onValueChange = onQuantityChange,
                label = { Text("Количество") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun MaterialRequestCard(
    request: MaterialRequest,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Заявка от ${request.createdAtMillis.dateTimeText()}",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить заявку")
                }
            }
            Text(
                text = "Отправитель: ${request.senderName} (${request.senderEmail})",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Получатель: ${request.recipientEmail} · ${request.emailStatus.statusTitle()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            request.items.forEach { item ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(
                        text = item.specificationName,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = "${item.quantity.quantityText()} ${item.unit}".trimEnd(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }
}

@Composable
private fun SpecificationEditorDialog(
    editor: SpecificationEditorState,
    onNameChange: (String) -> Unit,
    onUnitChange: (String) -> Unit,
    onInitialQuantityChange: (String) -> Unit,
    onRemainingQuantityChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isEdit) "Изменить материал" else "Добавить материал") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editor.name,
                    onValueChange = onNameChange,
                    label = { Text("Наименование") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editor.unit,
                    onValueChange = onUnitChange,
                    label = { Text("Единица измерения") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editor.initialQuantity,
                    onValueChange = onInitialQuantityChange,
                    label = { Text("Плановое количество") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editor.remainingQuantity,
                    onValueChange = onRemainingQuantityChange,
                    label = { Text("Остаток") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
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

private fun String.toQuantityOrNull(): Double? =
    trim().replace(',', '.').toDoubleOrNull()

private fun Double.quantityText(): String {
    val whole = toLong()
    return if (kotlin.math.abs(this - whole) < 0.000_001) whole.toString() else toString()
}

private fun Long.dateTimeText(): String {
    val dateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dateTime.dayOfMonth.twoDigits()}.${dateTime.monthNumber.twoDigits()}.${dateTime.year} " +
        "${dateTime.hour.twoDigits()}:${dateTime.minute.twoDigits()}"
}

private fun Int.twoDigits(): String =
    if (this < 10) "0$this" else toString()

private fun String.statusTitle(): String =
    when (this) {
        "SENT" -> "письмо отправлено"
        "NOT_CONFIGURED" -> "почта не настроена"
        "FAILED" -> "ошибка письма"
        else -> "ожидает отправки"
    }

private fun String.successMessage(): String =
    when (this) {
        "SENT" -> "Заявка отправлена, остатки спецификации обновлены"
        "NOT_CONFIGURED" -> "Заявка сохранена, остатки обновлены. SMTP на сервере не настроен"
        "FAILED" -> "Заявка сохранена, остатки обновлены, но письмо не отправилось"
        else -> "Заявка сохранена, остатки спецификации обновлены"
    }
