package com.profer.pocket.data

import com.profer.pocket.data.net.OutgoingMessage
import com.profer.pocket.data.net.PocketConnection
import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.OfflineCause
import com.profer.pocket.remote.EndpointScope
import com.profer.pocket.remote.RemoteEndpoint
import com.profer.pocket.remote.RemotePin
import com.profer.pocket.remote.RemoteProfile
import com.profer.pocket.remote.TrustMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1 连接：`remote_hello → authenticate → hello(profile)` 的先后顺序、
 * LAN→公网回退、profile 刷新断言。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PocketConnectionV1Test {

    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private val profile = RemoteProfile(
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
                pin = RemotePin(RemotePin.ALGORITHM, pin),
            ),
        ),
    )
    private val credential = "c".repeat(64)

    private fun connection(factory: FakeSocketFactory, scope: TestScope) = PocketConnection(
        transports = factory,
        scope = scope.backgroundScope,
        resumeState = FakeResumeStateStore(),
        clock = { 1_000L },
    )

    @Test
    fun `v1 does not connect before remote hello`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val socket = factory.created.single()
        socket.open()
        assertEquals(ConnectionStatus.Connecting, connection.status.value)
        assertTrue(socket.sent.isEmpty())
    }

    @Test
    fun `hello before remote hello is ignored and never connects`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        // 服务端顺序异常：跳过 remote_hello，先发带 profile 的 hello。
        lan.receive(helloWithProfile(profile))
        assertEquals(ConnectionStatus.Connecting, connection.status.value)
        assertTrue(lan.sent.isEmpty())

        // 顺序恢复正常：remote_hello 之后才发 authenticate，此时仍未 Connected。
        lan.receive(remoteHello("desktop-123", 1))
        val authenticate = JSONObject(lan.sent.single())
        assertEquals("authenticate", authenticate.getString("type"))
        assertEquals("desktop-123", authenticate.getString("deviceId"))
        assertEquals(credential, authenticate.getString("credential"))
        assertFalse(connection.status.value is ConnectionStatus.Connected)

        lan.receive(helloWithProfile(profile))
        val status = connection.status.value as ConnectionStatus.Connected
        assertEquals(profile.endpoints[0].url, status.endpoint)
    }

    @Test
    fun `duplicate hello after ready does not flush queue again`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive(helloWithProfile(profile))
        assertTrue(connection.status.value is ConnectionStatus.Connected)

        // 发送失败会让消息留在队列里：再来一次 flush 就会多一次发送尝试，可观测。
        lan.sendResult = false
        connection.sendMessage(
            OutgoingMessage(
                cmdId = "send-1",
                clientMessageId = "client-1",
                frame = FrameCodec.sendMessage("send-1", "client-1", "s1", "hi", "c1", null, null, null, 1),
            ),
        )
        val sentAfterQueued = lan.sent.size

        lan.receive(helloWithProfile(profile))
        assertTrue(connection.status.value is ConnectionStatus.Connected)
        assertEquals("重复 hello 不应再次 flush 队列", sentAfterQueued, lan.sent.size)
    }

    @Test
    fun `authenticate is sent only after matching remote hello`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        val authenticate = JSONObject(lan.sent.single())
        assertEquals("authenticate", authenticate.getString("type"))
        assertEquals("desktop-123", authenticate.getString("deviceId"))
        assertEquals(credential, authenticate.getString("credential"))
        assertFalse(connection.status.value is ConnectionStatus.Connected)

        lan.receive(helloWithProfile(profile))
        val status = connection.status.value as ConnectionStatus.Connected
        assertEquals(profile.endpoints[0].url, status.endpoint)
    }

    @Test
    fun `device mismatch falls back to the public endpoint without sending credential`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created[0]
        lan.open()
        lan.receive(remoteHello("other-device", 1))
        assertTrue(lan.sent.isEmpty())
        assertEquals(2, factory.created.size)
        val public = factory.created[1]
        assertEquals(profile.endpoints[1].url, public.lastUrl)
        assertEquals(TrustMode.PINNED, public.lastTarget?.endpoint?.trustMode)
        assertTrue(public.lastTarget?.subprotocols?.contains("profer.remote.v1") == true)
    }

    @Test
    fun `lan timeout advances to public endpoint`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        assertEquals(1, factory.created.size)
        advanceTimeBy(2_600)
        runCurrent()
        assertEquals(2, factory.created.size)
        assertEquals(profile.endpoints[1].url, factory.created[1].lastUrl)
        assertEquals("端点探测超时", connection.endpointFailures.value[profile.endpoints[0].url])
    }

    @Test
    fun `rotated pin in hello is rejected and stops reconnecting`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive(helloWithProfile(profileWithRotatedPin()))
        val offline = connection.status.value as ConnectionStatus.Offline
        assertEquals(OfflineCause.Unauthorized, offline.cause)
        assertTrue(offline.message.orEmpty().contains("TLS 信任锚变化"))
        // 信任锚变化必须断开，且不再尝试下一个端点。
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `connection profile update with new lan address is adopted`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        val updates = mutableListOf<RemoteProfile>()
        backgroundScope.launch { connection.profileUpdates.collect { updates += it } }
        runCurrent()
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive(helloWithProfile(profile))
        runCurrent()
        assertEquals(1, updates.size)

        val moved = profile.copy(
            updatedAt = 2,
            endpoints = listOf(
                RemoteEndpoint("ws://192.168.1.77:7788/ws", EndpointScope.LAN),
                profile.endpoints[1],
            ),
        )
        lan.receive(connectionProfile(moved))
        runCurrent()
        assertEquals(2, updates.size)
        assertEquals("ws://192.168.1.77:7788/ws", updates.last().endpoints[0].url)
        assertTrue(connection.status.value is ConnectionStatus.Connected)
    }

    @Test
    fun `connection profile update with rotated pin disconnects`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive(helloWithProfile(profile))
        assertTrue(connection.status.value is ConnectionStatus.Connected)

        lan.receive(connectionProfile(profileWithRotatedPin()))
        val offline = connection.status.value as ConnectionStatus.Offline
        assertEquals(OfflineCause.Unauthorized, offline.cause)
        assertEquals(4001 to "trust changed", lan.closedWith)
    }

    @Test
    fun `hello without profile is treated as endpoint failure`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive("""{"kind":"hello","serverTime":1,"serverInstanceId":"srv-1","latestAgentEventId":1,"oldestAgentEventId":1}""")
        assertFalse(connection.status.value is ConnectionStatus.Connected)
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `4001 stops reconnecting in v1 mode`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, this)
        connection.connect(profile, credential)
        val lan = factory.created.single()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.closeFromServer(4001, "authentication failed")
        val offline = connection.status.value as ConnectionStatus.Offline
        assertEquals(OfflineCause.Unauthorized, offline.cause)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, factory.created.size)
    }

    private fun profileWithRotatedPin(): RemoteProfile = profile.copy(
        updatedAt = 3,
        endpoints = listOf(
            profile.endpoints[0],
            profile.endpoints[1].copy(
                pin = RemotePin(RemotePin.ALGORITHM, "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBE="),
            ),
        ),
    )

    private fun remoteHello(deviceId: String, protocolVersion: Int): String = JSONObject()
        .put("kind", "remote_hello")
        .put("protocolVersion", protocolVersion)
        .put("deviceId", deviceId)
        .toString()

    private fun helloWithProfile(profile: RemoteProfile): String = JSONObject()
        .put("kind", "hello")
        .put("serverTime", 1)
        .put("serverInstanceId", "srv-1")
        .put("latestAgentEventId", 1)
        .put("oldestAgentEventId", 1)
        .put("protocolVersion", 1)
        .put("deviceId", profile.deviceId)
        .put("profile", JSONObject(profile.toJson()))
        .toString()

    private fun connectionProfile(profile: RemoteProfile): String = JSONObject()
        .put("kind", "connection_profile")
        .put("profile", JSONObject(profile.toJson()))
        .toString()
}
