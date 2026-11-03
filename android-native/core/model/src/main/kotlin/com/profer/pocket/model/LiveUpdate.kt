package com.profer.pocket.model

/**
 * 各家系统对「实时活动」的展示形态。
 *
 * 只影响文案，不影响行为：能提升的前提始终是 Android 16+ 的 Live Updates API。
 * 标了厂商名不代表已经打通该厂商的私有通道（vivo 原子岛、荣耀灵动胶囊、小米超级岛深度样式
 * 都需要厂商侧申请），所以对外文案统一用「实时活动」，厂商名只作副标题提示。
 */
enum class LiveUpdateSurface(val label: String) {
    /** OPPO / 一加 / realme。ColorOS 16+ 已完整接入原生 API。 */
    FluidCloud("流体云"),

    /** 小米 / Redmi / POCO。HyperOS 3 起部分支持原生 API。 */
    HyperIsland("超级岛"),

    /** vivo / iQOO。原子岛需要厂商侧「原子通知接入权限」，未接入时只能靠原生 API。 */
    AtomIsland("原子岛"),

    /** 荣耀。灵动胶囊需要厂商合作接入。 */
    MagicCapsule("灵动胶囊"),

    /** 三星。One UI 8 起对第三方开放原生 API，对应 Now Bar。 */
    NowBar("实时通知"),

    /** Google Pixel 及其它跟随原生实现的系统。 */
    LiveUpdate("实时活动"),
}

/**
 * 当前设备的实时活动能力。
 *
 * 判断依据只有两个：系统版本（API 36+）与系统是否允许本应用发布提升通知。
 * 后者由 `NotificationManager.canPostPromotedNotifications()` 提供，部分厂商（如三星 One UI 8）
 * 会返回 false 而要求用户先去设置里打开，所以单独用 [requiresUserSwitch] 表达。
 */
data class LiveUpdateCapability(
    val supported: Boolean,
    val requiresUserSwitch: Boolean,
    val surface: LiveUpdateSurface,
) {
    /** 本机对这个功能的叫法，用于副标题。 */
    val displayName: String get() = surface.label
}

object LiveUpdateCapabilities {

    /** 状态栏胶囊（Live Update）从 Android 16 开始提供。 */
    const val MIN_SDK = 36

    /**
     * 解析设备能力。
     *
     * @param sdkInt `Build.VERSION.SDK_INT`
     * @param manufacturer `Build.MANUFACTURER`
     * @param canPostPromoted 系统探测结果；null 表示探测失败（不催用户去开，避免误判）
     */
    fun resolve(
        sdkInt: Int,
        manufacturer: String,
        canPostPromoted: Boolean?,
    ): LiveUpdateCapability {
        val surface = surfaceOf(manufacturer)
        if (sdkInt < MIN_SDK) {
            return LiveUpdateCapability(
                supported = false,
                requiresUserSwitch = false,
                surface = surface,
            )
        }
        return LiveUpdateCapability(
            supported = true,
            // 只有系统明确回答「不允许」才算需要用户去开；探测失败一律不打扰。
            requiresUserSwitch = canPostPromoted == false,
            surface = surface,
        )
    }

    /** 厂商名大小写与别名都不统一，统一按小写比对。 */
    private fun surfaceOf(manufacturer: String): LiveUpdateSurface =
        when (manufacturer.trim().lowercase()) {
            "oppo", "oneplus", "realme" -> LiveUpdateSurface.FluidCloud
            "xiaomi", "redmi", "poco" -> LiveUpdateSurface.HyperIsland
            "vivo", "iqoo" -> LiveUpdateSurface.AtomIsland
            "honor" -> LiveUpdateSurface.MagicCapsule
            "samsung" -> LiveUpdateSurface.NowBar
            else -> LiveUpdateSurface.LiveUpdate
        }
}
