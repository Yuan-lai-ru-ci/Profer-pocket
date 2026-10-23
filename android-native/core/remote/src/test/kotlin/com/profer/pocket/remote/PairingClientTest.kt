package com.profer.pocket.remote

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 配对状态机：`remote_hello → pair → paired`。
 *
 * 重点覆盖：LAN 优先/公网回退、身份不匹配即停、4001 拒绝即停、
 * 发出 pair 后超时归为「配对结果未确认」、paired 里的 pin 变化必须拒绝。
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PairingClientTest {

    private val now = 1_761_100_000_000L
    private val profile = RemoteProfile(
        schemaVersion = 2,
        deviceId = "desktop-123",
        deviceName = "Test",
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
    private val qr = PairingQr(
        profile = profile,
        pairingCode = "a".repeat(64),
        issuedAt = now - 10_000,
        expiresAt = now + 290_000,
    )

    @Test
    fun `happy path sends pair after remote hello`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { client(sockets).pair(qr) }
        val socket = sockets.await()
        assertEquals(profile.endpoints[0].url, socket.endpoint.url)
        socket.open()
        socket.receive(remoteHello("desktop-123", 1))
        val pair = socket.sent.single()
        assertEquals("pair", pair.getString("type"))
        assertEquals(qr.pairingCode, pair.getString("pairingCode"))
        assertEquals("Pocket", pair.getString("deviceName"))
        socket.receive(pairedMessage(profile, "b".repeat(64)))
        val result = deferred.await()
        assertEquals("b".repeat(64), result.credential)
        assertEquals(profile.deviceId, result.profile.deviceId)
        // 拿到 paired 就收尾：socket 主动 close(1000)，不等服务端。
        assertEquals(1000, socket.closeCode)
    }

    @Test
    fun `falls back from lan to public endpoint`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { client(sockets).pair(qr) }
        val lan = sockets.await(0)
        lan.fail()
        val public = sockets.await(1)
        assertEquals(EndpointScope.PUBLIC, public.endpoint.scope)
        public.open()
        public.receive(remoteHello("desktop-123", 1))
        public.receive(pairedMessage(profile, "c".repeat(64)))
        assertEquals("c".repeat(64), deferred.await().credential)
    }

    @Test
    fun `device mismatch stops without trying public endpoint`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { runCatching { client(sockets).pair(qr) } }
        val lan = sockets.await()
        lan.open()
        lan.receive(remoteHello("other-device", 1))
        val failure = failureOf(deferred.await())
        assertEquals(PairingFailureKind.DeviceMismatch, failure.kind)
        assertEquals(1, sockets.opened.size)
        assertTrue(lan.sent.isEmpty())
        assertEquals(1000, lan.closeCode)
    }

    @Test
    fun `rejected pairing stops without fallback`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { runCatching { client(sockets).pair(qr) } }
        val lan = sockets.await()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.closeFromServer(4001)
        val failure = failureOf(deferred.await())
        assertEquals(PairingFailureKind.Rejected, failure.kind)
        assertEquals(1, sockets.opened.size)
        assertEquals(1000, lan.closeCode)
    }

    @Test
    fun `timeout after pair is reported as unconfirmed`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { runCatching { client(sockets, lanTimeoutMs = 100).pair(qr) } }
        val lan = sockets.await()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        val failure = failureOf(deferred.await())
        assertEquals(PairingFailureKind.Unconfirmed, failure.kind)
        assertTrue(failure.message.orEmpty().contains("配对结果未确认"))
        // 超时取消路径也必须关掉 socket。
        assertEquals(1000, lan.closeCode)
    }

    @Test
    fun `paired before pair request is rejected`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { runCatching { client(sockets).pair(qr) } }
        val lan = sockets.await()
        lan.open()
        lan.receive(pairedMessage(profile, "d".repeat(64)))
        assertEquals(PairingFailureKind.Unconfirmed, failureOf(deferred.await()).kind)
    }

    @Test
    fun `pin change in paired profile is rejected`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { runCatching { client(sockets).pair(qr) } }
        val lan = sockets.await()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        val rotated = profile.copy(
            endpoints = listOf(
                profile.endpoints[0],
                profile.endpoints[1].copy(
                    pin = RemotePin(RemotePin.ALGORITHM, "sha256/BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBE="),
                ),
            ),
        )
        lan.receive(pairedMessage(rotated, "e".repeat(64)))
        val failure = failureOf(deferred.await())
        assertEquals(PairingFailureKind.Unconfirmed, failure.kind)
        assertTrue(failure.message.orEmpty().contains("TLS 信任锚变化"))
    }

    @Test
    fun `expired qr text is rejected before opening sockets`() = runTest {
        val sockets = FakeSockets()
        val text = JSONObject()
            .put("kind", "profer-pairing")
            .put("schemaVersion", 1)
            .put("deviceId", "desktop-123")
            .put("deviceName", "Test")
            .put("updatedAt", 1)
            .put(
                "endpoints",
                JSONArray().put(JSONObject().put("url", "ws://192.168.1.2:7788/ws").put("scope", "lan")),
            )
            .put("pairingCode", "a".repeat(64))
            .put("issuedAt", now - 400_000)
            .put("expiresAt", now - 100_000)
            .toString()
        val failure = failureOf(runCatching { client(sockets).pair(text) })
        assertEquals(PairingFailureKind.InvalidQr, failure.kind)
        assertTrue(failure.message.orEmpty().contains("二维码已过期"))
        assertTrue(sockets.opened.isEmpty())
    }

    @Test
    fun `invalid credential is rejected`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { runCatching { client(sockets).pair(qr) } }
        val lan = sockets.await()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive(pairedMessage(profile, "SHORT"))
        assertEquals(PairingFailureKind.DeviceMismatch, failureOf(deferred.await()).kind)
    }

    @Test
    fun `pairing code never leaks into result`() = runTest {
        val sockets = FakeSockets()
        val deferred = backgroundScope.async { client(sockets).pair(qr) }
        val lan = sockets.await()
        lan.open()
        lan.receive(remoteHello("desktop-123", 1))
        lan.receive(pairedMessage(profile, "f".repeat(64)))
        val result = deferred.await()
        assertTrue(!result.toString().contains(qr.pairingCode))
    }

    private fun TestScope.client(sockets: FakeSockets, lanTimeoutMs: Long = 2_500) = PairingClient(
        sockets = sockets,
        clock = { now },
        lanTimeoutMs = lanTimeoutMs,
        publicTimeoutMs = 8_000,
    )

    private fun failureOf(result: Result<PairedDevice>): PairingException {
        val error = result.exceptionOrNull()
        assertTrue("预期 PairingException，实际 $error", error is PairingException)
        return error as PairingException
    }

    private fun remoteHello(deviceId: String, protocolVersion: Int): JSONObject = JSONObject()
        .put("kind", "remote_hello")
        .put("protocolVersion", protocolVersion)
        .put("deviceId", deviceId)

    private fun pairedMessage(profile: RemoteProfile, credential: String): JSONObject = JSONObject()
        .put("kind", "paired")
        .put("credential", credential)
        .put("bindingId", "binding-1")
        .put("profile", JSONObject(profile.toJson()))
}

private class FakeSockets : RemoteSocketFactory {
    val opened = mutableListOf<FakePairingSocket>()

    override fun open(endpoint: RemoteEndpoint, listener: RemoteSocket.Listener): RemoteSocket =
        FakePairingSocket(endpoint, listener).also { opened += it }

    suspend fun await(index: Int = opened.size): FakePairingSocket {
        while (opened.size <= index) yield()
        return opened[index]
    }
}

private class FakePairingSocket(
    val endpoint: RemoteEndpoint,
    private val listener: RemoteSocket.Listener,
) : RemoteSocket {
    val sent = mutableListOf<JSONObject>()
    /** 客户端主动关闭的 code；null 表示未关闭。 */
    var closeCode: Int? = null
    val closed: Boolean get() = closeCode != null

    fun open() = listener.onOpen()

    fun receive(message: JSONObject) = listener.onText(message.toString())

    fun fail() = listener.onFailure("connect failed")

    fun closeFromServer(code: Int) = listener.onClosed(code, "")

    override fun send(text: String): Boolean {
        sent += JSONObject(text)
        return true
    }

    override fun close(code: Int) {
        if (closeCode != null) return
        closeCode = code
    }
}
