package com.profer.pocket.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ProcessGroupSummaryTest {

    @Test
    fun `工具与思考分开计数`() {
        assertEquals("执行过程：4 次工具调用，1 段思考", processGroupSummary(toolCount = 4, thinkingCount = 1))
    }

    @Test
    fun `只有工具时不写思考`() {
        assertEquals("执行过程：2 次工具调用", processGroupSummary(toolCount = 2, thinkingCount = 0))
    }

    @Test
    fun `只有思考时不写工具`() {
        assertEquals("执行过程：1 段思考", processGroupSummary(toolCount = 0, thinkingCount = 1))
    }

    @Test
    fun `都没有时回退到过程`() {
        assertEquals("执行过程", processGroupSummary(toolCount = 0, thinkingCount = 0))
    }
}
