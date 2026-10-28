package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.SessionChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiMappingThinkingTest {

    private val status = ConnectionStatus.Connected("ws://host/ws")

    @Test
    fun `流式思考映射为思考中卡片`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(ChatEntry.Assistant("a1", "", streaming = true, at = 1L, thinking = "先看日志")),
        )
        val message = UiMapping.chatState("s1", chat, emptyList(), emptyList(), status)
            .messages
            .single() as ChatMessage.Assistant

        assertEquals("先看日志", message.thinking?.body)
        assertEquals("思考中…", message.thinking?.durationLabel)
    }

    @Test
    fun `思考结束后标签回到思考过程`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(ChatEntry.Assistant("a1", "结论", streaming = false, at = 1L, thinking = "推理")),
        )
        val message = UiMapping.chatState("s1", chat, emptyList(), emptyList(), status)
            .messages
            .single() as ChatMessage.Assistant

        assertEquals("思考过程", message.thinking?.durationLabel)
    }

    @Test
    fun `没有思考时不生成空卡片`() {
        val chat = SessionChat(
            sessionId = "s1",
            messages = listOf(ChatEntry.Assistant("a1", "回答", streaming = false, at = 1L)),
        )
        val message = UiMapping.chatState("s1", chat, emptyList(), emptyList(), status)
            .messages
            .single() as ChatMessage.Assistant

        assertNull(message.thinking)
    }
}
