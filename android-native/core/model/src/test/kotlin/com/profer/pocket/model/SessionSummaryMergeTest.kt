package com.profer.pocket.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 会话元数据合并：血缘字段缺失时不能被一条普通 projection 抹掉。 */
class SessionSummaryMergeTest {

    private fun session(
        id: String = "s1",
        revision: Int = 0,
        parentSessionId: String? = null,
        sourceDelegationId: String? = null,
        delegationStatus: String? = null,
        delegationDepth: Int = 0,
        explorationParentSessionId: String? = null,
        explorationSourceMessageId: String? = null,
        explorationSourceLabel: String? = null,
    ) = SessionSummary(
        id = id,
        title = "T",
        revision = revision,
        parentSessionId = parentSessionId,
        sourceDelegationId = sourceDelegationId,
        delegationStatus = delegationStatus,
        delegationDepth = delegationDepth,
        explorationParentSessionId = explorationParentSessionId,
        explorationSourceMessageId = explorationSourceMessageId,
        explorationSourceLabel = explorationSourceLabel,
    )

    @Test
    fun `projection 不带血缘时保留已知血缘`() {
        val known = session(
            revision = 3,
            parentSessionId = "p1",
            sourceDelegationId = "d1",
            delegationStatus = "running",
            delegationDepth = 1,
        )
        // 标题/未读更新这类普通 projection 不会重发血缘字段。
        val incoming = known.copy(title = "新标题", revision = 4, delegationStatus = null, delegationDepth = 0)
        val merged = known.merge(incoming)
        assertEquals("p1", merged.parentSessionId)
        assertEquals("d1", merged.sourceDelegationId)
        assertEquals("running", merged.delegationStatus)
        assertEquals(1, merged.delegationDepth)
    }

    @Test
    fun `血缘字段变化时按新值覆盖`() {
        val known = session(revision = 3, parentSessionId = "p1", sourceDelegationId = "d1", delegationStatus = "running")
        val merged = known.merge(known.copy(revision = 4, delegationStatus = "completed"))
        assertEquals("completed", merged.delegationStatus)
    }

    @Test
    fun `探索分支血缘同样保留`() {
        val known = session(
            revision = 2,
            explorationParentSessionId = "p1",
            explorationSourceMessageId = "m1",
            explorationSourceLabel = "这条 Agent 回复",
        )
        val merged = known.merge(known.copy(revision = 3, explorationSourceLabel = null))
        assertEquals("p1", merged.explorationParentSessionId)
        assertEquals("m1", merged.explorationSourceMessageId)
        assertEquals("这条 Agent 回复", merged.explorationSourceLabel)
    }

    @Test
    fun `普通会话没有血缘标记`() {
        val plain = session()
        assertNull(plain.relatedParentSessionId)
        assertEquals(false, plain.isSubSession)
        assertEquals(false, plain.isDelegatedChild)
        assertEquals(false, plain.isExplorationChild)
    }

    @Test
    fun `单侧字段不算委派子会话`() {
        val half = session(parentSessionId = "p1")
        assertEquals(false, half.isDelegatedChild)
        assertNull(half.relatedParentSessionId)
    }
}
