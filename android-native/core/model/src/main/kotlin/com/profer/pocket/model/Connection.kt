package com.profer.pocket.model

/**
 * 连接状态：全局只有这一份，页面只消费，不允许各页自建连接。
 *
 * - [Idle]：从未配置过连接，或用户主动清空。
 * - [Connecting]：首次握手 / 手动重连中，尚无可用链路。
 * - [Connected]：链路可用，命令可发送。
 * - [Reconnecting]：已断开但会自动重试，[attempt] 从 1 开始。
 * - [Offline]：不会再自动重试（用户断开、token 失效、缺少凭据）。
 */
sealed interface ConnectionStatus {
    data object Idle : ConnectionStatus

    data object Connecting : ConnectionStatus

    data class Connected(
        val endpoint: String,
        val serverInstanceId: String? = null,
        val latencyMs: Int? = null,
    ) : ConnectionStatus

    data class Reconnecting(
        val attempt: Int,
        val delayMs: Long,
        val reason: String? = null,
    ) : ConnectionStatus

    data class Offline(
        val cause: OfflineCause,
        val message: String? = null,
    ) : ConnectionStatus
}

enum class OfflineCause {
    /** 用户主动断开，或关闭了连接开关。 */
    UserDisconnected,

    /** 服务端 close(4001)：token 无效，重连只会死循环。 */
    Unauthorized,

    /** 本地没有可用的地址 / token。 */
    MissingCredentials,

    /** 明确失败且不会自动重试（例如地址非法）。 */
    Unreachable,
}

val ConnectionStatus.isOnline: Boolean
    get() = this is ConnectionStatus.Connected

/** 展示用短标签：连接页、设置页共用一套口径。 */
fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.Idle -> "未连接"
    ConnectionStatus.Connecting -> "正在连接"
    is ConnectionStatus.Connected -> "已连接"
    is ConnectionStatus.Reconnecting -> "正在重连"
    is ConnectionStatus.Offline -> when (cause) {
        OfflineCause.UserDisconnected -> "已断开"
        OfflineCause.Unauthorized -> "凭据已失效"
        OfflineCause.MissingCredentials -> "未配置连接"
        OfflineCause.Unreachable -> "连接失败"
    }
}
