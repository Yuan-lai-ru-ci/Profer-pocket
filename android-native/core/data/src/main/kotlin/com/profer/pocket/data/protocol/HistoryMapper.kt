package com.profer.pocket.data.protocol

import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ChatImage
import com.profer.pocket.model.ContextUsage
import com.profer.pocket.model.ToolCall
import com.profer.pocket.model.ToolTally
import com.profer.pocket.model.TurnFile

/**
 * 历史消息 → 移动端会话条目。
 *
 * 只保留用户正文、助手正文、思考预览与工具调用；与实时归约共用同一套去重规则，
 * 避免“打开会话时工具次数翻倍”。
 */
object HistoryMapper {

    data class History(
        val entries: List<ChatEntry>,
        val tools: ToolTally = ToolTally(),
        val countedToolIds: Set<String> = emptySet(),
        val toolCalls: List<ToolCall> = emptyList(),
        val completedToolIds: Set<String> = emptySet(),
        val failedToolIds: Set<String> = emptySet(),
        val files: List<String> = emptyList(),
        /** 历史里最后一条带 usage 的 assistant；用于打开会话时直接显示上下文占用。 */
        val contextUsage: ContextUsage? = null,
        /** 历史里 result 消息给出的上下文窗口。 */
        val contextWindow: Int? = null,
    )

