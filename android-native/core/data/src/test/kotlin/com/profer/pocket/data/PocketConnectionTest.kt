package com.profer.pocket.data

import com.profer.pocket.data.net.BackoffPolicy
import com.profer.pocket.data.net.HeartbeatPolicy
import com.profer.pocket.data.net.OutgoingMessage
import com.profer.pocket.data.net.PocketConnection
import com.profer.pocket.data.net.PocketTimeoutException
import com.profer.pocket.data.net.SendOutcome
import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.OfflineCause
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketConnectionTest {

    private var now = 0L
    private var sequence = 0

    private fun connection(
        factory: FakeSocketFactory,
        store: FakeResumeStateStore = FakeResumeStateStore(),
        scope: kotlinx.coroutines.CoroutineScope,
        queueStore: com.profer.pocket.data.store.OutgoingQueueStore? = null,
    ): PocketConnection = PocketConnection(
        transports = factory,
        scope = scope,
        resumeState = store,
        clock = { now },
        heartbeat = HeartbeatPolicy(intervalMs = 25_000, deadThresholdMs = 75_000),
        backoff = BackoffPolicy(initialMs = 2_000, maxMs = 60_000),
        requestTimeoutMs = 15_000,
        newId = { "cmd-${sequence++}" },
        queueStore = queueStore,
    )

    private fun hello(instance: String = "srv-1", latest: Long = 1): String =
        """{"kind":"hello","serverTime":1,"serverInstanceId":"$instance","latestAgentEventId":$latest,"oldestAgentEventId":1}"""

    @Test
    fun `连接打开后状态为 Connected 并带 token 发起握手`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)

        connection.connect("ws://10.0.0.2:7788/ws", "tok en")
        assertEquals(ConnectionStatus.Connecting, connection.status.value)
        val socket = factory.created.single()
        assertTrue(socket.lastUrl!!.startsWith("ws://10.0.0.2:7788/ws?token="))
        assertTrue(socket.lastUrl!!.contains("tok%20en"))

        socket.open()
        val status = connection.status.value as ConnectionStatus.Connected
        assertEquals("ws://10.0.0.2:7788/ws", status.endpoint)

        socket.receive(hello())
        assertTrue(socket.sent.any { it.contains("resume_agent_events") })
    }

    @Test
    fun `hello 后按已保存游标补发`() = runTest {
        val factory = FakeSocketFactory()
        val store = FakeResumeStateStore().apply {
            save("ws://host/ws", 12, "srv-1")
        }
        val connection = connection(factory, store, backgroundScope)
        connection.connect("ws://host/ws", "t")
        factory.created.single().open()
        factory.created.single().receive(hello("srv-1", latest = 20))

        val resume = factory.created.single().sent.first { it.contains("resume_agent_events") }
        assertEquals(12L, JSONObject(resume).getLong("cursor"))
    }

    @Test
    fun `服务端实例变化时游标归零`() = runTest {
        val factory = FakeSocketFactory()
        val store = FakeResumeStateStore().apply {
            save("ws://host/ws", 12, "srv-old")
        }
        val connection = connection(factory, store, backgroundScope)
        connection.connect("ws://host/ws", "t")
        factory.created.single().open()
        factory.created.single().receive(hello("srv-new"))

        val resume = factory.created.single().sent.first { it.contains("resume_agent_events") }
        assertTrue(JSONObject(resume).isNull("cursor"))
        assertTrue(store.cleared.contains("ws://host/ws"))
    }

    @Test
    fun `重复 eventId 不再分发`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        val received = mutableListOf<Long?>()
        val job = backgroundScope.launch { connection.events.collect { received += it.eventId } }
        runCurrent()

        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()
        socket.receive(hello())
        val event = """{"kind":"agent_event","eventId":5,"sessionId":"s1","payload":{"kind":"profer_event","event":{"type":"run_idle","sessionId":"s1"}}}"""
        socket.receive(event)
        socket.receive(event)
        runCurrent()

        assertEquals(listOf(5L), received)
        job.cancel()
    }

    @Test
    fun `断线按 2s 4s 指数退避重连`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        val first = factory.created.single()
        first.open()
        first.closeFromServer(1006, "网络中断")

        val reconnecting = connection.status.value as ConnectionStatus.Reconnecting
        assertEquals(1, reconnecting.attempt)
        assertEquals(2_000L, reconnecting.delayMs)

        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, factory.created.size)
        val second = factory.created[1]
        second.closeFromServer(1006, "再次中断")
        val secondStatus = connection.status.value as ConnectionStatus.Reconnecting
        assertEquals(2, secondStatus.attempt)
        assertEquals(4_000L, secondStatus.delayMs)
    }

    @Test
    fun `4001 停止重连并进入凭据失效`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "bad-token")
        val socket = factory.created.single()
        socket.open()
        socket.closeFromServer(4001, "unauthorized")

        val offline = connection.status.value as ConnectionStatus.Offline
        assertEquals(OfflineCause.Unauthorized, offline.cause)
        advanceTimeBy(120_000)
        runCurrent()
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `心跳每 25s 发 ping 且 75s 无入站主动重连`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()

        now = 25_000
        advanceTimeBy(25_000)
        runCurrent()
        assertTrue(socket.sent.any { it.contains("\"ping\"") })

        now = 100_000
        advanceTimeBy(75_000)
        runCurrent()
        assertTrue(connection.status.value is ConnectionStatus.Reconnecting)
        assertEquals(1001, socket.closedWith?.first)
    }

    @Test
    fun `pong 到达后记录真实往返延迟`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()
        assertTrue(socket.sent.any { it.contains("\"ping\"") })

        now = 42
        socket.receive("""{"kind":"pong"}""")
        runCurrent()
        assertEquals(42, connection.latencyMs.value)
    }

    @Test
    fun `指令超时后抛 PocketTimeoutException`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        factory.created.single().open()

        val deferred = async { runCatching { connection.request("list_sessions") } }
        advanceTimeBy(15_000)
        runCurrent()
        val error = deferred.await().exceptionOrNull()
        assertTrue(error is PocketTimeoutException)
    }

    @Test
    fun `离线时发送入队并在重连后重放`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        val outcomes = mutableListOf<SendOutcome>()
        val job = backgroundScope.launch { connection.sendOutcomes.collect { outcomes += it } }
        runCurrent()

        connection.connect("ws://host/ws", "t")
        val frame = FrameCodec.sendMessage(
            cmdId = "send-1",
            clientMessageId = "client-1",
            sessionId = "s1",
            userMessage = "你好",
            channelId = "c1",
            modelId = null,
            workspaceId = null,
            uuid = "u1",
            startedAt = 1,
        )
        connection.sendMessage(OutgoingMessage("send-1", "client-1", frame))
        runCurrent()
        assertTrue(outcomes.first() is SendOutcome.Queued)

        val socket = factory.created.single()
        socket.open()
        runCurrent()
        assertTrue(socket.sent.any { it.contains("\"clientMessageId\":\"client-1\"") })

        socket.receive("""{"kind":"command_result","requestId":"send-1","commandId":"send-1","ok":true,"data":{"accepted":true}}""")
        runCurrent()
        val accepted = outcomes.filterIsInstance<SendOutcome.Accepted>().single()
        assertEquals("client-1", accepted.clientMessageId)
        assertFalse(accepted.deduped)
        job.cancel()
    }

    @Test
    fun `业务拒绝不重试并上报 Rejected`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        val outcomes = mutableListOf<SendOutcome>()
        val job = backgroundScope.launch { connection.sendOutcomes.collect { outcomes += it } }
        runCurrent()

        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()
        connection.sendMessage(
            OutgoingMessage(
                cmdId = "send-1",
                clientMessageId = "client-1",
                frame = FrameCodec.sendMessage("send-1", "client-1", "s1", "hi", "c1", null, null, null, 1),
            ),
        )
        runCurrent()
        socket.receive("""{"kind":"command_result","requestId":"send-1","commandId":"send-1","ok":false,"error":"会话正在处理中"}""")
        runCurrent()

        assertEquals(
            "会话正在处理中",
            outcomes.filterIsInstance<SendOutcome.Rejected>().single().message,
        )
        job.cancel()
    }

    @Test
    fun `连接失败按退避重连`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        factory.created.single().fail("连接被拒绝")
        val offline = connection.status.value as ConnectionStatus.Reconnecting
        assertNotNull(offline.reason)
        advanceTimeBy(2_000)
        runCurrent()
        assertEquals(2, factory.created.size)
    }

    @Test
    fun `断网立即离线且不再按 2s 退避狂刷`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()
        socket.receive(hello())

        connection.onNetworkChanged(false)
        val offline = connection.status.value as ConnectionStatus.Offline
        assertEquals(OfflineCause.Unreachable, offline.cause)
        assertEquals("手机网络已断开", offline.message)
        assertEquals(1000, socket.closedWith?.first)

        // 没网时不该继续每 2s 建一次 socket。
        advanceTimeBy(3_000)
        runCurrent()
        assertEquals(1, factory.created.size)
    }

    @Test
    fun `网络恢复立即重连并跳过剩余退避`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        factory.created.single().fail("连接被拒绝")
        assertTrue(connection.status.value is ConnectionStatus.Reconnecting)

        connection.onNetworkChanged(false)
        connection.onNetworkChanged(true)
        runCurrent()

        assertEquals(2, factory.created.size)
        assertTrue(connection.status.value is ConnectionStatus.Connecting)
    }

    @Test
    fun `回前台时半死链路立即重建`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()
        socket.receive(hello())

        // 链路还在，但 80s 没有入站帧（Doze / 切后台后的常见状态）。
        now = 80_000
        connection.reconnectNow(force = true)

        assertEquals(2, factory.created.size)
        assertEquals(1001, socket.closedWith?.first)
        assertTrue(connection.status.value is ConnectionStatus.Connecting)
    }

    @Test
    fun `关闭自动重连后网络恢复也不自动连`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.autoReconnect = false
        connection.connect("ws://host/ws", "t")
        factory.created.single().fail("连接被拒绝")
        assertTrue(connection.status.value is ConnectionStatus.Offline)

        connection.onNetworkChanged(false)
        connection.onNetworkChanged(true)
        runCurrent()

        assertEquals(1, factory.created.size)
    }

    @Test
    fun `新鲜链路下回前台不打断现有连接`() = runTest {
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope)
        connection.connect("ws://host/ws", "t")
        val socket = factory.created.single()
        socket.open()
        socket.receive(hello())

        now = 5_000
        connection.reconnectNow(force = true)

        assertEquals(1, factory.created.size)
        assertTrue(connection.status.value is ConnectionStatus.Connected)
    }

    @Test
    fun `断线队列落盘并在进程重启后重放`() = runTest {
        val store = FakeOutgoingQueueStore()
        val factory = FakeSocketFactory()
        val first = connection(factory, scope = backgroundScope, queueStore = store)
        first.connect("ws://host/ws", "t")
        val frame = FrameCodec.sendMessage(
            cmdId = "send-1",
            clientMessageId = "client-1",
            sessionId = "s1",
            userMessage = "离线写的消息",
            channelId = "c1",
            modelId = null,
            workspaceId = null,
            uuid = "u1",
            startedAt = 1,
        )
        first.sendMessage(OutgoingMessage("send-1", "client-1", frame))
        advanceTimeBy(200)
        runCurrent()
        assertEquals("ws://host/ws", store.snapshot?.endpoint)
        assertEquals(1, store.snapshot?.items?.size)

        // 模拟进程被杀后重启：新的连接对象 + 同一份落盘队列。
        val restartedFactory = FakeSocketFactory()
        val second = connection(restartedFactory, scope = backgroundScope, queueStore = store)
        second.connect("ws://host/ws", "t")
        val socket = restartedFactory.created.single()
        socket.open()
        runCurrent()

        assertTrue(socket.sent.any { it.contains("\"clientMessageId\":\"client-1\"") })
        assertEquals(0, second.pendingSendCount.value)
    }

    @Test
    fun `切换地址时不重放上一台电脑的队列`() = runTest {
        val store = FakeOutgoingQueueStore()
        store.save(
            "ws://old/ws",
            listOf(OutgoingMessage("c1", "m1", "{\"type\":\"send_message\"}")),
        )
        val factory = FakeSocketFactory()
        val connection = connection(factory, scope = backgroundScope, queueStore = store)
        connection.connect("ws://new/ws", "t")
        val socket = factory.created.single()
        socket.open()
        runCurrent()

        assertTrue(socket.sent.none { it.contains("m1") })
        assertEquals(null, store.snapshot)
        assertEquals(0, connection.pendingSendCount.value)
    }
}
