package com.bkc.screens.user_panel_screen

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import com.bkc.core.app.AppStateStore
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.ShiftTaskRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.navigation.BottomBarVisibilityStore
import com.bkc.core.presentation.navigation.ChatsTab
import com.bkc.core.presentation.navigation.HomeTab
import com.bkc.core.presentation.navigation.LogoutNavigationStore
import com.bkc.core.presentation.navigation.ProfileTab
import com.bkc.core.presentation.navigation.RequestsTab
import com.bkc.core.presentation.notifications.NotificationOpenStore
import com.bkc.core.presentation.platform.isDesktop
import com.bkc.core.presentation.registration.RegistrationRequestsStore
import com.bkc.core.presentation.reports.ShiftReportNotificationsStore
import com.bkc.core.presentation.reports.refreshShiftReportUnreadCount
import com.bkc.screens.auth_start.ui.AuthStartScreen
import com.russhwolf.settings.Settings
import org.koin.mp.KoinPlatform.getKoin

class UserPanelScreen : Screen {

    @Composable
    override fun Content() {
        val chatRepository = getKoin().get<ChatRepository>()
        val rootNavigator = LocalNavigator.current
        val accountRepository = getKoin().get<AccountRepository>()
        val shiftTaskRepository = getKoin().get<ShiftTaskRepository>()
        val sessionStore = getKoin().get<UserSessionStore>()
        val settings = getKoin().get<Settings>()
        val unreadCount by chatRepository.observeUnreadCount().collectAsState(0)
        val pendingRegistrations by RegistrationRequestsStore.pendingCount.collectAsState()
        val unreadReports by ShiftReportNotificationsStore.unreadCount.collectAsState()
        val bottomBarHidden by BottomBarVisibilityStore.hidden.collectAsState()
        var isAdmin by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            val user = sessionStore.getUserOrNull()
            isAdmin = user?.status == "ADMINISTRATOR"
            getKoin().get<AppStateStore>().selectObject(
                user?.selectedObjectId,
                user?.selectedObjectName,
                user?.selectedObjectPhotoUrl
            )
            if (isAdmin) {
                runCatching { accountRepository.listUsers("", "INACTIVE", adminMode = true).size }
                    .onSuccess { RegistrationRequestsStore.setPendingCount(it) }
                runCatching { refreshShiftReportUnreadCount(shiftTaskRepository, sessionStore, settings) }
            } else {
                RegistrationRequestsStore.clear()
                ShiftReportNotificationsStore.clear()
            }
        }

        LaunchedEffect(Unit) {
            LogoutNavigationStore.events.collect {
                rootNavigator?.replaceAll(AuthStartScreen())
            }
        }

        TabNavigator(HomeTab) { tabNavigator ->
            val pendingChat by NotificationOpenStore.pendingChat.collectAsState()

            LaunchedEffect(pendingChat) {
                if (pendingChat != null) {
                    tabNavigator.current = ChatsTab
                }
            }

            val tabs = listOf(HomeTab, ChatsTab, RequestsTab, ProfileTab)

            val content: @Composable () -> Unit = {
                Scaffold(
                    bottomBar = {
                        if (!bottomBarHidden) {
                            NavigationBar(
                                containerColor = MaterialTheme.colorScheme.surface,
                                tonalElevation = 0.dp
                            ) {
                                tabs.forEach { tab ->
                                    val selected = tabNavigator.current == tab

                                    NavigationBarItem(
                                        selected = selected,
                                        onClick = { tabNavigator.current = tab },
                                        icon = {
                                            BadgedBox(
                                                badge = {
                                                    if (tab == HomeTab && isAdmin && unreadReports > 0) {
                                                        Badge {
                                                            Text(if (unreadReports > 99) "99+" else unreadReports.toString())
                                                        }
                                                    } else if (tab == ChatsTab && unreadCount > 0) {
                                                        Badge {
                                                            Text(if (unreadCount > 99) "99+" else unreadCount.toString())
                                                        }
                                                    } else if (tab == ProfileTab && isAdmin && pendingRegistrations > 0) {
                                                        Badge {
                                                            Text(if (pendingRegistrations > 99) "99+" else pendingRegistrations.toString())
                                                        }
                                                    }
                                                }
                                            ) {
                                                Icon(
                                                    tab.options.icon!!,
                                                    contentDescription = tab.options.title
                                                )
                                            }
                                        },
                                        colors = NavigationBarItemDefaults.colors(
                                            selectedIconColor = MaterialTheme.colorScheme.primary,
                                            selectedTextColor = MaterialTheme.colorScheme.primary,
                                            indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                            unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                            unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                                        ),
                                        label = { Text(tab.options.title) }
                                    )
                                }
                            }
                        }
                    }
                ) { padding ->

                    Box(
                        Modifier
                            .fillMaxSize()
                            .padding(padding)
                    ) {
                        CurrentTab()
                    }
                }
            }

            if (isDesktop()) {
                Box(
                    Modifier.fillMaxSize(),
                    contentAlignment = Alignment.TopCenter
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .widthIn(max = 720.dp)
                    ) {
                        content()
                    }
                }
            } else {
                content()
            }
        }
    }
}
