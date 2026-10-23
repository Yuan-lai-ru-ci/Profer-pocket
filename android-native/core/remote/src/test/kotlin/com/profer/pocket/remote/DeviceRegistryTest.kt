package com.profer.pocket.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 设备注册表：多设备持久化、凭据加密、迁移、损坏安全失败、信任锚断言。
 */
class DeviceRegistryTest {

    private val device = RemoteProfile(
        schemaVersion = 2,
        deviceId = "desktop-123",
        deviceName = "Mac",
        updatedAt = 1,
        endpoints = listOf(
            RemoteEndpoint("ws://192.168.1.2:7788/ws", EndpointScope.LAN),
            RemoteEndpoint(
                url = "wss://203.0.113.10:8443/ws",
                scope = EndpointScope.PUBLIC,
                trustMode = TrustMode.PINNED,
                pin = RemotePin(RemotePin.ALGORITHM, "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="),
            ),
        ),
    )

    private fun registry(
        storage: MemoryStorage = MemoryStorage(),
        cipher: RegistryCipher = FakeCipher(),
    ) = DeviceRegistry(storage, cipher, clock = { 1_761_100_000_000L }, newId = { "uuid-1" })

    @Test
    fun `save then load keeps profile and credential`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        val snapshot = registry.save(device, "a".repeat(64), TransportMode.V1)
        assertEquals(device.deviceId, snapshot.activeId)

