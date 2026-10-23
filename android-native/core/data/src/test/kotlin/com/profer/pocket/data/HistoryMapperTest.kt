package com.profer.pocket.data

import com.profer.pocket.data.protocol.HistoryMapper
import com.profer.pocket.data.protocol.ImageRef
import com.profer.pocket.data.protocol.SdkMessageView
import com.profer.pocket.data.protocol.ToolResultView
import com.profer.pocket.data.protocol.ToolUseView
import com.profer.pocket.data.protocol.TokenUsage
import com.profer.pocket.model.ChatEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HistoryMapperTest {

    private fun message(
        type: String,
        uuid: String,
        text: String,
        createdAt: Long? = null,
        reasoning: String? = null,
        toolUses: List<ToolUseView> = emptyList(),
        toolResults: List<ToolResultView> = emptyList(),
        usage: TokenUsage? = null,
        modelUsage: Map<String, Int> = emptyMap(),
    ) = SdkMessageView(
        type = type,
        uuid = uuid,
        createdAt = createdAt,
        partial = false,
        text = text,
        toolUses = toolUses,
        toolResults = toolResults,
        subtype = null,
        model = null,
        isError = false,
        errorMessage = null,
        reasoning = reasoning,
        usage = usage,
        modelUsage = modelUsage,
    )

    @Test
    fun `历史里工具产出的图片回到对应助手消息`() {
        val history = HistoryMapper.map(
            listOf(
                message("user", "u1", "画一张图"),
                message("assistant", "a1", "画好了"),
                message(
                    "user",
                    "u2",
                    "",
                    toolResults = listOf(
                        ToolResultView(
                            toolUseId = "t1",
                            images = listOf(ImageRef("/ws/s1/.context/agent-output-images/a.png", "a.png", "image/png")),
                        ),
                    ),
                ),
            ),
            now = 9_999L,
        )

        val assistant = history.entries.filterIsInstance<ChatEntry.Assistant>().single()
        assertEquals(listOf("a.png"), assistant.images.map { it.filename })
        assertEquals("/ws/s1/.context/agent-output-images/a.png", assistant.images.single().path)
    }

    @Test
    fun `历史消息使用落盘时间而不是加载时间`() {
        val history = HistoryMapper.map(
            listOf(
                message("user", "u1", "电脑上发的指令", createdAt = 1_000L),
                message("assistant", "a1", "收到", createdAt = 2_000L),
            ),
            now = 9_999L,
        )

        assertEquals(2, history.entries.size)
        assertEquals("电脑上发的指令", (history.entries[0] as ChatEntry.User).text)
        assertEquals(1_000L, history.entries[0].at)
        assertEquals(2_000L, history.entries[1].at)
    }

    @Test
    fun `历史消息保留思考，只有思考的助手消息也不丢`() {
        val history = HistoryMapper.map(
            listOf(
                message("assistant", "a0", "", reasoning = "只有思考的一轮"),
                message("assistant", "a1", "结论", reasoning = "推理过程"),
            ),
            now = 9_999L,
        )

        assertEquals(2, history.entries.size)
        val onlyThinking = history.entries[0] as ChatEntry.Assistant
        assertEquals("", onlyThinking.text)
        assertEquals("只有思考的一轮", onlyThinking.thinking)
        assertEquals("推理过程", (history.entries[1] as ChatEntry.Assistant).thinking)
    }

    @Test
    fun `历史保留本轮工具调用与完成失败状态`() {
        val history = HistoryMapper.map(
            listOf(
                message(
                    "assistant",
                    "a1",
                    "",
                    toolUses = listOf(
                        ToolUseView("t1", "Read", "/repo/a.ts"),
                        ToolUseView("t2", "Bash"),
                    ),
                ),
                message(
                    "user",
                    "u2",
                    "",
                    toolResults = listOf(ToolResultView("t1"), ToolResultView("t2", isError = true)),
                ),
            ),
            now = 9_999L,
        )

        assertEquals(listOf("t1", "t2"), history.toolCalls.map { it.id })
        assertEquals("/repo/a.ts", history.toolCalls.first().path)
        assertEquals(setOf("t1", "t2"), history.completedToolIds)
        assertEquals(setOf("t2"), history.failedToolIds)
    }

    @Test
    fun `缺少落盘时间时退回加载时间`() {
        val history = HistoryMapper.map(
            listOf(message("user", "u1", "旧消息")),
            now = 9_999L,
        )

        assertEquals(9_999L, history.entries.single().at)
    }

    @Test
    fun `历史水合带回最近一条 usage 与上下文窗口`() {
        val history = HistoryMapper.map(
            listOf(
                message("user", "u1", "第一轮", createdAt = 1_000L),
                message(
                    "assistant",
                    "a1",
                    "第一轮回复",
                    createdAt = 2_000L,
                    usage = TokenUsage(inputTokens = 100, outputTokens = 20, cacheReadInputTokens = 1_000),
                ),
                message("user", "u2", "第二轮", createdAt = 3_000L),
                message(
                    "assistant",
                    "a2",
                    "第二轮回复",
                    createdAt = 4_000L,
                    usage = TokenUsage(inputTokens = 200, outputTokens = 30, cacheReadInputTokens = 50_000),
                ),
                message("result", "r1", "", createdAt = 4_100L, modelUsage = mapOf("deepseek-flash" to 1_000_000)),
            ),
            now = 5_000L,
        )

        assertEquals(50_200, history.contextUsage?.usedTokens)
        assertEquals(4_000L, history.contextUsage?.updatedAt)
        assertEquals(1_000_000, history.contextWindow)
    }

    @Test
    fun `没有 usage 时上下文保持为空而不是零`() {
        val history = HistoryMapper.map(
            listOf(
                message("user", "u1", "只有提问", createdAt = 1_000L),
                message("assistant", "a1", "回复但不带 usage", createdAt = 2_000L),
            ),
            now = 5_000L,
        )

        assertNull(history.contextUsage)
        assertNull(history.contextWindow)
    }
}
