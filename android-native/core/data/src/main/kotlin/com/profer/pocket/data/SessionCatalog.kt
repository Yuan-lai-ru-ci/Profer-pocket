package com.profer.pocket.data

import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary

/** 会话目录的分组类型。置顶是跨项目的独立分组，项目分组保持服务端项目顺序。 */
enum class SessionGroupKind { Pinned, Project, Unassigned }

data class SessionGroup(
    val id: String,
    val name: String,
    val kind: SessionGroupKind,
    val workspaceId: String?,
    val sessions: List<SessionSummary>,
    /** 手机端本地置顶的常用项目；不来自桌面。 */
    val pinned: Boolean = false,
) {
    val runningCount: Int get() = sessions.count { it.isActive }
    val isEmptyProject: Boolean get() = kind == SessionGroupKind.Project && sessions.isEmpty()
}

/**
 * 会话目录组织规则：移动端抽屉只认这一套，`UiMapping` 不再各自排序。
 *
 * - 归档会话不进入主列表，只出现在归档视图；
 * - 置顶会话跨项目置顶；其余按项目分组，项目组按最近活动倒序；
 * - 空项目也保留，这样项目系统在移动端有「入口」而不是消失；
 * - 找不到项目的会话归入「未归入项目」，不伪造项目名；
 * - 搜索匹配会话标题或项目名（项目名命中时展示该项目全部会话）。
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
        val projectNames = projects.associate { it.id to it.displayName }
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

        val pinned = visible
            .filter { it.isPinned && matches(it, it.workspaceId) }
            .sortedByDescending { it.updatedAt }
            .map(::withLocalRunning)

        val grouped = visible.filterNot { it.isPinned }.groupBy { it.workspaceId }
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
            val list = (grouped[workspace.id] ?: emptyList())
                .filter { matches(it, workspace.id) }
                .sortedByDescending { it.updatedAt }
                .map(::withLocalRunning)
            // 搜索时不展示无命中的空项目，避免搜索被一堆空分组撑散。
            if (keyword.isNotEmpty() && list.isEmpty()) return@mapNotNull null
            SessionGroup(
                id = workspace.id,
                name = workspace.displayName,
                kind = SessionGroupKind.Project,
                workspaceId = workspace.id,
                sessions = list,
                pinned = workspace.id in pinnedProjectIds,
            )
        }

        val orphanSessions = visible
            .filterNot { it.isPinned }
            .filter { it.workspaceId == null || it.workspaceId !in projectIds }
            .filter { matches(it, it.workspaceId?.takeIf { id -> id in projectNames }) }
            .sortedByDescending { it.updatedAt }
            .map(::withLocalRunning)

        return buildList {
            if (pinned.isNotEmpty()) {
                add(
                    SessionGroup(
                        id = PINNED_GROUP_ID,
                        name = "置顶",
                        kind = SessionGroupKind.Pinned,
                        workspaceId = null,
                        sessions = pinned,
                    ),
                )
            }
            addAll(projectGroups)
            if (orphanSessions.isNotEmpty()) {
                add(
                    SessionGroup(
                        id = UNASSIGNED_GROUP_ID,
                        name = UNASSIGNED_GROUP_NAME,
                        kind = SessionGroupKind.Unassigned,
                        workspaceId = null,
                        sessions = orphanSessions,
                    ),
                )
            }
        }
    }

}
