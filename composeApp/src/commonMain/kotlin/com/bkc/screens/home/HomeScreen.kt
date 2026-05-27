package com.bkc.screens.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.repository.ShiftTaskRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.core.presentation.components.ListCard
import com.bkc.core.presentation.reports.ShiftReportNotificationsStore
import com.bkc.core.presentation.reports.refreshShiftReportUnreadCount
import com.bkc.core.presentation.utils.toUserStatusTitle
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.projects_user.ProjectsUserScreen
import com.bkc.screens.requests.SpecificationsScreen
import com.bkc.screens.schedules.SchedulesScreen
import com.bkc.screens.shit.ShiftTaskScreen
import com.russhwolf.settings.Settings
import org.koin.mp.KoinPlatform.getKoin

class HomeScreen : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val appStateStore = getKoin().get<AppStateStore>()
        val appState by appStateStore.state.collectAsState()
        val unreadReports by ShiftReportNotificationsStore.unreadCount.collectAsState()

        var statusText by remember { mutableStateOf<String?>(null) }

        LaunchedEffect(appState.selectedObjectId) {
            val sessionStore = getKoin().get<UserSessionStore>()
            val savedUser = sessionStore.getUserOrNull()
            statusText = savedUser?.status?.toUserStatusTitle()?.let { "Статус: $it" }
            if (savedUser?.status == "ADMINISTRATOR") {
                runCatching {
                    refreshShiftReportUnreadCount(
                        getKoin().get<ShiftTaskRepository>(),
                        sessionStore,
                        getKoin().get<Settings>()
                    )
                }
            }
        }

        val all = remember(unreadReports) {
            listOf(
                HomeItem.Projects, HomeItem.Schedules, HomeItem.Specs,
                HomeItem.Shift(unreadReports)
            )
        }
        val quick = all.filter { it is HomeItem.Projects || it is HomeItem.Schedules || it is HomeItem.Specs }
        val big = all.filterIsInstance<HomeItem.Shift>()

        Column(Modifier.fillMaxSize()) {
            AppTopBar(
                organization = appState.organizationName,
                objectName = appState.selectedObjectName,
                statusText = statusText,
                searchQuery = "",
                onSearchChange = {},
                onClear = {},
                onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
            )

            Column(
                Modifier.fillMaxSize().padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                quick.forEach { item ->
                    ListCard(
                        title = item.title,
                        onClick = {
                            when (item) {
                                is HomeItem.Projects -> navigator?.push(ProjectsUserScreen(showBackButton = true))
                                is HomeItem.Schedules -> {
                                    navigator?.push(SchedulesScreen(showBackButton = true))
                                }
                                is HomeItem.Specs -> {
                                    navigator?.push(SpecificationsScreen(showBackButton = true))
                                }
                                else -> Unit
                            }
                        },
                        height = 56
                    )
                }

                big.forEach { item ->
                    ListCard(
                        title = item.title,
                        onClick = { navigator?.push(ShiftTaskScreen(showBackButton = true)) },
                        height = 72,
                        accent = item.unreadReports > 0
                    )
                }
            }
        }
    }

}


private sealed class HomeItem(val title: String) {
    data object Projects : HomeItem("Проекты")
    data object Schedules : HomeItem("Графики")
    data object Specs : HomeItem("Спецификация")
    data class Shift(val unreadReports: Int = 0) : HomeItem(
        if (unreadReports > 0) "Сменное задание ($unreadReports)" else "Сменное задание"
    )
}
