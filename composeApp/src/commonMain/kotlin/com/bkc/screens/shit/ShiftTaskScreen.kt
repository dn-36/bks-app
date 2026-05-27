package com.bkc.screens.shit

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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
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
import com.bkc.core.domain.SHIFT_QUESTION_CHOICE
import com.bkc.core.domain.SHIFT_QUESTION_TEXT
import com.bkc.core.domain.ShiftAnswerInput
import com.bkc.core.domain.ShiftQuestion
import com.bkc.core.domain.ShiftQuestionInput
import com.bkc.core.domain.ShiftReport
import com.bkc.core.domain.repository.ShiftTaskRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.EmptyState
import com.bkc.core.presentation.components.LoadingState
import com.bkc.core.presentation.mvi.MviScreenModel
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.core.presentation.reports.markShiftReportsSeen
import com.bkc.screens.objects.ObjectsScreen
import com.russhwolf.settings.Settings
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.launch
import kotlinx.datetime.Instant
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.mp.KoinPlatform.getKoin

enum class ShiftTaskMode {
    FORM,
    REPORTS
}

data class ShiftTaskState(
    val mode: ShiftTaskMode = ShiftTaskMode.FORM,
    val searchQuery: String = "",
    val selectedReportUserUid: String? = null,
    val selectedReportDate: String? = null,
    val formState: UiListState<ShiftQuestion> = UiListState.Loading,
    val reportsState: UiListState<ShiftReport> = UiListState.Loading,
    val canManageForm: Boolean = false,
    val canSubmitReport: Boolean = false,
    val questionEditors: List<ShiftQuestionEditorState> = emptyList(),
    val answers: Map<String, String> = emptyMap(),
    val isSaving: Boolean = false,
    val error: String? = null,
    val success: String? = null
)

data class ShiftQuestionEditorState(
    val prompt: String = "",
    val type: String = SHIFT_QUESTION_TEXT,
    val optionsText: String = ""
)

sealed interface ShiftTaskIntent {
    data object Load : ShiftTaskIntent
    data class ChangeMode(val mode: ShiftTaskMode) : ShiftTaskIntent
    data class SearchChanged(val value: String) : ShiftTaskIntent
    data object ClearSearch : ShiftTaskIntent
    data class ReportUserFilterChanged(val userUid: String?) : ShiftTaskIntent
    data class ReportDateFilterChanged(val date: String?) : ShiftTaskIntent
    data class ApplyReportFilters(val userUid: String?, val date: String?) : ShiftTaskIntent
    data object AddQuestion : ShiftTaskIntent
    data class RemoveQuestion(val index: Int) : ShiftTaskIntent
    data class QuestionPromptChanged(val index: Int, val value: String) : ShiftTaskIntent
    data class QuestionTypeChanged(val index: Int, val value: String) : ShiftTaskIntent
    data class QuestionOptionsChanged(val index: Int, val value: String) : ShiftTaskIntent
    data object SaveForm : ShiftTaskIntent
    data class AnswerChanged(val questionId: String, val value: String) : ShiftTaskIntent
    data object SubmitReport : ShiftTaskIntent
}

