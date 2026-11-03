package com.profer.pocket.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ThinkingSummaryTest {

    @Test
    fun `摘要取首个非空行`() {
        val body = "\n\n丢消息通常来自两个位置：\n重连后没有按游标拉增量。"
        assertEquals("丢消息通常来自两个位置：", thinkingHeadline(body))
    }

    @Test
    fun `摘要压平行内空白`() {
        assertEquals("先看 重连分支", thinkingHeadline("先看   重连分支"))
    }

    @Test
    fun `超长首行截断并加省略号`() {
        val body = "一".repeat(100)
        val summary = thinkingHeadline(body, maxChars = 10)
        assertEquals("一".repeat(10) + "…", summary)
    }

    @Test
    fun `空正文得到空摘要`() {
        assertEquals("", thinkingHeadline("   \n\n  "))
    }

    @Test
    fun `流式摘要取尾部窗口`() {
        val body = "第一句。第二句。第三句。"
        assertEquals("…第二句。第三句。", thinkingTail(body, maxChars = 8))
    }

    @Test
    fun `流式摘要短正文不截断`() {
        assertEquals("正在看日志", thinkingTail("正在看日志", maxChars = 60))
    }
}
