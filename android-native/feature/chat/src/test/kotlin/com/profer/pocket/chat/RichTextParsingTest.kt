package com.profer.pocket.chat

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
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

    // ---- 流式增量分块 ----

    /** 覆盖模型实际高频用法的一整段回复；前缀测试靠它把每种块的中间态都走到。 */
    private val streamingSample = """
        # 结论

        第一段有 `inline code` 和 **加粗**，先说清要做什么。

        - 第一条要点
        - 第二条要点

        1. 先检查连接
        2. 再打开会话

        > 引用一行

        | # | 问题 | 修法 |
        |---|---|---|
        | 1 | pet off 后忽略 | 开头检查 enabled |

        ```kotlin
        fun main() {
            println("hi")
        }
        ```

        收尾一段。
    """.trimIndent()

    @Test
    fun `流式增量解析与整条解析逐字一致`() {
        val id = "prefix-sweep"
        for (length in 0..streamingSample.length) {
            val prefix = streamingSample.substring(0, length)
            assertEquals(
                "prefix length=$length",
                parseRichBlocks(prefix),
                streamingRichBlocks(id, prefix),
            )
        }
    }

    @Test
    fun `CRLF 正文的流式增量同样与整条解析一致`() {
        val id = "prefix-sweep-crlf"
        val crlf = streamingSample.replace("\n", "\r\n")
        for (length in 0..crlf.length) {
            val prefix = crlf.substring(0, length)
            assertEquals(
                "crlf prefix length=$length",
                parseRichBlocks(prefix),
                streamingRichBlocks(id, prefix),
            )
        }
    }

    @Test
    fun `增量解析复用稳定前缀的块实例`() {
        val id = "prefix-reuse"
        val first = streamingRichBlocks(id, "甲\n\n乙\n\n丙")
        val second = streamingRichBlocks(id, "甲\n\n乙\n\n丙丁")

        assertEquals(parseRichBlocks("甲\n\n乙\n\n丙丁"), second)
        // 前两块没变：必须是同一个实例，否则说明整条重算了。
        assertSame(first[0], second[0])
        assertSame(first[1], second[1])
        assertFalse(first[2] === second[2])
    }

    @Test
    fun `正文被重写时退回整条解析`() {
        val id = "rewrite"
        streamingRichBlocks(id, "原来的回答\n\n第二段")
        val rewritten = "换了一个回答"
        assertEquals(parseRichBlocks(rewritten), streamingRichBlocks(id, rewritten))
    }

    @Test
    fun `半张表格先按段落渲染 收全后变成表格`() {
        val id = "table-flip"
        val beforeSeparator = streamingRichBlocks(id, "| # | 问题 |")
        assertTrue(beforeSeparator.single() is ContentBlock.Paragraph)

        val complete = streamingRichBlocks(id, "| # | 问题 |\n|---|---|\n| 1 | pet off 后忽略 |")
        assertTrue(complete.single() is ContentBlock.Table)
    }

    @Test
    fun `流式中间态不写进已完成消息缓存`() {
        val id = "cache-hygiene"
        clearRichBlocksCache()
        for (length in 1..streamingSample.length) {
            streamingRichBlocks(id, streamingSample.substring(0, length))
        }
        assertEquals("流式帧污染了已完成缓存", 0, richBlocksCacheSize())
    }

    @Test
    fun `收尾时把流式结果转正进缓存`() {
        val id = "settle"
        val text = "第一段\n\n第二段"
        val streamed = streamingRichBlocks(id, text)
        clearRichBlocksCache()

        assertEquals(streamed, settleStreamingRichBlocks(id, text))
        assertEquals(streamed, cachedRichBlocks(text))
        // 转正后流式中间态要释放，不能继续占着。
        assertEquals(null, settleStreamingRichBlocks(id, text))
    }

    @Test
    fun `CRLF 与 LF 切成同样的行`() {
        assertEquals(parseRichBlocks("甲\n\n乙"), parseRichBlocks("甲\r\n\r\n乙"))
        assertEquals(parseRichBlocks("甲\n乙"), parseRichBlocks("甲\r乙"))
    }
}
