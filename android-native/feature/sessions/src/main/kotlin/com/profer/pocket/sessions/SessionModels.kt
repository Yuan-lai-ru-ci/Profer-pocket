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
    /** 挂在当前行下面的子会话（委派子会话 / 探索分支）；根会话恒为空。 */
    val children: List<SessionItem> = emptyList(),
    /** 自己就是子会话：以根行身份出现时（置顶/搜索）靠它标出层级。 */
    val isSubSession: Boolean = false,
    /** 子会话角色标签：「探索分支」「调研」「实现」…；普通会话为 null。 */
    val roleLabel: String? = null,
    /** 委派仍在跑：子会话 run 结束后委派状态可能还差一拍。 */
    val delegationRunning: Boolean = false,
) {
    /** 本行及其子会话的运行中数量。 */
    val activeCount: Int get() = (if (running) 1 else 0) + children.count { it.running }
}

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
    /** 根会话 + 子会话的运行中数量：子会话在跑也要在项目行上看得见。 */
    val runningCount: Int get() = sessions.sumOf { it.activeCount }
    /** 组内会话总数（含子会话），抽屉头部的「N 条会话」用它。 */
    val sessionCount: Int get() = sessions.sumOf { 1 + it.children.size }
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
    val totalSessions: Int get() = projects.sumOf { it.sessionCount }
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
                        // 委派子会话挂在母会话下：抽屉里不另起一行占位。
                        children = listOf(
                            SessionItem(
                                id = "s1-c1",
                                title = "复现：冷启动丢历史",
                                timeLabel = "8 分钟前",
                                running = true,
                                isSubSession = true,
                                roleLabel = "调研",
                                delegationRunning = true,
                            ),
                            SessionItem(
                                id = "s1-c2",
                                title = "检查 message 持久化",
                                timeLabel = "10 分钟前",
                                isSubSession = true,
                                roleLabel = "实现",
                            ),
                        ),
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
