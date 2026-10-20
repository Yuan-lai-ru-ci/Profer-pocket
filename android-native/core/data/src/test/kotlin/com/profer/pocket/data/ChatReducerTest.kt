package com.profer.pocket.data

import com.profer.pocket.data.protocol.AgentPayload
import com.profer.pocket.data.protocol.ChatReducer
import com.profer.pocket.data.protocol.ImageRef
import com.profer.pocket.data.protocol.ProferEventView
import com.profer.pocket.data.protocol.SdkMessageView
import com.profer.pocket.data.protocol.SessionSignal
import com.profer.pocket.data.protocol.ToolResultView
import com.profer.pocket.data.protocol.ToolUseView
import com.profer.pocket.data.protocol.TokenUsage
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.NoticeTone
import com.profer.pocket.model.NotificationKind
import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.SessionChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatReducerTest {

    private val reducer = ChatReducer(clock = { 1_000L })

    private fun chat() = SessionChat(sessionId = "s1", title = "会话")

    private fun assistant(
        text: String,
        partial: Boolean,
        uuid: String = "a1",
        tools: List<ToolUseView> = emptyList(),
        reasoning: String? = null,
    ) = AgentPayload.SdkMessage(
        SdkMessageView(
            type = "assistant",
            uuid = uuid,
            partial = partial,
            text = text,
            toolUses = tools,
            subtype = null,
            model = null,
            isError = false,
            errorMessage = null,
            reasoning = reasoning,
        ),
    )

    @Test
    fun `只有思考的 partial 也先进入消息列表`() {
        val next = reducer.reduce(chat(), "s1", 1, assistant("", partial = true, reasoning = "先想一下"))
        val entry = next.chat.messages.single() as ChatEntry.Assistant
        assertEquals("", entry.text)
        assertEquals("先想一下", entry.thinking)
        assertTrue(entry.streaming)
    }

    @Test
    fun `partial 与 final 的思考按更完整的一份合并`() {
        val partial = reducer.reduce(chat(), "s1", 1, assistant("", partial = true, reasoning = "…后半段推理"))
        val final = reducer.reduce(
            partial.chat,
            "s1",
            2,
            assistant("结论", partial = false, reasoning = "完整的前半段推理，后半段推理"),
        )
        val entry = final.chat.messages.single() as ChatEntry.Assistant
        assertEquals("完整的前半段推理，后半段推理", entry.thinking)
        assertEquals("结论", entry.text)
        assertFalse(entry.streaming)
    }

    @Test
    fun `final 思考为空时保留 partial 已收到的内容`() {
        val partial = reducer.reduce(chat(), "s1", 1, assistant("", partial = true, reasoning = "已收到的思考"))
        val final = reducer.reduce(partial.chat, "s1", 2, assistant("回答", partial = false, reasoning = null))
        assertEquals("已收到的思考", (final.chat.messages.single() as ChatEntry.Assistant).thinking)
    }

    @Test
    fun `partial 累计正文覆盖同一条助手消息`() {
        val first = reducer.reduce(chat(), "s1", 1, assistant("你", partial = true))
        val second = reducer.reduce(first.chat, "s1", 2, assistant("你好", partial = true))
        assertEquals(1, second.chat.messages.size)
        assertEquals("你好", (second.chat.messages.single() as ChatEntry.Assistant).text)
        assertTrue((second.chat.messages.single() as ChatEntry.Assistant).streaming)
    }

    @Test
    fun `final 到达后结束流式且不回退正文`() {
        val partial = reducer.reduce(chat(), "s1", 1, assistant("完整回答", partial = true))
        val final = reducer.reduce(partial.chat, "s1", 2, assistant("完整回答", partial = false))
        val entry = final.chat.messages.single() as ChatEntry.Assistant
        assertEquals("完整回答", entry.text)
        assertFalse(entry.streaming)
    }

    @Test
    fun `工具调用只统计次数且 partial 与 final 不重复计数`() {
        val tools = listOf(
            ToolUseView("t1", "Read"),
            ToolUseView("t2", "Read"),
            ToolUseView("t3", "Bash"),
        )
        val partial = reducer.reduce(chat(), "s1", 1, assistant("检查中", partial = true, tools = tools))
        val final = reducer.reduce(partial.chat, "s1", 2, assistant("检查完", partial = false, tools = tools))
        assertEquals(3, final.chat.tools.total)
        assertEquals(mapOf("Read" to 2, "Bash" to 1), final.chat.tools.byName)
        assertEquals("Read ×2 · Bash ×1", final.chat.tools.summary())
        assertTrue(final.chat.messages.none { it is ChatEntry.Assistant && it.text.contains("工具") })
    }

    @Test
    fun `权限请求进入待处理并产生通知`() {
        val result = reducer.reduce(
            chat(),
            "s1",
            3,
            AgentPayload.ProferEvent(
                ProferEventView.PermissionRequest(
                    requestId = "p1",
                    sessionId = "s1",
                    toolName = "Bash",
                    description = "执行测试",
                    command = "bun test",
                    dangerLevel = "medium",
                ),
            ),
        )
        val pending = result.chat.pendingInteraction as PendingInteraction.Permission
        assertEquals("p1", pending.id)
        assertEquals("bun test", pending.command)
        assertEquals(NotificationKind.Permission, result.notifications.single().kind)
    }

    @Test
    fun `权限已解决后清空待处理`() {
        val pending = reducer.reduce(
            chat(),
            "s1",
            3,
            AgentPayload.ProferEvent(
                ProferEventView.PermissionRequest("p1", "s1", "Bash", "x", null, null),
            ),
        )
        val resolved = reducer.reduce(
            pending.chat,
            "s1",
            4,
            AgentPayload.ProferEvent(ProferEventView.PermissionResolved("p1", "allow")),
        )
        assertNull(resolved.chat.pendingInteraction)
    }

    @Test
    fun `提问进入待处理并保留完整问题与选项`() {
        val result = reducer.reduce(
            chat(),
            "s1",
            5,
            AgentPayload.ProferEvent(
                ProferEventView.AskUserRequest(
                    "q1",
                    "s1",
                    listOf(
                        com.profer.pocket.model.AskQuestion(
                            question = "选哪个？",
                            options = listOf(com.profer.pocket.model.AskOption("A"), com.profer.pocket.model.AskOption("B")),
                        ),
                    ),
                ),
            ),
        )
        val pending = result.chat.pendingInteraction as PendingInteraction.Question
        assertEquals("选哪个？", pending.questions.single().question)
        assertEquals(listOf("A", "B"), pending.questions.single().options.map { it.label })
        assertEquals(NotificationKind.Question, result.notifications.single().kind)
    }

    @Test
    fun `run_completed 结束运行并抑制主动停止的通知`() {
        val running = chat().copy(running = true, messages = listOf(ChatEntry.Assistant("a", "x", true, 1)))
        val result = reducer.reduce(
            running,
            "s1",
            6,
            AgentPayload.ProferEvent(
                ProferEventView.RunCompleted(
                    sessionId = "s1",
                    stoppedByUser = true,
                    backgroundTasksPending = false,
                    resultErrors = emptyList(),
                    endReason = "stopped_by_user",
                ),
            ),
        )
        assertFalse(result.chat.running)
        assertTrue(result.notifications.isEmpty())
        assertFalse((result.chat.messages.single() as ChatEntry.Assistant).streaming)
    }

    @Test
    fun `run_completed 携带错误时追加警告提示`() {
        val result = reducer.reduce(
            chat(),
            "s1",
            7,
            AgentPayload.ProferEvent(
                ProferEventView.RunCompleted(
                    sessionId = "s1",
                    stoppedByUser = false,
                    backgroundTasksPending = false,
                    resultErrors = listOf("API Key 无效"),
                    endReason = "error",
                ),
            ),
        )
        val notice = result.chat.messages.filterIsInstance<ChatEntry.Notice>().single()
        assertEquals(NoticeTone.Warning, notice.tone)
        assertEquals("API Key 无效", notice.text)
        assertEquals(NotificationKind.RunCompleted, result.notifications.single().kind)
    }

    @Test
    fun `标题更新同时产生会话信号`() {
        val result = reducer.reduce(
            chat(),
            "s1",
            8,
            AgentPayload.ProferEvent(ProferEventView.TitleUpdated("新标题")),
        )
        assertEquals("新标题", result.chat.title)
        assertEquals(SessionSignal.Title("s1", "新标题"), result.signals.single())
    }

    @Test
    fun `用户消息按 uuid 去重`() {
        val payload = AgentPayload.SdkMessage(
            SdkMessageView(
                type = "user",
                uuid = "u1",
                partial = false,
                text = "你好",
                toolUses = emptyList(),
                toolResults = emptyList(),
                subtype = null,
                model = null,
                isError = false,
                errorMessage = null,
            ),
        )
        val first = reducer.reduce(chat(), "s1", 9, payload)
        val second = reducer.reduce(first.chat, "s1", 9, payload)
        assertEquals(1, second.chat.messages.size)
        assertEquals("你好", (second.chat.messages.single() as ChatEntry.User).text)
    }

    @Test
    fun `工具结果里的图片挂到助手消息上并按路径去重`() {
        val started = reducer.reduce(
            chat(),
            "s1",
            1,
            assistant("图做好了", partial = false),
        )
        val image = ImageRef(
            path = "/Users/mac/ws/s1/.context/agent-output-images/a.png",
            filename = "a.png",
            mediaType = "image/png",
        )
        val applied = reducer.reduce(
            started.chat,
            "s1",
            2,
            user(results = listOf(ToolResultView("t1", images = listOf(image)))),
        )
        val entry = applied.chat.messages.single() as ChatEntry.Assistant
        assertEquals(listOf("a.png"), entry.images.map { it.filename })

        // 重放/重复帧不重复追加。
        val replay = reducer.reduce(
            applied.chat,
            "s1",
            3,
            user(results = listOf(ToolResultView("t1", images = listOf(image)))),
        )
        assertEquals(1, (replay.chat.messages.single() as ChatEntry.Assistant).images.size)
    }

    @Test
    fun `图片之后的助手帧不会把图片冲掉`() {
        val started = reducer.reduce(chat(), "s1", 1, assistant("图做好了", partial = false))
        val withImage = reducer.reduce(
            started.chat,
            "s1",
            2,
            user(results = listOf(ToolResultView("t1", images = listOf(ImageRef("/x/a.png", "a.png", "image/png"))))),
        )
        val final = reducer.reduce(withImage.chat, "s1", 3, assistant("图做好了", partial = false))
        assertEquals(1, (final.chat.messages.single() as ChatEntry.Assistant).images.size)
    }

    private fun user(
        text: String = "",
        results: List<ToolResultView> = emptyList(),
        uuid: String = "u1",
    ) = AgentPayload.SdkMessage(
        SdkMessageView(
            type = "user",
            uuid = uuid,
            partial = false,
            text = text,
            toolUses = emptyList(),
            toolResults = results,
            subtype = null,
            model = null,
            isError = false,
            errorMessage = null,
        ),
    )

    @Test
    fun `工具调用按发生顺序逐条记录，并由 tool_result 标记完成与失败`() {
        val read = ToolUseView(id = "t1", name = "Read", filePath = "/repo/a.ts")
        val bash = ToolUseView(id = "t2", name = "Bash")
        val started = reducer.reduce(
            chat(),
            "s1",
            1,
            assistant("", partial = true, tools = listOf(read, bash)),
        )
        assertEquals(listOf("t1", "t2"), started.chat.toolCalls.map { it.id })
        assertEquals("Read", started.chat.toolCalls.first().name)
        assertEquals("/repo/a.ts", started.chat.toolCalls.first().path)
        assertTrue(started.chat.completedToolIds.isEmpty())

        val finished = reducer.reduce(
            started.chat,
            "s1",
            2,
            user(results = listOf(ToolResultView("t1"), ToolResultView("t2", isError = true))),
        )
        assertEquals(setOf("t1", "t2"), finished.chat.completedToolIds)
        assertEquals(setOf("t2"), finished.chat.failedToolIds)
    }

    @Test
    fun `partial 重复携带同一工具时不会重复创建卡片`() {
        val read = ToolUseView(id = "t1", name = "Read", filePath = "/repo/a.ts")
        val first = reducer.reduce(chat(), "s1", 1, assistant("", partial = true, tools = listOf(read)))
        val second = reducer.reduce(first.chat, "s1", 2, assistant("读到一半", partial = true, tools = listOf(read)))
        assertEquals(1, second.chat.toolCalls.size)
    }

    @Test
    fun `新用户回合清空上一轮工具调用`() {
        val read = ToolUseView(id = "t1", name = "Read")
        val started = reducer.reduce(chat(), "s1", 1, assistant("", partial = true, tools = listOf(read)))
        val next = reducer.reduce(started.chat, "s1", 2, user(text = "继续"))
        assertTrue(next.chat.toolCalls.isEmpty())
        assertTrue(next.chat.completedToolIds.isEmpty())
    }

    @Test
    fun `修改类工具的文件进入回合汇总，失败结果会剔除`() {
        val edit = ToolUseView(id = "t1", name = "Edit", filePath = "/repo/a.ts")
        val read = ToolUseView(id = "t2", name = "Read", filePath = "/repo/b.ts")
        val applied = reducer.reduce(
            chat(),
            "s1",
            1,
            assistant("改好了", partial = false, tools = listOf(edit, read)),
        )
        assertEquals(listOf("/repo/a.ts"), applied.chat.visibleTurnFiles)

        val failed = reducer.reduce(
            applied.chat,
            "s1",
            2,
            user(results = listOf(ToolResultView("t1", isError = true))),
        )
        assertTrue(failed.chat.visibleTurnFiles.isEmpty())
    }

    @Test
    fun `新用户回合清空上一轮文件汇总`() {
        val write = ToolUseView(id = "t1", name = "Write", filePath = "/repo/a.ts")
        val applied = reducer.reduce(
            chat(),
            "s1",
            1,
            assistant("写好了", partial = false, tools = listOf(write)),
        )
        val next = reducer.reduce(applied.chat, "s1", 2, user(text = "继续"))
        assertTrue(next.chat.visibleTurnFiles.isEmpty())
    }

    @Test
    fun `assistant usage 更新上下文占用，result modelUsage 更新窗口`() {
        val withUsage = AgentPayload.SdkMessage(
            SdkMessageView(
                type = "assistant",
                uuid = "a1",
                partial = false,
                text = "ok",
                toolUses = emptyList(),
                subtype = null,
                model = null,
                isError = false,
                errorMessage = null,
                usage = TokenUsage(
                    inputTokens = 1200,
                    outputTokens = 80,
                    cacheReadInputTokens = 300,
                    cacheCreationInputTokens = 40,
                ),
            ),
        )
        val first = reducer.reduce(chat(), "s1", 1, withUsage)
        assertEquals(1540, first.chat.contextUsed)
        assertEquals(1200, first.chat.contextUsage?.inputTokens)
        assertEquals(300, first.chat.contextUsage?.cacheReadInputTokens)
        assertEquals(40, first.chat.contextUsage?.cacheCreationInputTokens)
        assertEquals(80, first.chat.contextUsage?.outputTokens)

        val result = AgentPayload.SdkMessage(
            SdkMessageView(
                type = "result",
                uuid = null,
                partial = false,
                text = "",
                toolUses = emptyList(),
                subtype = "success",
                model = null,
                isError = false,
                errorMessage = null,
                modelUsage = mapOf("claude-x" to 200000),
            ),
        )
        val second = reducer.reduce(first.chat, "s1", 2, result)
        assertEquals(200000, second.chat.contextWindow)
        assertEquals(1540, second.chat.contextUsed)
    }
}
