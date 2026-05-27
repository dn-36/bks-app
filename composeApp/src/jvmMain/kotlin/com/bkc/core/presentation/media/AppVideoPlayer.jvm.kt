package com.bkc.core.presentation.media

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingPanel
import androidx.compose.ui.graphics.Color
import javafx.application.Platform
import javafx.embed.swing.JFXPanel
import javafx.geometry.Insets
import javafx.geometry.Pos
import javafx.scene.Scene
import javafx.scene.control.Button
import javafx.scene.control.Label
import javafx.scene.control.ProgressIndicator
import javafx.scene.control.Slider
import javafx.scene.layout.BorderPane
import javafx.scene.layout.HBox
import javafx.scene.layout.Priority
import javafx.scene.layout.StackPane
import javafx.scene.media.Media
import javafx.scene.media.MediaPlayer
import javafx.scene.media.MediaView
import javafx.scene.paint.Color as FxColor
import javafx.util.Duration
import java.awt.BorderLayout
import java.awt.Color as AwtColor
import java.util.concurrent.atomic.AtomicBoolean
import javax.swing.JPanel
import javax.swing.SwingUtilities

@Composable
actual fun AppVideoPlayer(
    url: String,
    modifier: Modifier,
    showPrevious: Boolean,
    showNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
) {
    val currentOnPrevious by rememberUpdatedState(onPrevious)
    val currentOnNext by rememberUpdatedState(onNext)
    val container = remember {
        ensureJavaFxRuntime()
        JPanel(BorderLayout()).apply {
            background = AwtColor.BLACK
            isOpaque = true
        }
    }
    val fxPanel = remember {
        JFXPanel().apply {
            background = AwtColor.BLACK
            isOpaque = true
        }
    }
    var hasError by remember(url) { mutableStateOf(url.isBlank()) }

    DisposableEffect(container, fxPanel) {
        container.removeAll()
        container.add(fxPanel, BorderLayout.CENTER)
        container.revalidate()
        container.repaint()
        onDispose {
            container.remove(fxPanel)
        }
    }

    DisposableEffect(url, fxPanel, showPrevious, showNext) {
        var disposed = false
        var mediaPlayer: MediaPlayer? = null

        fun setError(error: Boolean) {
            SwingUtilities.invokeLater {
                hasError = error
            }
        }

        if (url.isBlank()) {
            setError(true)
        } else {
            setError(false)
            Platform.runLater {
                if (disposed) return@runLater

                val root = StackPane().apply {
                    style = "-fx-background-color: black;"
                }
                val progress = ProgressIndicator()
                fxPanel.scene = Scene(root, 1.0, 1.0, FxColor.BLACK)

                runCatching {
                    val media = Media(url)
                    val player = MediaPlayer(media)
                    mediaPlayer = player

                    val mediaView = MediaView(player).apply {
                        isPreserveRatio = true
                        fitWidthProperty().bind(root.widthProperty())
                        fitHeightProperty().bind(root.heightProperty())
                    }
                    val controls = createVideoControls(
                        player = player,
                        showPrevious = showPrevious,
                        showNext = showNext,
                        onPrevious = { SwingUtilities.invokeLater { currentOnPrevious() } },
                        onNext = { SwingUtilities.invokeLater { currentOnNext() } }
                    )
                    root.children.setAll(mediaView, progress, controls)
                    StackPane.setAlignment(progress, Pos.CENTER)

                    media.setOnError {
                        progress.isVisible = false
                        setError(true)
                    }
                    player.setOnReady {
                        progress.isVisible = false
                        setError(false)
                        player.play()
                    }
                    player.setOnPlaying {
                        progress.isVisible = false
                        setError(false)
                    }
                    player.setOnStalled {
                        progress.isVisible = true
                        setError(false)
                    }
                    player.setOnError {
                        progress.isVisible = false
                        setError(true)
                    }
                }.onFailure {
                    progress.isVisible = false
                    setError(true)
                }
            }
        }

        onDispose {
            disposed = true
            Platform.runLater {
                mediaPlayer?.stop()
                mediaPlayer?.dispose()
                fxPanel.scene = Scene(
                    StackPane().apply { style = "-fx-background-color: black;" },
                    1.0,
                    1.0,
                    FxColor.BLACK
                )
            }
        }
    }

    Box(
        modifier = modifier.background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        if (url.isNotBlank() && !hasError) {
            SwingPanel(
                factory = { container },
                modifier = Modifier.fillMaxSize(),
                background = Color.Black
            )
        }
        if (hasError) {
            Text(
                text = "Не удалось загрузить видео",
                color = Color.White
            )
        }
    }
}

