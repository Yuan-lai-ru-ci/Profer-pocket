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
    val sessionId: String? = null,
    val pinned: Boolean = false,
    /** 会话绑定的 Agent 预设（岗位）；Chat 模式不用。 */
    val presetId: String? = null,
    val presetLabel: String = "默认",
    /** 思考强度入口标签（如「高」）；空表示不展示这个入口。 */
    val thinkingLabel: String = "",
    /** 当前值的来源：「本会话覆盖」/「跟随预设「高」」。 */
    val thinkingSourceLabel: String = "",
    val thinkingOptions: List<com.profer.pocket.model.ReasoningOption> = emptyList(),
    /** 弹层应勾选的档位；null = 跟随预设（会话覆盖收敛后可能不同于原始覆盖值）。 */
    val thinkingSelectedValue: String? = null,
    /** true = 按模型档位（Pi）切换；false = 按强度档（Claude 内核）。 */
    val thinkingUsesCapability: Boolean = false,
    /** 正在向电脑端读模型档位能力：弹层显示占位，不先闪回强度档。 */
    val thinkingLoading: Boolean = false,
    /** 上下文用量：最近一条 assistant usage 的明细；未知时为 null。 */
    val contextUsage: com.profer.pocket.model.ContextUsage? = null,
    val contextWindow: Int? = null,
    /** 本轮修改过的文件（桌面 TurnFileChangesSummary 的移动端对应物）。 */
    val turnFiles: List<String> = emptyList(),
    /** 子会话血缘：母会话 id（普通会话为 null）。 */
    val parentSessionId: String? = null,
    /** 子会话面包屑，如「调研 · 来自 会话标题」；普通会话为 null。 */
    val subSessionLabel: String? = null,
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

    /** 任务来源卡：定时任务 / Agent 交接 / 外部请求触发的输入，不伪装成用户气泡。 */
    data class Source(
        override val id: String,
        val authorLabel: String,
        /** 可读来源（来自会话「X」）；拿不到时为 null，不回显原始 ID。 */
        val sourceLabel: String?,
        val text: String,
        val scheduled: Boolean,
        val timeLabel: String,
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

    /**
     * 一个回合：两条用户输入之间的助手输出。
     *
     * 移动端一条助手条目对应模型的一步（一段思考 + 几次工具调用），一轮排查常有七八步。
     * 逐步渲染就是七八行「执行过程」，所以投影层把连续条目合成一个回合，
     * 整轮只留一个过程组——与桌面 `groupIntoTurns` 同口径。
     * 只有一步的回合仍是 [Assistant]，两条渲染路径共用 [layoutTurn]。
     */
    @Immutable
    data class Turn(
        /** 取第一步的 id：后续步骤追加时 LazyColumn 的 key 不变，滚动位置也不跳。 */
        override val id: String,
        val steps: List<Assistant>,
    ) : ChatMessage {
        val streaming: Boolean get() = steps.any { it.streaming }
    }
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
    /** 入参（已截断的可读 JSON）；投影尚未下发时为 null，卡片不显示参数分区。 */
    val input: String? = null,
    /** 结果摘要；投影尚未下发时为 null。 */
    val output: String? = null,
    /** 失败原因；投影尚未下发时为 null，失败时至少还有状态图标。 */
    val error: String? = null,
)

enum class NoticeTone { Info, Success, Warning }

enum class ToolKind { Read, Search, Bash, Edit, Other }

/**
 * 正文块。
 *
 * 每个实现都显式标 [Immutable]：接口上的注解不会传给实现类，而带 `List` 字段的
 * 数据类会被 Compose 判成不稳定——那样流式时每次重组都会重走列表/表格的组合。
 * 这些 list 构造完就不再改（解析器每次都造新 list），所以这个承诺成立。
 */
@Immutable
sealed interface ContentBlock {
    @Immutable
    data class Paragraph(val text: String) : ContentBlock

    @Immutable
    data class Heading(val text: String) : ContentBlock

    @Immutable
    data class Bullets(val items: List<String>) : ContentBlock

    @Immutable
    data class Code(val language: String, val code: String) : ContentBlock

    @Immutable
    data class Quote(val text: String) : ContentBlock

    /** Markdown 表格：手机上没有横向表格，渲染成按行分组的记录卡。 */
    @Immutable
    data class Table(val headers: List<String>, val rows: List<List<String>>) : ContentBlock

    /** 1. / 2. 这类有序列表；渲染时按序号重新编号。 */
    @Immutable
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
