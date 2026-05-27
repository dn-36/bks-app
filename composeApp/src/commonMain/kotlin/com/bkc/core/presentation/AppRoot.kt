package com.bkc.core.presentation

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.CurrentTab
import cafe.adriel.voyager.navigator.tab.TabNavigator
import com.bkc.common.Logger
import com.bkc.core.app.AppStateStore
import com.bkc.core.data.local_storage.SavedUser
import com.bkc.core.data.local_storage.models.UserProfile
import com.bkc.core.domain.repository.AccountRepository
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.core.domain.repository.UserSessionStore
import com.bkc.core.presentation.mvi.UiListState
import com.bkc.core.presentation.navigation.HomeTab
import com.bkc.core.presentation.navigation.ObjectsTab
import com.bkc.core.presentation.navigation.ProfileTab
import com.bkc.core.presentation.navigation.RequestsTab
import com.bkc.core.presentation.notifications.AppNotifications
import com.bkc.core.presentation.platform.isDesktop
import com.bkc.core.presentation.ui.AppTheme
import com.bkc.screens.profile.ProfileScreen
import com.bkc.screens.auth_start.ui.AuthStartScreen
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.projects.ui.ProjectsScreen
import com.bkc.screens.splash.ui.SplashScreen
import com.bkc.screens.user_panel_screen.UserPanelScreen
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.launch
import org.koin.mp.KoinPlatform.getKoin

class AppRoot {
   private var savedUserData : MutableState<SavedUser?> = mutableStateOf(null)

    init {
        CoroutineScope(Dispatchers.IO).launch {
            savedUserData.value = (getKoin().get() as UserSessionStore).getUserOrNull()
        }
    }
    @Composable
    fun Component() {
        AppTheme {

          //  var savedUserData by remember { mutableStateOf<SavedUser?>(null) }
                //  var isLoading by remember { mutableStateOf(true) }

            var startScreen by remember { mutableStateOf<Screen?>(null) }


            LaunchedEffect(Unit) {
                val user = (getKoin().get() as UserSessionStore).getUserOrNull()
                startScreen = if (user != null) {
                    val sessionStore = getKoin().get<UserSessionStore>()
                    val activeUser = runCatching { getKoin().get<AccountRepository>().loadMe() }.getOrNull()
                    if (activeUser == null || activeUser.accountStatus != "ACTIVE") {
                        sessionStore.clear()
                        SplashScreen(AuthStartScreen())
                    } else {
                        sessionStore.saveUser(
                            user.copy(
                                firstName = activeUser.firstName,
                                lastName = activeUser.lastName,
                                nickname = activeUser.nickname,
                                avatarUrl = activeUser.avatarUrl,
                                bio = activeUser.bio,
                                phone = activeUser.phone,
                                accountStatus = activeUser.accountStatus,
                                blockedReason = activeUser.blockedReason,
                                privacyProfileVisible = activeUser.privacyProfileVisible,
                                notificationsEnabled = activeUser.notificationsEnabled,
                                createdAt = activeUser.createdAt
                            )
                        )
                        AppNotifications.requestPermissionIfNeeded()
                        getKoin().get<ChatRepository>().startRealtime()
                        getKoin().get<AppStateStore>().selectObject(
                            user.selectedObjectId,
                            user.selectedObjectName,
                            user.selectedObjectPhotoUrl
                        )
                        val nextScreen = if (user.selectedObjectId == null) {
                            ObjectsScreen(openMainOnSelect = true)
                        } else {
                            UserPanelScreen()
                        }
                        SplashScreen(nextScreen)
                    }
                } else {
                    SplashScreen(AuthStartScreen())
                }
            }

            if(startScreen != null){
                Navigator(
                    startScreen!!
                )
            }



        }
    }
}
