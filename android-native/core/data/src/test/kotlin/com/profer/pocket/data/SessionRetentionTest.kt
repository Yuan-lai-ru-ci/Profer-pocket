package com.profer.pocket.data

import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.SessionChat
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 长会话内存窗口：逛过很多会话后只保留最近几条正文，其余按需重新水合。
 * 回收不能碰「在跑 / 等人 / 后台有任务 / 还有待发消息」的会话。
 */
class SessionRetentionTest {

    private fun chat(
        id: String,
        running: Boolean = false,
        pending: PendingInteraction? = null,
        backgroundWaiting: Boolean = false,
    ) = SessionChat(
        sessionId = id,
        running = running,
        pendingInteraction = pending,
        backgroundWaiting = backgroundWaiting,
    )

    @Test
    fun `窗口内不回收`() {
        val recent = listOf("a", "b", "c")
        val chats = recent.associateWith { chat(it) }
        assertTrue(sessionsToEvict(recent, chats, emptySet(), maxLoaded = 3).isEmpty())
    }

    @Test
    fun `超出窗口时从最久没打开的回收`() {
        val recent = listOf("a", "b", "c", "d", "e", "f")
        val chats = recent.associateWith { chat(it) }
        val victims = sessionsToEvict(recent, chats, emptySet(), maxLoaded = 3)
        assertEquals(listOf("a", "b", "c"), victims)
    }

    @Test
    fun `在跑或等人的会话不回收，宁可窗口超一点`() {
        val recent = listOf("a", "b", "c", "d")
        val chats = mapOf(
            "a" to chat("a", running = true),
            "b" to chat("b", pending = PendingInteraction.PlanApproval(id = "p1", summary = "计划")),
            "c" to chat("c"),
            "d" to chat("d"),
        )
        // 最新的 c、d 永远不动，即使 a、b 都被保护。
        assertTrue(sessionsToEvict(recent, chats, emptySet(), maxLoaded = 2).isEmpty())
    }

    @Test
    fun `溢出段里的普通会话照常回收`() {
        val recent = listOf("a", "b", "c", "d")
        val chats = mapOf(
            "a" to chat("a", running = true),
            "b" to chat("b"),
            "c" to chat("c"),
            "d" to chat("d"),
        )
        assertEquals(listOf("b"), sessionsToEvict(recent, chats, emptySet(), maxLoaded = 2))
    }

    @Test
    fun `还有待发消息的会话不回收`() {
        val recent = listOf("a", "b")
        val chats = recent.associateWith { chat(it) }
        assertTrue(sessionsToEvict(recent, chats, setOf("a"), maxLoaded = 1).isEmpty())
    }

    @Test
    fun `当前打开的会话不回收`() {
        val recent = listOf("a", "b", "c", "d")
        val chats = recent.associateWith { chat(it) }
        assertEquals(
            listOf("b"),
            sessionsToEvict(recent, chats, emptySet(), maxLoaded = 2, pinnedSessionId = "a"),
        )
    }

    @Test
    fun `目录里有但内存里没有的 id 直接清掉，不占名额`() {
        val recent = listOf("gone", "a", "b")
        val chats = mapOf("a" to chat("a"), "b" to chat("b"))
        val victims = sessionsToEvict(recent, chats, emptySet(), maxLoaded = 2)
        assertEquals(listOf("gone"), victims)
    }
}
