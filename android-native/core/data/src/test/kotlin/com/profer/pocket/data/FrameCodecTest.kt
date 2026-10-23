package com.profer.pocket.data

import com.profer.pocket.data.protocol.AgentPayload
import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.data.protocol.InboundFrame
import com.profer.pocket.data.protocol.ProferEventView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FrameCodecTest {

    @Test
    fun `解析 hello 与事件游标`() {
        val frame = FrameCodec.parseInbound(
            """{"kind":"hello","serverTime":1,"serverInstanceId":"srv-1","latestAgentEventId":9,"oldestAgentEventId":2}""",
        ) as InboundFrame.Hello
        assertEquals("srv-1", frame.serverInstanceId)
        assertEquals(9L, frame.latestEventId)
        assertEquals(2L, frame.oldestEventId)
    }

    @Test
    fun `解析 assistant sdk 消息的正文与工具名`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":7,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"assistant","uuid":"a1","_partial":true,
              "message":{"role":"assistant","content":[
                {"type":"text","text":"正在检查"},
                {"type":"tool_use","id":"t1","name":"Read","input":{"file":"x"}}
              ]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val payload = frame.payload as AgentPayload.SdkMessage
        assertEquals("a1", payload.message.uuid)
        assertTrue(payload.message.partial)
        assertEquals("正在检查", payload.message.text)
        assertEquals(listOf("Read"), payload.message.toolUses.map { it.name })
        assertEquals("t1", payload.message.toolUses.first().id)
    }

    @Test
    fun `解析 assistant 消息里的思考块`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":9,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"assistant","uuid":"a2","_partial":true,
              "message":{"role":"assistant","content":[
                {"type":"thinking","thinking":"先看日志"},
                {"type":"thinking","thinking":"再改代码"},
                {"type":"text","text":"开始处理"}
              ]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val payload = frame.payload as AgentPayload.SdkMessage
        assertEquals("先看日志再改代码", payload.message.reasoning)
        assertEquals("开始处理", payload.message.text)
    }

    @Test
    fun `解析用户消息及其落盘时间`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":8,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"user","uuid":"u1","_createdAt":1712345678901,
              "message":{"content":[{"type":"text","text":"电脑上下的指令"}]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val payload = frame.payload as AgentPayload.SdkMessage
        assertEquals("user", payload.message.type)
        assertEquals("电脑上下的指令", payload.message.text)
        assertEquals(1_712_345_678_901L, payload.message.createdAt)
    }

    @Test
    fun `解析用户消息里的图片引用`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":9,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"user","uuid":"u2",
              "message":{"content":[{"type":"tool_result","tool_use_id":"t1","images":[
                {"path":"/ws/s1/.context/agent-output-images/a.png","filename":"a.png","mediaType":"image/png"}
              ]}]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val result = (frame.payload as AgentPayload.SdkMessage).message.toolResults.single()
        assertEquals("t1", result.toolUseId)
        assertEquals("a.png", result.images.single().filename)
        assertEquals("image/png", result.images.single().mediaType)
    }

    @Test
    fun `解析权限请求`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":8,"sessionId":"s1","payload":{"kind":"profer_event","event":{
              "type":"permission_request","request":{"requestId":"p1","sessionId":"s1","toolName":"Bash",
              "description":"执行测试","command":"bun test","dangerLevel":"medium"}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val event = (frame.payload as AgentPayload.ProferEvent).event as ProferEventView.PermissionRequest
        assertEquals("p1", event.requestId)
        assertEquals("Bash", event.toolName)
        assertEquals("bun test", event.command)
    }

    @Test
    fun `解析提问首问`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":8,"sessionId":"s1","payload":{"kind":"profer_event","event":{
              "type":"ask_user_request","request":{"requestId":"q1","questions":[{"question":"选哪个？"}]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val event = (frame.payload as AgentPayload.ProferEvent).event as ProferEventView.AskUserRequest
        assertEquals("q1", event.requestId)
        assertEquals("选哪个？", event.questions.single().question)
    }

    @Test
    fun `解析 command_result 与冲突码`() {
        val frame = FrameCodec.parseInbound(
            """{"kind":"command_result","requestId":"c1","commandId":"c1","ok":false,"error":"正在处理","conflict":{"code":"busy"}}""",
        ) as InboundFrame.CommandResult
        assertEquals("c1", frame.commandId)
        assertEquals("busy", frame.conflictCode)
        assertEquals("正在处理", frame.error)
    }

    @Test
    fun `坏 JSON 与未知 kind 不抛异常`() {
        assertNull(FrameCodec.parseInbound("{not json"))
        val unknown = FrameCodec.parseInbound("""{"kind":"future_frame"}""") as InboundFrame.Unknown
        assertEquals("future_frame", unknown.kind)
    }

    @Test
    fun `会话列表解析包含 channelId 与置顶`() {
        val list = FrameCodec.parseSessionList(
            org.json.JSONArray(
                """[{"id":"s1","title":"T","channelId":"c1","pinned":true,"active":true,"updatedAt":5}]""",
            ),
        )
        assertEquals(1, list.size)
        assertEquals("c1", list.first().channelId)
        assertEquals(true, list.first().pinned)
        assertEquals(true, list.first().active)
        assertEquals(5L, list.first().updatedAt)
    }

    @Test
    fun `list_sessions 解析未读事实源与草稿 revision`() {
        val list = FrameCodec.parseSessionList(
            org.json.JSONArray(
                """[{"id":"s1","title":"T","revision":9,"createdAt":7,"updatedAt":8,
                  "pinned":true,"archived":false,"draft":true,"completedButUnconfirmed":true,
                  "workspaceId":"w1"}]""",
            ),
        )
        val session = list.single()
        assertEquals(9, session.revision)
        assertEquals(7L, session.createdAt)
        assertEquals(true, session.unread)
        assertEquals(true, session.draft)
        assertEquals("w1", session.workspaceId)
    }

    @Test
    fun `session_detail 单条会话带项目与置顶`() {
        val summary = FrameCodec.parseSessionItem(
            JSONObject(
                """{"id":"s1","title":"T","workspaceId":"w2","pinned":true,
                  "archived":true,"completedButUnconfirmed":false}""",
            ),
        )!!
        assertEquals("w2", summary.workspaceId)
        assertEquals(true, summary.pinned)
        assertEquals(true, summary.archived)
        assertEquals(false, summary.unread)
    }

    @Test
    fun `resume 帧 cursor 为空时显式 null`() {
        val frame = JSONObject(FrameCodec.resume("cmd", null))
        assertEquals("resume_agent_events", frame.getString("type"))
        assertTrue(frame.isNull("cursor"))
        val withCursor = JSONObject(FrameCodec.resume("cmd", 12))
        assertEquals(12L, withCursor.getLong("cursor"))
    }

    @Test
    fun `send_message 帧保留幂等键与追踪 ID`() {
        val json = JSONObject(
            FrameCodec.sendMessage(
                cmdId = "cmd",
                clientMessageId = "client-1",
                sessionId = "s1",
                userMessage = "你好",
                channelId = "c1",
                modelId = "m1",
                workspaceId = null,
                uuid = "u1",
                startedAt = 100,
            ),
        )
        assertEquals("cmd", json.getString("_cmdId"))
        assertEquals("client-1", json.getString("clientMessageId"))
        assertEquals("u1", json.getString("uuid"))
        assertEquals(100L, json.getLong("startedAt"))
        assertTrue(!json.has("workspaceId"))
    }

    @Test
    fun `解析 assistant 上下文用量与 result 模型窗口`() {
        val assistant = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":9,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"assistant","uuid":"a1","message":{"role":"assistant","content":[{"type":"text","text":"ok"}],
                "usage":{"input_tokens":1200,"output_tokens":80,"cache_read_input_tokens":300,"cache_creation_input_tokens":40,"service_tier":"standard"}}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val usage = (assistant.payload as AgentPayload.SdkMessage).message.usage
        assertEquals(1200, usage?.inputTokens)
        assertEquals(300, usage?.cacheReadInputTokens)
        assertEquals(40, usage?.cacheCreationInputTokens)
        assertEquals(1540, usage?.usedTokens)

        val result = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":10,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"result","subtype":"success","usage":{"input_tokens":99},
              "modelUsage":{"claude-x":{"contextWindow":200000}}}
            }}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val resultMessage = (result.payload as AgentPayload.SdkMessage).message
        assertEquals(mapOf("claude-x" to 200000), resultMessage.modelUsage)
    }

    @Test
    fun `解析预设与 Chat 工具列表`() {
        val presets = FrameCodec.parsePresetList(
            org.json.JSONArray(
                """[{"id":"standard","name":"标准","description":"通用岗位","scope":"builtin-meta","isBuiltin":true},
                    {"id":"p1","name":"写作","scope":"workspace","enabledInWorkspace":false,"effort":"high","permissionMode":"plan"}]""",
            ),
        )
        assertEquals(2, presets.size)
        assertEquals("标准", presets[0].displayName)
        assertTrue(presets[0].isSelectable)
        assertEquals("当前工作区", presets[1].groupLabel)
        assertTrue(!presets[1].isSelectable)
        assertEquals("high", presets[1].effort)

        val tools = FrameCodec.parseChatToolList(
            org.json.JSONArray(
                """[{"id":"memory","name":"记忆","description":"记住偏好","icon":"Brain","enabled":true,"available":true},
                    {"id":"gpt-image","name":"生图","enabled":false,"available":false}]""",
            ),
        )
        assertEquals(2, tools.size)
        assertTrue(tools[0].enabled && tools[0].available)
        assertTrue(!tools[1].available)
        assertEquals("Brain", tools[0].icon)
    }
}
