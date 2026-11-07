package com.profer.pocket.data.protocol

import com.profer.pocket.model.ChatMessage
import com.profer.pocket.model.ChatToolActivity
import com.profer.pocket.model.ConversationSummary
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat（普通对话）协议解析。
 *
 * 形状对齐桌面 `remote-service`：
 * - `list_conversations` → ConversationMeta[]
 * - `get_conversation_messages` → ChatMessage[]
 * - `chat_event` 的 payload 是流式事件对象（delta / messageId / error）。
 */
object ChatCodec {

    fun conversations(data: Any?): List<ConversationSummary> {
        val array = data as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::conversation)
        }
    }

    /** 单条会话元数据：用于 `update_conversation_model` 这类返回最新 meta 的命令。 */
    fun conversationMeta(data: Any?): ConversationSummary? =
        (data as? JSONObject)?.let(::conversation)

    private fun conversation(json: JSONObject): ConversationSummary? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return ConversationSummary(
            id = id,
            title = json.optString("title").orEmpty(),
            channelId = json.optString("channelId").takeIf { it.isNotBlank() },
            modelId = json.optString("modelId").takeIf { it.isNotBlank() },
            pinned = json.optBoolean("pinned", false),
            archived = json.optBoolean("archived", false),
            createdAt = json.optLong("createdAt", 0),
            updatedAt = json.optLong("updatedAt", 0),
            activePath = json.optJSONArray("activePath")?.toStringList().orEmpty(),
        )
    }

    fun messages(data: Any?): List<ChatMessage> {
        val array = data as? JSONArray ?: return emptyList()
        return (0 until array.length()).mapNotNull { index ->
            array.optJSONObject(index)?.let(::message)
        }
    }

    private fun message(json: JSONObject): ChatMessage? {
        val id = json.optString("id").takeIf { it.isNotBlank() } ?: return null
        return ChatMessage(
            id = id,
            role = json.optString("role").orEmpty(),
            content = json.optString("content").orEmpty(),
            createdAt = json.optLong("createdAt", 0),
            reasoning = json.optString("reasoning").takeIf { it.isNotBlank() },
            stopped = json.optBoolean("stopped", false),
            error = json.optString("error").takeIf { it.isNotBlank() },
            toolActivities = mergeToolActivities(json.optJSONArray("toolActivities")),
        )
    }

    /**
     * 把桌面持久化的 `start` / `result` 活动按 `toolCallId` 合并成一条。
     * 保留首次出现顺序：工具行按调用顺序排列，不因 result 晚到而被顶到末尾。
     */
    fun mergeToolActivities(array: JSONArray?): List<ChatToolActivity> {
        if (array == null) return emptyList()
        var merged = emptyList<ChatToolActivity>()
        for (index in 0 until array.length()) {
            val activity = array.optJSONObject(index)?.let(::toolActivity) ?: continue
            merged = mergeToolActivity(merged, activity)
        }
        return merged
    }

    /** 单条 `chat:stream:tool-activity` 的 activity 对象。 */
    fun toolActivity(json: JSONObject): ChatToolActivity? {
        val toolCallId = json.optString("toolCallId").takeIf { it.isNotBlank() } ?: return null
        val toolName = json.optString("toolName").takeIf { it.isNotBlank() } ?: return null
        val result = json.optString("result").takeIf { it.isNotBlank() }
        val input = json.optJSONObject("input")
        return ChatToolActivity(
            toolCallId = toolCallId,
            toolName = toolName,
            done = json.optString("type") == "result",
            isError = json.optBoolean("isError", false),
            input = input?.toString(),
            target = toolTarget(input),
            result = result,
        )
    }

    /** 从入参里取一个能当副标题的目标（路径/命令/查询词）；取不到就不编。 */
    private fun toolTarget(input: JSONObject?): String? {
        if (input == null) return null
        val value = TARGET_KEYS.firstNotNullOfOrNull { key ->
            input.optString(key).takeIf { it.isNotBlank() }
        } ?: return null
        val flat = value.replace(WHITESPACE, " ").trim()
        return if (flat.length <= TARGET_LIMIT) flat else flat.take(TARGET_LIMIT).trimEnd() + "…"
    }

    /** 按 toolCallId 合并到已有列表：只增不减，result 覆盖 start 的占位状态。 */
    fun mergeToolActivity(
        existing: List<ChatToolActivity>,
        incoming: ChatToolActivity,
    ): List<ChatToolActivity> {
        val index = existing.indexOfFirst { it.toolCallId == incoming.toolCallId }
        if (index < 0) return existing + incoming
        val current = existing[index]
        val merged = current.copy(
            toolName = incoming.toolName.ifBlank { current.toolName },
            done = current.done || incoming.done,
            isError = current.isError || incoming.isError,
            input = incoming.input ?: current.input,
            target = incoming.target ?: current.target,
            result = incoming.result ?: current.result,
        )
        return existing.toMutableList().also { it[index] = merged }
    }

    /**
     * 只保留激活分支的消息。桌面把编辑/重发留下的旧分支也写进同一个 JSONL，
     * 直接顺序渲染会把废弃版本也显示出来。
     */
    fun activeBranch(
        messages: List<ChatMessage>,
        activePath: List<String>,
    ): List<ChatMessage> {
        if (activePath.isEmpty()) return messages
        val byId = messages.associateBy { it.id }
        val branch = activePath.mapNotNull { byId[it] }
        return branch.ifEmpty { messages }
    }

    private fun JSONArray.toStringList(): List<String> =
        (0 until length()).mapNotNull { index -> optString(index).takeIf { it.isNotBlank() } }

    private val TARGET_KEYS = listOf(
        "file_path", "path", "command", "pattern", "query", "url", "prompt", "description", "app",
    )
    private val WHITESPACE = Regex("\\s+")
    private const val TARGET_LIMIT = 72
}