class ShiftTaskScreenModel :
    MviScreenModel<ShiftTaskState, ShiftTaskIntent, Unit>(ShiftTaskState()),
    KoinComponent {

    private val shiftTaskRepository: ShiftTaskRepository by inject()
    private val userSessionStore: UserSessionStore by inject()
    private val settings: Settings by inject()
    private val queryFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    init {
        screenModelScope.launch { loadSession() }
        screenModelScope.launch { loadForm() }
        screenModelScope.launch {
            queryFlow.debounce(300).collect {
                if (state.value.mode == ShiftTaskMode.REPORTS) loadReports()
            }
        }
    }

    override fun onIntent(intent: ShiftTaskIntent) {
        when (intent) {
            ShiftTaskIntent.Load -> loadForm()
            is ShiftTaskIntent.ChangeMode -> {
                setState { it.copy(mode = intent.mode, error = null, success = null) }
                if (intent.mode == ShiftTaskMode.REPORTS) loadReports()
            }
            is ShiftTaskIntent.SearchChanged -> {
                setState { it.copy(searchQuery = intent.value) }
                queryFlow.tryEmit(intent.value)
            }
            ShiftTaskIntent.ClearSearch -> {
                setState { it.copy(searchQuery = "") }
                queryFlow.tryEmit("")
            }
            is ShiftTaskIntent.ReportUserFilterChanged -> {
                setState { it.copy(selectedReportUserUid = intent.userUid) }
            }
            is ShiftTaskIntent.ReportDateFilterChanged -> {
                setState { it.copy(selectedReportDate = intent.date) }
            }
            is ShiftTaskIntent.ApplyReportFilters -> {
                setState {
                    it.copy(
                        mode = ShiftTaskMode.REPORTS,
                        selectedReportUserUid = intent.userUid,
                        selectedReportDate = intent.date,
                        error = null,
                        success = null
                    )
                }
                loadReports()
            }
            ShiftTaskIntent.AddQuestion -> {
                if (state.value.canManageForm) {
                    setState {
                        it.copy(
                            questionEditors = it.questionEditors + ShiftQuestionEditorState(),
                            error = null,
                            success = null
                        )
                    }
                }
            }
            is ShiftTaskIntent.RemoveQuestion -> removeQuestion(intent.index)
            is ShiftTaskIntent.QuestionPromptChanged -> updateQuestion(intent.index) { it.copy(prompt = intent.value) }
            is ShiftTaskIntent.QuestionTypeChanged -> updateQuestion(intent.index) {
                it.copy(
                    type = intent.value,
                    optionsText = if (intent.value == SHIFT_QUESTION_CHOICE && it.optionsText.isBlank()) {
                        "Да\nНет"
                    } else {
                        it.optionsText
                    }
                )
            }
            is ShiftTaskIntent.QuestionOptionsChanged -> updateQuestion(intent.index) { it.copy(optionsText = intent.value) }
            ShiftTaskIntent.SaveForm -> saveForm()
            is ShiftTaskIntent.AnswerChanged -> {
                setState {
                    it.copy(
                        answers = it.answers + (intent.questionId to intent.value),
                        error = null,
                        success = null
                    )
                }
            }
            ShiftTaskIntent.SubmitReport -> submitReport()
        }
    }

    private suspend fun loadSession() {
        val user = userSessionStore.getUserOrNull()
        setState {
            it.copy(
                canManageForm = user?.status == "ADMINISTRATOR",
                canSubmitReport = user?.status == "FOREMAN" || user?.status == "ELECTRICIAN"
            )
        }
    }

    private fun loadForm() {
        screenModelScope.launch {
            setState { it.copy(formState = UiListState.Loading, error = null) }
            runCatching { shiftTaskRepository.getForm() }
                .onSuccess { form ->
                    setState {
                        it.copy(
                            formState = if (form.questions.isEmpty()) {
                                UiListState.Empty("Бланк сменного задания не настроен")
                            } else {
                                UiListState.Content(form.questions)
                            },
                            questionEditors = form.questions.map { question ->
                                ShiftQuestionEditorState(
                                    prompt = question.prompt,
                                    type = question.type,
                                    optionsText = question.options.joinToString("\n")
                                )
                            },
                            answers = emptyMap(),
                            error = null
                        )
                    }
                }
                .onFailure { e ->
                    setState { it.copy(formState = UiListState.Error(e.message ?: "Ошибка загрузки формы")) }
                }
        }
    }

    private fun loadReports() {
        screenModelScope.launch {
            setState { it.copy(reportsState = UiListState.Loading, error = null) }
            val query = state.value.searchQuery
            val result = runCatching { shiftTaskRepository.getReports(query) }
            val reports = result.getOrNull()
            if (reports != null) {
                if (state.value.canManageForm && query.isBlank()) {
                    markShiftReportsSeen(reports, userSessionStore, settings)
                }
                setState {
                    it.copy(
                        reportsState = when {
                            reports.isEmpty() -> UiListState.Empty("Отчетов пока нет")
                            else -> UiListState.Content(reports)
                        }
                    )
                }
            } else {
                val e = result.exceptionOrNull()
                setState { it.copy(reportsState = UiListState.Error(e?.message ?: "Ошибка загрузки отчетов")) }
            }
        }
    }

    private fun saveForm() {
        if (!state.value.canManageForm) return
        val questions = state.value.questionEditors.mapNotNull { editor ->
            val prompt = editor.prompt.trim()
            if (prompt.isBlank()) return@mapNotNull null
            ShiftQuestionInput(
                prompt = prompt,
                type = editor.type,
                options = editor.optionsText.toOptions().takeIf { editor.type == SHIFT_QUESTION_CHOICE }.orEmpty()
            )
        }

        if (questions.size != state.value.questionEditors.size || questions.isEmpty()) {
            setState { it.copy(error = "Заполните текст каждого вопроса", success = null) }
            return
        }
        if (questions.any { it.type == SHIFT_QUESTION_CHOICE && it.options.size < 2 }) {
            setState { it.copy(error = "Для вопроса с вариантами добавьте минимум два варианта", success = null) }
            return
        }

        screenModelScope.launch {
            setState { it.copy(isSaving = true, error = null, success = null) }
            runCatching { shiftTaskRepository.saveForm(questions) }
                .onSuccess {
                    setState { current ->
                        current.copy(
                            formState = UiListState.Content(it.questions),
                            questionEditors = it.questions.map { question ->
                                ShiftQuestionEditorState(
                                    prompt = question.prompt,
                                    type = question.type,
                                    optionsText = question.options.joinToString("\n")
                                )
                            },
                            isSaving = false,
                            success = "Бланк сохранен"
                        )
                    }
                }
                .onFailure { e ->
                    setState { it.copy(isSaving = false, error = e.message ?: "Ошибка сохранения бланка") }
                }
        }
    }

    private fun submitReport() {
        if (!state.value.canSubmitReport) return
        val questions = (state.value.formState as? UiListState.Content)?.items.orEmpty()
        if (questions.isEmpty()) return
        val answers = questions.map { question ->
            ShiftAnswerInput(question.id, state.value.answers[question.id].orEmpty().trim())
        }
        if (answers.any { it.value.isBlank() }) {
            setState { it.copy(error = "Ответьте на все вопросы", success = null) }
            return
        }

        screenModelScope.launch {
            setState { it.copy(isSaving = true, error = null, success = null) }
            runCatching { shiftTaskRepository.submitReport(answers) }
                .onSuccess {
                    setState {
                        it.copy(
                            isSaving = false,
                            answers = emptyMap(),
                            success = "Сменное задание отправлено"
                        )
                    }
                }
                .onFailure { e ->
                    setState { it.copy(isSaving = false, error = e.message ?: "Ошибка отправки сменного задания") }
                }
        }
    }

    private fun removeQuestion(index: Int) {
        val current = state.value.questionEditors
        if (!state.value.canManageForm || current.size <= 1 || index !in current.indices) return
        setState {
            it.copy(
                questionEditors = current.filterIndexed { i, _ -> i != index },
                error = null,
                success = null
            )
        }
    }

    private fun updateQuestion(index: Int, transform: (ShiftQuestionEditorState) -> ShiftQuestionEditorState) {
        val current = state.value.questionEditors
        if (!state.value.canManageForm || index !in current.indices) return
        setState {
            it.copy(
                questionEditors = current.mapIndexed { i, editor -> if (i == index) transform(editor) else editor },
                error = null,
                success = null
            )
        }
    }
}

