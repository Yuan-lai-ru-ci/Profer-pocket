package com.profer.pocket.remote

/**
 * 远程连接资料模型：字段与桌面 `packages/shared/src/remote-connection.ts` 一一对应。
 *
 * 这里的类型只表达「已经通过校验」的资料，构造路径只有 [RemoteValidation]；
 * 解析失败一律抛 [RemoteValidationException]，不做字段补全。
 */
const val REMOTE_PROTOCOL = "profer.remote.v1"
const val PAIRING_TTL_MS = 300_000L

enum class EndpointScope(val wire: String) {
    LAN("lan"),
    PUBLIC("public"),
    ;

    companion object {
        fun fromWire(value: String?): EndpointScope? = entries.firstOrNull { it.wire == value }
    }
}

enum class TrustMode(val wire: String) {
    SYSTEM("system"),
    PINNED("pinned"),
    ;

    companion object {
        fun fromWire(value: String?): TrustMode? = entries.firstOrNull { it.wire == value }
    }
}

data class RemotePin(
    val algorithm: String,
    /** `sha256/<base64>` 形式的规范编码。 */
    val value: String,
) {
    companion object {
        const val ALGORITHM = "spki-sha256"
    }
}

data class RemoteEndpoint(
    /** 规范化后的 `ws(s)://host[:port]/ws`。 */
    val url: String,
    val scope: EndpointScope,
    val trustMode: TrustMode? = null,
    val pin: RemotePin? = null,
) {
    val isPinned: Boolean get() = trustMode == TrustMode.PINNED

    /** 与旧原生控制器一致：局域网 2.5s、公网 8s 的端点级超时。 */
    val timeoutMs: Long get() = if (scope == EndpointScope.LAN) 2_500 else 8_000
}

data class RemoteProfile(
    val schemaVersion: Int,
    val deviceId: String,
    val deviceName: String,
    val updatedAt: Long,
    val endpoints: List<RemoteEndpoint>,
) {
    /** 局域网优先、公网回退；同 scope 内保持服务端给的顺序。 */
    fun orderedEndpoints(): List<RemoteEndpoint> =
        endpoints.sortedBy { if (it.scope == EndpointScope.PUBLIC) 1 else 0 }

    fun toJson(): String = RemoteJson.encodeProfile(this)
}

data class PairingQr(
    val profile: RemoteProfile,
    val pairingCode: String,
    val issuedAt: Long,
    val expiresAt: Long,
) {
    val deviceId: String get() = profile.deviceId
    val endpoints: List<RemoteEndpoint> get() = profile.endpoints

    fun isExpired(now: Long): Boolean = expiresAt <= now
}

/** 校验失败：message 直接用于 UI（与桌面同一套中文文案）。 */
class RemoteValidationException(message: String) : Exception(message)

/** 设备资料损坏 / Keystore 不可用：要求用户重新扫码，绝不回退到明文或空资料。 */
class RemoteRegistryException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** 远程连接模式：扫码绑定的 v1 握手，或手动地址 + `?token=` 的旧私网路径。 */
enum class TransportMode(val wire: String) {
    V1("v1"),
    LEGACY_QUERY("legacy-query"),
    ;

    companion object {
        fun fromWire(value: String?): TransportMode? = entries.firstOrNull { it.wire == value }
    }
}

data class RemoteDevice(
    val profile: RemoteProfile,
    val transportMode: TransportMode,
)
