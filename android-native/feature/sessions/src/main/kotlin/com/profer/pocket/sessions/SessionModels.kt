package com.profer.pocket.sessions

import androidx.compose.runtime.Immutable

enum class SessionGroupKind { Pinned, Project, Unassigned }

@Immutable
data class SessionItem(
    val id: String,
    val title: String,
    val timeLabel: String,
    val running: Boolean = false,
    val unread: Boolean = false,
    val pinned: Boolean = false,
    val draft: Boolean = false,
    val workspaceId: String? = null,
    /** 会话所属项目名；跨项目列表（置顶/未归入/搜索）用它渲染 chip。 */
    val projectName: String? = null,
)

@Immutable
data class ProjectGroup(
    val id: String,
    val name: String,
    val branchLabel: String? = null,
    val sessions: List<SessionItem>,
    val kind: SessionGroupKind = SessionGroupKind.Project,
    val workspaceId: String? = id,
    /** 手机端本地置顶：只影响抽屉顺序，不写回桌面。 */
    val pinned: Boolean = false,
) {
    val runningCount: Int get() = sessions.count { it.running }
    val isEmptyProject: Boolean get() = kind == SessionGroupKind.Project && sessions.isEmpty()
}

@Immutable
data class SessionListState(
    val projects: List<ProjectGroup>,
    val query: String = "",
    val connected: Boolean = true,
    val deviceLabel: String = "Mac",
    val currentSessionId: String? = null,
    val refreshing: Boolean = false,
    /** 项目列表是否已从服务端拿到（冷启动快照/未连接时为 false）。 */
    val hasProjects: Boolean = false,
) {
    val filteredProjects: List<ProjectGroup> get() = projects
    val totalSessions: Int get() = projects.sumOf { it.sessions.size }
}

object SessionPreviewData {
    val state = SessionListState(
        projects = listOf(
            ProjectGroup(
                id = "p1",
                name = "profer-main",
                branchLabel = "main",
                sessions = listOf(
                    SessionItem(
                        id = "s1",
                        title = "会话恢复丢消息排查",
                        timeLabel = "12 分钟前",
                        running = true,
                    ),
                    SessionItem(
                        id = "s2",
                        title = "移动端输入区键盘遮挡",
                        timeLabel = "1 小时前",
                    ),
                    SessionItem(
                        id = "s3",
                        title = "整理 Pocket 发布清单",
                        timeLabel = "昨天",
                    ),
                ),
            ),
            ProjectGroup(
                id = "p2",
                name = "website",
                branchLabel = "main",
                pinned = true,
                sessions = listOf(
                    SessionItem(
                        id = "s4",
                        title = "官网定价页文案",
                        timeLabel = "昨天",
                        unread = true,
                    ),
                    SessionItem(
                        id = "s5",
                        title = "移动端截图替换",
                        timeLabel = "周一",
                    ),
                ),
            ),
            ProjectGroup(
                id = "p3",
                name = "Profer-pocket",
                branchLabel = "feat/native",
                sessions = listOf(
                    SessionItem(
                        id = "s6",
                        title = "Android 原生版设计基线",
                        timeLabel = "周一",
                    ),
                ),
            ),
        ),
        currentSessionId = "s1",
        hasProjects = true,
    )

    val empty = state.copy(projects = emptyList(), currentSessionId = null)
}
