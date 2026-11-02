package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.SessionSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UiMappingSourceTest {

    private val status = ConnectionStatus.Connected("ws://host/ws")

    private fun state(chat: SessionChat, sessions: List<SessionSummary> = emptyList()) =
        UiMapping.chatState("s1", chat, sessions, emptyList(), status)

    @Test
    fun `定时任务来源卡显示可读作者，不显示原始 id`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Source(
                    id = "u1",
                    authorLabel = "定时任务触发",
                    sourceType = "automation",
                    sourceId = "auto-1",
                    text = "检查今天的部署日志",
                    at = 1L,
                    scheduled = true,
                ),
            ),
        )

        val source = state(chat).messages.single() as ChatMessage.Source
        assertEquals("定时任务触发", source.authorLabel)
        assertEquals("来自定时任务", source.sourceLabel)
        assertEquals("检查今天的部署日志", source.text)
        assertTrue(source.scheduled)
    }

    @Test
    fun `派工来源卡用来源会话标题，不伪造用户气泡`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Source(
                    id = "u2",
                    authorLabel = "Agent 任务交接",
                    sourceType = "session",
                    sourceId = "parent-1",
                    text = "把方案落地",
                    at = 2L,
                ),
            ),
        )
        val sessions = listOf(SessionSummary(id = "parent-1", title = "方案讨论"))

        val ui = state(chat, sessions)
        val source = ui.messages.single() as ChatMessage.Source
        assertEquals("来自会话「方案讨论」", source.sourceLabel)
        assertTrue(ui.messages.none { it is ChatMessage.User })
    }

    @Test
    fun `来源会话不可访问时只给状态，不回显 id`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Source(
                    id = "u3",
                    authorLabel = "Agent 任务交接",
                    sourceType = "session",
                    sourceId = "gone-session",
                    text = "继续执行",
                    at = 3L,
                ),
            ),
        )

        val source = state(chat).messages.single() as ChatMessage.Source
        assertEquals("来源会话不可访问", source.sourceLabel)
        assertTrue(!source.sourceLabel!!.contains("gone-session"))
    }

    @Test
    fun `没有来源引用时不编造来源文案`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(
                ChatEntry.Source(
                    id = "u4",
                    authorLabel = "历史任务来源",
                    text = "旧记录",
                    at = 4L,
                ),
            ),
        )

        val source = state(chat).messages.single() as ChatMessage.Source
        assertNull(source.sourceLabel)
    }
}
