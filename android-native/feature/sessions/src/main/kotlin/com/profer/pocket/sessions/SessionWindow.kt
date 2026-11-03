package com.profer.pocket.sessions

import androidx.compose.runtime.Immutable

/**
 * 项目内会话的折叠窗口：手机抽屉一次铺不开整本项目，也不能把活跃会话藏起来。
 *
 * 规则对齐桌面 `computeProjectSessionVisibility`，但把「预览上限」按手机屏调小：
 * 桌面的 8 条是在 ~900px 高的窗口里铺，手机上 8 条已经吃掉半个抽屉。
 */
internal const val PROJECT_PREVIEW_LIMIT = 5

/** 剩余不足这个数量时直接铺完，避免出现「显示更多（1）」这类没意义的折叠。 */
internal const val PROJECT_TAIL_ALWAYS_VISIBLE = 2

@Immutable
internal data class ProjectSessionWindow(
    /** 当前应该铺出来的根会话行（子会话挂在各自母行下面，不单独计数）。 */
    val visible: List<SessionItem>,
    /** 折叠态下没铺出来的条数；展开后为 0。 */
    val hiddenCount: Int,
    /** 已展开且确实比预览多，需要给一行「收起已展开的会话」。 */
    val canCollapse: Boolean,
)

/**
 * 计算某个项目这次要铺出来的会话。
 *
 * 常显（不占预览名额）：项目内置顶的会话、正在运行/等人/未读的会话、当前打开的会话
 * （含「当前打开的是子会话，母行必须可见」）。其余按目录顺序补足到 [previewLimit]。
 */
internal fun projectSessionWindow(
    sessions: List<SessionItem>,
    currentSessionId: String?,
    expanded: Boolean,
    previewLimit: Int = PROJECT_PREVIEW_LIMIT,
    tailAlwaysVisible: Int = PROJECT_TAIL_ALWAYS_VISIBLE,
): ProjectSessionWindow {
    if (sessions.isEmpty()) return ProjectSessionWindow(emptyList(), 0, false)

    val alwaysVisibleIds = sessions.asSequence()
        .filter {
            it.pinned ||
                it.status != SessionStatus.Idle ||
                it.containsSession(currentSessionId)
        }
        .map { it.id }
        .toHashSet()
    val fillIds = sessions.asSequence()
        .filterNot { it.id in alwaysVisibleIds }
        .take(previewLimit.coerceAtLeast(0))
        .map { it.id }
        .toHashSet()

    // 保持目录原有顺序（置顶优先、其余按更新时间），不因为状态变化而重排。
    val preview = sessions.filter { it.id in alwaysVisibleIds || it.id in fillIds }
    val hidden = sessions.size - preview.size

    if (expanded || hidden <= tailAlwaysVisible) {
        return ProjectSessionWindow(
            visible = sessions,
            hiddenCount = 0,
            canCollapse = expanded && hidden > 0,
        )
    }
    return ProjectSessionWindow(visible = preview, hiddenCount = hidden, canCollapse = false)
}

/** 该行自身或其子会话是不是 [sessionId]（当前打开的是子会话时母行也得留着）。 */
private fun SessionItem.containsSession(sessionId: String?): Boolean =
    sessionId != null && (id == sessionId || children.any { it.id == sessionId })
