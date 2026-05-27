package com.bkc.screens.splash.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import bkcapp.composeapp.generated.resources.Res
import bkcapp.composeapp.generated.resources.img
import cafe.adriel.voyager.core.screen.Screen
import cafe.adriel.voyager.koin.getScreenModel
import cafe.adriel.voyager.navigator.LocalNavigator
import com.bkc.screens.splash.viewmodel.SplashEffect
import com.bkc.screens.splash.viewmodel.SplashIntent
import com.bkc.screens.splash.viewmodel.SplashScreenModel
import org.jetbrains.compose.resources.painterResource

class SplashScreen(private val nextScreen : Screen) : Screen {
    @Composable
    override fun Content() {
        val navigator = LocalNavigator.current
        val vm = getScreenModel<SplashScreenModel>()
        val state by vm.state.collectAsState()

        // слушаем эффект навигации
        LaunchedEffect(Unit) {
            vm.effects.collect { effect ->
                when (effect) {
                    SplashEffect.NavigateNext -> {
                        // после сплэша заменяем весь стек на корневой экран с табами
                        navigator?.replaceAll(nextScreen)
                    }
                }
            }
        }

        // Анимации появления (2 секунды мы держим экран в VM, но UI пусть красиво живёт)
        val alpha = remember { Animatable(0f) }
        val scale = remember { Animatable(0.92f) }

        LaunchedEffect(Unit) {
            alpha.animateTo(1f, animationSpec = tween(700, easing = FastOutSlowInEasing))
            scale.animateTo(1f, animationSpec = spring(dampingRatio = 0.75f, stiffness = 220f))
        }

        // "переливание" (shimmer) — бесконечное движение блика
        val infinite = rememberInfiniteTransition()
        val shimmerX by infinite.animateFloat(
            initialValue = -1f,
            targetValue = 2f,
            animationSpec = infiniteRepeatable(
                animation = tween(1100, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        val shimmerBrush = Brush.Companion.linearGradient(
            colorStops = arrayOf(
                0.0f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
                0.5f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.32f),
                1.0f to MaterialTheme.colorScheme.onSurface.copy(alpha = 0.10f),
            ),
            start = Offset(0f, 0f),
            end = Offset(600f * shimmerX, 0f)
        )

        Surface(Modifier.Companion.fillMaxSize()) {
            Box(
                Modifier.Companion.fillMaxSize(),
                contentAlignment = Alignment.Companion.Center
            ) {
                // Логотип (если есть картинка — можно заменить на Image(painterResource))
                Box(
                    modifier = Modifier.Companion
                        .size(150.dp)
                        .graphicsLayer {
                            this.alpha = alpha.value
                            this.scaleX = scale.value
                            this.scaleY = scale.value
                        }
                        .background(MaterialTheme.colorScheme.surface)
                ) {
                    // "переливание" сверху
                    Box(
                        Modifier.Companion
                            .matchParentSize()
                            .background(shimmerBrush)
                            .alpha(0.18f)
                    )

                    Image(
                        /* text = "БСК",
                         style = MaterialTheme.typography.headlineMedium,
                         color = MaterialTheme.colorScheme.onPrimary,*/
                        painter = painterResource(Res.drawable.img),
                        modifier = Modifier.Companion.align(Alignment.Companion.Center)
                            .size(120.dp),
                        contentDescription = null
                    )
                }

                // Ошибка + retry (на будущее)
                if (state.error != null) {
                    Column(
                        Modifier.Companion
                            .align(Alignment.Companion.BottomCenter)
                            .padding(24.dp),
                        horizontalAlignment = Alignment.Companion.CenterHorizontally
                    ) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        Spacer(Modifier.Companion.height(10.dp))
                        Button(onClick = { vm.onIntent(SplashIntent.Retry) }) {
                            Text("Повторить")
                        }
                    }
                }
            }
        }
    }


}
