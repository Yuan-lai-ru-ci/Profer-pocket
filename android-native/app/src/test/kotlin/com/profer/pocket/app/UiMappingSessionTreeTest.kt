package com.profer.pocket.app

import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 会话目录 → 抽屉 UI 模型：子会话挂载、角色标签与顶栏面包屑。 */
class UiMappingSessionTreeTest {

    private val workspaces = listOf(WorkspaceSummary(id = "w1", name = "profer-main"))

    private fun session(
        id: String,
        title: String = id,
        workspaceId: String? = "w1",
        updatedAt: Long = 0,
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
        parentSessionId = parentSessionId,
        sourceDelegationId = sourceDelegationId,
        delegationRole = delegationRole,
        delegationStatus = delegationStatus,
        explorationParentSessionId = explorationParentSessionId,
        explorationSourceMessageId = explorationSourceMessageId,
    )

    @Test
    fun `委派子会话挂在母会话下并带角色标签`() {
        val state = UiMapping.sessionListState(
            sessions = listOf(
                session("parent", title = "排查登录", updatedAt = 10),
                session(
                    "child",
                    title = "回归用例",
                    updatedAt = 90,
                    parentSessionId = "parent",
                    sourceDelegationId = "d1",
                    delegationRole = "research",
                    delegationStatus = "running",
                ),
            ),
            workspaces = workspaces,
            currentSessionId = "parent",
            query = "",
            online = true,
        )
        val project = state.projects.single()
        assertEquals(listOf("parent"), project.sessions.map { it.id })
        val child = project.sessions.single().children.single()
        assertEquals("child", child.id)
        assertEquals("调研", child.roleLabel)
        assertEquals(true, child.delegationRunning)
        assertEquals(true, child.isSubSession)
        // 组内总数包含子会话。
        assertEquals(2, state.totalSessions)
        assertEquals(1, project.runningCount)
    }

    @Test
    fun `探索分支标签单独一档`() {
        val state = UiMapping.sessionListState(
            sessions = listOf(
                session("parent"),
                session(
                    "branch",
                    parentSessionId = null,
                    explorationParentSessionId = "parent",
                    explorationSourceMessageId = "m1",
                ),
            ),
            workspaces = workspaces,
            currentSessionId = null,
            query = "",
            online = true,
        )
        val child = state.projects.single().sessions.single().children.single()
        assertEquals("探索分支", child.roleLabel)
    }

    @Test
    fun `普通会话没有子会话与角色标签`() {
        val state = UiMapping.sessionListState(
            sessions = listOf(session("only")),
            workspaces = workspaces,
            currentSessionId = "only",
            query = "",
            online = true,
        )
        val row = state.projects.single().sessions.single()
        assertEquals(emptyList<Any>(), row.children)
        assertNull(row.roleLabel)
        assertEquals(false, row.isSubSession)
    }

    @Test
    fun `子会话顶栏给出面包屑与母会话 id`() {
        val sessions = listOf(
            session("parent", title = "排查登录"),
            session(
                "child",
                title = "回归用例",
                parentSessionId = "parent",
                sourceDelegationId = "d1",
                delegationRole = "implement",
            ),
        )
        val chat = UiMapping.chatState(
            sessionId = "child",
            chat = null,
            sessions = sessions,
            workspaces = workspaces,
            status = ConnectionStatus.Connected("ws://host/ws"),
        )
        assertEquals("parent", chat.parentSessionId)
        assertEquals("实现 · 来自 排查登录", chat.subSessionLabel)
    }

    @Test
    fun `普通会话顶栏没有面包屑`() {
        val chat = UiMapping.chatState(
            sessionId = "parent",
            chat = null,
            sessions = listOf(session("parent", title = "排查登录")),
            workspaces = workspaces,
            status = ConnectionStatus.Connected("ws://host/ws"),
        )
        assertNull(chat.parentSessionId)
        assertNull(chat.subSessionLabel)
    }
}
