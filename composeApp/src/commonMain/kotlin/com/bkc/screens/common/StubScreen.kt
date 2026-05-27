package com.bkc.screens.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
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
import com.bkc.core.presentation.components.AppTopBar
import com.bkc.screens.objects.ObjectsScreen
import org.koin.mp.KoinPlatform

class StubScreen(private val title: String) : Screen {
    @Composable
    override fun Content() {
     //   val koin = koin.getKoin<Context>//GlobalContext.get().koin
        val appStateStore : AppStateStore = KoinPlatform.getKoin().get()
        val appState by appStateStore.state.collectAsState()
        val navigator = LocalNavigator.current

        var query by remember { mutableStateOf("") }

        Column(Modifier.Companion.fillMaxSize()) {
            AppTopBar(
                organization = "Название организации ${appState.organizationName}",
                objectName = appState.selectedObjectName,
                searchQuery = query,
                onSearchChange = { query = it },
                onClear = { query = "" },
                onBackClick = { navigator?.pop() },
                onObjectClick = { navigator?.push(ObjectsScreen(showBackButton = true)) }
            )
            Box(Modifier.Companion.fillMaxSize().padding(16.dp)) {
                Text(title)
            }
        }
    }
}
