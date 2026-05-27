package com.bkc.screens.schedules

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.model.screenModelScope
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.PlatformUser
import com.bkc.core.domain.ScheduleProgress
import com.bkc.core.domain.ScheduleTask
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ScheduleRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.mvi.MviScreenModel
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.core.presentation.utils.containsIgnoreCase
import com.bkc.core.presentation.utils.toUserStatusTitle
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.shit.ShiftTaskScreen
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatform.getKoin

private val scheduleColors = listOf(
    "#4F8EF7",
    "#2EAD71",
    "#F6A609",
    "#E05757",
    "#7C5CE6",
    "#00A6A6",
    "#D94FA3",
    "#8A5A44",
    "#1F9D55",
    "#0F766E",
    "#2563EB",
    "#9333EA",
    "#DC2626",
    "#EA580C",
    "#CA8A04",
    "#65A30D",
    "#0891B2",
    "#475569"
)

enum class ScheduleTaskFilter {
    Incomplete,
    Completed
}

data class SchedulesState(
    val searchQuery: String = "",
    val listState: UiListState<ScheduleTask> = UiListState.Loading,
    val visibleYear: Int = Clock.System.todayIn(TimeZone.currentSystemDefault()).year,
    val visibleMonth: Int = Clock.System.todayIn(TimeZone.currentSystemDefault()).monthNumber,
    val selectedTaskId: String? = null,
    val taskFilter: ScheduleTaskFilter = ScheduleTaskFilter.Incomplete,
    val currentUserId: String = "",
    val selectedWorkerId: String = "",
    val workers: List<ScheduleWorker> = emptyList(),
    val canManageDefinitions: Boolean = false,
    val canFillProgress: Boolean = false,
    val canViewTeamProgress: Boolean = false,
    val foremanName: String = "",
    val editor: ScheduleTaskEditorState? = null,
    val error: String? = null
)

data class ScheduleWorker(
    val id: String,
    val name: String,
    val status: String = ""
)

data class ScheduleTaskEditorState(
    val taskId: String? = null,
    val place: String = "",
    val workType: String = "",
    val color: String = scheduleColors.first()
) {
    val isEdit: Boolean get() = taskId != null
}

sealed interface SchedulesIntent {
    data class SearchChanged(val value: String) : SchedulesIntent
    data object ClearSearch : SchedulesIntent
    data object PreviousMonth : SchedulesIntent
    data object NextMonth : SchedulesIntent
    data class SelectWorker(val workerId: String) : SchedulesIntent
    data class SelectTaskFilter(val filter: ScheduleTaskFilter) : SchedulesIntent
    data class SelectTask(val task: ScheduleTask) : SchedulesIntent
    data class ToggleDate(val date: String) : SchedulesIntent
    data class ToggleDone(val task: ScheduleTask, val value: Boolean) : SchedulesIntent
    data object AddTask : SchedulesIntent
    data class EditTask(val task: ScheduleTask) : SchedulesIntent
    data class DeleteTask(val task: ScheduleTask) : SchedulesIntent
    data class PlaceChanged(val value: String) : SchedulesIntent
    data class WorkTypeChanged(val value: String) : SchedulesIntent
    data class ColorChanged(val value: String) : SchedulesIntent
    data object SaveTask : SchedulesIntent
    data object DismissEditor : SchedulesIntent
}

