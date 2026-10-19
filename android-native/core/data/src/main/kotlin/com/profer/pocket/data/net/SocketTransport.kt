package com.profer.pocket.data.net

/**
 * 原始 socket 抽象：只为把 OkHttp 与连接状态机隔开，单测用假实现驱动。
 * 回调可能来自任意线程，实现方不需要保证顺序之外的东西。
 */
interface SocketTransport {
    fun connect(url: String, listener: Listener)

    fun send(text: String): Boolean

    fun close(code: Int, reason: String)

    interface Listener {
        fun onOpen()

        fun onText(text: String)

        fun onClosed(code: Int, reason: String)

        fun onFailure(error: String)
    }
}

fun interface SocketTransportFactory {
    fun create(): SocketTransport
}

data class HeartbeatPolicy(
    val intervalMs: Long = 25_000,
    val deadThresholdMs: Long = 75_000,
)

data class BackoffPolicy(
    val initialMs: Long = 2_000,
    val maxMs: Long = 60_000,
) {
    fun delayFor(attempt: Int): Long {
        if (attempt <= 1) return initialMs
        var delay = initialMs
        repeat(attempt - 1) {
            delay *= 2
            if (delay >= maxMs) return maxMs
        }
        return delay.coerceAtMost(maxMs)
    }
}
