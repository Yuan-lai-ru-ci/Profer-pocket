package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.AgentPresetSummary
import com.profer.pocket.model.ReasoningCapability
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.SessionSummary
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

    @Test
    fun `思考强度按模型能力与预设默认档展示`() {
        val summary = SessionSummary(id = "s1", title = "会话", channelId = "c1", modelId = "m1", presetId = "p1")
        val state = UiMapping.chatState(
            sessionId = "s1",
            chat = SessionChat(sessionId = "s1"),
            sessions = listOf(summary),
            workspaces = emptyList(),
            status = status,
            presets = listOf(AgentPresetSummary(id = "p1", name = "研发", effort = "medium")),
            capability = ReasoningCapability(levels = listOf("off", "high", "max"), defaultLevel = "high"),
        )

        // 预设 medium 在 DeepSeek 上收敛为 high。
        assertEquals("高", state.thinkingLabel)
        assertEquals("跟随预设「高」", state.thinkingSourceLabel)
        assertNull(state.thinkingSelectedValue)
        assertEquals(listOf(null, "off", "high", "max"), state.thinkingOptions.map { it.value })
    }

    @Test
    fun `会话覆盖优先于预设默认`() {
        val summary = SessionSummary(
            id = "s1",
            title = "会话",
            channelId = "c1",
            modelId = "m1",
            thinkingLevel = "max",
        )
        val state = UiMapping.chatState(
            sessionId = "s1",
            chat = SessionChat(sessionId = "s1"),
            sessions = listOf(summary),
            workspaces = emptyList(),
            status = status,
            capability = ReasoningCapability(levels = listOf("off", "high", "max"), defaultLevel = "high"),
        )

        assertEquals("最高", state.thinkingLabel)
        assertEquals("本会话覆盖", state.thinkingSourceLabel)
        assertEquals("max", state.thinkingSelectedValue)
    }

    @Test
    fun `旧档位被收敛时勾选落在收敛后的档`() {
        // 会话里留着上一个模型的 xhigh，当前模型只有 off/high：勾选不能落空。
        val summary = SessionSummary(
            id = "s1",
            title = "会话",
            channelId = "c1",
            modelId = "m1",
            thinkingLevel = "xhigh",
        )
        val state = UiMapping.chatState(
            sessionId = "s1",
            chat = SessionChat(sessionId = "s1"),
            sessions = listOf(summary),
            workspaces = emptyList(),
            status = status,
            capability = ReasoningCapability(levels = listOf("off", "high"), defaultLevel = "high"),
        )

        assertEquals("高", state.thinkingLabel)
        assertEquals("high", state.thinkingSelectedValue)
    }

    @Test
    fun `没有模型能力时回退强度档`() {
        val summary = SessionSummary(id = "s1", title = "会话", channelId = "c1", modelId = "m1", agentEffort = "low")
        val state = UiMapping.chatState(
            sessionId = "s1",
            chat = SessionChat(sessionId = "s1"),
            sessions = listOf(summary),
            workspaces = emptyList(),
            status = status,
            capabilityLoading = true,
        )

        assertEquals("低", state.thinkingLabel)
        assertEquals("本会话覆盖", state.thinkingSourceLabel)
        assertEquals("low", state.thinkingSelectedValue)
        assertEquals(listOf(null, "low", "medium", "high", "max"), state.thinkingOptions.map { it.value })
        // 能力还在读：弹层显示占位，不先闪回强度档。
        assertEquals(true, state.thinkingLoading)
    }
}
