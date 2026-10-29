package com.profer.pocket.model

/**
 * 会话列表项：只保留抽屉渲染与目录操作需要的字段。
 *
 * [active] / [unread] / [archived] / [pinned] / [draft] 为三态：
 * `list_sessions` 带全量快照，`session_projection` 只带变更后的投影；
 * 合并时必须区分「服务端明确说 false」和「这次没带这个字段」，
 * 否则一条 projection 会把本地已知的运行态/未读清掉。
 */
data class SessionSummary(
    val id: String,
    val title: String,
    val workspaceId: String? = null,
    val channelId: String? = null,
    val modelId: String? = null,
    val presetId: String? = null,
    val permissionMode: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 服务端会话元数据版本；projection 乱序/重放时用它拒绝旧快照。 */
    val revision: Int = 0,
    val active: Boolean? = null,
    val unread: Boolean? = null,
    val archived: Boolean? = null,
    val pinned: Boolean? = null,
    val draft: Boolean? = null,
    // ===== 血缘：委派子会话与探索分支 =====
    // 桌面 `buildSessionItem` 已经下发这些字段（注释写明「仅用于树形导航」），
    // 移动端只用来把子会话挂回母会话，不展示委派内容本身。
    /** 委派子会话的母会话；与 [sourceDelegationId] 成对出现才算委派血缘。 */
    val parentSessionId: String? = null,
    val sourceDelegationId: String? = null,
    /** explore / research / implement / review / custom；探索分支为空。 */
    val delegationRole: String? = null,
    /** running / completed / failed / cancelled；只在委派血缘上有意义。 */
    val delegationStatus: String? = null,
    val delegationDepth: Int = 0,
    /** 探索分支：独立血缘字段，不参与委派删除级联。 */
    val explorationParentSessionId: String? = null,
    val explorationSourceMessageId: String? = null,
    /** 探索来源标签（桌面按选区文本截断后的文案）。 */
    val explorationSourceLabel: String? = null,
) {
    val isActive: Boolean get() = active == true
    val isUnread: Boolean get() = unread == true
    val isArchived: Boolean get() = archived == true
    val isPinned: Boolean get() = pinned == true
    val isDraft: Boolean get() = draft == true

    /** 委派子会话：两个字段同时存在才算，避免脏数据把普通会话挂到别人下面。 */
    val isDelegatedChild: Boolean
        get() = !parentSessionId.isNullOrBlank() && !sourceDelegationId.isNullOrBlank()

    /** 探索分支：同样要求血缘字段成对。 */
    val isExplorationChild: Boolean
        get() = !explorationParentSessionId.isNullOrBlank() && !explorationSourceMessageId.isNullOrBlank()

    /** 需要挂回的母会话 id；两种血缘互斥，委派优先。 */
    val relatedParentSessionId: String?
        get() = when {
            isDelegatedChild -> parentSessionId
            isExplorationChild -> explorationParentSessionId
            else -> null
        }

    val isSubSession: Boolean get() = relatedParentSessionId != null

    /** 子会话仍在跑（委派状态可能比 run 终态晚一拍，两者都算）。 */
    val isDelegationRunning: Boolean get() = isDelegatedChild && delegationStatus == "running"

    /**
     * 用一次更权威的快照覆盖本项：带了字段就覆盖（包括 false），没带就保留旧值。
     * [forceAll] 用于 `list_sessions` 全量快照——列表项本身就是完整状态。
     */
    fun merge(incoming: SessionSummary, forceAll: Boolean = false): SessionSummary {
        if (incoming.id != id) return this
        // 旧 revision 的投影不能覆盖更新的本地状态。
        if (!forceAll && incoming.revision in 1 until revision) return this
        return copy(
            title = incoming.title.ifBlank { title },
            workspaceId = incoming.workspaceId ?: workspaceId,
            channelId = incoming.channelId ?: channelId,
            modelId = incoming.modelId ?: modelId,
            presetId = incoming.presetId ?: presetId,
            permissionMode = incoming.permissionMode ?: permissionMode,
            createdAt = incoming.createdAt.takeIf { it > 0 } ?: createdAt,
            updatedAt = incoming.updatedAt.takeIf { it > 0 } ?: updatedAt,
            revision = maxOf(revision, incoming.revision),
            active = incoming.active ?: active,
            unread = incoming.unread ?: unread,
            archived = incoming.archived ?: archived,
            pinned = incoming.pinned ?: pinned,
            draft = incoming.draft ?: draft,
            // 血缘是创建时写定的元数据，projection 偶尔不带；缺失时保留旧值，
            // 否则一条普通更新就会把子会话从母会话下摘掉。
            parentSessionId = incoming.parentSessionId ?: parentSessionId,
            sourceDelegationId = incoming.sourceDelegationId ?: sourceDelegationId,
            delegationRole = incoming.delegationRole ?: delegationRole,
            delegationStatus = incoming.delegationStatus ?: delegationStatus,
            delegationDepth = incoming.delegationDepth.takeIf { it > 0 } ?: delegationDepth,
            explorationParentSessionId = incoming.explorationParentSessionId ?: explorationParentSessionId,
            explorationSourceMessageId = incoming.explorationSourceMessageId ?: explorationSourceMessageId,
            explorationSourceLabel = incoming.explorationSourceLabel ?: explorationSourceLabel,
        )
    }
}

/** 工作区（项目）：用于会话抽屉分组与项目内起草会话。 */
data class WorkspaceSummary(
    val id: String,
    val name: String,
    val slug: String? = null,
    /** `personal` 是 Agent 项目；`team` 属于团队工作区，移动端会话目录不展示。 */
    val type: String? = null,
    /** 桌面「收纳」的项目：数据保留，但不出现在移动端项目列表。 */
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
) {
    val isTeam: Boolean get() = type == "team"

    /** 是否应该在移动端目录中展示。 */
    val isVisible: Boolean get() = !isTeam && !archived

    /** 项目显示名：没有名字时不要用 UUID 冒充项目名。 */
    val displayName: String get() = name.ifBlank { "未命名项目" }
}

/** 通知点击后要落到的会话。 */
data class SessionRef(val sessionId: String)

/** 渠道里的可用模型（新建会话时给服务端一个明确默认值）。 */
data class ChannelModel(
    val id: String,
    val name: String = "",
)

/** 可用渠道：新建会话时确定 channelId / modelId。 */
data class ChannelSummary(
    val id: String,
    val name: String,
    val provider: String? = null,
    val enabled: Boolean = true,
    val models: List<ChannelModel> = emptyList(),
) {
    val firstModelId: String? get() = models.firstOrNull()?.id
}

/**
 * 电脑端「上次使用的渠道/模型」。
 *
 * 移动端没有默认模型设置页，也不该自己猜第一个渠道：桌面每次切换模型都会写进
 * settings.json，新建会话继承的就是这份记录。
 */
data class AgentModelSelection(
    val channelId: String,
    val modelId: String,
)
