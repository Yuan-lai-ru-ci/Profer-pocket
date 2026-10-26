package com.profer.pocket.remote

import org.json.JSONArray
import org.json.JSONObject
import java.net.URI
import java.util.UUID

/** 注册表持久化：SharedPreferences 或测试内存实现。写入失败必须抛出，不能静默丢资料。 */
interface RegistryStorage {
    fun read(key: String): String?

    fun write(key: String, value: String)

    fun remove(key: String)
}

/** 凭据加密：Android Keystore AES-GCM；测试用可逆假实现。 */
interface RegistryCipher {
    fun encrypt(plain: String): String?

    fun decrypt(stored: String): String?
}

/**
 * 多设备注册表：`devices[]` + `activeId` + 每设备加密 credential。
 *
 * 安全约束：
 * - 凭据只以密文落盘，明文只在内存；
 * - 资料损坏、schema 未知、credential 解不开一律抛 [RemoteRegistryException]，
 *   让 UI 提示重新扫码，绝不静默清空或回退到明文；
 * - 移除设备先删凭据再改列表，列表写失败时恢复凭据，避免「列表没了但密钥还在」。
 */
class DeviceRegistry(
    private val storage: RegistryStorage,
    private val cipher: RegistryCipher,
    private val clock: () -> Long = System::currentTimeMillis,
    private val newId: () -> String = { UUID.randomUUID().toString() },
) {

    data class Snapshot(
        val devices: List<RemoteDevice> = emptyList(),
        val activeId: String? = null,
    ) {
        fun active(): RemoteDevice? = devices.firstOrNull { it.profile.deviceId == activeId }
    }

    /**
     * 解密结果内存缓存：UI 每次连接状态变化都会问「有没有凭据」，
     * Keystore 解密是跨进程调用，不能落在主线程热路径上。
     */
    private val credentialCache = mutableMapOf<String, String>()

    @Synchronized
    fun load(): Snapshot {
        val raw = storage.read(KEY_DEVICES) ?: return Snapshot()
        val parsed = try {
            JSONObject(raw)
        } catch (error: Exception) {
            throw RemoteRegistryException("设备资料损坏，请重新扫码", error)
        }
        if (parsed.optInt("version", -1) != SCHEMA_VERSION) {
            throw RemoteRegistryException("设备资料版本不支持，请重新扫码")
        }
        val array = parsed.opt("devices")
        if (array !is JSONArray || array.length() > MAX_DEVICES) {
            throw RemoteRegistryException("设备资料损坏，请重新扫码")
        }
        val devices = buildList {
            for (index in 0 until array.length()) {
                val entry = array.opt(index)
                if (entry !is JSONObject) throw RemoteRegistryException("设备资料损坏，请重新扫码")
                val profile = try {
                    RemoteValidation.parseProfile(entry.opt("profile"))
                } catch (error: RemoteValidationException) {
                    throw RemoteRegistryException("设备资料损坏，请重新扫码", error)
                }
                val mode = TransportMode.fromWire(entry.stringOrNull("transportMode"))
                    ?: throw RemoteRegistryException("设备资料损坏，请重新扫码")
                add(RemoteDevice(profile, mode))
            }
        }
        if (devices.map { it.profile.deviceId }.toSet().size != devices.size) {
            throw RemoteRegistryException("设备资料损坏，请重新扫码")
        }
        val activeId = parsed.opt("activeId") as? String
        if (activeId != null && devices.none { it.profile.deviceId == activeId }) {
            throw RemoteRegistryException("设备资料损坏，请重新扫码")
        }
        return Snapshot(devices, activeId)
    }

    /** 读取并解密某台设备的 credential；缺失返回 null，损坏抛异常（安全失败）。 */
    @Synchronized
    fun credential(deviceId: String): String? {
        credentialCache[deviceId]?.let { return it }
        val stored = storage.read(credentialKey(deviceId)) ?: return null
        val plain = cipher.decrypt(stored)
            ?: throw RemoteRegistryException("凭据不可用，请重新扫码")
        if (plain.isBlank()) throw RemoteRegistryException("凭据不可用，请重新扫码")
        credentialCache[deviceId] = plain
        return plain
    }

    /** 新增或替换设备（重新扫码视为重新建立信任锚）。 */
    @Synchronized
    fun save(profile: RemoteProfile, credential: String, mode: TransportMode): Snapshot {
        // v1 的 credential 由桌面生成，固定 64 位 hex；手动连接的 token 是历史格式，只限长度。
        val credentialOk = if (mode == TransportMode.V1) {
            RemoteValidation.isValidCredential(credential)
        } else {
            credential.isNotBlank() && credential.length <= 1024
        }
        if (!credentialOk) {
            throw RemoteRegistryException("凭据格式无效，请重新扫码")
        }
        val current = load()
        val encrypted = cipher.encrypt(credential)
            ?: throw RemoteRegistryException("安全存储不可用，请重新扫码")
        val previous = storage.read(credentialKey(profile.deviceId))
        storage.write(credentialKey(profile.deviceId), encrypted)
        credentialCache[profile.deviceId] = credential
        val next = Snapshot(
            devices = current.devices
                .filterNot { it.profile.deviceId == profile.deviceId }
                .plus(RemoteDevice(profile, mode)),
            activeId = profile.deviceId,
        )
        try {
            persist(next)
        } catch (error: Exception) {
            restoreCredential(profile.deviceId, previous)
            credentialCache.remove(profile.deviceId)
            throw error
        }
        return next
    }

    @Synchronized
    fun select(deviceId: String): Snapshot {
        val current = load()
        if (current.devices.none { it.profile.deviceId == deviceId }) {
            throw RemoteRegistryException("设备不存在，请重新扫码")
        }
        // 先确认凭据可解，避免切到一台连不上的设备。
        credential(deviceId) ?: throw RemoteRegistryException("凭据不可用，请重新扫码")
        val next = current.copy(activeId = deviceId)
        persist(next)
        return next
    }

    @Synchronized
    fun remove(deviceId: String): Snapshot {
        val current = load()
        if (current.devices.none { it.profile.deviceId == deviceId }) return current
        val key = credentialKey(deviceId)
        val previous = storage.read(key)
        if (previous != null) storage.remove(key)
        credentialCache.remove(deviceId)
        val next = Snapshot(
            devices = current.devices.filterNot { it.profile.deviceId == deviceId },
            activeId = current.activeId?.takeIf { it != deviceId },
        )
        try {
            persist(next)
        } catch (error: Exception) {
            restoreCredential(deviceId, previous)
            credentialCache.remove(deviceId)
            throw error
        }
        return next
    }

    /** 服务端 `connection_profile` 更新：只允许改地址，不允许改信任锚或设备身份。 */
    @Synchronized
    fun updateProfile(profile: RemoteProfile): Snapshot {
        val current = load()
        val existing = current.devices.firstOrNull { it.profile.deviceId == profile.deviceId }
            ?: throw RemoteRegistryException("设备未绑定，请重新扫码")
        RemoteValidation.assertProfileTrustUnchanged(existing.profile, profile)
        val next = current.copy(
            devices = current.devices.map {
                if (it.profile.deviceId == profile.deviceId) it.copy(profile = profile) else it
            },
        )
        persist(next)
        return next
    }

    /** 旧版单 endpoint + token 迁移：生成 `legacy-<uuid>` 手动资料，token 保留。 */
    @Synchronized
    fun migrateLegacy(endpoint: String, token: String): Snapshot {
        val profile = manualProfile(endpoint, "legacy-${newId()}", clock())
        return save(profile, token, TransportMode.LEGACY_QUERY)
    }

    fun manualProfile(endpoint: String, deviceId: String, updatedAt: Long = clock(), pin: String? = null): RemoteProfile {
        val host = try {
            URI(endpoint).host?.lowercase()
        } catch (_: Exception) {
            null
        } ?: throw RemoteValidationException("地址格式不正确")
        val scope = if (RemoteValidation.isPrivateHost(host)) EndpointScope.LAN else EndpointScope.PUBLIC
        val endpointJson = JSONObject().put("url", endpoint).put("scope", scope.wire)
        if (scope == EndpointScope.PUBLIC) {
            // 公网自签端点必须带 pin；不带 pin 则按系统可信证书处理（用户自己的反代）。
            if (pin != null) {
                endpointJson
                    .put("trustMode", TrustMode.PINNED.wire)
                    .put("pin", JSONObject().put("algorithm", RemotePin.ALGORITHM).put("value", pin))
            } else {
                endpointJson.put("trustMode", TrustMode.SYSTEM.wire)
            }
        }
        val validated = RemoteValidation.validateEndpoint(endpointJson)
        return RemoteProfile(
            schemaVersion = if (scope == EndpointScope.PUBLIC) 2 else 1,
            deviceId = deviceId,
            deviceName = "手动连接",
            updatedAt = updatedAt,
            endpoints = listOf(validated),
        )
    }

    private fun persist(snapshot: Snapshot) {
        val devices = JSONArray()
        snapshot.devices.forEach { device ->
            devices.put(
                JSONObject()
                    .put("profile", JSONObject(device.profile.toJson()))
                    .put("transportMode", device.transportMode.wire),
            )
        }
        val json = JSONObject()
            .put("version", SCHEMA_VERSION)
            .put("activeId", snapshot.activeId ?: JSONObject.NULL)
            .put("devices", devices)
        storage.write(KEY_DEVICES, json.toString())
    }

    private fun restoreCredential(deviceId: String, previous: String?) {
        try {
            if (previous == null) storage.remove(credentialKey(deviceId)) else storage.write(credentialKey(deviceId), previous)
        } catch (_: Exception) {
            // 恢复失败时保持原异常上抛；调用方会提示重新扫码。
        }
    }

    private fun credentialKey(deviceId: String): String = "$KEY_CREDENTIAL_PREFIX$deviceId"

    private companion object {
        const val SCHEMA_VERSION = 1
        const val MAX_DEVICES = 64
        const val KEY_DEVICES = "profer-remote-devices-v1"
        const val KEY_CREDENTIAL_PREFIX = "profer-remote-credential:"
    }
}