    fun map(messages: List<SdkMessageView>, now: Long): History {
        val entries = mutableListOf<ChatEntry>()
        var tools = ToolTally()
        val counted = mutableSetOf<String>()
        var toolCalls = mutableListOf<ToolCall>()
        var turnFiles = mutableListOf<TurnFile>()
        val completed = mutableSetOf<String>()
        val failed = mutableSetOf<String>()
        var contextUsage: ContextUsage? = null
        var contextWindow: Int? = null

        messages.forEachIndexed { index, message ->
            if (message.partial) return@forEachIndexed
            // 任务来源通知（桌面投影为 role=status）：单独成卡，不进入正文。
            if (message.subtype == "task_trigger" || message.type == "status") {
                appendSource(
                    entries,
                    ChatEntry.Source(
                        id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-trigger-$index",
                        authorLabel = sourceAuthorLabel(message.authorType),
                        sourceType = message.sourceType,
                        sourceId = message.sourceId,
                        text = "",
                        at = message.createdAt ?: now,
                        scheduled = isScheduledSource(message.authorType, message.sourceType),
                    ),
                )
                return@forEachIndexed
            }
            when (message.type) {
                "user" -> {
                    // Goal 迭代控制消息：正文是内部注入 prompt，只标一轮，不当作用户输入。
                    message.goalIteration?.let { iteration ->
                        entries += ChatEntry.Notice(
                            id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-goal-$index",
                            text = "长时任务第 $iteration 轮",
                            tone = com.profer.pocket.model.NoticeTone.Info,
                            at = message.createdAt ?: now,
                        )
                        return@forEachIndexed
                    }
                    // tool_result 消息只携带“已返回 + 是否失败”：先记账，
                    // 等回合汇总时剔除对应文件，并给工具卡切换成完成/失败状态。
                    message.toolResults.forEach { result ->
                        completed += result.toolUseId
                        if (result.isError) failed += result.toolUseId
                    }
                    // 工具产出的图片挂在同回合的助手消息上，历史打开时也要能看见。
                    attachImages(entries, message.toolResults.flatMap { it.images })
                    // 历史里按用户回合分段，只保留最后一个回合的工具与文件。
                    val text = message.text.trim()
                    if (text.isNotEmpty()) {
                        tools = ToolTally()
                        toolCalls = mutableListOf()
                        turnFiles = mutableListOf()
                        completed.clear()
                        failed.clear()
                        // 作者不是用户（定时任务/Agent 交接/外部请求）：来源卡接管，不伪装用户气泡。
                        val author = message.authorType
                        if (author != null && author != "user") {
                            appendSource(
                                entries,
                                ChatEntry.Source(
                                    id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-source-$index",
                                    authorLabel = sourceAuthorLabel(author),
                                    sourceType = message.sourceType,
                                    sourceId = message.sourceId,
                                    text = text,
                                    at = message.createdAt ?: now,
                                    scheduled = isScheduledSource(author, message.sourceType),
                                ),
                            )
                            return@forEachIndexed
                        }
                        entries += ChatEntry.User(
                            id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-user-$index",
                            text = text,
                            // 历史消息优先用落盘时间：用加载时刻会把每条旧消息都显示成“刚刚”。
                            at = message.createdAt ?: now,
                        )
                    }
                }

                "assistant" -> {
                    // 历史水合也要带回上下文占用，否则重开一个已有会话时顶栏环是空的。
                    message.usage?.takeIf { it.usedTokens > 0 }?.let { usage ->                        contextUsage = ContextUsage(
                            inputTokens = usage.inputTokens,
                            outputTokens = usage.outputTokens,
                            cacheReadInputTokens = usage.cacheReadInputTokens,
                            cacheCreationInputTokens = usage.cacheCreationInputTokens,
                            updatedAt = message.createdAt ?: now,
                        )
                    }
                    // 本条助手消息自己发起的工具调用：逐条工具卡从这里渲染。
                    val messageTools = mutableListOf<ToolCall>()
                    message.toolUses.forEachIndexed { toolIndex, tool ->
                        val key = tool.id?.takeIf { it.isNotBlank() } ?: "history-$index-$toolIndex-${tool.name}"
                        if (counted.add(key)) {
                            tools = tools.record(tool.name)
                            val call = ToolCall(
                                id = key,
                                name = tool.name,
                                path = tool.filePath,
                                at = message.createdAt ?: now,
                            )
                            toolCalls += call
                            messageTools += call
                        }
                        val path = tool.filePath
                        if (path != null && tool.name.lowercase() in MUTATING_TOOLS &&
                            turnFiles.none { it.toolId == key }
                        ) {
                            turnFiles += TurnFile(key, path)
                        }
                    }
                    val text = message.text.trim()
                    val thinking = message.reasoning?.trim()?.takeIf { it.isNotEmpty() }
                    if (text.isNotEmpty() || thinking != null || messageTools.isNotEmpty()) {
                        entries += ChatEntry.Assistant(
                            id = message.uuid?.takeIf { it.isNotBlank() } ?: "history-assistant-$index",
                            text = text,
                            streaming = false,
                            at = message.createdAt ?: now,
                            thinking = thinking,
                            toolCalls = messageTools,
                        )
                    }
                }
            }
            if (message.modelUsage.isNotEmpty()) {
                // 分母以最近一次 result 的 modelUsage 为准；同一会话切过模型时取最后出现的。
                contextWindow = message.modelUsage.values.lastOrNull() ?: contextWindow
            }
        }

        return History(
            entries = entries,
            tools = tools,
            countedToolIds = counted,
            toolCalls = toolCalls,
            completedToolIds = completed,
            failedToolIds = failed,
            files = turnFiles
                .filterNot { it.toolId in failed }
                .distinctBy { it.path }
                .map { it.path },
            contextUsage = contextUsage,
            contextWindow = contextWindow,
        )
    }
}

private val MUTATING_TOOLS = setOf("edit", "write", "multiedit", "notebookedit")

/**
 * 来源卡入列：触发通知先到、正文随后到，正文接管前一条空的同作者通知，
 * 与实时归约 `ChatReducer.appendSource` 保持同一条去重规则。
 */
private fun appendSource(entries: MutableList<ChatEntry>, entry: ChatEntry.Source) {
    val last = entries.lastOrNull()
    if (last is ChatEntry.Source && last.text.isBlank() && last.authorLabel == entry.authorLabel) {
        entries[entries.lastIndex] = entry
        return
    }
    entries += entry
}

/** 把图片挂到最近一条助手条目；按路径去重，重复历史不会重复追加。 */
private fun attachImages(entries: MutableList<ChatEntry>, images: List<ImageRef>) {
    if (images.isEmpty()) return
    val index = entries.indexOfLast { it is ChatEntry.Assistant }
    if (index < 0) return
    val entry = entries[index] as ChatEntry.Assistant
    val merged = (entry.images + images.map { ChatImage(it.path, it.filename, it.mediaType) })
        .distinctBy { it.path }
    if (merged.size == entry.images.size) return
    entries[index] = entry.copy(images = merged)
}
