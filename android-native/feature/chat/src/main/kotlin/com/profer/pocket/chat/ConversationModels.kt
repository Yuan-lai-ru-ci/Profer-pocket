package com.profer.pocket.chat

import androidx.compose.runtime.Immutable

/** Chat（普通对话）页面状态：只承载渲染需要的字段。 */
@Immutable
data class ConversationUiState(
    val conversationId: String? = null,
    val title: String = "",
    /** 对话绑定的渠道/模型；模型切换写回电脑端后从这里回显。 */
    val channelId: String? = null,
    val modelId: String? = null,
    val messages: List<ConversationMessage> = emptyList(),
    val streamText: String = "",
    val streamReasoning: String = "",
    /** 流式中的工具调用；与桌面 Chat 一致，开始先出现行，结束后补结果。 */
    val streamTools: List<ToolBlock> = emptyList(),
    val streaming: Boolean = false,
    val error: String? = null,
    val offline: Boolean = false,
    /** 离线条文案（含原因与待发条数）；在线时为 null。 */
    val offlineLabel: String? = null,
)

@Immutable
sealed interface ConversationMessage {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val meta: String,
    ) : ConversationMessage

    data class Assistant(
        override val id: String,
        val text: String,
        val reasoning: String? = null,
        val error: String? = null,
        val stopped: Boolean = false,
        /** 工具活动（历史与流式完成后的持久化版本）。 */
        val tools: List<ToolBlock> = emptyList(),
    ) : ConversationMessage
}
