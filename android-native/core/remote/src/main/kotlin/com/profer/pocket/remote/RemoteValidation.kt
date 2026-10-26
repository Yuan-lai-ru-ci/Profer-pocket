package com.profer.pocket.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI

/**
 * 连接资料的严格校验，逐条对齐桌面 `packages/shared/src/remote-connection.ts`。
 *
 * 两端必须给出完全相同的判定：QR 由桌面生成、手机校验，任何一条规则漂移都会
 * 变成「电脑能出码、手机连不上」或更糟的「放宽了信任」。规则清单：
 *
 * - 未知字段拒绝（[record]）；
 * - `schemaVersion` 1/2、`deviceId` `[a-zA-Z0-9_-]{8,80}`、设备名 1..120 字；
 * - endpoints 1..16 且 URL 不重复；
 * - URL 不得带 userinfo/query/hash，path 必须 `/ws`；
 * - LAN 必须私网且允许 `ws`；公网必须 `wss`；
 * - `trustMode` 仅 `system`/`pinned`；LAN 不得带信任字段；
 * - pinned 仅公网 WSS 且地址必须是 IP，pin 必须是规范的 `sha256/<43 字符>=`；
 * - v1 不得带 pin，v2 公网端点必须指定 trustMode；
 * - 配对二维码 TTL 固定 300000ms，issuedAt 不得超前 30s。
 */
object RemoteValidation {

    private val DEVICE_ID = Regex("^[a-zA-Z0-9_-]{8,80}$")
    private val PIN_VALUE = Regex("^sha256/[A-Za-z0-9+/]{43}=$")
    private val PIN_CANONICAL_TAIL = Regex("[AEIMQUYcgkosw048]=$")
    private val IPV4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")
    private val IPV6_PRIVATE = Regex("^(fc|fd)[0-9a-f]{2}:", RegexOption.IGNORE_CASE)
    private val PAIRING_CODE = Regex("^[a-f0-9]{64}$")
    private val CREDENTIAL = Regex("^[a-f0-9]{64}$")

    private val PROFILE_KEYS = setOf("schemaVersion", "deviceId", "deviceName", "updatedAt", "endpoints")
    private val QR_KEYS = PROFILE_KEYS + setOf("kind", "issuedAt", "expiresAt", "pairingCode")
    /** 精简配对码：v/d/h/c/x，只保留配对必需字段。 */
    private val COMPACT_QR_KEYS = setOf("v", "d", "h", "c", "x")
    /** 带公网端点的精简配对码：v/d/h/p/k/c/x（k 仅 IP 公网端点）。 */
    private val COMPACT_QR_V3_KEYS = setOf("v", "d", "h", "p", "k", "c", "x")
    private val ENDPOINT_KEYS = setOf("url", "scope", "trustMode", "pin")
    private val PIN_KEYS = setOf("algorithm", "value")

    /** 二维码体积上限：超过 8KB 的码不可能是桌面生成的。 */
    const val MAX_QR_BYTES = 8192
    const val MAX_CLOCK_SKEW_MS = 30_000L

    // ===== 端点 =====

    fun isPrivateHost(host: String): Boolean {
        val h = host.lowercase().removePrefix("[").removeSuffix("]")
        if (h == "localhost" || h == "::1" || IPV6_PRIVATE.containsMatchIn(h)) return true
        if (!IPV4.matches(h)) return false
        val parts = h.split('.').map { it.toIntOrNull() ?: return false }
        if (parts.any { it < 0 || it > 255 }) return false
        return parts[0] == 10 ||
            parts[0] == 127 ||
            (parts[0] == 192 && parts[1] == 168) ||
            (parts[0] == 172 && parts[1] in 16..31)
    }

