package com.profer.pocket.data

import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary

/** 会话目录的分组类型。置顶是跨项目的独立分组，项目分组保持服务端项目顺序。 */
enum class SessionGroupKind { Pinned, Project, Unassigned }

/**
 * 目录里的一行：根会话 + 挂在它下面的子会话（委派子会话 / 探索分支）。
 *
 * 子会话不单独占项目里的根位置——它们跟着母会话走，否则每次委派都会把项目顶部
 * 刷出一串「陌生会话」。层级规则对齐桌面 `left-sidebar/session-tree.ts`。
 */
data class SessionTreeItem(
    val session: SessionSummary,
    val children: List<SessionSummary> = emptyList(),
) {
    /** 本行或其子会话是否就是某个会话（高亮、默认展开用）。 */
    fun contains(sessionId: String?): Boolean =
        sessionId != null && (session.id == sessionId || children.any { it.id == sessionId })
}

data class SessionGroup(
    val id: String,
    val name: String,
    val kind: SessionGroupKind,
    val workspaceId: String?,
    val items: List<SessionTreeItem>,
    /** 手机端本地置顶的常用项目；不来自桌面。 */
    val pinned: Boolean = false,
) {
    /** 组内全部会话（根 + 子），供计数与「N 条会话」统计。 */
    val sessions: List<SessionSummary>
        get() = items.flatMap { listOf(it.session) + it.children }

    val runningCount: Int get() = sessions.count { it.isActive }
    val isEmptyProject: Boolean get() = kind == SessionGroupKind.Project && items.isEmpty()
}

/**
 * 会话目录组织规则：移动端抽屉只认这一套，`UiMapping` 不再各自排序。
 *
 * - 归档会话不进入主列表，只出现在归档视图；
 * - 置顶会话跨项目置顶；其余按项目分组，项目组按最近活动倒序；
 * - 空项目也保留，这样项目系统在移动端有「入口」而不是消失；
 * - 找不到项目的会话归入「未归入项目」，不伪造项目名；
 * - 搜索匹配会话标题或项目名（项目名命中时展示该项目全部会话）；
 * - 委派子会话与探索分支挂在母会话下，跨项目不归并。
 */
object SessionCatalog {

    const val PINNED_GROUP_ID = "__pinned__"
    const val UNASSIGNED_GROUP_ID = "__unassigned__"
    const val UNASSIGNED_GROUP_NAME = "未归入项目"

