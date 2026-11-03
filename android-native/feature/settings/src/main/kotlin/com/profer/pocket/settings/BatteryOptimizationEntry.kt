package com.profer.pocket.settings

import android.content.Context
import android.content.Intent
import android.os.PowerManager
import android.provider.Settings

/**
 * 后台运行白名单（忽略电池优化）。
 *
 * ColorOS 等系统会在后台压缩/冻结进程（`osense.compress`），前台服务的协程也会停摆，
 * 结果是 WebSocket 断开后状态不再更新、状态栏胶囊卡在「执行中」。
 * 代码绕不过系统策略，只能引导用户把 App 加进电池白名单。
 */
internal object BatteryOptimizationEntry {

    /** 是否已被排除在电池优化之外（true = 后台不受限制）。 */
    fun isUnrestricted(context: Context): Boolean {
        val manager = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { manager.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    /**
     * 打开系统的电池优化列表。
     *
     * 不用 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`：那个需要
     * `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` 权限，且 Google Play 对用途有审核限制。
     */
    fun openSettings(context: Context): Boolean {
        val intent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)
    }
}