    fun validateEndpoint(input: Any?): RemoteEndpoint {
        val e = record(input, ENDPOINT_KEYS)
        val url = e.opt("url")
        val scope = EndpointScope.fromWire(e.stringOrNull("scope"))
        if (url !is String || url.length > 512 || scope == null) fail("连接端点无效")

        val uri = try {
            URI(url)
        } catch (_: Exception) {
            fail("连接端点无效")
        }
        val host = uri.host?.lowercase() ?: fail("连接端点无效")
        val scheme = uri.scheme?.lowercase()
        if (uri.userInfo != null || uri.rawQuery != null || uri.rawFragment != null) fail("端点不得包含凭据或查询参数")
        if (uri.rawPath != "/ws") fail("端点不得包含凭据或查询参数")
        if (scope == EndpointScope.LAN && !isPrivateHost(host)) fail("局域网端点必须是私网地址")
        val isWss = scheme == "wss"
        val lanWs = scope == EndpointScope.LAN && scheme == "ws" && isPrivateHost(host)
        if (!isWss && !lanWs) fail("公网连接必须使用可信 WSS")

        val trustMode = if (e.has("trustMode")) {
            TrustMode.fromWire(e.stringOrNull("trustMode")) ?: fail("未知 TLS 信任模式")
        } else {
            null
        }
        if (scope == EndpointScope.LAN && (trustMode != null || e.has("pin"))) {
            fail("局域网端点不能携带公网信任字段")
        }
        if (trustMode == TrustMode.PINNED) {
            if (scope != EndpointScope.PUBLIC || !isWss) fail("Pin 仅用于公网 WSS")
            val p = record(e.opt("pin"), PIN_KEYS)
            val value = p.opt("value")
            if (p.stringOrNull("algorithm") != RemotePin.ALGORITHM ||
                value !is String ||
                !PIN_VALUE.matches(value)
            ) {
                fail("SPKI SHA256 pin 无效")
            }
            // SHA256 的最后一个 base64 字符必须是规范编码，不能接受模糊等价值。
            if (!PIN_CANONICAL_TAIL.containsMatchIn(value)) fail("pin 编码无效")
            val bareHost = host.removePrefix("[").removeSuffix("]")
            if (!IPV4.matches(bareHost) && !bareHost.contains(':')) fail("Pinned 地址必须是 IP")
            return RemoteEndpoint(
                url = canonicalUrl(uri, scheme),
                scope = scope,
                trustMode = TrustMode.PINNED,
                pin = RemotePin(RemotePin.ALGORITHM, value),
            )
        }
        if (e.has("pin")) fail("系统可信端点不能携带 pin")
        return RemoteEndpoint(
            url = canonicalUrl(uri, scheme),
            scope = scope,
            trustMode = trustMode,
        )
    }

    /** 与 JS `new URL(...).toString()` 对齐：scheme/host 小写、去掉默认端口、path 固定 `/ws`。 */
    private fun canonicalUrl(uri: URI, scheme: String?): String {
        val host = uri.host?.lowercase() ?: fail("连接端点无效")
        val defaultPort = if (scheme == "ws") 80 else 443
        val authority = if (uri.port == -1 || uri.port == defaultPort) host else "$host:${uri.port}"
        return "$scheme://$authority/ws"
    }

    // ===== 连接资料 =====

    fun parseProfile(input: Any?): RemoteProfile = profile(record(input, PROFILE_KEYS))