class ShiftTaskScreen(
    private val showBackButton: Boolean = false,
    private val initialReportUserUid: String? = null,
    private val initialReportDate: String? = null
) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<ShiftTaskScreenModel>()
        val state by vm.state.collectAsState()
        val appStateStore: AppStateStore = getKoin().get()
        val appState by appStateStore.state.collectAsState()

        LaunchedEffect(initialReportUserUid, initialReportDate) {
            if (!initialReportUserUid.isNullOrBlank() || !initialReportDate.isNullOrBlank()) {
                vm.onIntent(ShiftTaskIntent.ApplyReportFilters(initialReportUserUid, initialReportDate))
            }
        }

        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                organization = appState.organizationName,
                objectName = appState.selectedObjectName,
                searchQuery = state.searchQuery,
                onSearchChange = { vm.onIntent(ShiftTaskIntent.SearchChanged(it)) },
                onClear = { vm.onIntent(ShiftTaskIntent.ClearSearch) },
                showSearch = state.mode == ShiftTaskMode.REPORTS,
                onBackClick = if (showBackButton) {
                    { navigator?.pop() }
                } else {
                    null
                },
                onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
            )

            if (state.canManageForm) {
                AdminModeSwitcher(
                    mode = state.mode,
                    onModeChange = { vm.onIntent(ShiftTaskIntent.ChangeMode(it)) }
                )
            }

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

            when {
                state.canManageForm && state.mode == ShiftTaskMode.FORM -> ShiftFormEditor(
                    state = state,
                    onPromptChange = { index, value -> vm.onIntent(ShiftTaskIntent.QuestionPromptChanged(index, value)) },
                    onTypeChange = { index, value -> vm.onIntent(ShiftTaskIntent.QuestionTypeChanged(index, value)) },
                    onOptionsChange = { index, value -> vm.onIntent(ShiftTaskIntent.QuestionOptionsChanged(index, value)) },
                    onAddQuestion = { vm.onIntent(ShiftTaskIntent.AddQuestion) },
                    onRemoveQuestion = { vm.onIntent(ShiftTaskIntent.RemoveQuestion(it)) },
                    onSave = { vm.onIntent(ShiftTaskIntent.SaveForm) }
                )
                state.canManageForm && state.mode == ShiftTaskMode.REPORTS -> ShiftReportsList(
                    state = state,
                    onUserFilterChange = { vm.onIntent(ShiftTaskIntent.ReportUserFilterChanged(it)) },
                    onDateFilterChange = { vm.onIntent(ShiftTaskIntent.ReportDateFilterChanged(it)) }
                )
                state.canSubmitReport -> ShiftReportForm(
                    state = state,
                    onAnswerChange = { questionId, value -> vm.onIntent(ShiftTaskIntent.AnswerChanged(questionId, value)) },
                    onSubmit = { vm.onIntent(ShiftTaskIntent.SubmitReport) }
                )
                else -> EmptyState("Сменное задание заполняют прораб и электромонтажник")
            }
        }
    }
}