        val reloaded = registry(storage).load()
        assertEquals(1, reloaded.devices.size)
        assertEquals(TransportMode.V1, reloaded.devices[0].transportMode)
        assertEquals(device.deviceId, reloaded.devices[0].profile.deviceId)
        assertEquals("a".repeat(64), registry(storage).credential(device.deviceId))
        // 凭据不得以明文落盘。
        assertTrue(storage.dump().none { it.contains("a".repeat(64)) })
    }

    @Test
    fun `select switches active device and validates credential`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        val second = device.copy(deviceId = "desktop-456", deviceName = "Office")
        registry.save(device, "a".repeat(64), TransportMode.V1)
        registry.save(second, "b".repeat(64), TransportMode.V1)
        assertEquals(second.deviceId, registry.load().activeId)

        val switched = registry.select(device.deviceId)
        assertEquals(device.deviceId, switched.activeId)
    }

    @Test
    fun `select rejects unknown device`() {
        val registry = registry()
        try {
            registry.select("missing-device")
            fail("预期拒绝")
        } catch (error: RemoteRegistryException) {
            assertTrue(error.message.orEmpty().contains("设备不存在"))
        }
    }

    @Test
    fun `remove clears credential and active id`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        registry.save(device, "a".repeat(64), TransportMode.V1)
        val next = registry.remove(device.deviceId)
        assertNull(next.activeId)
        assertTrue(next.devices.isEmpty())
        assertNull(registry.credential(device.deviceId))
        assertTrue(storage.dump().none { it.contains("profer-remote-credential:") })
    }

    @Test
    fun `legacy migration keeps token and uses legacy profile`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        val snapshot = registry.migrateLegacy("ws://192.168.1.2:7788/ws", "legacy-token")
        val migrated = snapshot.devices.single()
        assertEquals("legacy-uuid-1", migrated.profile.deviceId)
        assertEquals(TransportMode.LEGACY_QUERY, migrated.transportMode)
        assertEquals("legacy-token", registry.credential(migrated.profile.deviceId))
        assertEquals("ws://192.168.1.2:7788/ws", migrated.profile.endpoints.single().url)
    }

    @Test
    fun `corrupted registry fails safely`() {
        val storage = MemoryStorage()
        storage.write("profer-remote-devices-v1", "{not-json")
        try {
            registry(storage).load()
            fail("预期拒绝")
        } catch (error: RemoteRegistryException) {
            assertTrue(error.message.orEmpty().contains("设备资料损坏"))
        }
        // 不得静默清空原资料。
        assertEquals("{not-json", storage.read("profer-remote-devices-v1"))
    }

    @Test
    fun `unknown schema version fails safely`() {
        val storage = MemoryStorage()
        storage.write("profer-remote-devices-v1", JSONObject().put("version", 99).toString())
        try {
            registry(storage).load()
            fail("预期拒绝")
        } catch (error: RemoteRegistryException) {
            assertTrue(error.message.orEmpty().contains("版本不支持"))
        }
    }

    @Test
    fun `active id missing from devices fails safely`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        registry.save(device, "a".repeat(64), TransportMode.V1)
        val raw = JSONObject(storage.read("profer-remote-devices-v1")!!)
        raw.put("activeId", "ghost-device")
        storage.write("profer-remote-devices-v1", raw.toString())
        try {
            registry(storage).load()
            fail("预期拒绝")
        } catch (error: RemoteRegistryException) {
            assertTrue(error.message.orEmpty().contains("设备资料损坏"))
        }
    }

    @Test
    fun `undecryptable credential fails safely`() {
        val storage = MemoryStorage()
        val cipher = FakeCipher()
        val registry = registry(storage, cipher)
        registry.save(device, "a".repeat(64), TransportMode.V1)
        cipher.broken = true
        // 冷启动：缓存里没有明文，必须真的去解密。
        try {
            registry(storage, cipher).credential(device.deviceId)
            fail("预期拒绝")
        } catch (error: RemoteRegistryException) {
            assertTrue(error.message.orEmpty().contains("凭据不可用"))
        }
    }

    @Test
    fun `profile update rejects pin rotation`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        registry.save(device, "a".repeat(64), TransportMode.V1)
        val rotated = device.copy(
            updatedAt = 2,
            endpoints = listOf(
                device.endpoints[0],
                device.endpoints[1].copy(
                    pin = RemotePin(RemotePin.ALGORITHM, "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBE="),
                ),
            ),
        )
        try {
            registry.updateProfile(rotated)
            fail("预期拒绝")
        } catch (error: RemoteValidationException) {
            assertTrue(error.message.orEmpty().contains("TLS 信任锚变化"))
        }
        assertEquals(device.endpoints[1].pin, registry.load().devices.single().profile.endpoints[1].pin)
    }

    @Test
    fun `profile update adopts new lan address`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        registry.save(device, "a".repeat(64), TransportMode.V1)
        val moved = device.copy(
            updatedAt = 2,
            endpoints = listOf(
                RemoteEndpoint("ws://192.168.1.77:7788/ws", EndpointScope.LAN),
                device.endpoints[1],
            ),
        )
        registry.updateProfile(moved)
        assertEquals(
            "ws://192.168.1.77:7788/ws",
            registry.load().devices.single().profile.endpoints[0].url,
        )
    }

    @Test
    fun `failed registry write rolls back credential`() {
        val storage = MemoryStorage()
        val registry = registry(storage)
        registry.save(device, "a".repeat(64), TransportMode.V1)
        storage.failWrites = true
        try {
            registry.save(device.copy(deviceName = "Renamed"), "b".repeat(64), TransportMode.V1)
            fail("预期写入失败")
        } catch (error: Exception) {
            assertNotNull(error)
        }
        storage.failWrites = false
        // 旧凭据仍可用，新凭据没有残留成孤儿。
        assertEquals("a".repeat(64), registry.credential(device.deviceId))
    }

    @Test
    fun `manual profile for public wss uses system trust and schema 2`() {
        val profile = registry().manualProfile("wss://remote.example.com/ws", "legacy-uuid-1")
        assertEquals(2, profile.schemaVersion)
        assertEquals(TrustMode.SYSTEM, profile.endpoints.single().trustMode)
        assertEquals(EndpointScope.PUBLIC, profile.endpoints.single().scope)
    }

    @Test
    fun `manual profile for private lan stays schema 1`() {
        val profile = registry().manualProfile("ws://192.168.1.2:7788/ws", "legacy-uuid-1")
        assertEquals(1, profile.schemaVersion)
        assertNull(profile.endpoints.single().trustMode)
    }
}

private class MemoryStorage : RegistryStorage {
    private val values = mutableMapOf<String, String>()
    var failWrites = false

    override fun read(key: String): String? = values[key]

    override fun write(key: String, value: String) {
        if (failWrites) throw IllegalStateException("write failed")
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    fun dump(): List<String> = values.values.toList()
}

private class FakeCipher : RegistryCipher {
    var broken = false

    override fun encrypt(plain: String): String? =
        "enc:" + java.util.Base64.getEncoder().encodeToString(plain.toByteArray())

    override fun decrypt(stored: String): String? {
        if (broken || !stored.startsWith("enc:")) return null
        return String(java.util.Base64.getDecoder().decode(stored.removePrefix("enc:")))
    }
}
