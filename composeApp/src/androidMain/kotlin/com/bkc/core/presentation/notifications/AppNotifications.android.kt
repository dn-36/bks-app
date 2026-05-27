package com.bkc.core.presentation.notifications

import android.Manifest
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.RingtoneManager
import android.os.Build
import com.bkc.MainActivity
import com.bkc.R
import java.lang.ref.WeakReference

actual object AppNotifications {
    private const val CHANNEL_ID = "bks_chat_messages"
    private const val NOTIFICATION_PERMISSION_REQUEST = 1001
    private const val MAX_RECENT_NOTIFICATION_KEYS = 80
    private const val EXTRA_CHAT_ID = "com.bkc.extra.CHAT_ID"
    private const val EXTRA_CHAT_TITLE = "com.bkc.extra.CHAT_TITLE"
    private const val EXTRA_CHAT_AVATAR = "com.bkc.extra.CHAT_AVATAR"
    private var context: Context? = null
    private var activityRef: WeakReference<Activity>? = null
    private val recentNotificationKeys = ArrayDeque<String>()
    private val recentNotificationKeySet = mutableSetOf<String>()

    fun init(context: Context) {
        this.context = context.applicationContext
        (context as? Activity)?.let { activityRef = WeakReference(it) }
        createChannel(context.applicationContext)
    }

    fun openChatFromIntent(intent: Intent?) {
        val chatId = intent?.getStringExtra(EXTRA_CHAT_ID)
            ?: intent?.getStringExtra("chatId")
            ?: ""
        if (chatId.isBlank()) return
        NotificationOpenStore.openChat(
            chatId = chatId,
            title = intent?.getStringExtra(EXTRA_CHAT_TITLE)
                ?: intent?.getStringExtra("title")
                ?: "",
            avatarUrl = intent?.getStringExtra(EXTRA_CHAT_AVATAR)
                ?: intent?.getStringExtra("avatarUrl")
        )
    }

    actual fun registerPushToken() {
        FcmTokenRegistrar.registerCurrentToken(context)
    }

    actual fun requestPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < 33) return
        val activity = activityRef?.get() ?: return
        if (activity.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED) {
            return
        }
        activity.requestPermissions(
            arrayOf(Manifest.permission.POST_NOTIFICATIONS),
            NOTIFICATION_PERMISSION_REQUEST
        )
    }

    actual fun unregisterPushToken() {
        FcmTokenRegistrar.unregisterCurrentToken(context)
    }

    actual fun shouldShowRealtimeNotifications(): Boolean = false

    actual fun showMessage(
        chatId: String,
        senderName: String,
        text: String,
        avatarUrl: String?,
        notificationKey: String?
    ) {
        val appContext = context ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (isDuplicateNotification(notificationKey)) return

        val intent = Intent(appContext, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_CHAT_ID, chatId)
            putExtra(EXTRA_CHAT_TITLE, senderName)
            putExtra(EXTRA_CHAT_AVATAR, avatarUrl)
        }
        val pendingIntent = PendingIntent.getActivity(
            appContext,
            chatId.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
            .setSmallIcon(R.drawable.img)
            .setContentTitle(senderName)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(chatNotificationId(chatId), notification)
    }

    actual fun showInfo(title: String, text: String, notificationKey: String?) {
        val appContext = context ?: return
        if (Build.VERSION.SDK_INT >= 33 &&
            appContext.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        if (isDuplicateNotification(notificationKey)) return

        val notification = if (Build.VERSION.SDK_INT >= 26) {
            Notification.Builder(appContext, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(appContext)
        }
            .setSmallIcon(R.drawable.img)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setDefaults(Notification.DEFAULT_ALL)
            .setPriority(Notification.PRIORITY_HIGH)
            .build()

        val manager = appContext.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(infoNotificationId(title, text, notificationKey), notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Сообщения",
            NotificationManager.IMPORTANCE_HIGH
        ).apply {
            description = "Уведомления о новых сообщениях"
            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val audioAttributes = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_NOTIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
            setSound(soundUri, audioAttributes)
            enableVibration(true)
        }
        manager.createNotificationChannel(channel)
    }

    private fun isDuplicateNotification(notificationKey: String?): Boolean {
        val key = notificationKey?.trim()?.takeIf { it.isNotBlank() } ?: return false
        synchronized(recentNotificationKeySet) {
            if (!recentNotificationKeySet.add(key)) return true
            recentNotificationKeys.addLast(key)
            while (recentNotificationKeys.size > MAX_RECENT_NOTIFICATION_KEYS) {
                recentNotificationKeySet.remove(recentNotificationKeys.removeFirst())
            }
        }
        return false
    }

    private fun chatNotificationId(chatId: String): Int =
        "chat:$chatId".hashCode()

    private fun infoNotificationId(title: String, text: String, notificationKey: String?): Int {
        val stableKey = notificationKey?.trim()?.takeIf { it.isNotBlank() } ?: "$title:$text"
        return "info:$stableKey".hashCode()
    }
}
