package com.bkc.screens.user_login.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.Button
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import bkcapp.composeapp.generated.resources.Res
import bkcapp.composeapp.generated.resources.img
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.core.domain.repository.ChatRepository
import com.bkc.screens.objects.ObjectsScreen
import com.bkc.screens.user_panel_screen.UserPanelScreen
import com.bkc.screens.user_login.viewmodel.UserLoginViewModel
import com.bkc.screens.user_login.viewmodel.models.UserLoginEffect
import com.bkc.screens.user_login.viewmodel.models.UserLoginIntent
import com.bkc.screens.user_register.ui.RegisterScreen
import com.bkc.screens.user_register.viewmodel.RegisterViewModel
import kotlinx.coroutines.flow.collectLatest
import org.koin.mp.KoinPlatform.getKoin
import org.jetbrains.compose.resources.painterResource

class  UserLoginScreen(
    val viewModel: UserLoginViewModel,
) : Screen {

    @OptIn(ExperimentalMaterial3Api::class)
    @Composable
    override fun Content() {

        val navigator = LocalNavigator.current

        val state by viewModel.state.collectAsState()
        var passwordVisible by remember { mutableStateOf(false) }

        LaunchedEffect(Unit) {
            viewModel.effects.collectLatest { eff ->
                when (eff) {
                    is UserLoginEffect.NavigateToApp -> {
                        getKoin().get<ChatRepository>().startRealtime()
                        val nextScreen = if (eff.hasSelectedObject) UserPanelScreen() else ObjectsScreen(openMainOnSelect = true)
                        navigator?.replaceAll(nextScreen)
                    }
                }
            }
        }

        Scaffold(
            topBar = {
                CenterAlignedTopAppBar(
                    title = { Text("Вход") },
                    navigationIcon = {
                        IconButton(onClick = { navigator?.pop() }) { Text("←") }
                    },
                    colors = TopAppBarDefaults.centerAlignedTopAppBarColors(
                        containerColor = MaterialTheme.colorScheme.surface,
                        titleContentColor = MaterialTheme.colorScheme.onSurface,
                        navigationIconContentColor = MaterialTheme.colorScheme.onSurface
                    )
                )
            }
        ) { padding ->

            Column(
                modifier = Modifier.Companion
                    .padding(padding)
                    .fillMaxSize()
                    .padding(16.dp),
                horizontalAlignment = Alignment.Companion.CenterHorizontally
            ) {

                Spacer(Modifier.Companion.height(12.dp))

                Image(
                    painter = painterResource(Res.drawable.img),
                    modifier = Modifier.Companion.size(width = 68.dp, height = 82.dp),
                    contentDescription = null
                )

                Spacer(Modifier.Companion.height(20.dp))

                OutlinedTextField(
                    value = state.login,
                    onValueChange = { viewModel.process(UserLoginIntent.LoginChanged(it)) },
                    label = { Text("Логин") },
                    singleLine = true,
                    modifier = Modifier.Companion.fillMaxWidth()
                )

                Spacer(Modifier.Companion.height(12.dp))

                OutlinedTextField(
                    value = state.password,
                    onValueChange = { viewModel.process(UserLoginIntent.PasswordChanged(it)) },
                    label = { Text("Пароль") },
                    singleLine = true,
                    visualTransformation = if (passwordVisible) {
                        VisualTransformation.None
                    } else {
                        PasswordVisualTransformation()
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                contentDescription = if (passwordVisible) "Скрыть пароль" else "Показать пароль"
                            )
                        }
                    },
                    modifier = Modifier.Companion.fillMaxWidth()
                )

                Spacer(Modifier.Companion.height(16.dp))

                Button(
                    onClick = { viewModel.process(UserLoginIntent.Submit) },
                    enabled = !state.isLoading,
                    modifier = Modifier.Companion.fillMaxWidth()
                ) {
                    if (state.isLoading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.Companion.size(18.dp)
                        )
                        Spacer(Modifier.Companion.width(10.dp))
                    }
                    Text("Войти")
                }

                Spacer(Modifier.Companion.height(12.dp))

                TextButton(
                    onClick = {
                        navigator?.push(
                            RegisterScreen(
                                viewModel = RegisterViewModel(getKoin().get(), getKoin().get()),
                                onBack = { navigator.pop() },
                                onRegistered = {
                                    navigator.replaceAll(ObjectsScreen(openMainOnSelect = true))
                                }
                            )
                        )
                    },
                    enabled = !state.isLoading
                ) { Text("Регистрация") }

                TextButton(
                    onClick = { viewModel.process(UserLoginIntent.RecoverAccess) },
                    enabled = !state.isLoading
                ) { Text("Восстановить доступ") }

                state.error?.let {
                    Spacer(Modifier.Companion.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.error)
                }
                state.message?.let {
                    Spacer(Modifier.Companion.height(12.dp))
                    Text(it, color = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }

}
