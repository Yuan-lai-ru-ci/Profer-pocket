package com.profer.pocket.settings

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Build
import com.profer.pocket.model.LiveUpdateCapabilities
import com.profer.pocket.model.LiveUpdateCapability

/**
 * 系统的「实时活动 / 流体云」开关。
 *
 * 能提升的前提是 Android 16+ 的 Live Updates API；部分系统（ColorOS 17 实测、One UI 8 有报告）
 * 默认关闭每个应用的开关，关着时 `canPostPromotedNotifications()` 返回 false，通知照发但不提升。
 * 这个开关只能由用户在系统设置里改，App 无法直接写，所以这里只做能力读取与跳转。
 */
internal object LiveUpdateEntry {

    /** ColorOS / AOSP 实际存在的设置页；`ACTION_MANAGE_APP_PROMOTED_NOTIFICATIONS` 反而不存在。 */
    private const val ACTION_PROMOTION_SETTINGS = "android.settings.APP_NOTIFICATION_PROMOTION_SETTINGS"
    private const val EXTRA_APP_PACKAGE = "android.provider.extra.APP_PACKAGE"

    /** 本机能力：系统版本、厂商形态、是否需要用户去开开关。 */
    fun capability(context: Context): LiveUpdateCapability =
        LiveUpdateCapabilities.resolve(
            sdkInt = Build.VERSION.SDK_INT,
            manufacturer = Build.MANUFACTURER,
            canPostPromoted = canPostPromoted(context),
        )

    /** 是否允许发布提升通知；API < 36 返回 null，调用方据此不展示入口。 */
    fun canPostPromoted(context: Context): Boolean? {
        if (Build.VERSION.SDK_INT < 36) return null
        val manager = context.getSystemService(NotificationManager::class.java) ?: return null
        // compileSdk 35 下没有这个公开方法，只能按名字反射；失败就当不支持。
        return runCatching {
            manager.javaClass.getMethod("canPostPromotedNotifications").invoke(manager) as? Boolean
        }.getOrNull()
    }

    /** 打开系统的提升通知开关页；系统没有该页面时返回 false，界面保持原样。 */
    fun openSettings(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < 36) return false
        val intent = Intent(ACTION_PROMOTION_SETTINGS)
            .putExtra(EXTRA_APP_PACKAGE, context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