@Composable
private fun AdminModeSwitcher(
    mode: ShiftTaskMode,
    onModeChange: (ShiftTaskMode) -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        if (mode == ShiftTaskMode.FORM) {
            Button(onClick = { onModeChange(ShiftTaskMode.FORM) }, modifier = Modifier.weight(1f)) {
                Text("Бланк")
            }
            OutlinedButton(onClick = { onModeChange(ShiftTaskMode.REPORTS) }, modifier = Modifier.weight(1f)) {
                Text("Отчеты")
            }
        } else {
            OutlinedButton(onClick = { onModeChange(ShiftTaskMode.FORM) }, modifier = Modifier.weight(1f)) {
                Text("Бланк")
            }
            Button(onClick = { onModeChange(ShiftTaskMode.REPORTS) }, modifier = Modifier.weight(1f)) {
                Text("Отчеты")
            }
        }
    }
}

@Composable
private fun ShiftFormEditor(
    state: ShiftTaskState,
    onPromptChange: (Int, String) -> Unit,
    onTypeChange: (Int, String) -> Unit,
    onOptionsChange: (Int, String) -> Unit,
    onAddQuestion: () -> Unit,
    onRemoveQuestion: (Int) -> Unit,
    onSave: () -> Unit
) {
    if (state.formState == UiListState.Loading) {
        LoadingState()
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        itemsIndexed(state.questionEditors) { index, editor ->
            ShiftQuestionEditorCard(
                index = index,
                editor = editor,
                canRemove = state.questionEditors.size > 1,
                onPromptChange = { onPromptChange(index, it) },
                onTypeChange = { onTypeChange(index, it) },
                onOptionsChange = { onOptionsChange(index, it) },
                onRemove = { onRemoveQuestion(index) }
            )
        }
        item {
            OutlinedButton(onClick = onAddQuestion, modifier = Modifier.fillMaxWidth()) {
                Icon(Icons.Default.Add, contentDescription = null)
                Spacer(Modifier.width(8.dp))
                Text("Добавить вопрос")
            }
        }
        item {
            Button(
                onClick = onSave,
                enabled = !state.isSaving,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(if (state.isSaving) "Сохранение..." else "Сохранить бланк")
            }
        }
    }
}

