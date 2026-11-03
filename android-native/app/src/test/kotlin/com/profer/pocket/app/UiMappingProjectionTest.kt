package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.ConversationSummary
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.ToolCall
import com.profer.pocket.model.ChatMessage as DomainChatMessage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长会话投影缓存：流式 token 每 60ms 触发一次整表映射，
 * 未变化的消息必须复用上一次的实例，否则每次都要重新分块 Markdown、重新分配整张表。
 */
class UiMappingProjectionTest {

    private val status = ConnectionStatus.Connected("ws://host/ws")

    private fun state(chat: SessionChat, sessions: List<com.profer.pocket.model.SessionSummary> = emptyList()) =
        UiMapping.chatState("s1", chat, sessions, emptyList(), status)

    private fun conversation(id: String, messages: List<DomainChatMessage>) =
        UiMapping.conversationUiState(
            conversation = ConversationSummary(id = id, title = "对话"),
            messages = messages,
            stream = null,
            status = status,
        )

    private fun history(count: Int): List<ChatEntry> = List(count) { index ->
        if (index % 2 == 0) {
            ChatEntry.User(id = "u$index", text = "问题 $index", at = 1_700_000_000_000 + index)
        } else {
            ChatEntry.Assistant(
                id = "a$index",
                text = "## 回答 $index\n\n- 第一条\n- 第二条\n\n```kt\nval x = $index\n```",
                streaming = false,
                at = 1_700_000_000_000 + index,
            )
        }
    }

    @Test
    fun `流式 token 只重建变化的那条消息`() {
        val before = history(40)
        val first = state(SessionChat(sessionId = "s1", messages = before, loaded = true)).messages

        // 只有最后一条助手消息追加了 token，其余条目对象保持不变。
        val streamingTail = ChatEntry.Assistant(
            id = "a39",
            text = before.last().let { (it as ChatEntry.Assistant).text } + "\n新增一行",
            streaming = true,
            at = 1_700_000_000_039,
        )
        val after = before.dropLast(1) + streamingTail
        val second = state(SessionChat(sessionId = "s1", messages = after, loaded = true)).messages

        assertEquals(first.size, second.size)
        for (index in 0 until first.lastIndex) {
            assertSame("第 $index 条未变化的消息应复用上一次的投影", first[index], second[index])
        }
        assertNotSame(first.last(), second.last())
        assertTrue((second.last() as ChatMessage.Assistant).streaming)
    }

    @Test
    fun `消息表没有变化时复用同一个列表实例`() {
        val chat = SessionChat(sessionId = "s1", messages = history(30), loaded = true)
        val first = state(chat).messages
        val second = state(chat).messages
        assertSame(first, second)
    }

    @Test
    fun `切换会话后不会串用上一条会话的投影`() {
        val a = SessionChat(
            sessionId = "s1",
            messages = listOf(ChatEntry.Assistant(id = "same", text = "会话 A", streaming = false, at = 1)),
            loaded = true,
        )
        val b = SessionChat(
            sessionId = "s2",
            messages = listOf(ChatEntry.Assistant(id = "same", text = "会话 B", streaming = false, at = 1)),
            loaded = true,
        )
        UiMapping.chatState("s1", a, emptyList(), emptyList(), status)
        val second = UiMapping.chatState("s2", b, emptyList(), emptyList(), status).messages.single()
        assertEquals("会话 B", (second as ChatMessage.Assistant).blocks.first().let { block ->
            (block as com.profer.pocket.chat.ContentBlock.Paragraph).text
        })
    }

    @Test
    fun `工具状态变化会刷新工具卡`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Assistant(
                    id = "a1",
                    text = "执行中",
                    streaming = true,
                    at = 1,
                    toolCalls = listOf(ToolCall(id = "t1", name = "Bash", path = "/repo")),
                ),
            ),
            running = true,
            loaded = true,
        )
        val running = (state(chat).messages.single() as ChatMessage.Assistant).tools.single()
        assertEquals(ToolStatus.Running, running.status)

        val done = state(chat.copy(completedToolIds = setOf("t1"))).messages.single() as ChatMessage.Assistant
        assertEquals(ToolStatus.Success, done.tools.single().status)
    }

    @Test
    fun `Chat 模式消息未变化时复用投影`() {
        val messages = List(20) { index ->
            DomainChatMessage(
                id = "m$index",
                role = if (index % 2 == 0) "user" else "assistant",
                content = "内容 $index",
                createdAt = 1_700_000_000_000 + index,
            )
        }
        val first = conversation("c1", messages).messages
        val second = conversation("c1", messages).messages
        assertSame(first, second)

        val appended = messages + DomainChatMessage(
            id = "m20",
            role = "assistant",
            content = "追加",
            createdAt = 1_700_000_000_020,
        )
        val third = conversation("c1", appended).messages
        for (index in messages.indices) {
            assertSame(first[index], third[index])
        }
        assertEquals(21, third.size)
    }

    @Test
    fun `图片降采样倍数按长边计算`() {
        assertEquals(1, sampleSizeFor(1080, 1920, 1440))
        assertEquals(2, sampleSizeFor(4000, 3000, 1440))
        assertEquals(4, sampleSizeFor(8000, 6000, 1440))
        assertEquals(1, sampleSizeFor(0, 0, 1440))
    }
}
