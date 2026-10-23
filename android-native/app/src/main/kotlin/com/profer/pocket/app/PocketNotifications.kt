package com.profer.pocket.app

import android.Manifest
import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.profer.pocket.model.NotificationKind
import com.profer.pocket.model.PocketNotification
import com.profer.pocket.model.PocketSettings

/**
 * 系统通知：常驻连接通知 + 事件提醒。
 *
 * 前台时由界面直接渲染，不弹系统通知；后台时只提醒真正需要人介入的事件与任务完成。
 */
object PocketNotifications {

    const val CHANNEL_KEEPALIVE = "profer_keepalive"
    const val CHANNEL_MESSAGES = "profer_messages"
    const val KEEPALIVE_ID = 1001
    const val EXTRA_SESSION_ID = "profer_pocket_session_id"
    const val EXTRA_KIND = "profer_pocket_notification_kind"

    private const val MESSAGE_ID_BASE = 2001

    fun ensureChannels(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val keepalive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            "Profer 后台连接",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "保持与电脑上 Profer 的消息连接"
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(keepalive)

        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            "Profer 消息提醒",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "权限确认、计划审批、提问与任务完成"
            setShowBadge(true)
        }
        manager.createNotificationChannel(messages)
    }

    fun keepalive(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat_profer)
            .setContentTitle("Profer 后台连接")
            .setContentText("切到其他应用或熄屏后仍保持连接")
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    fun allowed(kind: NotificationKind, settings: PocketSettings): Boolean {
        if (!settings.notificationsEnabled) return false
        return when (kind) {
            NotificationKind.Permission,
            NotificationKind.Question,
            NotificationKind.PlanApproval,
            -> settings.permissionAlertsEnabled

            NotificationKind.RunCompleted -> true
        }
    }

    @SuppressLint("MissingPermission")
    fun notifyMessage(context: Context, notification: PocketNotification) {
        ensureChannels(context)
        if (!hasPermission(context)) return

        val intent = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
            putExtra(EXTRA_SESSION_ID, notification.sessionId)
            putExtra(EXTRA_KIND, notification.kind.name)
        }
        val requestCode = (notification.sessionId + notification.kind.name).hashCode() and 0x7FFFFFFF
        val contentIntent = PendingIntent.getActivity(
            context,
            requestCode,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

        val built = NotificationCompat.Builder(context, CHANNEL_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat_profer)
            .setContentTitle(notification.title)
            .setContentText(notification.body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(notification.body))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setCategory(NotificationCompat.CATEGORY_EVENT)
            .setAutoCancel(true)
            .setContentIntent(contentIntent)
            .build()

        try {
            NotificationManagerCompat.from(context).notify(messageId(notification), built)
        } catch (_: SecurityException) {
            // 权限在检查与发送之间被撤销：静默失败，连接不受影响。
        }
    }

    fun messageId(notification: PocketNotification): Int =
        MESSAGE_ID_BASE + ((notification.sessionId + notification.kind.name).hashCode() and 0x7FF)

    private fun hasPermission(context: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
}
