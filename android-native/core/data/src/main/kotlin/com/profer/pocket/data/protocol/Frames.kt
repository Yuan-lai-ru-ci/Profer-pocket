package com.profer.pocket.data.protocol

import com.profer.pocket.model.SessionSummary
import org.json.JSONArray
import org.json.JSONObject

/** 服务端 → 客户端的帧。未知帧保留类型名，便于诊断但不参与业务。 */
sealed interface InboundFrame {
    data class Hello(
        val serverTime: Long,
        val serverInstanceId: String?,
        val latestEventId: Long?,
        val oldestEventId: Long?,
    ) : InboundFrame

    data object Pong : InboundFrame

    data class AgentEvent(
        val eventId: Long?,
        val sessionId: String,
        val payload: AgentPayload,
    ) : InboundFrame

    data class AgentEventsResumed(
        val requestId: String?,
        val replayed: Int,
        val complete: Boolean,
        val requiresSnapshot: Boolean,
        val latestEventId: Long?,
        val oldestEventId: Long?,
    ) : InboundFrame

    data class ChatEvent(
        val conversationId: String,
        val channel: String,
        val payload: JSONObject? = null,
    ) : InboundFrame

    data class CommandResult(
        val requestId: String?,
        val commandId: String?,
        val ok: Boolean,
        val error: String?,
        val conflictCode: String?,
        val data: Any?,
        val raw: JSONObject,
    ) : InboundFrame

    data class Unknown(val kind: String) : InboundFrame
}

/** agent_event.payload：kind 是唯一分流依据。 */
sealed interface AgentPayload {
    data class SdkMessage(val message: SdkMessageView) : AgentPayload

    data class ProferEvent(val event: ProferEventView) : AgentPayload

    data class RunError(val error: String?) : AgentPayload

    data class SessionProjection(
        val operation: String,
        val session: SessionSummary?,
        val sessionId: String?,
        val revision: Int = 0,
    ) : AgentPayload

    data object Ignored : AgentPayload
}

/**
 * SDK 消息的移动端投影：只保留正文、工具名称与少量元信息。
 * 服务端已经裁掉思考正文与工具入参/结果（见 desktop pocket-agent-projection）。
 */
data class SdkMessageView(
    val type: String,
    val uuid: String?,
    /** 服务端落盘时间（_createdAt）；历史消息用它显示真实的发送/生成时间。 */
    val createdAt: Long? = null,
    val partial: Boolean,
    val text: String,
    val toolUses: List<ToolUseView>,
    val toolResults: List<ToolResultView> = emptyList(),
    val subtype: String?,
    val model: String?,
    val isError: Boolean,
    val errorMessage: String?,
    /** 上下文用量：assistant 消息自带 usage（服务端投影只保留四个 token 计数）。 */
    val usage: TokenUsage? = null,
    /** result 消息里的模型上下文窗口（modelUsage[model].contextWindow）。 */
    val modelUsage: Map<String, Int> = emptyMap(),
)

/**
 * 单条消息的 token 用量。
 * [usedTokens] 与桌面 ContextUsageBadge 同口径：input + cache_read + cache_creation。
 */
data class TokenUsage(
    val inputTokens: Int = 0,
    val outputTokens: Int = 0,
    val cacheReadInputTokens: Int = 0,
    val cacheCreationInputTokens: Int = 0,
) {
    val usedTokens: Int
        get() = inputTokens + cacheReadInputTokens + cacheCreationInputTokens
}

data class ToolUseView(
    val id: String?,
    val name: String,
    /** 服务端只透传 file_path；用于正文/回合末尾的文件 chip。 */
    val filePath: String? = null,
)

data class ToolResultView(
    val toolUseId: String,
    val isError: Boolean = false,
)

/** profer_event 的移动端投影：只解析首版需要的字段，其余忽略。 */
sealed interface ProferEventView {
    data class PermissionRequest(
        val requestId: String,
        val sessionId: String?,
        val toolName: String,
        val description: String,
        val command: String?,
        val dangerLevel: String?,
    ) : ProferEventView

    data class PermissionResolved(val requestId: String, val behavior: String?) : ProferEventView

    data class AskUserRequest(
        val requestId: String,
        val sessionId: String?,
        val questions: List<com.profer.pocket.model.AskQuestion>,
    ) : ProferEventView

    data class AskUserResolved(val requestId: String) : ProferEventView

    data class ExitPlanModeRequest(
        val requestId: String,
        val sessionId: String?,
        val summary: String?,
    ) : ProferEventView

    data class ExitPlanModeResolved(val requestId: String) : ProferEventView

    data class RunCompleted(
        val sessionId: String?,
        val stoppedByUser: Boolean,
        val backgroundTasksPending: Boolean,
        val resultErrors: List<String>,
        val endReason: String?,
    ) : ProferEventView

    data class RunIdle(val sessionId: String?) : ProferEventView

    data class TitleUpdated(val title: String) : ProferEventView

    data class SessionUpdated(val session: SessionSummary) : ProferEventView

    data class SessionDeleted(val sessionId: String) : ProferEventView

    data class PermissionModeChanged(val mode: String) : ProferEventView

    data class ModelResolved(val model: String) : ProferEventView

    data class ContextWindow(val contextWindow: Int) : ProferEventView

    data class ExternalRunStarted(val sessionId: String?, val title: String?) : ProferEventView

    data object Ignored : ProferEventView
}

/** 从 event 对象里提取所有字符串元素。 */
internal fun JSONArray.toStringList(): List<String> = buildList {
    for (index in 0 until length()) {
        val value = opt(index)
        if (value is String && value.isNotBlank()) add(value)
    }
}