private fun createVideoControls(
    player: MediaPlayer,
    showPrevious: Boolean,
    showNext: Boolean,
    onPrevious: () -> Unit,
    onNext: () -> Unit
): BorderPane {
    val currentTimeLabel = timeLabel("0:00")
    val durationLabel = timeLabel("0:00")
    val timeline = Slider(0.0, 1.0, 0.0).apply {
        isDisable = true
        isFocusTraversable = false
        prefWidth = 360.0
    }
    val controlBar = HBox(10.0, currentTimeLabel, timeline, durationLabel).apply {
        alignment = Pos.CENTER
        padding = Insets(8.0, 12.0, 8.0, 12.0)
        maxWidth = Double.MAX_VALUE
        style = "-fx-background-color: rgba(0, 0, 0, 0.70); -fx-background-radius: 6;"
    }
    HBox.setHgrow(timeline, Priority.ALWAYS)

    fun seekToTimelineValue() {
        if (!timeline.isDisable) {
            player.seek(Duration.seconds(timeline.value.coerceIn(0.0, timeline.max)))
        }
    }

    timeline.valueChangingProperty().addListener { _, _, changing ->
        if (!changing) seekToTimelineValue()
    }
    timeline.setOnMouseReleased { seekToTimelineValue() }
    timeline.setOnKeyReleased { seekToTimelineValue() }

    player.currentTimeProperty().addListener { _, _, currentTime ->
        currentTimeLabel.text = formatDuration(currentTime)
        if (!timeline.isValueChanging && !timeline.isPressed && currentTime.isKnownFinite()) {
            timeline.value = currentTime.toSeconds().coerceIn(0.0, timeline.max)
        }
    }
    player.totalDurationProperty().addListener { _, _, duration ->
        val enabled = duration.isKnownFinite()
        timeline.isDisable = !enabled
        if (enabled) {
            timeline.max = duration.toSeconds().coerceAtLeast(1.0)
            durationLabel.text = formatDuration(duration)
        } else {
            durationLabel.text = "0:00"
        }
    }

    return BorderPane().apply {
        isPickOnBounds = false
        padding = Insets(0.0, 24.0, 18.0, 24.0)
        left = navigationButton("<", showPrevious, onPrevious)
        right = navigationButton(">", showNext, onNext)
        bottom = controlBar
        BorderPane.setAlignment(left, Pos.CENTER_LEFT)
        BorderPane.setAlignment(right, Pos.CENTER_RIGHT)
        BorderPane.setAlignment(bottom, Pos.BOTTOM_CENTER)
    }
}

private fun navigationButton(
    text: String,
    visible: Boolean,
    onClick: () -> Unit
): Button =
    Button(text).apply {
        isVisible = visible
        isManaged = visible
        isFocusTraversable = false
        prefWidth = 44.0
        prefHeight = 64.0
        textFill = FxColor.WHITE
        style = """
            -fx-background-color: rgba(0, 0, 0, 0.46);
            -fx-background-radius: 22;
            -fx-font-size: 28;
            -fx-font-weight: bold;
            -fx-padding: 0 0 4 0;
        """.trimIndent()
        setOnAction { onClick() }
    }

private fun timeLabel(text: String): Label =
    Label(text).apply {
        textFill = FxColor.WHITE
        minWidth = 44.0
        alignment = Pos.CENTER
        style = "-fx-font-size: 12; -fx-font-weight: 600;"
    }

private fun formatDuration(duration: Duration): String {
    if (!duration.isKnownFinite()) return "0:00"
    val totalSeconds = (duration.toMillis() / 1000.0).toLong().coerceAtLeast(0L)
    val seconds = totalSeconds % 60
    val minutes = (totalSeconds / 60) % 60
    val hours = totalSeconds / 3600
    return if (hours > 0) {
        "$hours:${minutes.toString().padStart(2, '0')}:${seconds.toString().padStart(2, '0')}"
    } else {
        "$minutes:${seconds.toString().padStart(2, '0')}"
    }
}

private fun Duration.isKnownFinite(): Boolean {
    val millis = toMillis()
    return !isUnknown && !isIndefinite && !millis.isNaN() && !millis.isInfinite() && millis >= 0.0
}

private val javaFxInitialized = AtomicBoolean(false)

private fun ensureJavaFxRuntime() {
    if (javaFxInitialized.compareAndSet(false, true)) {
        JFXPanel()
        Platform.setImplicitExit(false)
    }
}
