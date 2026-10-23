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

/** Chat 消息：只渲染正文；推理、错误作为可选补充。 */
data class ChatMessage(
    val id: String,
    val role: String,
    val content: String,
    val createdAt: Long = 0,
    val reasoning: String? = null,
    val stopped: Boolean = false,
    val error: String? = null,
) {
    val isUser: Boolean get() = role == "user"
}

/** 某段对话正在流式的状态；完成后清空，正文进入消息列表。 */
data class ChatStream(
    val conversationId: String,
    val text: String = "",
    val reasoning: String = "",
    val running: Boolean = false,
    val error: String? = null,
)
