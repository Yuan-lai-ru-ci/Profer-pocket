package com.profer.pocket.data

import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCatalogTest {

    private fun session(
        id: String,
        title: String = id,
        workspaceId: String? = null,
        updatedAt: Long = 0,
        pinned: Boolean = false,
        archived: Boolean = false,
        active: Boolean = false,
        draft: Boolean = false,
    ) = SessionSummary(
        id = id,
        title = title,
        workspaceId = workspaceId,
        updatedAt = updatedAt,
        pinned = pinned,
        archived = archived,
        active = active,
        draft = draft,
    )

    private val workspaces = listOf(
        WorkspaceSummary(id = "w1", name = "profer-main"),
        WorkspaceSummary(id = "w2", name = "website"),
        WorkspaceSummary(id = "w3", name = "空项目"),
        WorkspaceSummary(id = "team", name = "团队", type = "team"),
    )

    @Test
    fun `置顶会话跨项目置顶，项目内按更新时间倒序`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("a", workspaceId = "w1", updatedAt = 10),
                session("b", workspaceId = "w1", updatedAt = 30),
                session("c", workspaceId = "w2", updatedAt = 20, pinned = true),
            ),
            workspaces = workspaces,
        )
        assertEquals(SessionCatalog.PINNED_GROUP_ID, groups.first().id)
        assertEquals(listOf("c"), groups.first().sessions.map { it.id })
        val project = groups.first { it.id == "w1" }
        assertEquals(listOf("b", "a"), project.sessions.map { it.id })
        assertEquals(SessionGroupKind.Project, project.kind)
    }


    @Test
    fun `项目按最近活动倒序，而不是服务端下发顺序`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("old", workspaceId = "w1", updatedAt = 10),
                session("new", workspaceId = "w2", updatedAt = 90),
                session("draft", workspaceId = "w3", updatedAt = 999, draft = true),
            ),
            workspaces = workspaces,
        )
        // w2 最近活跃；w1 次之；w3 只有草稿，不能因为草稿时间冲到最前，回退项目 updatedAt（0）。
        assertEquals(listOf("w2", "w1", "w3"), groups.map { it.id })
    }

    @Test
    fun `空项目保留，团队工作区不出现`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(session("a", workspaceId = "w1")),
            workspaces = workspaces,
        )
        val empty = groups.first { it.id == "w3" }
        assertTrue(empty.isEmptyProject)
        assertEquals("空项目", empty.name)
        assertTrue(groups.none { it.id == "team" })
    }

    @Test
    fun `归档会话不进入移动端目录`() {
        val sessions = listOf(
            session("live", workspaceId = "w1"),
            session("gone", workspaceId = "w1", archived = true, updatedAt = 50),
        )
        val groups = SessionCatalog.groups(sessions, workspaces)
        assertEquals(listOf("live"), groups.flatMap { it.sessions }.map { it.id })
    }

    @Test
    fun `未归入项目的孤儿会话单独成组`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("orphan", workspaceId = "deleted-project"),
                session("no-project"),
                session("live", workspaceId = "w1"),
            ),
            workspaces = workspaces,
        )
        val unassigned = groups.first { it.id == SessionCatalog.UNASSIGNED_GROUP_ID }
        assertEquals(setOf("orphan", "no-project"), unassigned.sessions.map { it.id }.toSet())
    }

    @Test
    fun `搜索项目名展示该项目全部会话，搜索标题只留命中项`() {
        val sessions = listOf(
            session("a", title = "修复登录", workspaceId = "w1"),
            session("b", title = "整理发布清单", workspaceId = "w1"),
            session("c", title = "官网文案", workspaceId = "w2"),
        )
        val byProject = SessionCatalog.groups(sessions, workspaces, query = "profer")
        assertEquals(listOf("w1"), byProject.filter { it.kind == SessionGroupKind.Project }.map { it.id })
        assertEquals(setOf("a", "b"), byProject.flatMap { it.sessions }.map { it.id }.toSet())

        val byTitle = SessionCatalog.groups(sessions, workspaces, query = "官网")
        assertEquals(listOf("c"), byTitle.flatMap { it.sessions }.map { it.id })
        // 搜索时不保留空项目。
        assertTrue(byTitle.all { it.sessions.isNotEmpty() })
    }

    @Test
    fun `本地运行的会话即使快照未标 active 也计入运行数`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(session("a", workspaceId = "w1")),
            workspaces = workspaces,
            runningIds = setOf("a"),
        )
        val project = groups.first { it.id == "w1" }
        assertEquals(1, project.runningCount)
        assertTrue(project.sessions.single().isActive)
    }

    @Test
    fun `归档项目及其会话都不进入目录`() {
        val archivedWorkspaces = listOf(
            WorkspaceSummary(id = "w1", name = "已收纳项目", archived = true),
            WorkspaceSummary(id = "w2", name = "website"),
            WorkspaceSummary(id = "w0", name = "另一个归档项目", archived = true),
        )
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("a", workspaceId = "w1"),
                session("b", workspaceId = "w2"),
                session("c", workspaceId = "w0"),
            ),
            workspaces = archivedWorkspaces,
        )
        assertEquals(listOf("w2"), groups.map { it.id })
        assertEquals(listOf("b"), groups.flatMap { it.sessions }.map { it.id })
    }
}
