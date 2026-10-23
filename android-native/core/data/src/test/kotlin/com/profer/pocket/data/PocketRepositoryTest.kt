package com.profer.pocket.data

import com.profer.pocket.data.net.BackoffPolicy
import com.profer.pocket.data.net.HeartbeatPolicy
import com.profer.pocket.data.net.PocketConnection
import com.profer.pocket.data.store.SessionSnapshotStore
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.NotificationKind
import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.PocketNotification
import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.SendStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PocketRepositoryTest {

    private var now = 1_000L
    private var sequence = 0

    private class Fixture(
        val factory: FakeSocketFactory,
        val repository: PocketRepository,
        val registry: com.profer.pocket.remote.DeviceRegistry,
        val credentials: FakeCredentialStore,
    )

    private fun fixture(scope: CoroutineScope, snapshot: SessionSnapshotStore.Snapshot): Fixture {
        val factory = FakeSocketFactory()
        val connection = PocketConnection(
            transports = factory,
            scope = scope,
            resumeState = FakeResumeStateStore(),
            clock = { now },
            heartbeat = HeartbeatPolicy(),
            backoff = BackoffPolicy(),
            newId = { "cmd-${sequence++}" },
        )
        val credentials = FakeCredentialStore()
        val registry = fakeRegistry()
        val repository = PocketRepository(
            connection = connection,
            credentials = credentials,
            registry = registry,
            pairing = UnsupportedRemoteSocketFactory().client(),
            snapshots = FakeSnapshotStore(snapshot),
            settingsStore = FakeSettingsStore(),
            scope = scope,
            clock = { now },
            streamMergeWindowMs = 60,
            newId = { "uuid-${sequence++}" },
            // 快照读取在测试里立即完成，保持与旧同步语义一致的断言时机。
            snapshotDispatcher = kotlinx.coroutines.Dispatchers.Unconfined,
        )
        return Fixture(factory, repository, registry, credentials)
    }

    private fun hello(): String =
        """{"kind":"hello","serverTime":1,"serverInstanceId":"srv-1","latestAgentEventId":0,"oldestAgentEventId":0}"""

    private fun assistantEvent(
        eventId: Long,
        text: String,
        partial: Boolean,
        uuid: String = "a1",
        tools: String = "",
    ): String = """
        {"kind":"agent_event","eventId":$eventId,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
          "type":"assistant","uuid":"$uuid","_partial":$partial,
          "message":{"content":[{"type":"text","text":"$text"}$tools]}
        }}}
    """.trimIndent()

    @Test
    fun `冷启动先展示离线快照`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "上次的会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        assertEquals("上次的会话", fixture.repository.sessions.value.single().title)
    }

    @Test
    fun `流式 partial 在 60ms 窗口内合并`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        assertTrue(fixture.repository.connect("ws://192.168.1.2:7788/ws", "t"))
        val socket = fixture.factory.created.single()
        socket.open()
        socket.receive(hello())
        runCurrent()

        socket.receive(assistantEvent(1, "你", partial = true))
        socket.receive(assistantEvent(2, "你好", partial = true))
        runCurrent()
        // 窗口未到：此时还没有推送合并结果。
        assertTrue(fixture.repository.chats.value["s1"]?.messages.isNullOrEmpty())

        advanceTimeBy(60)
        runCurrent()
        val message = fixture.repository.chats.value["s1"]!!.messages.single() as ChatEntry.Assistant
        assertEquals("你好", message.text)
        assertTrue(message.streaming)
    }

    @Test
    fun `工具调用只保留次数且 final 不重复计数`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        fixture.repository.connect("ws://192.168.1.2:7788/ws", "t")
        val socket = fixture.factory.created.single()
        socket.open()
        socket.receive(hello())
        runCurrent()

        val tools = """,{"type":"tool_use","id":"t1","name":"Read"},{"type":"tool_use","id":"t2","name":"Bash"}"""
        socket.receive(assistantEvent(1, "先读", partial = true, tools = tools))
        socket.receive(assistantEvent(2, "读完了", partial = false, tools = tools))
        runCurrent()

        val chat = fixture.repository.chats.value["s1"]!!
        assertEquals(2, chat.tools.total)
        assertEquals(mapOf("Read" to 1, "Bash" to 1), chat.tools.byName)
        assertEquals("Read ×1 · Bash ×1", chat.tools.summary())
    }

    @Test
    fun `权限请求进入会话并投递通知`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        val notifications = mutableListOf<PocketNotification>()
        backgroundScope.launch { fixture.repository.notifications.collect { notifications += it } }
        runCurrent()
        fixture.repository.connect("ws://192.168.1.2:7788/ws", "t")
        val socket = fixture.factory.created.single()
        socket.open()
        socket.receive(hello())
        runCurrent()

        socket.receive(
            """{"kind":"agent_event","eventId":3,"sessionId":"s1","payload":{"kind":"profer_event","event":{
              "type":"permission_request","request":{"requestId":"p1","sessionId":"s1","toolName":"Bash",
              "description":"执行测试","command":"bun test"}}}}""".trimIndent(),
        )
        runCurrent()

        val pending = fixture.repository.chats.value["s1"]!!.pendingInteraction as PendingInteraction.Permission
        assertEquals("p1", pending.id)
        val notification = notifications.single()
        assertEquals(NotificationKind.Permission, notification.kind)
        assertEquals("执行测试", notification.body)
    }

    @Test
    fun `发送消息先落乐观气泡再发 frame`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1", modelId = "m1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        fixture.repository.connect("ws://192.168.1.2:7788/ws", "t")
        val socket = fixture.factory.created.single()
        socket.open()
        socket.receive(hello())
        runCurrent()

        fixture.repository.sendMessage("s1", "帮我跑测试")
        runCurrent()

        val chat = fixture.repository.chats.value["s1"]!!
        val optimistic = chat.messages.filterIsInstance<ChatEntry.User>().last()
        assertEquals("帮我跑测试", optimistic.text)
        assertTrue(chat.running)

        val frame = socket.sent.map { JSONObject(it) }
            .last { it.optString("type") == "send_message" }
        assertEquals("s1", frame.getString("sessionId"))
        assertEquals("c1", frame.getString("channelId"))
        assertEquals(optimistic.id, frame.getString("uuid"))
    }

    @Test
    fun `任务完成通知带会话标题且 run_idle 去重`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "修复登录", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        val notifications = mutableListOf<PocketNotification>()
        backgroundScope.launch { fixture.repository.notifications.collect { notifications += it } }
        runCurrent()
        fixture.repository.connect("ws://192.168.1.2:7788/ws", "t")
        val socket = fixture.factory.created.single()
        socket.open()
        socket.receive(hello())
        runCurrent()

        socket.receive(
            """{"kind":"agent_event","eventId":4,"sessionId":"s1","payload":{"kind":"profer_event","event":{"type":"run_completed","sessionId":"s1"}}}""",
        )
        socket.receive(
            """{"kind":"agent_event","eventId":5,"sessionId":"s1","payload":{"kind":"profer_event","event":{"type":"run_idle","sessionId":"s1"}}}""",
        )
        runCurrent()

        val notification = notifications.single()
        assertEquals("修复登录 已完成", notification.body)
    }

    // ===== 会话/项目系统真实链路 =====

    private fun connect(fixture: Fixture) {
        assertTrue(fixture.repository.connect("ws://192.168.1.2:7788/ws", "t"))
        val socket = fixture.factory.created.single()
        socket.open()
        socket.receive(hello())
    }

    private fun commandFrame(fixture: Fixture, type: String): JSONObject =
        fixture.factory.created.single().sent
            .map { JSONObject(it) }
            .last { it.optString("type") == type }

    private fun respondCommand(fixture: Fixture, type: String, data: String) {
        val frame = commandFrame(fixture, type)
        fixture.factory.created.single().receive(
            """{"kind":"command_result","commandId":"${frame.getString("_cmdId")}","ok":true,"data":$data}""",
        )
    }

    @Test
    fun `session_projection 同步置顶、归档与未读且不覆盖运行态`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(
                SessionSummary(
                    id = "s1",
                    title = "旧标题",
                    channelId = "c1",
                    revision = 1,
                    active = true,
                ),
            ),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        fixture.factory.created.single().receive(
            """{"kind":"agent_event","eventId":1,"sessionId":"s1","payload":{"kind":"session_projection",
              "operation":"upsert","session":{"id":"s1","revision":2,"title":"新标题","workspaceId":"w1",
              "pinned":true,"archived":false,"completedButUnconfirmed":true,"updatedAt":10}}}""".trimIndent(),
        )
        runCurrent()

        val session = fixture.repository.sessions.value.single()
        assertEquals("新标题", session.title)
        assertEquals("w1", session.workspaceId)
        assertEquals(true, session.pinned)
        assertEquals(true, session.unread)
        assertEquals(true, session.active)
        assertEquals(2, session.revision)
    }

    @Test
    fun `旧 revision 的投影不能回退新状态`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", revision = 5, pinned = true)),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        fixture.factory.created.single().receive(
            """{"kind":"agent_event","eventId":1,"sessionId":"s1","payload":{"kind":"session_projection",
              "operation":"upsert","session":{"id":"s1","revision":4,"title":"旧快照","pinned":false,"updatedAt":1}}}""".trimIndent(),
        )
        runCurrent()

        val session = fixture.repository.sessions.value.single()
        assertEquals("会话", session.title)
        assertEquals(true, session.pinned)
        assertEquals(5, session.revision)
    }

    @Test
    fun `删除投影移除会话且墓碑阻止旧投影复活`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", revision = 5)),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val socket = fixture.factory.created.single()
        socket.receive(
            """{"kind":"agent_event","eventId":1,"sessionId":"s1","payload":{"kind":"session_projection",
              "operation":"delete","sessionId":"s1","revision":7}}""",
        )
        runCurrent()
        assertTrue(fixture.repository.sessions.value.isEmpty())

        socket.receive(
            """{"kind":"agent_event","eventId":2,"sessionId":"s1","payload":{"kind":"session_projection",
              "operation":"upsert","session":{"id":"s1","revision":6,"title":"迟到快照"}}}""",
        )
        runCurrent()
        assertTrue(fixture.repository.sessions.value.isEmpty())
    }

    @Test
    fun `list_sessions 成功空列表以服务端为准`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "本地快照")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val job = launch { fixture.repository.refreshSessions() }
        runCurrent()
        respondCommand(fixture, "list_sessions", "[]")
        job.join()

        assertTrue(fixture.repository.sessions.value.isEmpty())
    }

    @Test
    fun `置顶命令回填权威会话`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", revision = 3)),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val job = launch { fixture.repository.toggleSessionPin("s1") }
        runCurrent()
        respondCommand(
            fixture,
            "toggle_session_pin",
            """{"id":"s1","revision":4,"title":"会话","pinned":true,"archived":false}""",
        )
        job.join()

        val session = fixture.repository.sessions.value.single()
        assertEquals(true, session.pinned)
        assertEquals(4, session.revision)
        assertEquals("s1", commandFrame(fixture, "toggle_session_pin").getString("sessionId"))
    }

    @Test
    fun `项目内新建会话走草稿复用并带上渠道与模型`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = emptyList(),
            workspaces = listOf(com.profer.pocket.model.WorkspaceSummary(id = "w1", name = "profer-main")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val channelsJob = launch { fixture.repository.refreshChannels() }
        runCurrent()
        respondCommand(
            fixture,
            "list_channels",
            """[{"id":"c1","name":"主渠道","enabled":true,"models":[{"id":"m1","name":"Opus"}]}]""",
        )
        channelsJob.join()

        val job = launch { fixture.repository.createSession("w1") }
        runCurrent()
        val frame = commandFrame(fixture, "ensure_project_draft_session")
        assertEquals("w1", frame.getString("workspaceId"))
        assertEquals("c1", frame.getString("channelId"))
        assertEquals("m1", frame.getString("modelId"))
        respondCommand(
            fixture,
            "ensure_project_draft_session",
            """{"id":"s2","sessionId":"s2","revision":1,"title":"新 Agent 会话","workspaceId":"w1",
              "channelId":"c1","modelId":"m1","presetId":"global-project-lead","draft":true,"updatedAt":20}""",
        )
        job.join()

        val created = fixture.repository.sessions.value.first { it.id == "s2" }
        assertEquals("w1", created.workspaceId)
        assertEquals("global-project-lead", created.presetId)
        assertEquals(true, created.draft)
    }

    @Test
    fun `标记已读带 explicit 并清本地未读`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", revision = 2, unread = true)),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val job = launch { fixture.repository.markSessionRead("s1") }
        runCurrent()
        val frame = commandFrame(fixture, "mark_session_read")
        assertEquals(true, frame.getBoolean("explicit"))
        respondCommand(fixture, "mark_session_read", """{"id":"s1","revision":3,"title":"会话","completedButUnconfirmed":false}""")
        job.join()

        assertEquals(false, fixture.repository.sessions.value.single().unread)
    }

    @Test
    fun `创建项目后目录立即出现`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        val job = launch { fixture.repository.createWorkspace("新项目") }
        runCurrent()
        respondCommand(
            fixture,
            "create_workspace",
            """{"id":"w9","name":"新项目","slug":"new-project","type":"personal","createdAt":1,"updatedAt":1}""",
        )
        job.join()

        val workspace = fixture.repository.workspaces.value.first { it.id == "w9" }
        assertEquals("新项目", workspace.name)
        assertEquals("personal", workspace.type)
    }

    @Test
    fun `移动会话到项目使用 targetWorkspaceId 并回填新项目`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", revision = 2, workspaceId = "w1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val job = launch { fixture.repository.moveSessionToWorkspace("s1", "w2") }
        runCurrent()
        val frame = commandFrame(fixture, "move_session_to_workspace")
        assertEquals("s1", frame.getString("sessionId"))
        assertEquals("w2", frame.getString("targetWorkspaceId"))
        respondCommand(
            fixture,
            "move_session_to_workspace",
            """{"id":"s1","revision":3,"title":"会话","workspaceId":"w2"}""",
        )
        job.join()

        assertEquals("w2", fixture.repository.sessions.value.single().workspaceId)
    }

    @Test
    fun `归档投影把会话移出移动端目录，取消归档后恢复`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", revision = 2)),
        )
        val fixture = fixture(backgroundScope, snapshot)
        connect(fixture)
        runCurrent()

        val socket = fixture.factory.created.single()
        socket.receive(
            """{"kind":"agent_event","eventId":1,"sessionId":"s1","payload":{"kind":"session_projection",
              "operation":"upsert","session":{"id":"s1","revision":3,"title":"会话","archived":true}}}""",
        )
        runCurrent()
        assertTrue(fixture.repository.sessions.value.isEmpty())

        socket.receive(
            """{"kind":"agent_event","eventId":2,"sessionId":"s1","payload":{"kind":"session_projection",
              "operation":"upsert","session":{"id":"s1","revision":4,"title":"会话","archived":false}}}""",
        )
        runCurrent()
        assertEquals(listOf("s1"), fixture.repository.sessions.value.map { it.id })
    }

    @Test
    fun `发送被拒后单条消息标记失败并可重试`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        connect(fixture)

        val job = launch { fixture.repository.sendMessage("s1", "你好") }
        runCurrent()
        val socket = fixture.factory.created.single()
        val frame = socket.sent.map { JSONObject(it) }
            .last { it.optString("type") == "send_message" }
        socket.receive(
            """{"kind":"command_result","commandId":"${frame.getString("_cmdId")}","ok":false,"error":"会话正在处理中"}""",
        )
        job.join()
        runCurrent()

        val user = fixture.repository.chats.value["s1"]!!.messages
            .first { it is ChatEntry.User } as ChatEntry.User
        assertEquals(SendStatus.Failed, user.status)
        assertEquals("会话正在处理中", user.failure)
    }

    @Test
    fun `运行中插话先上屏乐观气泡并复用同一 uuid`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        connect(fixture)

        val job = launch { fixture.repository.queueMessage("s1", "补充一句") }
        runCurrent()
        val frame = commandFrame(fixture, "queue_message")
        assertEquals("补充一句", frame.getString("userMessage"))
        val uuid = frame.getString("uuid")
        val bubble = fixture.repository.chats.value["s1"]!!.messages.last() as ChatEntry.User
        assertEquals(uuid, bubble.id)

        respondCommand(fixture, "queue_message", """{"accepted":true,"uuid":"$uuid"}""")
        job.join()
        assertEquals(1, fixture.repository.chats.value["s1"]!!.messages.count { it is ChatEntry.User })
    }

    @Test
    fun `保存图片附件返回会话目录路径`() = runTest {
        val snapshot = SessionSnapshotStore.Snapshot(
            sessions = listOf(SessionSummary(id = "s1", title = "会话", channelId = "c1")),
        )
        val fixture = fixture(backgroundScope, snapshot)
        runCurrent()
        connect(fixture)

        var saved: com.profer.pocket.model.AgentAttachment? = null
        val job = launch {
            saved = fixture.repository.saveAgentAttachment("s1", "image.jpg", "aGk=").getOrThrow()
        }
        runCurrent()
        respondCommand(
            fixture,
            "save_agent_attachment",
            """{"filename":"image.jpg","path":"/workspace/s1/image.jpg","size":2}""",
        )
        job.join()
        assertEquals("image.jpg", saved?.filename)
        assertEquals("/workspace/s1/image.jpg", saved?.path)
    }

    // ===== Chat 时序：乐观消息与历史水合的竞态 =====

    @Test
    fun `Chat 发送后历史水合不会吞掉尚未落盘的乐观消息`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        val listJob = launch { fixture.repository.refreshConversations() }
        runCurrent()
        respondCommand(
            fixture,
            "list_conversations",
            """[{"id":"c1","title":"新对话","channelId":"ch1","modelId":"m1","activePath":[]}]""",
        )
        listJob.join()

        val sendJob = launch { fixture.repository.sendConversationMessage("c1", "hi") }
        runCurrent()
        respondCommand(fixture, "chat_send_message", """{"accepted":true}""")
        sendJob.join()
        assertEquals("hi", fixture.repository.conversationMessages.value["c1"]!!.single().content)

        // 服务端异步落盘尚未完成：此时历史里还没有 hi，整体覆盖会把气泡吞掉。
        val staleLoad = launch { fixture.repository.openConversation("c1") }
        runCurrent()
        respondCommand(
            fixture,
            "get_conversation_messages",
            """[{"id":"u0","role":"user","content":"你好","createdAt":1},{"id":"a0","role":"assistant","content":"你好！","createdAt":2}]""",
        )
        staleLoad.join()
        assertEquals(
            listOf("你好", "你好！", "hi"),
            fixture.repository.conversationMessages.value["c1"]!!.map { it.content },
        )

        // 服务端落盘后再水合：乐观副本让位，不重复。
        val freshLoad = launch { fixture.repository.openConversation("c1") }
        runCurrent()
        respondCommand(
            fixture,
            "get_conversation_messages",
            """[{"id":"u0","role":"user","content":"你好","createdAt":1},{"id":"a0","role":"assistant","content":"你好！","createdAt":2},{"id":"u1","role":"user","content":"hi","createdAt":3},{"id":"a1","role":"assistant","content":"嗨！","createdAt":4}]""",
        )
        freshLoad.join()
        val confirmed = fixture.repository.conversationMessages.value["c1"]!!
        assertEquals(4, confirmed.size)
        assertEquals(1, confirmed.count { it.isUser && it.content == "hi" })
    }

    @Test
    fun `Chat 重复发送同一句话时只保留尚未确认的那一条`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        val listJob = launch { fixture.repository.refreshConversations() }
        runCurrent()
        respondCommand(
            fixture,
            "list_conversations",
            """[{"id":"c1","title":"新对话","channelId":"ch1","modelId":"m1","activePath":[]}]""",
        )
        listJob.join()

        val first = launch { fixture.repository.sendConversationMessage("c1", "hi") }
        runCurrent()
        respondCommand(fixture, "chat_send_message", """{"accepted":true}""")
        first.join()

        val second = launch { fixture.repository.sendConversationMessage("c1", "hi") }
        runCurrent()
        respondCommand(fixture, "chat_send_message", """{"accepted":true}""")
        second.join()
        assertEquals(2, fixture.repository.conversationMessages.value["c1"]!!.size)

        // 服务端只落盘了第一条：第二次发送的气泡必须保留。
        val load = launch { fixture.repository.openConversation("c1") }
        runCurrent()
        respondCommand(
            fixture,
            "get_conversation_messages",
            """[{"id":"u1","role":"user","content":"hi","createdAt":1}]""",
        )
        load.join()
        assertEquals(2, fixture.repository.conversationMessages.value["c1"]!!.count { it.isUser && it.content == "hi" })
    }

    // ===== Chat 实时链路：桌面端发起的用户消息 =====

    @Test
    fun `Chat 切换模型后会话元数据写回最新 channel 与 model`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        val listJob = launch { fixture.repository.refreshConversations() }
        runCurrent()
        respondCommand(
            fixture,
            "list_conversations",
            """[{"id":"c1","title":"新对话","channelId":"ch1","modelId":"m1","activePath":[]}]""",
        )
        listJob.join()

        val update = launch {
            fixture.repository.updateConversationModel("c1", "ch2", "m2")
        }
        runCurrent()
        respondCommand(
            fixture,
            "update_conversation_model",
            """{"id":"c1","title":"新对话","channelId":"ch2","modelId":"m2","activePath":[]}""",
        )
        update.join()

        val updated = fixture.repository.conversations.value.single { it.id == "c1" }
        assertEquals("ch2", updated.channelId)
        assertEquals("m2", updated.modelId)
    }

    @Test
    fun `已加载的 Chat 会话收到桌面端用户消息广播后立即上屏`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        val listJob = launch { fixture.repository.refreshConversations() }
        runCurrent()
        respondCommand(
            fixture,
            "list_conversations",
            """[{"id":"c1","title":"新对话","channelId":"ch1","modelId":"m1","activePath":[]}]""",
        )
        listJob.join()

        val load = launch { fixture.repository.openConversation("c1") }
        runCurrent()
        respondCommand(fixture, "get_conversation_messages", "[]")
        load.join()
        assertTrue(fixture.repository.conversationMessages.value.containsKey("c1"))

        fixture.factory.created.single().receive(
            """{"kind":"chat_event","conversationId":"c1","channel":"chat:stream:user","payload":{"message":{"id":"u9","role":"user","content":"gg","createdAt":5}}}""",
        )
        runCurrent()

        val messages = fixture.repository.conversationMessages.value["c1"].orEmpty()
        assertEquals(1, messages.size)
        assertEquals("u9", messages.single().id)
        assertEquals("gg", messages.single().content)
    }

    @Test
    fun `未加载的 Chat 会话收到用户消息广播不创建孤立消息`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        fixture.factory.created.single().receive(
            """{"kind":"chat_event","conversationId":"c2","channel":"chat:stream:user","payload":{"message":{"id":"u9","role":"user","content":"gg","createdAt":5}}}""",
        )
        runCurrent()

        assertTrue(!fixture.repository.conversationMessages.value.containsKey("c2"))
    }

    @Test
    fun `本机乐观消息收到持久化广播后换成真实 id 且不重复`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()

        val listJob = launch { fixture.repository.refreshConversations() }
        runCurrent()
        respondCommand(
            fixture,
            "list_conversations",
            """[{"id":"c1","title":"新对话","channelId":"ch1","modelId":"m1","activePath":[]}]""",
        )
        listJob.join()

        val send = launch { fixture.repository.sendConversationMessage("c1", "gg") }
        runCurrent()
        respondCommand(fixture, "chat_send_message", """{"accepted":true}""")
        send.join()
        assertEquals("gg", fixture.repository.conversationMessages.value["c1"]!!.single().content)
        assertTrue(fixture.repository.conversationMessages.value["c1"]!!.single().id.startsWith("local-"))

        fixture.factory.created.single().receive(
            """{"kind":"chat_event","conversationId":"c1","channel":"chat:stream:user","payload":{"message":{"id":"u9","role":"user","content":"gg","createdAt":5}}}""",
        )
        runCurrent()

        val messages = fixture.repository.conversationMessages.value["c1"].orEmpty()
        assertEquals(1, messages.size)
        assertEquals("u9", messages.single().id)
    }

    // ===== 设备注册表与迁移 =====

    @Test
    fun `旧单连接迁移为 legacy 设备并继续可连`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        fixture.credentials.saved = com.profer.pocket.data.store.CredentialStore.SavedConnection(
            endpoint = "ws://192.168.1.2:7788/ws",
            token = "legacy-token",
        )
        fixture.repository.start()
        runCurrent()

        val snapshot = fixture.repository.devices.value
        val device = snapshot.devices.single()
        assertTrue(device.profile.deviceId.startsWith("legacy-"))
        assertEquals(com.profer.pocket.remote.TransportMode.LEGACY_QUERY, device.transportMode)
        assertEquals(device.profile.deviceId, snapshot.activeId)
        // token 保留在新存储里，旧键在迁移成功后才清理。
        assertEquals("legacy-token", fixture.registry.credential(device.profile.deviceId))
        assertEquals(null, fixture.credentials.saved)
        assertTrue(fixture.factory.created.single().lastUrl!!.startsWith("ws://192.168.1.2:7788/ws?token="))
    }

    @Test
    fun `配对失败只上报原因不落盘设备`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        fixture.repository.pairWithQr("not-a-qr")
        runCurrent()
        val state = fixture.repository.pairingState.value
        assertTrue(state is PocketRepository.PairingState.Failed)
        assertTrue((state as PocketRepository.PairingState.Failed).message.isNotEmpty())
        assertTrue(fixture.repository.devices.value.devices.isEmpty())
    }

    @Test
    fun `移除当前设备后回到未连接`() = runTest {
        val fixture = fixture(backgroundScope, SessionSnapshotStore.Snapshot())
        connect(fixture)
        runCurrent()
        val active = fixture.repository.devices.value.active()!!
        fixture.repository.removeDevice(active.profile.deviceId)
        runCurrent()

        assertTrue(fixture.repository.devices.value.devices.isEmpty())
        assertEquals(null, fixture.repository.devices.value.activeId)
        assertTrue(fixture.repository.connectionStatus.value is com.profer.pocket.model.ConnectionStatus.Offline)
    }
}