class SchedulesScreenModel :
    MviScreenModel<SchedulesState, SchedulesIntent, Unit>(SchedulesState()),
    KoinComponent {

    private val scheduleRepository: ScheduleRepository by inject()
    private val accountRepository: AccountRepository by inject()
    private val userSessionStore: UserSessionStore by inject()
    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)
    private var all: List<ScheduleTask> = emptyList()

    init {
        screenModelScope.launch { loadSession() }
        screenModelScope.launch {
            setState { it.copy(listState = UiListState.Loading) }
            scheduleRepository.observeTasks().collect { tasks ->
                all = tasks
                syncWorkersFromTasks()
                applyFilter(state.value.searchQuery)
                if (state.value.selectedTaskId == null && tasks.isNotEmpty()) {
                    setState { it.copy(selectedTaskId = tasks.first().id) }
                }
            }
        }
        screenModelScope.launch {
            queryFlow.debounce(300).collect { applyFilter(it) }
        }
    }

    override fun onIntent(intent: SchedulesIntent) {
        when (intent) {
            is SchedulesIntent.SearchChanged -> {
                setState { it.copy(searchQuery = intent.value) }
                queryFlow.tryEmit(intent.value)
            }
            SchedulesIntent.ClearSearch -> {
                setState { it.copy(searchQuery = "") }
                queryFlow.tryEmit("")
            }
            SchedulesIntent.PreviousMonth -> shiftMonth(-1)
            SchedulesIntent.NextMonth -> shiftMonth(1)
            is SchedulesIntent.SelectWorker -> setState { it.copy(selectedWorkerId = intent.workerId) }
            is SchedulesIntent.SelectTaskFilter -> selectTaskFilter(intent.filter)
            is SchedulesIntent.SelectTask -> setState { it.copy(selectedTaskId = intent.task.id) }
            is SchedulesIntent.ToggleDate -> toggleDate(intent.date)
            is SchedulesIntent.ToggleDone -> {
                if (canEditSelectedProgress()) {
                    saveProgress(intent.task, intent.task.progress?.workDates.orEmpty(), intent.value)
                }
            }
            SchedulesIntent.AddTask -> {
                if (state.value.canManageDefinitions) {
                    setState { it.copy(editor = ScheduleTaskEditorState(), error = null) }
                }
            }
            is SchedulesIntent.EditTask -> {
                if (state.value.canManageDefinitions) {
                    setState {
                        it.copy(
                            editor = ScheduleTaskEditorState(
                                taskId = intent.task.id,
                                place = intent.task.place,
                                workType = intent.task.workType,
                                color = intent.task.color
                            ),
                            error = null
                        )
                    }
                }
            }
            is SchedulesIntent.DeleteTask -> {
                if (state.value.canManageDefinitions) deleteTask(intent.task)
            }
            is SchedulesIntent.PlaceChanged -> {
                setState { current -> current.copy(editor = current.editor?.copy(place = intent.value), error = null) }
            }
            is SchedulesIntent.WorkTypeChanged -> {
                setState { current -> current.copy(editor = current.editor?.copy(workType = intent.value), error = null) }
            }
            is SchedulesIntent.ColorChanged -> {
                setState { current -> current.copy(editor = current.editor?.copy(color = intent.value), error = null) }
            }
            SchedulesIntent.SaveTask -> saveTask()
            SchedulesIntent.DismissEditor -> setState { it.copy(editor = null, error = null) }
        }
    }

    private suspend fun loadSession() {
        val user = userSessionStore.getUserOrNull()
        val userName = listOfNotNull(user?.firstName, user?.lastName).joinToString(" ").trim()
        val currentWorker = user?.uid?.takeIf { it.isNotBlank() }?.let {
            ScheduleWorker(
                id = it,
                name = userName.ifBlank { user.nickname.ifBlank { user.email.orEmpty().ifBlank { "Текущий пользователь" } } },
                status = user.status
            )
        }
        val canViewTeamProgress = user?.status == "FOREMAN" || user?.status == "ADMINISTRATOR"
        setState { current ->
            current.copy(
                currentUserId = user?.uid.orEmpty(),
                selectedWorkerId = current.selectedWorkerId.ifBlank { user?.uid.orEmpty() },
                workers = mergeScheduleWorkers(current.workers + listOfNotNull(currentWorker) + progressWorkers(all)),
                canManageDefinitions = user?.status == "ADMINISTRATOR",
                canFillProgress = canViewTeamProgress,
                canViewTeamProgress = canViewTeamProgress,
                foremanName = userName
            )
        }
        syncWorkersFromTasks()
        if (canViewTeamProgress) {
            loadWorkers(adminMode = user.status == "ADMINISTRATOR")
        }
    }

    private suspend fun loadWorkers(adminMode: Boolean) {
        runCatching { accountRepository.listUsers("", "ACTIVE", adminMode = adminMode) }
            .onSuccess { users ->
                setState { current ->
                    val workers = mergeScheduleWorkers(
                        current.workers + users.map { it.toScheduleWorker() } + progressWorkers(all)
                    )
                    current.copy(
                        workers = workers,
                        selectedWorkerId = current.selectedWorkerId.ifBlank {
                            current.currentUserId.ifBlank { workers.firstOrNull()?.id.orEmpty() }
                        }
                    )
                }
            }
            .onFailure { e ->
                setState { it.copy(error = e.message ?: "Ошибка загрузки сотрудников") }
            }
    }

    private fun syncWorkersFromTasks() {
        if (!state.value.canViewTeamProgress) return
        setState { current ->
            val workers = mergeScheduleWorkers(current.workers + progressWorkers(all))
            current.copy(
                workers = workers,
                selectedWorkerId = current.selectedWorkerId.ifBlank {
                    current.currentUserId.ifBlank { workers.firstOrNull()?.id.orEmpty() }
                }
            )
        }
    }

    private fun canEditSelectedProgress(): Boolean =
        state.value.canFillProgress && state.value.selectedWorkerId == state.value.currentUserId

    private fun shiftMonth(delta: Int) {
        val current = state.value
        val nextMonth = current.visibleMonth + delta
        val year = when {
            nextMonth < 1 -> current.visibleYear - 1
            nextMonth > 12 -> current.visibleYear + 1
            else -> current.visibleYear
        }
        val month = when {
            nextMonth < 1 -> 12
            nextMonth > 12 -> 1
            else -> nextMonth
        }
        setState { it.copy(visibleYear = year, visibleMonth = month) }
    }

    private fun selectTaskFilter(filter: ScheduleTaskFilter) {
        val current = state.value
        val tasks = (current.listState as? UiListState.Content)?.items.orEmpty()
            .map { it.withProgressFor(current.selectedWorkerId) }
        val filteredTasks = when (filter) {
            ScheduleTaskFilter.Incomplete -> tasks.filter { it.progress?.isDone != true }
            ScheduleTaskFilter.Completed -> tasks.filter { it.progress?.isDone == true }
        }
        setState {
            it.copy(
                taskFilter = filter,
                selectedTaskId = filteredTasks.firstOrNull()?.id ?: it.selectedTaskId
            )
        }
    }

    private fun toggleDate(date: String) {
        if (!canEditSelectedProgress()) return
        val selected = all.firstOrNull { it.id == state.value.selectedTaskId } ?: return
        val currentDates = selected.progress?.workDates.orEmpty()
        val nextDates = if (date in currentDates) {
            currentDates - date
        } else {
            (currentDates + date).distinct().sorted()
        }
        saveProgress(selected, nextDates, selected.progress?.isDone == true)
    }

    private fun saveProgress(task: ScheduleTask, dates: List<String>, isDone: Boolean) {
        val previous = all
        updateTaskProgressLocally(task, dates, isDone)
        screenModelScope.launch {
            runCatching { scheduleRepository.saveProgress(task, dates, isDone) }
                .onFailure { e ->
                    all = previous
                    applyFilter(state.value.searchQuery)
                    setState { it.copy(error = e.message ?: "Ошибка сохранения графика") }
                }
        }
    }

    private fun updateTaskProgressLocally(task: ScheduleTask, dates: List<String>, isDone: Boolean) {
        val progress = task.progress ?: ScheduleProgress(
            taskId = task.id,
            userId = state.value.currentUserId,
            foremanName = state.value.foremanName,
            workDates = emptyList(),
            isDone = false,
            updatedAtMillis = 0L
        )
        val updatedProgress = progress.copy(
            userId = progress.userId.ifBlank { state.value.currentUserId },
            foremanName = progress.foremanName.ifBlank { state.value.foremanName },
            workDates = dates,
            isDone = isDone,
            updatedAtMillis = Clock.System.now().toEpochMilliseconds()
        )
        all = all.map {
            if (it.id == task.id) {
                it.copy(
                    progress = updatedProgress,
                    progresses = (it.allProgresses.filterNot { item ->
                        item.userId.isBlank() || item.userId == updatedProgress.userId
                    } + updatedProgress)
                )
            } else {
                it
            }
        }
        syncWorkersFromTasks()
        applyFilter(state.value.searchQuery)
    }

    private fun saveTask() {
        val editor = state.value.editor ?: return
        val place = editor.place.trim()
        val workType = editor.workType.trim()
        if (place.isBlank() || workType.isBlank()) {
            setState { it.copy(error = "Заполните место и вид работ") }
            return
        }

        screenModelScope.launch {
            runCatching {
                if (editor.isEdit) {
                    scheduleRepository.updateTask(editor.taskId.orEmpty(), place, workType, editor.color)
                } else {
                    scheduleRepository.addTask(place, workType, editor.color)
                }
            }.onSuccess {
                setState { it.copy(editor = null, error = null) }
            }.onFailure { e ->
                setState { it.copy(error = e.message ?: "Ошибка сохранения задачи") }
            }
        }
    }

    private fun deleteTask(task: ScheduleTask) {
        screenModelScope.launch {
            runCatching { scheduleRepository.deleteTask(task.id) }
                .onFailure { e -> setState { it.copy(error = e.message ?: "Ошибка удаления задачи") } }
        }
    }

    private fun applyFilter(query: String) {
        val q = query.trim()
        val filtered = if (q.isEmpty()) all else all.filter {
            containsIgnoreCase(it.place, q) || containsIgnoreCase(it.workType, q)
        }
        setState {
            it.copy(
                listState = when {
                    all.isEmpty() -> UiListState.Empty("Задачи графика не созданы")
                    filtered.isEmpty() -> UiListState.Empty("Ничего не найдено")
                    else -> UiListState.Content(filtered)
                }
            )
        }
    }
}

