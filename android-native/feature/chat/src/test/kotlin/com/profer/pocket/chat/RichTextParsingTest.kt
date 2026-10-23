package com.profer.pocket.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RichTextParsingTest {

    @Test
    fun `markdown 表格解析成表头与数据行`() {
        val raw = """
            | # | 问题 | 修法 | 验证 |
            |---|------|------|------|
            | 1 | pet off 后忽略 | 开头检查 enabled | 实机通过 |
            | 2 | 每次编译告警 | 改生成器 | 0 告警 |
        """.trimIndent()

        val table = parseRichBlocks(raw).filterIsInstance<ContentBlock.Table>().single()
        assertEquals(listOf("#", "问题", "修法", "验证"), table.headers)
        assertEquals(2, table.rows.size)
        assertEquals(listOf("2", "每次编译告警", "改生成器", "0 告警"), table.rows[1])
        assertFalse(parseRichBlocks(raw).any { it is ContentBlock.Paragraph })
    }

    @Test
    fun `表格单元格里的竖线转义不会被拆列`() {
        val raw = """
            | 命令 | 说明 |
            |---|---|
            | a \| b | 管道 |
        """.trimIndent()

        val table = parseRichBlocks(raw).filterIsInstance<ContentBlock.Table>().single()
        assertEquals(listOf("a | b", "管道"), table.rows.single())
    }

    @Test
    fun `有序列表解析成 Ordered`() {
        val blocks = parseRichBlocks(
            """
            1. 先检查连接
            2. 再打开会话
            3. 最后滚动到底
            """.trimIndent(),
        )
        val ordered = blocks.filterIsInstance<ContentBlock.Ordered>().single()
        assertEquals(listOf("先检查连接", "再打开会话", "最后滚动到底"), ordered.items)
    }

    @Test
    fun `行内代码去掉反引号并保留等宽 span`() {
        val annotated = inlineMarkdown("跑 `pet off` 后看状态", Color.Transparent)
        assertEquals("跑 pet off 后看状态", annotated.text)
        assertTrue(annotated.spanStyles.any { it.item.fontFamily == FontFamily.Monospace })
    }

    @Test
    fun `加粗与行内代码可以共存`() {
        val annotated = inlineMarkdown("**结论** 是 `pet off` 有效", Color.Transparent)
        assertEquals("结论 是 pet off 有效", annotated.text)
        assertEquals(2, annotated.spanStyles.size)
    }

    @Test
    fun `没有闭合的反引号不会吞掉后半段`() {
        val annotated = inlineMarkdown("开头 `没有闭合 后面还有正文", Color.Transparent)
        assertTrue(annotated.text.endsWith("后面还有正文"))
        assertTrue(annotated.text.contains("`没有闭合"))
    }
}
