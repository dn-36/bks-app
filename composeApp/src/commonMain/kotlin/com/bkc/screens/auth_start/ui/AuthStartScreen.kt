package com.bkc.screens.auth_start.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import bkcapp.composeapp.generated.resources.Res
import bkcapp.composeapp.generated.resources.img
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.screens.auth_start.viewmodel.AuthStartEffect
import com.bkc.screens.auth_start.viewmodel.AuthStartIntent
import com.bkc.screens.auth_start.viewmodel.AuthStartViewModel
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.user_login.ui.UserLoginScreen
import com.bkc.screens.user_login.viewmodel.UserLoginViewModel
import com.bkc.screens.user_register.ui.RegisterScreen
import com.bkc.screens.user_register.viewmodel.RegisterViewModel
import org.jetbrains.compose.resources.painterResource
import org.koin.mp.KoinPlatform.getKoin


class AuthStartScreen : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {

        val navigator = LocalNavigator.current
        val viewModel = remember { AuthStartViewModel() }
        val state by viewModel.state.collectAsState()

        // Навигация через эффекты
        LaunchedEffect(Unit) {
            viewModel.effect.collect { effect ->
                when (effect) {
                    AuthStartEffect.NavigateToLogin ->
                        navigator?.push(
                            UserLoginScreen(
                            UserLoginViewModel(
                                getKoin().get(), getKoin().get(), getKoin().get()
                            ),
                        ))

                    AuthStartEffect.NavigateToRegister ->
                        navigator?.push(
                            RegisterScreen(

                                RegisterViewModel(
                                    getKoin().get(),
                                    getKoin().get()
                                ),
                                { navigator.pop() },
                                {
                                    navigator.replaceAll(ObjectsScreen(openMainOnSelect = true))
                                }
                            ))


                }
            }
        }

        Scaffold(
            topBar = {

            }
        ) { padding ->

            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Image(
                    painterResource(Res.drawable.img),
                    alignment = Alignment.Companion.Center,
                    modifier = Modifier.Companion.size(width = 128.dp, height = 154.dp),
                    contentDescription = null
                )
                Spacer(Modifier.height(18.dp))
                Text(
                    text = "Рабочий доступ",
                    style = MaterialTheme.typography.headlineSmall,
                    color = MaterialTheme.colorScheme.onSurface
                )
                Text(
                    text = "вход для сотрудников и подрядчиков",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(32.dp))

                Button(
                    onClick = { viewModel.process(AuthStartIntent.ClickLogin) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading
                ) {
                    Text("Вход")
                }


                OutlinedButton(
                    onClick = { viewModel.process(AuthStartIntent.ClickRegister) },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !state.isLoading
                ) {
                    Text("Регистрация")
                }
            }
        }
    }


}
