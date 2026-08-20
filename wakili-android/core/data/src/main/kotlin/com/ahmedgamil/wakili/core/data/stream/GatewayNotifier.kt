package com.ahmedgamil.wakili.core.data.stream

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import com.ahmedgamil.wakili.core.data.R
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Local notifications for gateway events while the app is backgrounded —
 * a finished turn or a permission request waiting on an answer. Tapping one
 * opens the app. Posting is a no-op until the user grants POST_NOTIFICATIONS.
 */
@Singleton
class GatewayNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
) {

    private val manager get() = context.getSystemService(NotificationManager::class.java)

    fun notifyTurnEnd(sessionId: String, agentText: String?) {
        post(
            id = ID_TURN_END,
            sessionId = sessionId,
            title = context.getString(R.string.data_notify_turn_end_title),
            text = agentText ?: context.getString(R.string.data_notify_turn_end_text),
        )
    }

    fun notifyPermission(sessionId: String, tool: String) {
        post(
            id = ID_PERMISSION,
            sessionId = sessionId,
            title = context.getString(R.string.data_notify_permission_title),
            text = context.getString(R.string.data_notify_permission_text, tool),
            channel = ATTENTION_CHANNEL_ID,
        )
    }

    /** ask_options card waiting on the phone — the turn is blocked until answered. */
    fun notifyQuestion(sessionId: String, question: String?) {
        post(
            id = ID_QUESTION,
            sessionId = sessionId,
            title = context.getString(R.string.data_notify_question_title),
            text = question ?: context.getString(R.string.data_notify_question_text),
            channel = ATTENTION_CHANNEL_ID,
        )
    }

    private fun post(
        id: Int,
        sessionId: String,
        title: String,
        text: String,
        channel: String = CHANNEL_ID,
    ) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }
        ensureChannel()
        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.apply {
                putExtra(NOTIFICATION_SESSION_ID_EXTRA, sessionId)
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
            ?: return
        val notificationId = notificationId(id, sessionId)
        val pending = PendingIntent.getActivity(
            context,
            notificationId,
            launch,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = Notification.Builder(context, channel)
            .setSmallIcon(context.applicationInfo.icon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setContentIntent(pending)
            .setAutoCancel(true)
            .build()
        runCatching { manager.notify(notificationId, notification) }
    }

    private fun ensureChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.data_notify_channel),
                NotificationManager.IMPORTANCE_DEFAULT,
            ),
        )
        // Cards BLOCK the turn until answered — they get a heads-up banner
        // (high importance floats over whatever the user is doing), unlike the
        // informational turn-end pings on the default channel above.
        manager.createNotificationChannel(
            NotificationChannel(
                ATTENTION_CHANNEL_ID,
                context.getString(R.string.data_notify_attention_channel),
                NotificationManager.IMPORTANCE_HIGH,
            ),
        )
    }

    private companion object {
        const val CHANNEL_ID = "wakili_events"
        const val ATTENTION_CHANNEL_ID = "wakili_cards"
        const val ID_TURN_END = 100
        const val ID_PERMISSION = 101
        const val ID_QUESTION = 102

        fun notificationId(kind: Int, sessionId: String): Int =
            31 * kind + sessionId.hashCode()
    }
}

/** Session selected when a local gateway notification is opened. */
const val NOTIFICATION_SESSION_ID_EXTRA = "com.ahmedgamil.wakili.notification.SESSION_ID"
