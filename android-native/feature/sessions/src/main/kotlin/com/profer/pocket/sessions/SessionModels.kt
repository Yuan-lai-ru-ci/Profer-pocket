package com.profer.pocket.sessions

import androidx.compose.runtime.Immutable

enum class SessionGroupKind { Project, Unassigned }

/**
 * 会话行左侧状态条的状态，对齐桌面 `SessionIndicatorStatus` 的活动三态。
 *
 * 桌面把「等人」放在最高优先级：会话在等权限/回答时 `running` 仍是 true，
 * 只按运行中显示会把「等你处理」误报成「还在干活」。
 */
enum class SessionStatus { Idle, Running, Blocked, Completed }

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
    /** 正在等用户确认/回答（权限、提问、计划审批）；只有已加载的会话知道。 */
    val waiting: Boolean = false,
    /** 会话所属项目名；跨项目列表（未归入/搜索）用它渲染 chip。 */
    val projectName: String? = null,
    /** 挂在当前行下面的子会话（委派子会话 / 探索分支）；根会话恒为空。 */
    val children: List<SessionItem> = emptyList(),
    /** 自己就是子会话：以根行身份出现时（搜索）靠它标出层级。 */
    val isSubSession: Boolean = false,
    /** 子会话角色标签：「探索分支」「调研」「实现」…；普通会话为 null。 */
    val roleLabel: String? = null,
    /** 委派状态（running / completed / failed / cancelled）；子行图标靠它上色。 */
    val delegationStatus: String? = null,
    /** 探索分支：子行用分叉图标，而不是委派的分支图标。 */
    val isExploration: Boolean = false,
    /** 由定时任务创建/复用：行内用钟表标记（与桌面侧栏同口径）。 */
    val sourcedByAutomation: Boolean = false,
) {
    /** 行首状态：等人 > 运行中 > 未读；与桌面 `getSessionTreeStatus` 同序。 */
    val status: SessionStatus
        get() = when {
            waiting -> SessionStatus.Blocked
            running -> SessionStatus.Running
            unread -> SessionStatus.Completed
            else -> SessionStatus.Idle
        }

    /** 本行及其子会话的运行中数量。 */
    val activeCount: Int get() = (if (running) 1 else 0) + children.count { it.running }

    /** 委派仍在跑：子会话 run 结束后委派状态可能还差一拍。 */
    val delegationRunning: Boolean get() = delegationStatus == "running"

    /** 父行右侧的关联会话摘要；无子会话时为 null。 */
    val relatedSummary: RelatedSummary?
        get() {
            if (children.isEmpty()) return null
            val delegated = children.filterNot { it.isExploration }
            val hasExploration = children.any { it.isExploration }
            return RelatedSummary(
                total = children.size,
                completed = delegated.count { it.delegationStatus == "completed" },
                label = when {
                    delegated.isNotEmpty() && hasExploration -> "关联会话"
                    hasExploration -> "探索分支"
                    else -> "子会话"
                },
                // 探索分支的进度不适合用 x/y 表达（对齐桌面）：只留箭头。
                showCount = !hasExploration,
            )
        }
}

/** 父行右侧的「x/y 子会话」摘要（与桌面 `getRelatedSessionSummary` 同义）。 */
@Immutable
data class RelatedSummary(
    val total: Int,
    val completed: Int,
    val label: String,
    val showCount: Boolean,
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
    // 项目顺序与 SessionCatalog 的输出一致：置顶项目在最前，其余按最近活动。
    val state = SessionListState(
        projects = listOf(
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
                                delegationStatus = "running",
                            ),
                            SessionItem(
                                id = "s1-c2",
                                title = "检查 message 持久化",
                                timeLabel = "10 分钟前",
                                isSubSession = true,
                                roleLabel = "实现",
                                delegationStatus = "completed",
                            ),
                        ),
                    ),
                    SessionItem(
                        id = "s2",
                        title = "移动端输入区键盘遮挡",
                        timeLabel = "1 小时前",
                        // 等权限确认：行首竖条用 warning 色，不是「运行中」。
                        running = true,
                        waiting = true,
                    ),
                    SessionItem(
                        id = "s3",
                        title = "整理 Pocket 发布清单",
                        timeLabel = "昨天",
                        pinned = true,
                    ),
                    SessionItem(id = "s7", title = "抽屉状态条改版验收", timeLabel = "昨天"),
                    SessionItem(id = "s8", title = "会话快照合并丢字段", timeLabel = "昨天"),
                    SessionItem(id = "s9", title = "通知渠道文案", timeLabel = "周一", unread = true),
                    SessionItem(id = "s10", title = "平板双栏回归", timeLabel = "周一"),
                    SessionItem(id = "s11", title = "长列表滚动性能", timeLabel = "上周"),
                    SessionItem(id = "s12", title = "键盘 insets 重构", timeLabel = "上周"),
                    SessionItem(id = "s13", title = "深浅色 token 校对", timeLabel = "上周"),
                    SessionItem(id = "s14", title = "连接页错误文案", timeLabel = "上周"),
                    SessionItem(id = "s15", title = "图片附件解码", timeLabel = "上周"),
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
