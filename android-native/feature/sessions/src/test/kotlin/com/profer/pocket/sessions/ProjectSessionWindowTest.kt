package com.profer.pocket.sessions

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 项目内折叠窗口。
 *
 * 回归背景：抽屉默认把每个项目全铺开，875 条会话的项目一打开就是几百行；
 * 但反过来一律只留最近 5 条，会把「正在跑/等你确认」的会话藏起来，
 * 用户得先展开项目才知道有活要干。
 */
class ProjectSessionWindowTest {

    private fun item(
        id: String,
        pinned: Boolean = false,
        running: Boolean = false,
        waiting: Boolean = false,
        unread: Boolean = false,
        children: List<SessionItem> = emptyList(),
    ) = SessionItem(
        id = id,
        title = id,
        timeLabel = "刚刚",
        pinned = pinned,
        running = running,
        waiting = waiting,
        unread = unread,
        children = children,
    )

    @Test
    fun `少于一屏时全部铺开，不出现显示更多`() {
        val sessions = (1..4).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = null, expanded = false)
        assertEquals(sessions.map { it.id }, window.visible.map { it.id })
        assertEquals(0, window.hiddenCount)
        assertFalse(window.canCollapse)
    }

    @Test
    fun `超出预览上限时只铺最近几条，其余收进显示更多`() {
        val sessions = (1..20).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = null, expanded = false)
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), window.visible.map { it.id })
        assertEquals(15, window.hiddenCount)
        assertFalse(window.canCollapse)
    }

    @Test
    fun `运行中与等人确认的会话常显且不占预览名额`() {
        val sessions = (1..12).map { item("s$it") } +
            listOf(
                item("running", running = true),
                item("blocked", running = true, waiting = true),
                item("unread", unread = true),
            )
        val window = projectSessionWindow(sessions, currentSessionId = null, expanded = false)
        val visibleIds = window.visible.map { it.id }
        assertTrue("running" in visibleIds)
        assertTrue("blocked" in visibleIds)
        assertTrue("unread" in visibleIds)
        // 预览名额仍然给最近 5 条，不会被活动会话顶掉。
        assertEquals(listOf("s1", "s2", "s3", "s4", "s5"), visibleIds.take(5))
        // 15 条里铺出 5 条预览 + 3 条活动会话，剩下 7 条收进「显示更多」。
        assertEquals(7, window.hiddenCount)
    }

    @Test
    fun `当前打开的会话即使在项目深处也保留`() {
        val sessions = (1..30).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = "s28", expanded = false)
        assertTrue(window.visible.any { it.id == "s28" })
    }

    @Test
    fun `当前打开的是子会话时母行必须可见`() {
        val sessions = listOf(
            item("s1"),
            item("s2"),
            item("parent", children = listOf(item("child"))),
        ) + (3..12).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = "child", expanded = false)
        assertTrue(window.visible.any { it.id == "parent" })
    }

    @Test
    fun `剩余不足尾巴阈值时直接铺完`() {
        val sessions = (1..7).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = null, expanded = false)
        assertEquals(7, window.visible.size)
        assertEquals(0, window.hiddenCount)
    }

    @Test
    fun `展开后铺出全部，并给出收起入口`() {
        val sessions = (1..20).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = null, expanded = true)
        assertEquals(20, window.visible.size)
        assertEquals(0, window.hiddenCount)
        assertTrue(window.canCollapse)
    }

    @Test
    fun `全部都能铺下时展开态也不给收起入口`() {
        val sessions = (1..3).map { item("s$it") }
        val window = projectSessionWindow(sessions, currentSessionId = null, expanded = true)
        assertFalse(window.canCollapse)
    }
}