class SchedulesScreen(
    private val showBackButton: Boolean = false
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<SchedulesScreenModel>()
        val state by vm.state.collectAsState()
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()

        Scaffold(
            topBar = {
                AppTopBar(
                    organization = appState.organizationName,
                    objectName = appState.selectedObjectName,
                    searchQuery = state.searchQuery,
                    onSearchChange = { vm.onIntent(SchedulesIntent.SearchChanged(it)) },
                    onClear = { vm.onIntent(SchedulesIntent.ClearSearch) },
                    onBackClick = if (showBackButton) {
                        { navigator?.pop() }
                    } else {
                        null
                    },
                    onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
                )
            },
            floatingActionButton = {
                if (state.canManageDefinitions) {
                    FloatingActionButton(onClick = { vm.onIntent(SchedulesIntent.AddTask) }) {
                        Icon(Icons.Default.Add, contentDescription = "Добавить задачу")
                    }
                }
            }
        ) { padding ->
            val tasks = when (val listState = state.listState) {
                is UiListState.Content -> listState.items
                else -> emptyList()
            }
            val selectedWorker = state.workers.firstOrNull { it.id == state.selectedWorkerId }
            val visibleTasks = tasks.map { it.withProgressFor(state.selectedWorkerId) }
            val canEditSelectedProgress = state.canFillProgress && state.selectedWorkerId == state.currentUserId
            val incompleteTasks = visibleTasks.filter { it.progress?.isDone != true }
            val completedTasks = visibleTasks.filter { it.progress?.isDone == true }
            val displayedTasks = when (state.taskFilter) {
                ScheduleTaskFilter.Incomplete -> incompleteTasks
                ScheduleTaskFilter.Completed -> completedTasks
            }
            val emptyTaskMessage = when (state.taskFilter) {
                ScheduleTaskFilter.Incomplete -> "Нет невыполненных задач"
                ScheduleTaskFilter.Completed -> "Нет выполненных задач"
            }

            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentPadding = PaddingValues(bottom = 16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                state.error?.let {
                    item {
                        Text(
                            text = it,
                            color = MaterialTheme.colorScheme.error,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }
                }

                if (state.canViewTeamProgress) {
                    item {
                        ScheduleWorkersPanel(
                            workers = state.workers,
                            selectedWorkerId = state.selectedWorkerId,
                            tasks = tasks,
                            onWorkerSelect = { vm.onIntent(SchedulesIntent.SelectWorker(it)) }
                        )
                    }
                }
                item {
                    ScheduleCalendar(
                        state = state,
                        tasks = visibleTasks,
                        canEditSelectedProgress = canEditSelectedProgress,
                        onPrevious = { vm.onIntent(SchedulesIntent.PreviousMonth) },
                        onNext = { vm.onIntent(SchedulesIntent.NextMonth) },
                        onDateClick = { vm.onIntent(SchedulesIntent.ToggleDate(it)) },
                        onDateLongClick = { date ->
                            val workerId = state.selectedWorkerId.ifBlank { state.currentUserId }
                            if (workerId.isNotBlank()) {
                                navigator?.push(
                                    ShiftTaskScreen(
                                        showBackButton = true,
                                        initialReportUserUid = workerId,
                                        initialReportDate = date
                                    )
                                )
                            }
                        }
                    )
                }
                item {
                    ScheduleTaskFilterButtons(
                        selectedFilter = state.taskFilter,
                        incompleteCount = incompleteTasks.size,
                        completedCount = completedTasks.size,
                        onSelect = { vm.onIntent(SchedulesIntent.SelectTaskFilter(it)) }
                    )
                }

                when (val listState = state.listState) {
                    UiListState.Loading -> item {
                        LoadingState(Modifier.fillMaxWidth().height(180.dp))
                    }
                    is UiListState.Empty -> item {
                        EmptyState(listState.message, Modifier.fillMaxWidth().height(180.dp))
                    }
                    is UiListState.Error -> item {
                        EmptyState(listState.message, Modifier.fillMaxWidth().height(180.dp))
                    }
                    is UiListState.Content -> {
                        if (state.canViewTeamProgress && selectedWorker != null) {
                            item {
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    ScheduleProgressSummary(
                                        worker = selectedWorker,
                                        tasks = visibleTasks,
                                        canEditProgress = canEditSelectedProgress
                                    )
                                }
                            }
                        }
                        if (displayedTasks.isEmpty()) {
                            item {
                                Text(
                                    text = emptyTaskMessage,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
                                )
                            }
                        } else {
                            items(displayedTasks, key = { it.id }) { task ->
                                Box(Modifier.padding(horizontal = 16.dp)) {
                                    ScheduleTaskCard(
                                        task = task,
                                        selected = task.id == state.selectedTaskId,
                                        canManageDefinitions = state.canManageDefinitions,
                                        canEditProgress = canEditSelectedProgress,
                                        workerName = selectedWorker?.name.orEmpty().ifBlank { state.foremanName },
                                        onSelect = { vm.onIntent(SchedulesIntent.SelectTask(task)) },
                                        onDoneChange = { vm.onIntent(SchedulesIntent.ToggleDone(task, it)) },
                                        onEdit = { vm.onIntent(SchedulesIntent.EditTask(task)) },
                                        onDelete = { vm.onIntent(SchedulesIntent.DeleteTask(task)) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        state.editor?.let { editor ->
            ScheduleTaskEditorDialog(
                editor = editor,
                onPlaceChange = { vm.onIntent(SchedulesIntent.PlaceChanged(it)) },
                onWorkTypeChange = { vm.onIntent(SchedulesIntent.WorkTypeChanged(it)) },
                onColorChange = { vm.onIntent(SchedulesIntent.ColorChanged(it)) },
                onDismiss = { vm.onIntent(SchedulesIntent.DismissEditor) },
                onSave = { vm.onIntent(SchedulesIntent.SaveTask) }
            )
        }
    }
}

@Composable
private fun ScheduleWorkersPanel(
    workers: List<ScheduleWorker>,
    selectedWorkerId: String,
    tasks: List<ScheduleTask>,
    onWorkerSelect: (String) -> Unit
) {
    if (workers.isEmpty()) return

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "Сотрудники",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f)
            )
            Text(
                text = workers.size.toString(),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            items(workers, key = { it.id }) { worker ->
                val workerTasks = tasks.map { it.withProgressFor(worker.id) }
                val completed = workerTasks.count { it.progress?.isDone == true }
                val planned = workerTasks.count { it.progress != null }
                WorkerButton(
                    worker = worker,
                    selected = worker.id == selectedWorkerId,
                    completed = completed,
                    planned = planned,
                    onClick = { onWorkerSelect(worker.id) }
                )
            }
        }
    }
}

@Composable
private fun WorkerButton(
    worker: ScheduleWorker,
    selected: Boolean,
    completed: Int,
    planned: Int,
    onClick: () -> Unit
) {
    val modifier = Modifier.height(52.dp).widthIn(min = 132.dp, max = 220.dp)
    val statusTitle = worker.status.takeIf { it.isNotBlank() }?.toUserStatusTitle()
    val progressText = listOfNotNull(statusTitle, "$completed/$planned").joinToString(" · ")
    val content: @Composable () -> Unit = {
        Column(horizontalAlignment = Alignment.Start) {
            Text(worker.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                text = progressText,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
    if (selected) {
        Button(onClick = onClick, modifier = modifier) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = modifier) { content() }
    }
}

@Composable
private fun ScheduleProgressSummary(
    worker: ScheduleWorker,
    tasks: List<ScheduleTask>,
    canEditProgress: Boolean
) {
    val plannedDays = tasks.sumOf { it.progress?.workDates.orEmpty().size }
    val completedTasks = tasks.count { it.progress?.isDone == true }
    val activeTasks = tasks.size - completedTasks
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 2.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        ScheduleMetric(
            label = if (canEditProgress) "Мой график" else "Просмотр",
            value = worker.name,
            modifier = Modifier.weight(1.6f)
        )
        ScheduleMetric(
            label = "В работе",
            value = activeTasks.toString(),
            modifier = Modifier.weight(1f)
        )
        ScheduleMetric(
            label = "Дней",
            value = plannedDays.toString(),
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScheduleMetric(
    label: String,
    value: String,
    modifier: Modifier = Modifier
) {
    Column(modifier = modifier) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        Text(
            text = value,
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.SemiBold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun ScheduleTaskFilterButtons(
    selectedFilter: ScheduleTaskFilter,
    incompleteCount: Int,
    completedCount: Int,
    onSelect: (ScheduleTaskFilter) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        ScheduleFilterButton(
            text = "Невыполненные ($incompleteCount)",
            selected = selectedFilter == ScheduleTaskFilter.Incomplete,
            onClick = { onSelect(ScheduleTaskFilter.Incomplete) },
            modifier = Modifier.weight(1f)
        )
        ScheduleFilterButton(
            text = "Выполненные ($completedCount)",
            selected = selectedFilter == ScheduleTaskFilter.Completed,
            onClick = { onSelect(ScheduleTaskFilter.Completed) },
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun ScheduleFilterButton(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val buttonModifier = modifier.height(44.dp)
    val content: @Composable () -> Unit = {
        Text(
            text = text,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
    if (selected) {
        Button(onClick = onClick, modifier = buttonModifier) { content() }
    } else {
        OutlinedButton(onClick = onClick, modifier = buttonModifier) { content() }
    }
}

@Composable
private fun ScheduleCalendar(
    state: SchedulesState,
    tasks: List<ScheduleTask>,
    canEditSelectedProgress: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onDateClick: (String) -> Unit,
    onDateLongClick: (String) -> Unit
) {
    val selectedTask = tasks.firstOrNull { it.id == state.selectedTaskId }
    val days = remember(state.visibleYear, state.visibleMonth) {
        calendarCells(state.visibleYear, state.visibleMonth)
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onPrevious) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Предыдущий месяц")
            }
            Text(
                text = "${monthName(state.visibleMonth)} ${state.visibleYear}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            IconButton(onClick = onNext) {
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "Следующий месяц")
            }
        }

        selectedTask?.let {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(
                    Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(colorFromHex(it.color))
                )
                Text(
                    text = "${it.place} · ${it.workType}",
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            listOf("Пн", "Вт", "Ср", "Чт", "Пт", "Сб", "Вс").forEach {
                Text(
                    text = it,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
            }
        }

        days.chunked(7).forEach { week ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                week.forEach { date ->
                    if (date == null) {
                        Spacer(Modifier.weight(1f).height(42.dp))
                    } else {
                        val dateText = date.toIsoString()
                        val markedTask = tasks.firstOrNull { dateText in it.progress?.workDates.orEmpty() }
                        val isSelectedTaskDate = selectedTask != null && dateText in selectedTask.progress?.workDates.orEmpty()
                        CalendarDay(
                            day = date.dayOfMonth.toString(),
                            color = markedTask?.color,
                            selected = isSelectedTaskDate,
                            enabled = canEditSelectedProgress && selectedTask != null,
                            longClickEnabled = state.canViewTeamProgress && state.selectedWorkerId.isNotBlank(),
                            onClick = { onDateClick(dateText) },
                            onLongClick = { onDateLongClick(dateText) },
                            modifier = Modifier.weight(1f)
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun CalendarDay(
    day: String,
    color: String?,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
    longClickEnabled: Boolean,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val background = color?.let { colorFromHex(it) } ?: MaterialTheme.colorScheme.surface
    val contentColor = if (color == null) {
        MaterialTheme.colorScheme.onSurface
    } else {
        Color.White
    }
    Box(
        modifier = modifier
            .height(42.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(background)
            .border(
                width = if (selected) 2.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
                shape = RoundedCornerShape(6.dp)
            )
            .then(
                if (enabled || longClickEnabled) {
                    Modifier.combinedClickable(
                        onClick = { if (enabled) onClick() },
                        onLongClick = if (longClickEnabled) onLongClick else null
                    )
                } else {
                    Modifier
                }
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(day, color = contentColor, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun ScheduleTaskCard(
    task: ScheduleTask,
    selected: Boolean,
    canManageDefinitions: Boolean,
    canEditProgress: Boolean,
    workerName: String,
    onSelect: () -> Unit,
    onDoneChange: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .border(
                width = if (selected) 2.dp else 0.dp,
                color = MaterialTheme.colorScheme.primary,
                shape = RoundedCornerShape(8.dp)
            ),
        shape = MaterialTheme.shapes.small,
        border = if (selected) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            Box(
                Modifier
                    .size(width = 8.dp, height = 64.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(colorFromHex(task.color))
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = task.place,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = task.workType,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                val name = task.progress?.foremanName?.ifBlank { workerName } ?: workerName
                if (name.isNotBlank()) {
                    Text(
                        text = "Сотрудник: $name",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                val dateCount = task.progress?.workDates.orEmpty().size
                if (dateCount > 0) {
                    Text(
                        text = "Дней в графике: $dateCount",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (canEditProgress) {
                Checkbox(
                    checked = task.progress?.isDone == true,
                    onCheckedChange = onDoneChange,
                    enabled = canEditProgress
                )
            }
            if (canManageDefinitions) {
                IconButton(onClick = onEdit) {
                    Icon(Icons.Default.Edit, contentDescription = "Изменить задачу")
                }
                IconButton(onClick = onDelete) {
                    Icon(Icons.Default.Delete, contentDescription = "Удалить задачу")
                }
            }
        }
    }
}

@Composable
private fun ScheduleTaskEditorDialog(
    editor: ScheduleTaskEditorState,
    onPlaceChange: (String) -> Unit,
    onWorkTypeChange: (String) -> Unit,
    onColorChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onSave: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (editor.isEdit) "Изменить задачу" else "Добавить задачу") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = editor.place,
                    onValueChange = onPlaceChange,
                    label = { Text("Место") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = editor.workType,
                    onValueChange = onWorkTypeChange,
                    label = { Text("Вид работ") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text("Цвет задачи", style = MaterialTheme.typography.labelMedium)
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    scheduleColors.chunked(6).forEach { rowColors ->
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            rowColors.forEach { color ->
                                Box(
                                    modifier = Modifier
                                        .size(34.dp)
                                        .clip(CircleShape)
                                        .background(colorFromHex(color))
                                        .border(
                                            width = if (editor.color == color) 3.dp else 1.dp,
                                            color = MaterialTheme.colorScheme.onSurface,
                                            shape = CircleShape
                                        )
                                        .clickable { onColorChange(color) }
                                )
                            }
                        }
                    }
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

private fun calendarCells(year: Int, month: Int): List<LocalDate?> {
    val first = LocalDate(year, month, 1)
    val leadingBlanks = first.dayOfWeek.ordinal
    val days = mutableListOf<LocalDate?>()
    repeat(leadingBlanks) { days += null }
    repeat(daysInMonth(year, month)) { index ->
        days += LocalDate(year, month, index + 1)
    }
    while (days.size % 7 != 0) days += null
    return days
}

private fun daysInMonth(year: Int, month: Int): Int =
    when (month) {
        1, 3, 5, 7, 8, 10, 12 -> 31
        4, 6, 9, 11 -> 30
        2 -> if (isLeapYear(year)) 29 else 28
        else -> 30
    }

private fun isLeapYear(year: Int): Boolean =
    (year % 4 == 0 && year % 100 != 0) || year % 400 == 0

private fun LocalDate.toIsoString(): String =
    "${year.toString().padStart(4, '0')}-${monthNumber.toString().padStart(2, '0')}-${dayOfMonth.toString().padStart(2, '0')}"

private fun monthName(month: Int): String =
    listOf(
        "Январь",
        "Февраль",
        "Март",
        "Апрель",
        "Май",
        "Июнь",
        "Июль",
        "Август",
        "Сентябрь",
        "Октябрь",
        "Ноябрь",
        "Декабрь"
    ).getOrElse(month - 1) { "" }

private fun colorFromHex(hex: String): Color {
    val normalized = hex.removePrefix("#")
    val value = normalized.toLongOrNull(16) ?: 0x4F8EF7
    return Color(0xFF000000 or value)
}

private fun ScheduleTask.withProgressFor(workerId: String): ScheduleTask =
    copy(progress = progressFor(workerId))

private fun progressWorkers(tasks: List<ScheduleTask>): List<ScheduleWorker> =
    tasks.flatMap { it.allProgresses }
        .filter { it.userId.isNotBlank() }
        .map {
            ScheduleWorker(
                id = it.userId,
                name = it.foremanName.ifBlank { "Сотрудник ${it.userId.take(6)}" }
            )
        }

private fun mergeScheduleWorkers(workers: List<ScheduleWorker>): List<ScheduleWorker> =
    workers
        .filter { it.id.isNotBlank() }
        .groupBy { it.id }
        .map { (_, sameIdWorkers) ->
            sameIdWorkers.firstOrNull { it.status.isNotBlank() && !it.name.startsWith("Сотрудник ") }
                ?: sameIdWorkers.firstOrNull { !it.name.startsWith("Сотрудник ") }
                ?: sameIdWorkers.first()
        }
        .sortedBy { it.name.lowercase() }

private fun PlatformUser.toScheduleWorker(): ScheduleWorker =
    ScheduleWorker(
        id = uid,
        name = "${firstName} ${lastName}".trim().ifBlank { nickname.ifBlank { "Пользователь" } },
        status = status
    )
