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
import android.os.Bundle
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.profer.pocket.model.LiveUpdateCapabilities
import com.profer.pocket.model.LiveUpdateCapability
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
    const val CHANNEL_LIVE = "profer_live"
    const val KEEPALIVE_ID = 1001
    const val LIVE_ID = 1002
    const val EXTRA_SESSION_ID = "profer_pocket_session_id"
    const val EXTRA_KIND = "profer_pocket_notification_kind"

    /** 胶囊最长存活时间：进程被系统冻结后协程不再执行，只能靠系统计时兜底。 */
    private const val LIVE_TIMEOUT_MS = 15 * 60 * 1000L

    /** 品牌陶土色（core:designsystem 的 Clay）；胶囊图标与进度条的强调色。 */
    private const val BRAND_COLOR = 0xFF846558.toInt()

    private const val MESSAGE_ID_BASE = 2001

    /**
     * `Notification.EXTRA_REQUEST_PROMOTED_ONGOING` 的字符串值。
     *
     * 该常量在 compileSdk 35 与 android-36.0 的 android.jar 里都还没有（36.1 才进公开 API），
     * 但 Android 16 的 Live Update 规范要求用它主动声明「请求提升」；
     * ColorOS 的流体云按同一份规范判定，缺这个键就不会提升成状态栏胶囊。
     */
    private const val EXTRA_REQUEST_PROMOTED_ONGOING = "android.requestPromotedOngoing"

    /** `Notification.FLAG_PROMOTED_ONGOING` 的值（0x40000）；compileSdk 35 里没有该常量。 */
    private const val FLAG_PROMOTED_ONGOING = 0x40000

    /** 系统设置页 action：ColorOS / AOSP 实际存在这个；官方文档写的 ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS 反而不存在。 */
    private const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    private const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"

    fun ensureChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return

        val keepalive = NotificationChannel(
            CHANNEL_KEEPALIVE,
            context.getString(R.string.notification_channel_keepalive),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_keepalive_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(keepalive)

        val messages = NotificationChannel(
            CHANNEL_MESSAGES,
            context.getString(R.string.notification_channel_messages),
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = context.getString(R.string.notification_channel_messages_description)
            setShowBadge(true)
        }
        manager.createNotificationChannel(messages)

        // 实时活动通道：静音、无徽章，只承载「对话运行中」这一条常驻通知。
        val live = NotificationChannel(
            CHANNEL_LIVE,
            context.getString(R.string.notification_channel_live),
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = context.getString(R.string.notification_channel_live_description)
            setShowBadge(false)
            enableVibration(false)
            enableLights(false)
        }
        manager.createNotificationChannel(live)
    }

    fun keepalive(context: Context): Notification =
        NotificationCompat.Builder(context, CHANNEL_KEEPALIVE)
            .setSmallIcon(R.drawable.ic_stat_profer)
            .setContentTitle(context.getString(R.string.notification_keepalive_title))
            .setContentText(context.getString(R.string.notification_keepalive_text))
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .build()

    /**
     * 对话运行中的系统级实时活动。
     *
     * Android 16 起 `setShortCriticalText` 会让系统把 ongoing 通知提升成状态栏胶囊
     * （Live Update）；低版本退回一条静音常驻通知，不影响其它逻辑。
     * 该方法在 compileSdk 35 的 SDK 里还不存在，只能按名字反射，失败就静默降级。
     */
    @SuppressLint("MissingPermission")
    fun postLiveUpdate(
        context: Context,
        sessionId: String,
        title: String,
        text: String,
        shortText: String,
    ) {
        ensureChannels(context)
        if (!hasPermission(context)) return

        val contentIntent = PendingIntent.getActivity(
            context,
            LIVE_ID,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
                putExtra(EXTRA_SESSION_ID, sessionId)
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        try {
            NotificationManagerCompat.from(context).notify(
                LIVE_ID,
                buildLiveUpdate(context, title, text, shortText, contentIntent),
            )
            logPromotionState(context)
        } catch (_: SecurityException) {
            // 权限在检查与发送之间被撤销：静默失败。
        }
    }

    /**
     * 打出提升结果，供真机排查流体云不显示的原因。
     *
     * `canPostPromotedNotifications()` 与 `FLAG_PROMOTED_ONGOING` 在 compileSdk 35 下不可直接引用，
     * 只能反射；两者都失败就只记录一条降级日志。
     */
    private fun logPromotionState(context: Context) {
        if (Build.VERSION.SDK_INT < 36) return
        val manager = context.getSystemService(NotificationManager::class.java)
        val canPost = runCatching {
            manager?.javaClass?.getMethod("canPostPromotedNotifications")?.invoke(manager) as? Boolean
        }.getOrNull()
        val promoted = runCatching {
            manager?.activeNotifications
                ?.firstOrNull { it.id == LIVE_ID }
                ?.let { (it.notification.flags and FLAG_PROMOTED_ONGOING) != 0 }
        }.getOrNull()
        Log.i(
            "ProferLive",
            "canPostPromotedNotifications=$canPost promoted=$promoted sdk=${Build.VERSION.SDK_INT} " +
                "surface=${capability(context).surface} needSwitch=${capability(context).requiresUserSwitch}",
        )
    }

    fun cancelLiveUpdate(context: Context) {
        try {
            NotificationManagerCompat.from(context).cancel(LIVE_ID)
        } catch (_: SecurityException) {
            // 同上。
        }
    }

    /** 本机的实时活动能力：系统版本 + 厂商形态 + 是否需要用户去开开关。 */
    fun capability(context: Context): LiveUpdateCapability =
        LiveUpdateCapabilities.resolve(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            canPostPromoted = canPostPromoted(context),
        )

    /**
     * 系统是否允许本应用发布提升通知（流体云 / 实时活动）。
     *
     * API < 36 返回 null（这台机器没这个能力）；反射失败也返回 null，不当成「未开启」去骚扰用户。
     */
    fun canPostPromoted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        return runCatching {
            manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as? Boolean
        }.getOrNull()
    }

    /** 打开系统的「流体云显示实时活动」开关页；系统没有该页面时返回 false。 */
    fun openPromotionSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val intent = Intent(ACTION_PROMOTION_SETTINGS)
            .putExtra(EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }

    private fun buildLiveUpdate(
        context: Context,
        title: String,
        text: String,
        shortText: String,
        contentIntent: PendingIntent,
    ): Notification {
        if (Build.VERSION.SDK_INT >= 36) {
            val builder = Notification.Builder(context, CHANNEL_LIVE)
                .setSmallIcon(R.drawable.ic_stat_profer)
                .setContentTitle(title)
                .setContentText(text)
                .setOngoing(true)
                .setOnlyAlertOnce(true)
                .setCategory(Notification.CATEGORY_PROGRESS)
                // Live Update 要求通知是可提升的样式（BigText/Progress/Call/Metric 之一）。
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(contentIntent)
                .setColor(BRAND_COLOR)
                // 进程被 ColorOS 冻结时协程不跑，定时校准失效；靠系统计时避免胶囊永久卡住。
                // 每次状态变化都会重新 post，计时随之重置。
                .setTimeoutAfter(LIVE_TIMEOUT_MS)
                // 主动请求提升：Android 16 规范与 ColorOS 流体云都按这个 extra 判定，
                // 只声明 POST_PROMOTED_NOTIFICATIONS 不会自动提升。
                .addExtras(Bundle().apply { putBoolean(EXTRA_REQUEST_PROMOTED_ONGOING, true) })
            // API 36 的签名是 setShortCriticalText(String)；系统据此把 ongoing 通知
            // 提升成状态栏胶囊（Live Update），不需要额外的 request 调用。
            runCatching {
                builder::class.java
                    .getMethod("setShortCriticalText", String::class.java)
                    .invoke(builder, shortText)
            }.onFailure { Log.w("ProferLive", "setShortCriticalText 不可用，降级为普通通知", it) }
            return builder.build()
        }
        return NotificationCompat.Builder(context, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_stat_profer)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setSilent(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_PROGRESS)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(contentIntent)
            .setColor(BRAND_COLOR)
            .setTimeoutAfter(LIVE_TIMEOUT_MS)
            .build()
    }

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
