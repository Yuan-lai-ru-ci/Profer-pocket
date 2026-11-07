package com.profer.pocket.chat

import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.model.ChatImage
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 回合拆分：一轮七八步时必须收成一个过程组，组外只留答案正文。
 * 这里锁的是「哪些内容进过程、哪些留在组外、按什么顺序」。
 */
class TurnLayoutTest {

    private fun tool(id: String, kind: ToolKind = ToolKind.Read) = ToolBlock(
        id = id,
        kind = kind,
        title = "读取 $id",
        subtitle = "src/$id.kt",
        status = ToolStatus.Success,
    )

    private fun step(
        id: String,
        thinking: String? = null,
        tools: List<ToolBlock> = emptyList(),
        text: String = "",
        images: List<ChatImage> = emptyList(),
    ) = ChatMessage.Assistant(
        id = id,
        thinking = thinking?.let { ThinkingBlock("3 秒", it) },
        tools = tools,
        blocks = if (text.isEmpty()) emptyList() else listOf(ContentBlock.Paragraph(text)),
        images = images,
    )

    @Test
    fun `多步回合合成一个过程组，思考与工具按步骤交替`() {
        val layout = layoutTurn(
            listOf(
                step("s1", thinking = "先看日志", tools = listOf(tool("a"))),
                step("s2", thinking = "再看游标", tools = listOf(tool("b", ToolKind.Search))),
                step("s3", thinking = "最后跑测试", tools = listOf(tool("c", ToolKind.Bash))),
            ),
        )

        assertEquals(3, layout.stepCount)
        assertEquals(3, layout.tools.size)
        assertEquals(3, layout.thinkingCount)
        assertTrue(layout.tailBlocks.isEmpty())
        assertEquals(
            listOf("s1:thinking", "a", "s2:thinking", "b", "s3:thinking", "c"),
            layout.items.map { it.key },
        )
        assertEquals(listOf(0, 0, 1, 1, 2, 2), layout.items.map { it.step })
    }

    @Test
    fun `只有最后一步的正文留在过程组外`() {
        val layout = layoutTurn(
            listOf(
                step("s1", thinking = "先确认", tools = listOf(tool("a")), text = "我先读一下配置。"),
                step("s2", thinking = "可以回答", text = "结论：游标推进得太早。"),
            ),
        )

        assertEquals(
            listOf(ContentBlock.Paragraph("结论：游标推进得太早。")),
            layout.tailBlocks,
        )
        // 中间步骤的说明是给工具看的，跟着过程组一起折。
        assertTrue(layout.items.any { it is TurnItem.Text && it.key == "s1:text" })
        assertFalse(layout.items.any { it is TurnItem.Text && it.key == "s2:text" })
    }

    @Test
    fun `单步回合保持原来的渲染：正文始终在组外`() {
        // 单步消息拿不到「正文在工具调用之前还是之后」，保守保持既有行为，
        // 否则一个带工具调用的普通回复会突然只剩一行「执行过程」。
        val layout = layoutTurn(
            listOf(step("s1", thinking = "先确认", tools = listOf(tool("a")), text = "结论：游标推进得太早。")),
        )

        assertEquals(listOf(ContentBlock.Paragraph("结论：游标推进得太早。")), layout.tailBlocks)
        assertEquals(1, layout.tools.size)
    }

    @Test
    fun `最后一段正文之后还有工具调用时，正文留在过程里`() {
        val layout = layoutTurn(
            listOf(
                step("s1", text = "我先看看配置。", tools = listOf(tool("a"))),
                step("s2", tools = listOf(tool("b"))),
            ),
        )

        assertTrue(layout.tailBlocks.isEmpty())
        assertTrue(layout.items.any { it is TurnItem.Text && it.key == "s1:text" })
    }

    @Test
    fun `只有思考的回合标记为思考即回答`() {
        val layout = layoutTurn(listOf(step("s1", thinking = "模型只回了一段思考。")))

        assertTrue(layout.thinkingOnly)
        assertTrue(layout.hasProcess)
        // 过程组内容仍在，渲染层据此把思考提到正文位置并展开。
        assertEquals(1, layout.items.size)
    }

    @Test
    fun `有工具或正文就不算思考即回答`() {
        assertFalse(layoutTurn(listOf(step("s1", thinking = "想", tools = listOf(tool("a"))))).thinkingOnly)
        assertFalse(layoutTurn(listOf(step("s1", thinking = "想", text = "答"))).thinkingOnly)
    }

    @Test
    fun `多步回合的图片按顺序合并去重`() {
        val layout = layoutTurn(
            listOf(
                step("s1", tools = listOf(tool("a")), images = listOf(ChatImage("p1", "a.png", "image/png"))),
                step("s2", tools = listOf(tool("b")), images = listOf(ChatImage("p1", "a.png", "image/png"))),
                step("s3", text = "看这张图", images = listOf(ChatImage("p2", "b.png", "image/png"))),
            ),
        )

        assertEquals(listOf("p1", "p2"), layout.images.map { it.path })
    }
}
