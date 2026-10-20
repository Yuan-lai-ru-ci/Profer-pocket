package com.profer.pocket.chat

import androidx.compose.runtime.Immutable
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.model.SendStatus

@Immutable
data class ChatUiState(
    val title: String,
    val project: String,
    val modelLabel: String,
    val modeLabel: String,
    val channelId: String? = null,
    val modelId: String? = null,
    val permissionMode: String? = null,
    val messages: List<ChatMessage>,
    val streaming: Boolean,
    val offline: Boolean,
    /** 离线条文案（含原因与待发条数）；在线时为 null。 */
    val offlineLabel: String? = null,
    val pendingPermission: PermissionRequest?,
    /** Agent 追问：问题列表与请求 id，用于表单回答。 */
    val pendingQuestionId: String? = null,
    val pendingQuestions: List<com.profer.pocket.model.AskQuestion> = emptyList(),
    val draft: String = "",
    val sessionId: String? = null,
    val pinned: Boolean = false,
    /** 会话绑定的 Agent 预设（岗位）；Chat 模式不用。 */
    val presetId: String? = null,
    val presetLabel: String = "默认",
    /** 上下文用量：最近一条 assistant usage 的明细；未知时为 null。 */
    val contextUsage: com.profer.pocket.model.ContextUsage? = null,
    val contextWindow: Int? = null,
    /** 本轮修改过的文件（桌面 TurnFileChangesSummary 的移动端对应物）。 */
    val turnFiles: List<String> = emptyList(),
    /** 本轮工具调用：按发生顺序逐条渲染，每条用自己的图标与状态。 */
    val toolCalls: List<ToolBlock> = emptyList(),
)

@Immutable
sealed interface ChatMessage {
    val id: String

    data class User(
        override val id: String,
        val text: String,
        val meta: String,
        val status: SendStatus = SendStatus.Sent,
        val failure: String? = null,
        /** 从 `<attached_files>` 解析出的附件；正文已剥离协议块。 */
        val attachments: List<com.profer.pocket.model.AgentAttachment> = emptyList(),
    ) : ChatMessage

    data class Notice(
        override val id: String,
        val text: String,
        val tone: NoticeTone = NoticeTone.Info,
    ) : ChatMessage

    data class Assistant(
        override val id: String,
        val thinking: ThinkingBlock? = null,
        val tools: List<ToolBlock> = emptyList(),
        val blocks: List<ContentBlock> = emptyList(),
        val streaming: Boolean = false,
        /** 本回合工具产出的图片；缩略图由宿主注入的 loader 拉取。 */
        val images: List<com.profer.pocket.model.ChatImage> = emptyList(),
    ) : ChatMessage
}

/** 发送栏模型选择项：模型必须和渠道一起提交。 */
@Immutable
data class ModelOption(
    val channelId: String,
    val modelId: String,
    val label: String,
    val channelLabel: String,
    /** 完整模型 id；短名重复时用它区分（例如 cn:/global: 前缀）。 */
    val rawLabel: String = "",
)

@Immutable
data class ThinkingBlock(
    val durationLabel: String,
    val body: String,
)

@Immutable
data class ToolBlock(
    val id: String,
    val kind: ToolKind,
    val title: String,
    val subtitle: String,
    val status: ToolStatus,
    val detail: String? = null,
)

enum class NoticeTone { Info, Success, Warning }

enum class ToolKind { Read, Search, Bash, Edit, Other }

@Immutable
sealed interface ContentBlock {
    data class Paragraph(val text: String) : ContentBlock
    data class Heading(val text: String) : ContentBlock
    data class Bullets(val items: List<String>) : ContentBlock
    data class Code(val language: String, val code: String) : ContentBlock
    data class Quote(val text: String) : ContentBlock

    /** Markdown 表格：手机上没有横向表格，渲染成按行分组的记录卡。 */
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ContentBlock

    /** 1. / 2. 这类有序列表；渲染时按序号重新编号。 */
    data class Ordered(val items: List<String>) : ContentBlock
}

@Immutable
data class PermissionRequest(
    val id: String,
    val title: String,
    val description: String,
    val command: String? = null,
    val contextLabel: String? = null,
)

enum class ConnectionState { Connected, Connecting, Offline }
