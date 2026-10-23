package com.profer.pocket.model

enum class ThemeMode { System, Light, Dark }

/** 本地设置：主题、通知、重连策略、后台连接。 */
data class PocketSettings(
    val theme: ThemeMode = ThemeMode.System,
    val notificationsEnabled: Boolean = true,
    val permissionAlertsEnabled: Boolean = true,
    val autoReconnect: Boolean = true,
    val backgroundConnection: Boolean = true,
)

enum class NotificationKind { Permission, Question, PlanApproval, RunCompleted }

/** 需要投递到系统通知栏的事件（已按设置过滤前）。 */
data class PocketNotification(
    val title: String,
    val body: String,
    val sessionId: String,
    val kind: NotificationKind,
    val at: Long,
)
