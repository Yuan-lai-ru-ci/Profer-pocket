package com.profer.pocket.app

import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.ToolCall
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveStateTest {

    private fun chat(
        id: String,
        running: Boolean = false,
        title: String = "",
        pending: PendingInteraction? = null,
        toolName: String? = null,
    ) = SessionChat(
        sessionId = id,
        title = title,
        running = running,
        pendingInteraction = pending,
        toolCalls = if (toolName == null) emptyList() else listOf(ToolCall(id = "t-$id", name = toolName)),
    )

    @Test
    fun `没有会话时不显示胶囊`() {
        assertNull(selectLiveState(emptyList()))
    }

    @Test
    fun `运行中且无工具时显示回复中`() {
        val state = selectLiveState(listOf(chat("a", running = true, title = "会话 A")))
        assertEquals("a", state?.sessionId)
        assertEquals("会话 A", state?.title)
        assertEquals(LiveKind.Working(null), state?.kind)
    }

    @Test
    fun `运行中带工具时带上工具名`() {
        val state = selectLiveState(listOf(chat("a", running = true, toolName = "Bash")))
        assertEquals(LiveKind.Working("Bash"), state?.kind)
    }

    @Test
    fun `追问优先于仍在运行的会话`() {
        val runningFirst = chat("a", running = true, title = "在跑")
        val waiting = chat(
            "b",
            running = true,
            title = "等我",
            pending = PendingInteraction.Permission(
                id = "p1",
                toolName = "Bash",
                description = "执行 rm",
                command = "rm -rf x",
                dangerLevel = "high",
            ),
        )
        val state = selectLiveState(listOf(runningFirst, waiting))
        assertEquals("b", state?.sessionId)
        assertEquals(LiveKind.WaitingPermission, state?.kind)
        assertEquals(1, state?.others)
    }

    @Test
    fun `提问与计划审批各自映射到对应文案`() {
        val question = selectLiveState(
            listOf(
                chat("q", running = true, pending = PendingInteraction.Question("q1", emptyList())),
            ),
        )
        assertEquals(LiveKind.WaitingAnswer, question?.kind)

        val plan = selectLiveState(
            listOf(
                chat("p", running = true, pending = PendingInteraction.PlanApproval("p1", "计划")),
            ),
        )
        assertEquals(LiveKind.WaitingPlan, plan?.kind)
    }

    @Test
    fun `标题为空时用会话占位`() {
        val state = selectLiveState(listOf(chat("a", running = true, title = "  ")))
        assertEquals("会话", state?.title)
    }

    @Test
    fun `其它会话计数不重复算同一会话`() {
        val same = chat(
            "a",
            running = true,
            pending = PendingInteraction.Question("q1", emptyList()),
        )
        val state = selectLiveState(listOf(same, chat("b", running = true)))
        assertEquals("a", state?.sessionId)
        // a 同时满足 waiting 与 running，只能算一次；再加 b，共 1 个其它会话。
        assertEquals(1, state?.others)
    }

    @Test
    fun `结束时按有无错误区分完成与失败`() {
        assertEquals(LiveKind.Completed, completionKind(null))
        assertEquals(LiveKind.Completed, completionKind("  "))
        assertEquals(LiveKind.Failed, completionKind("工具执行失败"))
    }

    @Test
    fun `只有已结束的会话时不显示胶囊`() {
        val state = selectLiveState(listOf(chat("a"), chat("b")))
        assertNull(state)
        assertTrue(true)
    }
}