    fun parseProfileJson(text: String): RemoteProfile {
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            fail("连接资料损坏")
        }
        return parseProfile(json)
    }

    private fun profile(p: JSONObject): RemoteProfile {
        val schemaVersion = p.opt("schemaVersion")
        if (schemaVersion !is Int || (schemaVersion != 1 && schemaVersion != 2)) fail("不支持的连接资料版本")
        val deviceId = p.opt("deviceId")
        val deviceName = p.opt("deviceName")
        val updatedAt = safeInteger(p.opt("updatedAt"))
        if (deviceId !is String || !DEVICE_ID.matches(deviceId) ||
            deviceName !is String || deviceName.isBlank() || deviceName.length > 120 ||
            updatedAt == null || updatedAt < 0
        ) {
            fail("设备身份无效")
        }
        val rawEndpoints = p.opt("endpoints")
        if (rawEndpoints !is JSONArray || rawEndpoints.length() < 1 || rawEndpoints.length() > 16) {
            fail("连接端点无效")
        }
        val endpoints = buildList {
            for (index in 0 until rawEndpoints.length()) add(validateEndpoint(rawEndpoints.opt(index)))
        }
        if (endpoints.map { it.url }.toSet().size != endpoints.size) fail("连接端点重复")
        if (schemaVersion == 1 && endpoints.any { it.trustMode != null || it.pin != null }) {
            fail("v1 不支持 pin；请重新扫码")
        }
        if (schemaVersion == 2 && endpoints.any { it.scope == EndpointScope.PUBLIC && it.trustMode == null }) {
            fail("v2 公网端点必须指定信任模式")
        }
        return RemoteProfile(schemaVersion, deviceId, deviceName, updatedAt, endpoints)
    }

    // ===== 配对二维码 =====

    fun parsePairingQr(text: String, now: Long = System.currentTimeMillis()): PairingQr {
        if (text.toByteArray(Charsets.UTF_8).size > MAX_QR_BYTES) fail("二维码过大")
        val json = try {
            JSONObject(text)
        } catch (_: Exception) {
            fail("二维码损坏")
        }
        if (json.opt("v") == 3) return parseCompactPairingQrV3(json, now)
        if (json.opt("v") == 2) return parseCompactPairingQr(json, now)
        val p = record(json, QR_KEYS)
        val parsed = profile(p)
        val code = p.opt("pairingCode")
        val issuedAt = safeInteger(p.opt("issuedAt"))
        val expiresAt = safeInteger(p.opt("expiresAt"))
        if (p.opt("kind") != "profer-pairing" ||
            code !is String || !PAIRING_CODE.matches(code) ||
            issuedAt == null || expiresAt == null || issuedAt < 0 ||
            expiresAt - issuedAt != PAIRING_TTL_MS ||
            issuedAt > now + MAX_CLOCK_SKEW_MS
        ) {
            fail("二维码无效")
        }
        if (expiresAt <= now) fail("二维码已过期，请在电脑刷新")
        return PairingQr(parsed, code, issuedAt, expiresAt)
    }

    /**
     * 精简配对二维码：`{"v":2,"d":deviceId,"h":"host:port","c":pairingCode,"x":过期秒}`。
     * 只用于局域网单端点；完整 profile 在 `paired` 响应里返回。
     */
    private fun parseCompactPairingQr(p: JSONObject, now: Long): PairingQr {
        val obj = record(p, COMPACT_QR_KEYS)
        val version = obj.opt("v")
        val deviceId = obj.opt("d") as? String
        val hostValue = obj.opt("h") as? String
        val code = obj.opt("c") as? String
        val expiresSec = safeInteger(obj.opt("x"))
        if (version != 2 ||
            deviceId == null || !DEVICE_ID.matches(deviceId) ||
            hostValue == null ||
            code == null || !PAIRING_CODE.matches(code) ||
            expiresSec == null || expiresSec <= 0 || expiresSec > 9_007_199_254_740L
        ) {
            fail("二维码无效")
        }
        val host = compactHost(hostValue)
        val expiresAt = expiresSec * 1000
        val issuedAt = expiresAt - PAIRING_TTL_MS
        if (issuedAt > now + MAX_CLOCK_SKEW_MS) fail("二维码无效")
        if (expiresAt <= now) fail("二维码已过期，请在电脑刷新")
        val endpoint = validateEndpoint(
            JSONObject().put("url", "ws://$host/ws").put("scope", "lan"),
        )
        val profile = RemoteProfile(
            schemaVersion = 1,
            deviceId = deviceId,
            deviceName = "电脑",
            updatedAt = issuedAt,
            endpoints = listOf(endpoint),
        )
        return PairingQr(profile, code, issuedAt, expiresAt)
    }

    /**
     * 带公网端点的精简配对码：`{"v":3,"d":deviceId,"h":"局域网host:port","p":"公网host:port","k":"sha256/...","c":配对码,"x":过期秒}`。
     *
     * 公网端点信任模式由主机形态决定（与桌面 v3 生成规则一致）：
     * IP 必须带 pin（桌面自签 TLS）；域名必须不带 pin（用户自己的反代终止 TLS，系统可信）。
     */
    private fun parseCompactPairingQrV3(p: JSONObject, now: Long): PairingQr {
        val obj = record(p, COMPACT_QR_V3_KEYS)
        val version = obj.opt("v")
        val deviceId = obj.opt("d") as? String
        val lanValue = obj.opt("h") as? String
        val publicValue = obj.opt("p") as? String
        val code = obj.opt("c") as? String
        val expiresSec = safeInteger(obj.opt("x"))
        if (version != 3 ||
            deviceId == null || !DEVICE_ID.matches(deviceId) ||
            lanValue == null || publicValue == null ||
            code == null || !PAIRING_CODE.matches(code) ||
            expiresSec == null || expiresSec <= 0 || expiresSec > 9_007_199_254_740L
        ) {
            fail("二维码无效")
        }
        val lanHost = compactHost(lanValue)
        val publicHost = compactHost(publicValue)
        val expiresAt = expiresSec * 1000
        val issuedAt = expiresAt - PAIRING_TTL_MS
        if (issuedAt > now + MAX_CLOCK_SKEW_MS) fail("二维码无效")
        if (expiresAt <= now) fail("二维码已过期，请在电脑刷新")
        val lanEndpoint = validateEndpoint(
            JSONObject().put("url", "ws://$lanHost/ws").put("scope", "lan"),
        )
        val publicHostname = hostOf("wss://$publicHost/ws") ?: fail("连接端点无效")
        val isIpHost = IPV4.matches(publicHostname) || publicHostname.contains(':')
        val publicEndpoint = if (isIpHost) {
            validateEndpoint(
                JSONObject()
                    .put("url", "wss://$publicHost/ws")
                    .put("scope", "public")
                    .put("trustMode", "pinned")
                    .put("pin", JSONObject().put("algorithm", RemotePin.ALGORITHM).put("value", obj.opt("k"))),
            )
        } else {
            if (obj.has("k")) fail("域名公网端点不能携带 pin")
            validateEndpoint(
                JSONObject().put("url", "wss://$publicHost/ws").put("scope", "public").put("trustMode", "system"),
            )
        }
        val profile = RemoteProfile(
            schemaVersion = 2,
            deviceId = deviceId,
            deviceName = "电脑",
            updatedAt = issuedAt,
            endpoints = listOf(lanEndpoint, publicEndpoint),
        )
        return PairingQr(profile, code, issuedAt, expiresAt)
    }

    private fun compactHost(value: String): String {
        if (value.length > 128 || value.any { it in "/?#@ \t\r\n" }) fail("连接端点无效")
        val uri = try {
            URI("ws://$value/ws")
        } catch (_: Exception) {
            fail("连接端点无效")
        }
        val host = uri.host?.lowercase() ?: fail("连接端点无效")
        if (uri.port !in 1..65535 || uri.rawPath != "/ws" || uri.rawQuery != null || uri.userInfo != null) {
            fail("连接端点无效")
        }
        val bare = host.removePrefix("[").removeSuffix("]")
        return if (bare.contains(':')) "[$bare]:${uri.port}" else "$bare:${uri.port}"
    }

    // ===== 信任锚断言 =====

    /**
     * 网络只可更新已绑定身份的地址，不能建立、移除或替换信任锚。
     * 与桌面 `assertProfileTrustUnchanged` 同一判定顺序与文案。
     */
    fun assertProfileTrustUnchanged(previous: RemoteProfile?, next: RemoteProfile) {
        val pins = { p: RemoteProfile? ->
            p?.endpoints
                ?.filter { it.trustMode == TrustMode.PINNED }
                ?.mapNotNull { it.pin?.value }
                ?.toSet()
                ?.sorted()
                ?: emptyList()
        }
        val previousPins = pins(previous)
        if (previousPins != pins(next)) fail("TLS 信任锚变化，请在电脑重新扫码")
        if (previousPins.isNotEmpty()) {
            val trusted = previous?.endpoints
                ?.filter { it.scope == EndpointScope.PUBLIC && it.trustMode != TrustMode.PINNED }
                ?.mapNotNull { hostOf(it.url) }
                ?.toSet()
                ?: emptySet()
            val added = next.endpoints.any {
                it.scope == EndpointScope.PUBLIC &&
                    it.trustMode != TrustMode.PINNED &&
                    hostOf(it.url) !in trusted
            }
            if (added) fail("网络不能新增未扫码绑定的公网信任模式")
        }
        if (previous != null && previous.deviceId != next.deviceId && !previous.deviceId.startsWith("legacy-")) {
            fail("设备身份变化")
        }
    }

    fun hostOf(url: String): String? = try {
        URI(url).host?.lowercase()?.removePrefix("[")?.removeSuffix("]")
    } catch (_: Exception) {
        null
    }

    fun isValidCredential(value: String?): Boolean = value != null && CREDENTIAL.matches(value)

    // ===== 工具 =====

    private fun record(input: Any?, keys: Set<String>): JSONObject {
        if (input !is JSONObject) fail("连接资料必须是普通对象")
        val names = input.keys()
        while (names.hasNext()) {
            if (names.next() !in keys) fail("连接资料包含未知字段")
        }
        return input
    }

    /** JS `Number.isSafeInteger` 语义：非整数、超出 ±2^53-1、非数字一律 null。 */
    private fun safeInteger(value: Any?): Long? {
        val number = when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Double -> {
                if (!value.isFinite() || value != Math.floor(value)) return null
                if (value > 9_007_199_254_740_991.0 || value < -9_007_199_254_740_991.0) return null
                value.toLong()
            }

            is Number -> value.toLong()
            else -> return null
        }
        if (number > 9_007_199_254_740_991L || number < -9_007_199_254_740_991L) return null
        return number
    }

    private fun fail(message: String): Nothing = throw RemoteValidationException(message)
}

/** 字段缺失或显式 null 返回 null；数字等非字符串值走 optString 的字符串化结果，由调用方判定非法。 */
internal fun JSONObject.stringOrNull(key: String): String? =
    if (!has(key) || isNull(key)) null else optString(key)
