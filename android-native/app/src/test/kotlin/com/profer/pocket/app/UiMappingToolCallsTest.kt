package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.chat.ToolKind
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMappingToolCallsTest {

    private val status = ConnectionStatus.Connected("ws://host/ws")

    private fun state(chat: SessionChat) =
        UiMapping.chatState("s1", chat, emptyList(), emptyList(), status)

    private fun tools(chat: SessionChat): List<com.profer.pocket.chat.ToolBlock> =
        state(chat).messages.filterIsInstance<ChatMessage.Assistant>().flatMap { it.tools }

    @Test
    fun `每种工具映射成自己的图标分类与状态`() {
        val chat = SessionChat(
            sessionId = "s1",
            running = true,
            messages = listOf(
                ChatEntry.Assistant(
                    id = "a1",
                    text = "结论",
                    streaming = true,
                    at = 1L,
                    toolCalls = listOf(
                        ToolCall("t1", "Read", "/repo/a.ts", 1L),
                        ToolCall("t2", "Bash", null, 2L),
                        ToolCall("t3", "mcp__custom", null, 3L),
                    ),
                ),
            ),
            completedToolIds = setOf("t1"),
        )

        val mapped = tools(chat)
        assertEquals(listOf(ToolKind.Read, ToolKind.Bash, ToolKind.Other), mapped.map { it.kind })
        assertEquals(ToolStatus.Success, mapped[0].status)
        assertEquals(ToolStatus.Running, mapped[1].status)
        assertEquals(ToolStatus.Running, mapped[2].status)
        assertEquals("/repo/a.ts", mapped[0].subtitle)
        // 没有路径时不伪造命令：副标题用状态文案，不重复工具名。
        assertEquals("运行中…", mapped[1].subtitle)
    }

    @Test
    fun `失败工具标记为 Error，回合结束后未完成的工具标为 Waiting`() {
        val chat = SessionChat(
            sessionId = "s1",
            running = false,
            messages = listOf(
                ChatEntry.Assistant(
                    id = "a1",
                    text = "",
                    streaming = false,
                    at = 1L,
                    toolCalls = listOf(
                        ToolCall("t1", "Write", "/repo/a.ts", 1L),
                        ToolCall("t2", "Bash", null, 2L),
                    ),
                ),
            ),
            completedToolIds = setOf("t1"),
            failedToolIds = setOf("t2"),
        )

        val mapped = tools(chat)
        assertEquals(ToolStatus.Success, mapped[0].status)
        assertEquals(ToolStatus.Error, mapped[1].status)
    }

    @Test
    fun `连续助手条目的工具卡收进同一个回合`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Assistant(
                    id = "a1",
                    text = "先看看文件",
                    streaming = false,
                    at = 1L,
                    toolCalls = listOf(ToolCall("t1", "Read", "/repo/a.ts", 1L)),
                ),
                ChatEntry.Assistant(
                    id = "a2",
                    text = "再跑测试",
                    streaming = false,
                    at = 2L,
                    toolCalls = listOf(ToolCall("t2", "Bash", null, 2L)),
                ),
            ),
        )

        // 两段连续助手条目属于同一轮：工具卡按发生顺序留在回合里，不再各占一块。
        val turn = state(chat).messages.single() as ChatMessage.Turn
        assertEquals(listOf("t1", "t2"), turn.steps.flatMap { step -> step.tools.map { it.id } })
        assertEquals(ToolKind.Read, turn.steps[0].tools.single().kind)
        assertEquals(ToolKind.Bash, turn.steps[1].tools.single().kind)
    }

    @Test
    fun `纯工具回合的助手消息没有正文，但工具卡仍然显示`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Assistant(
                    id = "a1",
                    text = "",
                    streaming = false,
                    at = 1L,
                    toolCalls = listOf(ToolCall("t1", "Grep", null, 1L)),
                ),
            ),
        )

        val assistant = state(chat).messages.filterIsInstance<ChatMessage.Assistant>().single()
        assertTrue(assistant.blocks.isEmpty())
        assertEquals(ToolKind.Search, assistant.tools.single().kind)
    }
}
