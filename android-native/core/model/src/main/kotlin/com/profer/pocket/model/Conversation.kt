package com.profer.pocket.model

/** 首页两种模式：Agent 会话与 Chat 对话。 */
enum class HomeMode { Agent, Chat }

/**
 * Chat（普通对话）的轻量索引项，对应桌面 `list_conversations` 的 ConversationMeta。
 *
 * 只保留移动端列表与发消息需要的字段：标题、模型、置顶/归档、更新时间。
 */
data class ConversationSummary(
    val id: String,
    val title: String,
    val channelId: String? = null,
    val modelId: String? = null,
    val pinned: Boolean = false,
    val archived: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** 主视图激活分支（root→leaf 的消息 ID）；空表示取全部消息。 */
    val activePath: List<String> = emptyList(),
) {
    val displayTitle: String get() = title.ifBlank { "新对话" }
}

/** Chat 消息：只渲染正文；推理、错误、工具活动作为可选补充。 */
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long = 0,
    val reasoning: String? = null,
    val stopped: Boolean = false,
    val error: String? = null,
    /** 桌面持久化的工具活动（start/result 已合并）；老桌面没有该字段时为空。 */
    val toolActivities: List<ChatToolActivity> = emptyList(),
) {
    val isUser: Boolean get() = role == "user"
}

/**
 * Chat 模式的一次工具调用。
 *
 * 桌面按 `start` / `result` 两条事件下发；移动端在数据层按 `toolCallId` 合并成一条，
 * UI 只消费最终状态，避免每行卡片自己再合并一遍。
 */
data class ChatToolActivity(
    val toolCallId: String,
    val toolName: String,
    /** 已收到 result；false 时表示还在跑。 */
    val done: Boolean = false,
    val isError: Boolean = false,
    /** 入参（桌面下发的对象已序列化成 JSON 文本）；缺失时为 null。 */
    val input: String? = null,
    /** 从入参里提取的可读目标（路径/命令/查询词），数据层算好，UI 不再解 JSON。 */
    val target: String? = null,
    /** 执行结果文本；start 阶段为 null。 */
    val result: String? = null,
)

/** 某段对话正在流式的状态；完成后清空，正文进入消息列表。 */
data class ChatStream(
    val conversationId: String,
    val text: String = "",
    val reasoning: String = "",
    val running: Boolean = false,
    val error: String? = null,
    /** 本次流式已经发生的工具调用（按开始顺序）。 */
    val toolActivities: List<ChatToolActivity> = emptyList(),
)
