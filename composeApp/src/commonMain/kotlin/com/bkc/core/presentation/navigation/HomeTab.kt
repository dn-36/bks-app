package com.bkc.core.presentation.navigation

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Inbox
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import cafe.adriel.voyager.navigator.Navigator
import cafe.adriel.voyager.navigator.tab.Tab
import cafe.adriel.voyager.navigator.tab.TabOptions
import com.bkc.screens.chats.ChatsScreen
import com.bkc.screens.home.HomeScreen
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.profile.ProfileScreen
import com.bkc.screens.requests.RequestsMenuScreen

object HomeTab : Tab {
    @Composable
    override fun Content() {
        Navigator(HomeScreen())
    }
    override val options: TabOptions
        @Composable get() = TabOptions(0u, "Главная", rememberVectorPainter(Icons.Default.Home))
}

object ObjectsTab : Tab {
    @Composable override fun Content() { Navigator(ObjectsScreen()) }
    override val options: TabOptions
        @Composable get() = TabOptions(1u, "Объекты",  rememberVectorPainter(Icons.Default.LocationOn))
}

object ProfileTab : Tab {
    @Composable override fun Content() { Navigator(ProfileScreen()) }
    override val options: TabOptions
        @Composable get() = TabOptions(2u, "Профиль", rememberVectorPainter(Icons.Default.AccountCircle))
}

object ChatsTab : Tab {
    @Composable override fun Content() { Navigator(ChatsScreen()) }
    override val options: TabOptions
        @Composable get() = TabOptions(3u, "Чаты", rememberVectorPainter(Icons.AutoMirrored.Filled.Chat))
}

object RequestsTab : Tab {
    @Composable override fun Content() { Navigator(RequestsMenuScreen()) }
    override val options: TabOptions
        @Composable get() = TabOptions(4u, "Заявки", rememberVectorPainter(Icons.Default.Inbox))
}