    fun groups(
        sessions: List<SessionSummary>,
        workspaces: List<WorkspaceSummary>,
        query: String = "",
        runningIds: Set<String> = emptySet(),
        pinnedProjectIds: Set<String> = emptySet(),
    ): List<SessionGroup> {
        val keyword = query.trim()
        val archivedWorkspaceIds = workspaces.filter { it.archived }.map { it.id }.toSet()
        // 归档项目整体收纳：项目行不出现，项目里的会话也不落到「未归入项目」。
        val visible = sessions.filterNot {
            it.isArchived || (it.workspaceId != null && it.workspaceId in archivedWorkspaceIds)
        }
        val projects = workspaces.filter { it.isVisible }
        val projectIds = projects.map { it.id }.toSet()
        val matchedProjects = if (keyword.isEmpty()) {
            emptySet()
        } else {
            projects.filter { it.displayName.contains(keyword, ignoreCase = true) }
                .map { it.id }
                .toSet()
        }
        fun matches(session: SessionSummary, projectId: String?): Boolean {
            if (keyword.isEmpty()) return true
            if (projectId != null && projectId in matchedProjects) return true
            val title = session.title.ifBlank { "未命名会话" }
            return title.contains(keyword, ignoreCase = true)
        }

        // 运行态以本地正在流式的会话为准；list_sessions 的 active 只是快照。
        fun withLocalRunning(session: SessionSummary): SessionSummary =
            if (session.id in runningIds) session.copy(active = true) else session

        // 搜索时母会话命中要带上它的子会话；子会话单独命中则自己升为根行（见 buildTree 的父守卫）。
        val matchedIds = visible.filter { matches(it, it.workspaceId) }.mapTo(HashSet()) { it.id }
        val treeSource = visible
            .filter { keyword.isEmpty() || it.id in matchedIds || it.relatedParentSessionId in matchedIds }
            .map(::withLocalRunning)
            .sortedWith(
                // 置顶优先，其余按更新时间倒序；与项目组内原有排序一致。
                compareByDescending<SessionSummary> { it.isPinned }
                    .thenByDescending { it.updatedAt },
            )
        val treeItems = buildTree(treeSource)

        val pinned = treeItems.filter { it.session.isPinned }
        val unpinned = treeItems.filterNot { it.session.isPinned }

        // 项目顺序按「最近活动」：取该项目里非草稿、非归档会话的最大 updatedAt，
        // 空项目回退项目自身 updatedAt。手机上没有「项目排序」开关，
        // 这条规则与桌面侧栏的「最近」一致；服务端下发的顺序只是索引插入顺序，不适合直接展示。
        val recencyByWorkspace = visible.asSequence()
            .filter { it.workspaceId != null && !it.isDraft && !it.isArchived }
            .groupingBy { it.workspaceId!! }
            .fold(0L) { acc, item -> maxOf(acc, item.updatedAt) }
        val orderedProjects = projects.sortedWith(
            // 手机端置顶的项目永远在前；其余仍按最近活动，避免整个列表随活动频繁跳动。
            compareByDescending<WorkspaceSummary> { if (it.id in pinnedProjectIds) 1 else 0 }
                .thenByDescending { workspace ->
                    recencyByWorkspace[workspace.id]?.takeIf { it > 0 } ?: workspace.updatedAt
                },
        )
        val projectGroups = orderedProjects.mapNotNull { workspace ->
            val list = unpinned.filter { it.session.workspaceId == workspace.id }
            // 搜索时不展示无命中的空项目，避免搜索被一堆空分组撑散。
            if (keyword.isNotEmpty() && list.isEmpty()) return@mapNotNull null
            SessionGroup(
                id = workspace.id,
                name = workspace.displayName,
                kind = SessionGroupKind.Project,
                workspaceId = workspace.id,
                items = list,
                pinned = workspace.id in pinnedProjectIds,
            )
        }

        val orphanItems = unpinned.filter {
            it.session.workspaceId == null || it.session.workspaceId !in projectIds
        }

        return buildList {
            if (pinned.isNotEmpty()) {
                add(
                    SessionGroup(
                        id = PINNED_GROUP_ID,
                        name = "置顶",
                        kind = SessionGroupKind.Pinned,
                        workspaceId = null,
                        items = pinned,
                    ),
                )
            }
            addAll(projectGroups)
            if (orphanItems.isNotEmpty()) {
                add(
                    SessionGroup(
                        id = UNASSIGNED_GROUP_ID,
                        name = UNASSIGNED_GROUP_NAME,
                        kind = SessionGroupKind.Unassigned,
                        workspaceId = null,
                        items = orphanItems,
                    ),
                )
            }
        }
    }

    /**
     * 把子会话挂回母会话，返回根行列表。
     *
     * 三条守卫与桌面一致：
     * - 母会话必须也在本次可见集合里，否则子会话保持为根行（搜索/归档时不该整行消失）；
     * - 母会话与子会话必须同项目：历史脏数据里的错误 workspaceId 不能让子会话被「合并」进别的项目；
     * - 自己不能是自己的父会话（坏数据不能把整行吞掉）。
     */
    private fun buildTree(sessions: List<SessionSummary>): List<SessionTreeItem> {
        val byId = sessions.associateBy { it.id }
        val childrenByParentId = HashMap<String, MutableList<SessionSummary>>()
        val roots = ArrayList<SessionSummary>(sessions.size)
        for (session in sessions) {
            val parentId = session.relatedParentSessionId
            val parent = parentId?.let(byId::get)
            if (parent != null && parent.id != session.id && parent.workspaceId == session.workspaceId) {
                childrenByParentId.getOrPut(parent.id) { ArrayList() }.add(session)
            } else {
                roots.add(session)
            }
        }
        return roots.map { root -> SessionTreeItem(root, childrenByParentId[root.id].orEmpty()) }
    }
}
