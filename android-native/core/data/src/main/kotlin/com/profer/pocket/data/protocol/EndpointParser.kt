package com.profer.pocket.data.protocol

import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * 连接地址归一化。
 *
 * 桌面设置页给的是 `http://192.168.x.x:7788` 形态的局域网地址，WebSocket 实际端点是
 * `ws://192.168.x.x:7788/ws`。用户也可能直接粘贴带 token 的完整地址，因此这里统一：
 *
 * - `http/https` → `ws/wss`；
 * - 无 scheme 时补 `ws://`；
 * - 空路径补 `/ws`；
 * - query 里的 `token` 抽出来单独保存，不再出现在展示/持久化地址中。
 */
object EndpointParser {

    data class Normalized(
        val wsUrl: String,
        val token: String?,
    )

    fun normalize(raw: String, fallbackToken: String? = null): Normalized? {
        val trimmed = raw.trim()
        if (trimmed.isEmpty()) return null
        val withScheme = if ("://" in trimmed) trimmed else "ws://$trimmed"
        val uri = try {
            URI(withScheme)
        } catch (_: Exception) {
            return null
        }
        val scheme = when (uri.scheme?.lowercase()) {
            "http", "ws" -> "ws"
            "https", "wss" -> "wss"
            else -> return null
        }
        val host = uri.host?.trim().orEmpty()
        if (host.isEmpty()) return null

        val queryToken = parseQuery(uri.rawQuery).entries
            .firstOrNull { it.key == "token" }
            ?.value
            ?.takeIf { it.isNotBlank() }
        val keptQuery = parseQuery(uri.rawQuery)
            .filterKeys { it != "token" }
            .map { (key, value) -> "${encode(key)}=${encode(value)}" }
            .joinToString("&")

        val path = when (val current = uri.rawPath.orEmpty()) {
            "", "/" -> "/ws"
            else -> current
        }
        val authority = buildString {
            append(host)
            if (uri.port != -1) {
                append(':').append(uri.port)
            }
        }
        val wsUrl = buildString {
            append(scheme).append("://").append(authority).append(path)
            if (keptQuery.isNotEmpty()) append('?').append(keptQuery)
        }
        return Normalized(wsUrl = wsUrl, token = fallbackToken ?: queryToken)
    }

    private fun parseQuery(rawQuery: String?): Map<String, String> {
        if (rawQuery.isNullOrBlank()) return emptyMap()
        return rawQuery.split('&').mapNotNull { part ->
            if (part.isBlank()) return@mapNotNull null
            val index = part.indexOf('=')
            val key = if (index >= 0) part.substring(0, index) else part
            val value = if (index >= 0) part.substring(index + 1) else ""
            decode(key) to decode(value)
        }.toMap()
    }

    private fun decode(value: String): String = try {
        URLDecoder.decode(value, "UTF-8")
    } catch (_: Exception) {
        value
    }

    private fun encode(value: String): String = try {
        URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    } catch (_: Exception) {
        value
    }
}
