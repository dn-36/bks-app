package com.bkc.core.presentation.notifications

import java.awt.Color
import java.awt.Font
import java.awt.MenuItem
import java.awt.PopupMenu
import java.awt.SystemTray
import java.awt.Toolkit
import java.awt.TrayIcon
import java.awt.image.BufferedImage
import java.util.concurrent.TimeUnit
import javax.swing.SwingUtilities

actual object AppNotifications {
    private var openAppHandler: (() -> Unit)? = null
    private var exitAppHandler: (() -> Unit)? = null
    private var lastTarget: ChatNotificationTarget? = null

    private val trayIcon: TrayIcon? by lazy {
        if (!SystemTray.isSupported()) return@lazy null
        runCatching {
            TrayIcon(createIcon(), "BKS APP").apply {
                isImageAutoSize = true
                popupMenu = createMenu()
                addActionListener { openLastTargetOrApp() }
                SystemTray.getSystemTray().add(this)
            }
        }.getOrNull()
    }

    fun init(onOpenApp: () -> Unit, onExitApp: () -> Unit) {
        openAppHandler = onOpenApp
        exitAppHandler = onExitApp
        trayIcon
    }

    actual fun requestPermissionIfNeeded() = Unit

    actual fun registerPushToken() = Unit

    actual fun unregisterPushToken() = Unit

    actual fun shouldShowRealtimeNotifications(): Boolean = true

    actual fun showMessage(
        chatId: String,
        senderName: String,
        text: String,
        avatarUrl: String?,
        notificationKey: String?
    ) {
        lastTarget = ChatNotificationTarget(chatId, senderName, avatarUrl)
        playNotificationSound()
        showDesktopNotification(senderName, text)
    }

    actual fun showInfo(title: String, text: String, notificationKey: String?) {
        if (title == "Заявка на регистрацию") {
            playNotificationSound()
        }
        showDesktopNotification(title, text)
    }

    private fun openLastTargetOrApp() {
        val target = lastTarget
        SwingUtilities.invokeLater {
            openAppHandler?.invoke()
            if (target != null) {
                NotificationOpenStore.openChat(target)
            }
        }
    }

    private fun createMenu(): PopupMenu =
        PopupMenu().apply {
            add(MenuItem("Открыть").apply {
                addActionListener { openLastTargetOrApp() }
            })
            add(MenuItem("Выход").apply {
                addActionListener {
                    SwingUtilities.invokeLater {
                        exitAppHandler?.invoke()
                    }
                }
            })
        }

    private fun createIcon(): BufferedImage {
        val image = BufferedImage(32, 32, BufferedImage.TYPE_INT_ARGB)
        val graphics = image.createGraphics()
        graphics.color = Color(0x1E, 0x4D, 0x8F)
        graphics.fillOval(0, 0, 31, 31)
        graphics.color = Color.WHITE
        graphics.font = Font(Font.SANS_SERIF, Font.BOLD, 18)
        graphics.drawString("B", 10, 23)
        graphics.dispose()
        return image
    }

    private fun showDesktopNotification(title: String, text: String) {
        val osName = System.getProperty("os.name").lowercase()
        if ((osName.contains("mac") || osName.contains("linux")) && showNativeNotification(title, text, osName)) {
            return
        }

        val icon = trayIcon
        if (icon != null && runCatching { icon.displayMessage(title, text, TrayIcon.MessageType.INFO) }.isSuccess) return

        showNativeNotification(title, text, osName)
    }

    private fun showNativeNotification(title: String, text: String, osName: String = System.getProperty("os.name").lowercase()): Boolean {
        val command = when {
            osName.contains("mac") -> listOf(
                "osascript",
                "-e",
                "display notification ${text.appleScriptLiteral()} with title ${title.appleScriptLiteral()}"
            )
            osName.contains("linux") -> listOf("notify-send", title, text)
            else -> return false
        }

        return runCatching {
            val process = ProcessBuilder(*command.toTypedArray()).start()
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroy()
                false
            } else {
                process.exitValue() == 0
            }
        }.getOrDefault(false)
    }

    private fun playNotificationSound() {
        val osName = System.getProperty("os.name").lowercase()
        val nativeStarted = when {
            osName.contains("mac") -> runCatching {
                ProcessBuilder("afplay", "/System/Library/Sounds/Glass.aiff").start()
            }.isSuccess
            osName.contains("linux") -> runCatching {
                ProcessBuilder("paplay", "/usr/share/sounds/freedesktop/stereo/message.oga").start()
            }.isSuccess
            else -> false
        }
        if (!nativeStarted) {
            runCatching { Toolkit.getDefaultToolkit().beep() }
        }
    }

    private fun String.appleScriptLiteral(): String =
        "\"" + replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\r", " ")
            .replace("\n", " ") + "\""
}
