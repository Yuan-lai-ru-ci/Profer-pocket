package com.profer.pocket.app

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
        UiMapping.chatState("s1", chat, emptyList(), emptyList(), status, draft = "")

    @Test
    fun `每种工具映射成自己的图标分类与状态`() {
        val chat = SessionChat(
            sessionId = "s1",
            running = true,
            toolCalls = listOf(
                ToolCall("t1", "Read", "/repo/a.ts", 1L),
                ToolCall("t2", "Bash", null, 2L),
                ToolCall("t3", "mcp__custom", null, 3L),
            ),
            completedToolIds = setOf("t1"),
        )

        val tools = state(chat).toolCalls
        assertEquals(listOf(ToolKind.Read, ToolKind.Bash, ToolKind.Other), tools.map { it.kind })
        assertEquals(ToolStatus.Success, tools[0].status)
        assertEquals(ToolStatus.Running, tools[1].status)
        assertEquals(ToolStatus.Running, tools[2].status)
        assertEquals("/repo/a.ts", tools[0].subtitle)
        // 没有路径时不伪造命令：副标题用状态文案，不重复工具名。
        assertEquals("运行中…", tools[1].subtitle)
    }

    @Test
    fun `失败工具标记为 Error，回合结束后未完成的工具标为 Waiting`() {
        val chat = SessionChat(
            sessionId = "s1",
            running = false,
            toolCalls = listOf(
                ToolCall("t1", "Write", "/repo/a.ts", 1L),
                ToolCall("t2", "Bash", null, 2L),
            ),
            completedToolIds = setOf("t1"),
            failedToolIds = setOf("t2"),
        )

        val tools = state(chat).toolCalls
        assertEquals(ToolStatus.Success, tools[0].status)
        assertEquals(ToolStatus.Error, tools[1].status)
    }

    @Test
    fun `工具不再聚合成一条卡片塞进助手消息`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(ChatEntry.Assistant("a1", "结论", false, 1L)),
            toolCalls = listOf(ToolCall("t1", "Read", "/repo/a.ts", 1L)),
        )

        val ui = state(chat)
        assertTrue(ui.messages.single().let { it is com.profer.pocket.chat.ChatMessage.Assistant && it.tools.isEmpty() })
        assertEquals(1, ui.toolCalls.size)
    }
}
