package com.profer.pocket.data.protocol

import com.profer.pocket.model.ChatMessage
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
        )
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
}
