package com.profer.pocket.data

import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        parentSessionId: String? = null,
        sourceDelegationId: String? = null,
        delegationRole: String? = null,
        delegationStatus: String? = null,
        explorationParentSessionId: String? = null,
        explorationSourceMessageId: String? = null,
    ) = SessionSummary(
        id = id,
        title = title,
        workspaceId = workspaceId,
        updatedAt = updatedAt,
        pinned = pinned,
        archived = archived,
        active = active,
        draft = draft,
        parentSessionId = parentSessionId,
        sourceDelegationId = sourceDelegationId,
        delegationRole = delegationRole,
        delegationStatus = delegationStatus,
        explorationParentSessionId = explorationParentSessionId,
        explorationSourceMessageId = explorationSourceMessageId,
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

    @Test
    fun `本地置顶的项目排在最近活动之前，置顶组仍最前`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("old", workspaceId = "w1", updatedAt = 10),
                session("new", workspaceId = "w2", updatedAt = 90),
                session("pinned", workspaceId = "w2", updatedAt = 50, pinned = true),
            ),
            workspaces = workspaces,
            pinnedProjectIds = setOf("w1"),
        )
        // 置顶会话组不受项目置顶影响；w1 虽不活跃，但本地置顶后排在 w2 前面。
        assertEquals(SessionCatalog.PINNED_GROUP_ID, groups.first().id)
        assertEquals(listOf("w1", "w2", "w3"), groups.drop(1).map { it.id })
        assertTrue(groups.first { it.id == "w1" }.pinned)
        assertFalse(groups.first { it.id == "w2" }.pinned)
    }

    // ===== 子会话（委派 / 探索分支） =====

    @Test
    fun `委派子会话挂在母会话下，不单独占根行`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("parent", workspaceId = "w1", updatedAt = 10),
                session(
                    "child",
                    workspaceId = "w1",
                    updatedAt = 90,
                    parentSessionId = "parent",
                    sourceDelegationId = "d1",
                    delegationRole = "research",
                    delegationStatus = "running",
                ),
            ),
            workspaces = workspaces,
        )
        val project = groups.first { it.id == "w1" }
        // 子会话更新更晚，也不能漂到母会话上面单独成行。
        assertEquals(listOf("parent"), project.items.map { it.session.id })
        assertEquals(listOf("child"), project.items.single().children.map { it.id })
        // 组内总数仍然包含子会话。
        assertEquals(listOf("parent", "child"), project.sessions.map { it.id })
    }

    @Test
    fun `探索分支同样挂回母会话`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("parent", workspaceId = "w1"),
                session(
                    "branch",
                    workspaceId = "w1",
                    explorationParentSessionId = "parent",
                    explorationSourceMessageId = "m1",
                ),
            ),
            workspaces = workspaces,
        )
        val project = groups.first { it.id == "w1" }
        assertEquals(listOf("branch"), project.items.single().children.map { it.id })
    }

    @Test
    fun `只有单侧血缘字段的脏数据不归并`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("parent", workspaceId = "w1"),
                // 缺 sourceDelegationId：不算委派子会话，保持为根行而不是被吞掉。
                session("half", workspaceId = "w1", parentSessionId = "parent"),
            ),
            workspaces = workspaces,
        )
        val project = groups.first { it.id == "w1" }
        assertEquals(setOf("parent", "half"), project.items.map { it.session.id }.toSet())
    }

    @Test
    fun `跨项目的子会话不归并`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("parent", workspaceId = "w1"),
                session(
                    "child",
                    workspaceId = "w2",
                    parentSessionId = "parent",
                    sourceDelegationId = "d1",
                ),
            ),
            workspaces = workspaces,
        )
        // 母会话在 w1、子会话的 workspaceId 是 w2：保持为各自项目的根行，不能被「合并」过去。
        assertEquals(listOf("parent"), groups.first { it.id == "w1" }.items.map { it.session.id })
        assertEquals(listOf("child"), groups.first { it.id == "w2" }.items.map { it.session.id })
    }

    @Test
    fun `母会话不在可见集合时子会话升为根行`() {
        val sessions = listOf(
            session("parent", title = "排查登录", workspaceId = "w1"),
            session(
                "child",
                title = "登录回归用例",
                workspaceId = "w1",
                parentSessionId = "parent",
                sourceDelegationId = "d1",
            ),
        )
        // 母会话标题不命中、子会话命中：子会话必须还能被搜到。
        val groups = SessionCatalog.groups(sessions, workspaces, query = "回归")
        val project = groups.first { it.id == "w1" }
        assertEquals(listOf("child"), project.items.map { it.session.id })
        assertTrue(project.items.single().children.isEmpty())
    }

    @Test
    fun `母会话命中时带上子会话`() {
        val sessions = listOf(
            session("parent", title = "排查登录", workspaceId = "w1"),
            session(
                "child",
                title = "复现步骤",
                workspaceId = "w1",
                parentSessionId = "parent",
                sourceDelegationId = "d1",
            ),
        )
        val groups = SessionCatalog.groups(sessions, workspaces, query = "登录")
        val project = groups.first { it.id == "w1" }
        assertEquals(listOf("parent"), project.items.map { it.session.id })
        assertEquals(listOf("child"), project.items.single().children.map { it.id })
    }

    @Test
    fun `置顶母会话把子会话一起带进置顶组`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("parent", workspaceId = "w1", pinned = true),
                session(
                    "child",
                    workspaceId = "w1",
                    parentSessionId = "parent",
                    sourceDelegationId = "d1",
                ),
            ),
            workspaces = workspaces,
        )
        val pinned = groups.first { it.id == SessionCatalog.PINNED_GROUP_ID }
        assertEquals(listOf("parent"), pinned.items.map { it.session.id })
        assertEquals(listOf("child"), pinned.items.single().children.map { it.id })
        // 项目组里不能再重复一份。
        assertTrue(groups.first { it.id == "w1" }.items.isEmpty())
    }

    @Test
    fun `运行中的子会话计入项目运行数`() {
        val groups = SessionCatalog.groups(
            sessions = listOf(
                session("parent", workspaceId = "w1"),
                session(
                    "child",
                    workspaceId = "w1",
                    active = true,
                    parentSessionId = "parent",
                    sourceDelegationId = "d1",
                ),
            ),
            workspaces = workspaces,
        )
        assertEquals(1, groups.first { it.id == "w1" }.runningCount)
    }
}
