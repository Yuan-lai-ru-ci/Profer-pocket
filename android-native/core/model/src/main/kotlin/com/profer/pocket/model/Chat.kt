package com.profer.pocket.model

/**
 * 工具调用在移动端只保留次数统计：不搬运入参、不搬运结果、不做逐条卡片。
 * 统计按工具名聚合，按 tool_use id 去重（partial 与 final 会重复携带同一个工具调用）。
 */
data class ToolTally(
    val total: Int = 0,
    val byName: Map<String, Int> = emptyMap(),
) {
    fun record(toolName: String?): ToolTally {
        val name = toolName?.trim().orEmpty().ifEmpty { "tool" }
        return copy(total = total + 1, byName = byName + (name to (byName[name] ?: 0) + 1))
    }

    /** 形如 “Bash ×2 · Read ×1”，按次数降序，最多列出 4 种。 */
    fun summary(maxKinds: Int = 4): String {
        if (total == 0) return ""
        return byName.entries
            .sortedByDescending { it.value }
            .take(maxKinds)
            .joinToString(" · ") { "${it.key} ×${it.value}" }
    }
}

/** 本轮的一次工具调用：只保留渲染身份与名称，入参与结果仍不下发。 */
data class ToolCall(
    val id: String,
    val name: String,
    val path: String? = null,
    val at: Long = 0L,
)

enum class NoticeTone { Info, Success, Warning }

/** 单条用户消息的发送状态；历史消息一律 Sent。 */
enum class SendStatus { Sending, Sent, Failed }

/** 模型/工具产出的图片：只带引用，字节在点开或首次渲染时向电脑端拉取。 */
data class ChatImage(
    val path: String,
    val filename: String,
    val mediaType: String,
)

sealed interface ChatEntry {
    val id: String
    val at: Long

    data class User(
        override val id: String,
        val text: String,
        override val at: Long,
        val status: SendStatus = SendStatus.Sent,
        val failure: String? = null,
    ) : ChatEntry

    data class Assistant(
        override val id: String,
        val text: String,
        val streaming: Boolean,
        override val at: Long,
        /** 模型思考正文（服务端已做有界截断；partial 帧只带尾部窗口）。 */
        val thinking: String? = null,
        /** 本回合工具产出的图片（send_local_image / generate_image），按路径去重。 */
        val images: List<ChatImage> = emptyList(),
    ) : ChatEntry

    data class Notice(
        override val id: String,
        val text: String,
        val tone: NoticeTone,
        override val at: Long,
    ) : ChatEntry
}

/** 需要人决定的事：权限、计划审批、提问。移动端首版只实现前两种的回应。 */
sealed interface PendingInteraction {
    val id: String

    data class Permission(
        override val id: String,
        val toolName: String,
        val description: String,
        val command: String?,
        val dangerLevel: String?,
    ) : PendingInteraction

    data class PlanApproval(
        override val id: String,
        val summary: String?,
    ) : PendingInteraction

    data class Question(
        override val id: String,
        val questions: List<AskQuestion>,
    ) : PendingInteraction
}

/** AskUserQuestion 的单个选项。 */
data class AskOption(
    val label: String,
    val description: String? = null,
)

/** AskUserQuestion 的单个问题；answers 的 key 用 question 文本（与桌面一致）。 */
data class AskQuestion(
    val question: String,
    val header: String? = null,
    val options: List<AskOption> = emptyList(),
    val multiSelect: Boolean = false,
)

/**
 * 单个会话在移动端的完整可渲染状态。
 *
 * [countedToolIds] 只为归约去重服务，不进入 UI。
 */
/** 本轮修改类工具触及的文件；toolId 用来在工具失败时把文件从汇总里剔除。 */
data class TurnFile(
    val toolId: String,
    val path: String,
)

/**
 * 最近一条 assistant usage 的四个 token 计数。
 * 与桌面 ContextUsageBadge 同口径：上下文占用 = 输入 + 缓存读取 + 缓存写入，输出不计入。
 */
data class ContextUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
    /** 收到这条 usage 的时间，用于弹层显示数据时效。 */
    val updatedAt: Long = 0L,
) {
    val usedTokens: Int
        get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens

    /** 纯输入 = usage.input_tokens；缓存读取与写入单独计入。 */
    val pureInputTokens: Int
        get() = inputTokens.coerceAtLeast(0)
}

data class SessionChat(
    val sessionId: String,
    val title: String = "",
    val workspaceId: String? = null,
    val channelId: String? = null,
    val modelId: String? = null,
    val permissionMode: String? = null,
    val messages: List<ChatEntry> = emptyList(),
    val running: Boolean = false,
    val pendingInteraction: PendingInteraction? = null,
    val tools: ToolTally = ToolTally(),
    val countedToolIds: Set<String> = emptySet(),
    /** 本轮工具调用（按发生顺序），用于逐条渲染各自的图标与状态。 */
    val toolCalls: List<ToolCall> = emptyList(),
    /** 已收到 tool_result 的工具 id（不区分成功失败）。 */
    val completedToolIds: Set<String> = emptySet(),
    /** 当前用户回合内被修改的文件（桌面 TurnFileChangesSummary 的移动端对应物）。 */
    val turnFiles: List<TurnFile> = emptyList(),
    val failedToolIds: Set<String> = emptySet(),
    val loaded: Boolean = false,
    val contextWindow: Int? = null,
    /** 最近一条 assistant usage；上下文占用与明细都从这里来。 */
    val contextUsage: ContextUsage? = null,
    val lastError: String? = null,
) {
    /** 当前上下文已用 token；保留这个派生属性，UI 与测试不用关心明细结构。 */
    val contextUsed: Int?
        get() = contextUsage?.usedTokens

    /** 去掉失败工具留下的文件，按路径去重后交给 UI。 */
    val visibleTurnFiles: List<String>
        get() = turnFiles
            .filterNot { it.toolId in failedToolIds }
            .distinctBy { it.path }
            .map { it.path }

    fun append(entry: ChatEntry): SessionChat {
        if (messages.any { it.id == entry.id }) return this
        return copy(messages = messages + entry)
    }
}