@Composable
private fun ShiftQuestionEditorCard(
    index: Int,
    editor: ShiftQuestionEditorState,
    canRemove: Boolean,
    onPromptChange: (String) -> Unit,
    onTypeChange: (String) -> Unit,
    onOptionsChange: (String) -> Unit,
    onRemove: () -> Unit
) {
    var typeMenuExpanded by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "Вопрос ${index + 1}",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                if (canRemove) {
                    IconButton(onClick = onRemove) {
                        Icon(Icons.Default.Delete, contentDescription = "Удалить вопрос")
                    }
                }
            }
            OutlinedTextField(
                value = editor.prompt,
                onValueChange = onPromptChange,
                label = { Text("Вопрос") },
                modifier = Modifier.fillMaxWidth()
            )
            Box {
                OutlinedButton(onClick = { typeMenuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
                    Text(editor.type.typeTitle())
                }
                DropdownMenu(
                    expanded = typeMenuExpanded,
                    onDismissRequest = { typeMenuExpanded = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Описать ответ") },
                        onClick = {
                            typeMenuExpanded = false
                            onTypeChange(SHIFT_QUESTION_TEXT)
                        }
                    )
                    DropdownMenuItem(
                        text = { Text("Выбрать вариант") },
                        onClick = {
                            typeMenuExpanded = false
                            onTypeChange(SHIFT_QUESTION_CHOICE)
                        }
                    )
                }
            }
            if (editor.type == SHIFT_QUESTION_CHOICE) {
                OutlinedTextField(
                    value = editor.optionsText,
                    onValueChange = onOptionsChange,
                    label = { Text("Варианты ответа") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

@Composable
private fun ShiftReportForm(
    state: ShiftTaskState,
    onAnswerChange: (String, String) -> Unit,
    onSubmit: () -> Unit
) {
    when (val formState = state.formState) {
        UiListState.Loading -> LoadingState()
        is UiListState.Empty -> EmptyState(formState.message)
        is UiListState.Error -> EmptyState(formState.message)
        is UiListState.Content -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(formState.items, key = { it.id }) { question ->
                ShiftAnswerCard(
                    question = question,
                    value = state.answers[question.id].orEmpty(),
                    onChange = { onAnswerChange(question.id, it) }
                )
            }
            item {
                Button(
                    onClick = onSubmit,
                    enabled = !state.isSaving,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(if (state.isSaving) "Отправка..." else "Отправить сменное задание")
                }
            }
        }
    }
}

@Composable
private fun ShiftAnswerCard(
    question: ShiftQuestion,
    value: String,
    onChange: (String) -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(
                text = question.prompt,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (question.type == SHIFT_QUESTION_CHOICE) {
                question.options.forEach { option ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onChange(option) },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = value == option,
                            onClick = { onChange(option) }
                        )
                        Text(option, modifier = Modifier.weight(1f))
                    }
                }
            } else {
                OutlinedTextField(
                    value = value,
                    onValueChange = onChange,
                    label = { Text("Ответ") },
                    minLines = 2,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}

private data class ReportUserFilter(
    val uid: String,
    val name: String,
    val email: String
)

@Composable
private fun ShiftReportsList(
    state: ShiftTaskState,
    onUserFilterChange: (String?) -> Unit,
    onDateFilterChange: (String?) -> Unit
) {
    when (val reportsState = state.reportsState) {
        UiListState.Loading -> LoadingState()
        is UiListState.Empty -> EmptyState(reportsState.message)
        is UiListState.Error -> EmptyState(reportsState.message)
        is UiListState.Content -> {
            val reports = reportsState.items
            val users = remember(reports) {
                reports
                    .map { report ->
                        ReportUserFilter(
                            uid = report.senderUid,
                            name = report.senderName,
                            email = report.senderEmail
                        )
                    }
                    .distinctBy { it.uid }
                    .sortedWith(compareBy({ it.name.lowercase() }, { it.email.lowercase() }))
            }
            val selectedUid = state.selectedReportUserUid
            val selectedDate = state.selectedReportDate
            val filteredReports = reports
                .let { current ->
                    selectedUid?.let { uid -> current.filter { it.senderUid == uid } } ?: current
                }
                .let { current ->
                    selectedDate?.let { date -> current.filter { it.createdAtMillis.dateIsoString() == date } } ?: current
                }

            Column(Modifier.fillMaxSize()) {
                ReportUserFilterMenu(
                    users = users,
                    selectedUid = selectedUid,
                    onSelected = onUserFilterChange
                )
                selectedDate?.let { date ->
                    ReportDateFilterBar(
                        date = date,
                        onClear = { onDateFilterChange(null) }
                    )
                }
                if (filteredReports.isEmpty()) {
                    EmptyState(
                        if (selectedDate != null) "За выбранную дату отчетов нет" else "У выбранного пользователя отчетов нет",
                        Modifier.weight(1f)
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        items(filteredReports, key = { it.id }) { report ->
                            ShiftReportCard(report)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ReportDateFilterBar(
    date: String,
    onClear: () -> Unit
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = "Дата: ${date.displayIsoDate()}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f)
        )
        TextButton(onClick = onClear) {
            Text("Сбросить")
        }
    }
}

@Composable
private fun ReportUserFilterMenu(
    users: List<ReportUserFilter>,
    selectedUid: String?,
    onSelected: (String?) -> Unit
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedUser = users.firstOrNull { it.uid == selectedUid }
    val selectedText = selectedUser?.name?.ifBlank { selectedUser.email }
        ?: if (selectedUid != null) "Выбранный пользователь" else "Все пользователи"

    Box(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedButton(
            onClick = { expanded = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = "Пользователь: $selectedText",
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            DropdownMenuItem(
                text = { Text("Все пользователи") },
                onClick = {
                    expanded = false
                    onSelected(null)
                }
            )
            users.forEach { user ->
                DropdownMenuItem(
                    text = {
                        Column {
                            Text(user.name.ifBlank { "Пользователь" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(
                                user.email,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    },
                    onClick = {
                        expanded = false
                        onSelected(user.uid)
                    }
                )
            }
        }
    }
}

@Composable
private fun ShiftReportCard(report: ShiftReport) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.small,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = report.createdAtMillis.dateTimeText(),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                text = "${report.senderName} · ${report.senderEmail} · ${report.senderStatus.statusTitle()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            HorizontalDivider()
            report.answers.forEach { answer ->
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(
                        text = answer.questionPrompt,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(answer.value, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

private fun String.toOptions(): List<String> =
    split('\n', ',')
        .map { it.trim() }
        .filter { it.isNotBlank() }
        .distinct()

private fun String.typeTitle(): String =
    when (this) {
        SHIFT_QUESTION_CHOICE -> "Выбрать вариант"
        else -> "Описать ответ"
    }

private fun String.statusTitle(): String =
    when (this) {
        "ADMINISTRATOR" -> "Администратор"
        "FOREMAN" -> "Прораб"
        "ELECTRICIAN" -> "Электромонтажник"
        "DELETED" -> "Пользователь удален"
        else -> this
    }

private fun Long.dateTimeText(): String {
    val dateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dateTime.dayOfMonth.twoDigits()}.${dateTime.monthNumber.twoDigits()}.${dateTime.year} " +
        "${dateTime.hour.twoDigits()}:${dateTime.minute.twoDigits()}"
}

private fun Long.dateIsoString(): String {
    val dateTime = Instant.fromEpochMilliseconds(this).toLocalDateTime(TimeZone.currentSystemDefault())
    return "${dateTime.year}-${dateTime.monthNumber.twoDigits()}-${dateTime.dayOfMonth.twoDigits()}"
}

private fun String.displayIsoDate(): String {
    val parts = split("-")
    if (parts.size != 3) return this
    return "${parts[2]}.${parts[1]}.${parts[0]}"
}

private fun Int.twoDigits(): String =
    if (this < 10) "0$this" else toString()
