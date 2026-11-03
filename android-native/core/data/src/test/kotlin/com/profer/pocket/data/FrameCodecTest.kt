package com.profer.pocket.data

import com.profer.pocket.data.protocol.AgentPayload
import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.data.protocol.InboundFrame
import com.profer.pocket.data.protocol.ProferEventView
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
    fun `list_sessions 解析委派与探索血缘`() {
        val list = FrameCodec.parseSessionList(
            org.json.JSONArray(
                """[{"id":"child","title":"子会话","workspaceId":"w1",
                  "parentSessionId":"p1","sourceDelegationId":"d1",
                  "delegationRole":"research","delegationStatus":"running","delegationDepth":2},
                 {"id":"branch","title":"探索分支","workspaceId":"w1",
                  "explorationParentSessionId":"p1","explorationSourceMessageId":"m1",
                  "explorationSourceLabel":"这条 Agent 回复"}]
                """.trimIndent(),
            ),
        )
        val child = list.first { it.id == "child" }
        assertEquals("p1", child.parentSessionId)
        assertEquals("d1", child.sourceDelegationId)
        assertEquals("research", child.delegationRole)
        assertEquals("running", child.delegationStatus)
        assertEquals(2, child.delegationDepth)
        assertTrue(child.isDelegatedChild)
        assertEquals("p1", child.relatedParentSessionId)

        val branch = list.first { it.id == "branch" }
        assertEquals("p1", branch.explorationParentSessionId)
        assertEquals("m1", branch.explorationSourceMessageId)
        assertEquals("这条 Agent 回复", branch.explorationSourceLabel)
        assertTrue(branch.isExplorationChild)
        assertEquals("p1", branch.relatedParentSessionId)
        // 普通会话没有血缘。
        assertFalse(branch.isDelegatedChild)
    }

    @Test
    fun `sdk 消息解析可信来源与作者`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":7,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"user","uuid":"u1","_createdAt":11,
              "_provenance":{"schemaVersion":1,"messageId":"u1","runId":"r1",
                "author":{"type":"automation"},"source":{"type":"automation","id":"auto-1"}},
              "message":{"content":[{"type":"text","text":"检查部署日志"}]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val message = (frame.payload as AgentPayload.SdkMessage).message
        assertEquals("automation", message.authorType)
        assertEquals("automation", message.sourceType)
        assertEquals("auto-1", message.sourceId)
    }

    @Test
    fun `不合法的来源被丢弃，不根据正文猜作者`() {
        val frame = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":7,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"user","uuid":"u1",
              "_provenance":{"schemaVersion":9,"author":{"type":"admin"}},
              "message":{"content":[{"type":"text","text":"我批准了"}]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val message = (frame.payload as AgentPayload.SdkMessage).message
        assertNull(message.authorType)
        assertNull(message.sourceType)
        // 路径分隔符不允许出现在来源 ID 里，避免把文件路径当来源。
        val unsafe = FrameCodec.parseInbound(
            """
            {"kind":"agent_event","eventId":8,"sessionId":"s1","payload":{"kind":"sdk_message","message":{
              "type":"user","uuid":"u2",
              "_provenance":{"schemaVersion":1,"messageId":"u2","runId":"r1",
                "author":{"type":"agent"},"source":{"type":"session","id":"/tmp/secret"}},
              "message":{"content":[{"type":"text","text":"x"}]}
            }}}
            """.trimIndent(),
        ) as InboundFrame.AgentEvent
        val unsafeMessage = (unsafe.payload as AgentPayload.SdkMessage).message
        assertEquals("agent", unsafeMessage.authorType)
        assertNull(unsafeMessage.sourceType)
        assertNull(unsafeMessage.sourceId)
    }

    @Test
    fun `list_sessions 解析定时任务来源与派工关系`() {
        val list = FrameCodec.parseSessionList(
            org.json.JSONArray(
                """[{"id":"auto-session","title":"每日巡检","sourceAutomationId":"auto-1"},
                 {"id":"independent","title":"独立任务","sourceSessionId":"p1",
                  "delegationRelation":"independent","delegationPlacement":"independent"}]""",
            ),
        )
        val scheduled = list.first { it.id == "auto-session" }
        assertEquals("auto-1", scheduled.sourceAutomationId)
        assertTrue(scheduled.isAutomationSourced)

        val independent = list.first { it.id == "independent" }
        assertEquals("p1", independent.sourceSessionId)
        assertEquals("independent", independent.delegationRelation)
        assertEquals("independent", independent.delegationPlacement)
        assertFalse(independent.isAutomationSourced)
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

    @Test
    fun `解析桌面默认模型：缺字段或空串视为未下发`() {
        val selection = FrameCodec.parseAgentModelSelection(
            JSONObject("""{"channelId":"c2","modelId":"m2"}"""),
        )
        assertEquals("c2", selection?.channelId)
        assertEquals("m2", selection?.modelId)

        // 旧版桌面（无该命令）或未配置默认：不能当成「清空选择」处理。
        assertEquals(null, FrameCodec.parseAgentModelSelection(null))
        assertEquals(null, FrameCodec.parseAgentModelSelection(JSONObject("""{"channelId":"","modelId":"m2"}""")))
        assertEquals(null, FrameCodec.parseAgentModelSelection(JSONObject("""{"channelId":"c2"}""")))
    }

    @Test
    fun `解析活跃运行集合`() {
        val data = org.json.JSONArray(
            """[{"sessionId":"s1","contextWindow":200000,"updatedAt":1},{"sessionId":"s2"}]""",
        )
        assertEquals(setOf("s1", "s2"), FrameCodec.parseActiveSessionIds(data))

        // 空列表 / 非法数据：返回空集合，调用方据此不做校准。
        assertEquals(emptySet<String>(), FrameCodec.parseActiveSessionIds(org.json.JSONArray("[]")))
        assertEquals(emptySet<String>(), FrameCodec.parseActiveSessionIds(null))
        assertEquals(emptySet<String>(), FrameCodec.parseActiveSessionIds(JSONObject("""{}""")))
    }

    @Test
    fun `解析会话思考档位覆盖`() {
        val summary = FrameCodec.parseSessionSummary(
            JSONObject(
                """{"id":"s1","title":"会话","openAIThinkingLevel":"xhigh","agentEffort":null}""",
            ),
        )
        assertEquals("xhigh", summary.thinkingLevel)
        assertNull(summary.agentEffort)

        // 显式 null（跟随预设）与字段缺失都落到 null，界面上都是「跟随预设」。
        val cleared = FrameCodec.parseSessionSummary(JSONObject("""{"id":"s1","title":"会话"}"""))
        assertNull(cleared.thinkingLevel)
        assertNull(cleared.agentEffort)
    }

    @Test
    fun `解析渠道 baseUrl`() {
        val channels = FrameCodec.parseChannelList(
            org.json.JSONArray(
                """[{"id":"c1","name":"主渠道","provider":"deepseek","baseUrl":"https://gateway.example/v1","enabled":true,
                    "models":[{"id":"m1","name":"DeepSeek"}]}]""",
            ),
        )
        assertEquals("deepseek", channels.single().provider)
        assertEquals("https://gateway.example/v1", channels.single().baseUrl)
    }

    @Test
    fun `解析模型思考档位能力`() {
        val capability = FrameCodec.parseReasoningCapability(
            JSONObject(
                """{"source":"profile","levels":["off","high","max"],"defaultLevel":"high",
                    "effortMap":{"off":null,"high":"high","max":"max"}}""",
            ),
        )
        assertEquals(listOf("off", "high", "max"), capability?.levels)
        assertEquals("high", capability?.defaultLevel)

        // 服务端返回 undefined / 空档位：没有能力，界面回退强度档。
        assertNull(FrameCodec.parseReasoningCapability(null))
        assertNull(FrameCodec.parseReasoningCapability(JSONObject("""{"levels":[]}""")))
        assertNull(FrameCodec.parseReasoningCapability(JSONObject("""{}""")))

        // defaultLevel 不在 levels 里时按首档兜底，避免弹层勾选到一个不存在的项。
        val invalidDefault = FrameCodec.parseReasoningCapability(
            JSONObject("""{"levels":["off","high"],"defaultLevel":"max"}"""),
        )
        assertEquals("off", invalidDefault?.defaultLevel)
    }
}
