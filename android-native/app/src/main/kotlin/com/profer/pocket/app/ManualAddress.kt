package com.profer.pocket.app

import java.net.URI

/**
 * 手动连接表单的「地址 + 端口」拼装与回显。
 *
 * 两条硬约束：
 * - 用户显式写了 scheme（尤其 `ws://`）时必须保留，隧道后面没有 TLS；
 * - 表单把主机和端口拆成两个输入框，回显时不能把 scheme 丢掉，
 *   否则下次重连会被当成「没写 scheme 的公网地址」自动升级成 wss。
 */
internal object ManualAddress {

    /** 地址 + 端口拼成连接地址；地址已带端口时不重复拼。 */
    fun compose(host: String, port: String): String {
        val trimmed = host.trim()
        val p = port.trim()
        if (p.isEmpty()) return trimmed
        if (!trimmed.contains("://")) return "$trimmed:$p"

        val schemeEnd = trimmed.indexOf("://") + 3
        val pathStart = trimmed.indexOf('/', schemeEnd)
        val head = if (pathStart >= 0) trimmed.substring(0, pathStart) else trimmed
        val tail = if (pathStart >= 0) trimmed.substring(pathStart) else ""
        // 已经有端口就不再追加：`wss://host:443` + 空端口以外的重复输入不该拼成 host:443:8443。
        val existingPort = runCatching { URI(head).port }.getOrNull() ?: -1
        if (existingPort > 0) return trimmed
        return "$head:$p$tail"
    }

    /** 回显已保存地址：拆成「地址」与「端口」，保留显式 scheme。 */
    fun split(url: String): Pair<String, String> {
        if (url.isBlank()) return "" to ""
        val uri = runCatching { URI(url) }.getOrNull() ?: return url to ""
        val bare = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return url to ""
        val scheme = uri.scheme?.lowercase()
        val display = if (scheme == "ws" || scheme == "wss") "$scheme://$bare" else bare
        return display to if (uri.port > 0) uri.port.toString() else ""
    }
}
