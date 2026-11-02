package com.profer.pocket.data.protocol

import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ChatImage
import com.profer.pocket.model.ContextUsage
import com.profer.pocket.model.NoticeTone
import com.profer.pocket.model.NotificationKind
import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.PocketNotification
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.ToolCall
import com.profer.pocket.model.ToolTally
import com.profer.pocket.model.TurnFile

/** 归约过程中产生的会话级副作用，由仓库应用到会话列表。 */
sealed interface SessionSignal {
    data class Upsert(val summary: SessionSummary) : SessionSignal

    data class Delete(val sessionId: String, val revision: Int = 0) : SessionSignal

    data class Title(val sessionId: String, val title: String) : SessionSignal

    data class Model(val sessionId: String, val modelId: String) : SessionSignal

    data class PermissionMode(val sessionId: String, val mode: String) : SessionSignal
}

data class ChatReduction(
    val chat: SessionChat,
    val signals: List<SessionSignal> = emptyList(),
    val notifications: List<PocketNotification> = emptyList(),
)

/**
 * agent_event → 会话状态。
 *
 * 纯函数：不碰网络、不碰存储，时间由 [clock] 注入，便于单测。
 * 工具调用保留名称与文件路径（[ToolCall]），不保留入参与结果；
 * 完成/失败状态由 tool_result 驱动。
 */
class ChatReducer(
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    fun reduce(
        chat: SessionChat,
        sessionId: String,
        eventId: Long?,
        payload: AgentPayload,
    ): ChatReduction = when (payload) {
        is AgentPayload.SdkMessage -> reduceSdk(chat, eventId, payload.message)
        is AgentPayload.ProferEvent -> reduceProfer(chat, sessionId, eventId, payload.event)
        is AgentPayload.RunError -> {
            val notice = ChatEntry.Notice(
                id = "error-${eventId ?: clock()}",
                text = payload.error?.takeIf { it.isNotBlank() } ?: "执行出错",
                tone = NoticeTone.Warning,
                at = clock(),
            )
            ChatReduction(
                chat = finishStreaming(chat).append(notice).copy(
                    running = false,
                    pendingInteraction = null,
                    lastError = notice.text,
                ),
            )
        }

        // 桌面所有会话元数据写入的唯一出口：置顶/归档/重命名/移动项目/未读都在这里同步。
        is AgentPayload.SessionProjection -> reduceProjection(chat, sessionId, payload)
        AgentPayload.Ignored -> ChatReduction(chat)
    }

    /**
     * 会话投影：upsert 更新聊天页元数据并广播到会话列表；delete 交给仓库移除。
     * 投影不含运行态，不能触碰 [SessionChat.running]。
     */
    private fun reduceProjection(
        chat: SessionChat,
        sessionId: String,
        projection: AgentPayload.SessionProjection,
    ): ChatReduction {
        if (projection.operation == "delete") {
            val target = projection.sessionId?.takeIf { it.isNotBlank() } ?: sessionId
            return ChatReduction(
                chat = if (target == sessionId) {
                    chat.copy(pendingInteraction = null)
                } else {
                    chat
                },
                signals = listOf(SessionSignal.Delete(target, projection.revision)),
            )
        }
        val summary = projection.session ?: return ChatReduction(chat)
        return ChatReduction(
            chat = chat.copy(
                title = summary.title.ifBlank { chat.title },
                workspaceId = summary.workspaceId ?: chat.workspaceId,
                modelId = summary.modelId ?: chat.modelId,
                permissionMode = summary.permissionMode ?: chat.permissionMode,
            ),
            signals = listOf(SessionSignal.Upsert(summary)),
        )
    }

    private fun reduceSdk(chat: SessionChat, eventId: Long?, message: SdkMessageView): ChatReduction {
        // result 消息不进正文，但带着模型上下文窗口（modelUsage），是重连后恢复窗口的唯一来源。
        if (message.type == "result") {
            val window = message.modelUsage.values.maxOrNull()
            return if (window != null) ChatReduction(chat.copy(contextWindow = window)) else ChatReduction(chat)
        }
        if (message.type != "assistant" && message.type != "user" && message.type != "status" && message.subtype != "task_trigger") {
            return ChatReduction(chat)
        }
        // 任务来源通知（定时任务/交接/外部请求）：单独成卡，不进入正文。
        // 正文随后到达时由正文卡接管，见 [appendSource]。
        if (message.subtype == "task_trigger" || message.type == "status") {
            val entry = ChatEntry.Source(
                id = message.uuid?.takeIf { it.isNotBlank() } ?: "trigger-${eventId ?: clock()}",
                authorLabel = sourceAuthorLabel(message.authorType),
                sourceType = message.sourceType,
                sourceId = message.sourceId,
                text = "",
                at = message.createdAt ?: clock(),
                scheduled = isScheduledSource(message.authorType, message.sourceType),
            )
            return ChatReduction(chat.resetTurn().appendSource(entry))
        }
        if (message.type == "user") {
            // Goal 迭代控制消息：正文是内部注入的 prompt，不当作普通用户消息展示。
            message.goalIteration?.let { iteration ->
                val entry = ChatEntry.Notice(
                    id = message.uuid?.takeIf { it.isNotBlank() } ?: "goal-${eventId ?: iteration}",
                    text = "长时任务第 $iteration 轮",
                    tone = NoticeTone.Info,
                    at = message.createdAt ?: clock(),
                )
                return ChatReduction(chat.append(entry))
            }
            // 工具结果消息只带 tool_result：记下哪些工具已返回、哪些失败，
            // 回合末尾汇总时剔除失败文件，工具卡也能从 Running 切到 Success/Error。
            var next = chat
            if (message.toolResults.isNotEmpty()) {
                val completed = message.toolResults.map { it.toolUseId }.toSet()
                val failed = message.toolResults.filter { it.isError }.map { it.toolUseId }.toSet()
                next = next.copy(
                    completedToolIds = next.completedToolIds + completed,
                    failedToolIds = next.failedToolIds + failed,
                )
                // 工具产出的图片（send_local_image / generate_image）挂在最近一条助手消息上，
                // 帧里只有路径与文件名，字节由 UI 点开时再向电脑端拉取。
                val images = message.toolResults
                    .flatMap { it.images }
                    .map { ChatImage(path = it.path, filename = it.filename, mediaType = it.mediaType) }
                next = attachImages(next, images)
            }
            val text = message.text.trim()
            if (text.isEmpty()) return ChatReduction(next)
            val id = message.uuid?.takeIf { it.isNotBlank() } ?: "user-${eventId ?: text.hashCode()}"
            // 作者不是用户（定时任务/Agent 交接/外部请求）：显示为来源卡，不伪装成用户刚发的一条消息。
            val author = message.authorType
            if (author != null && author != "user") {
                val entry = ChatEntry.Source(
                    id = id,
                    authorLabel = sourceAuthorLabel(author),
                    sourceType = message.sourceType,
                    sourceId = message.sourceId,
                    text = text,
                    at = message.createdAt ?: clock(),
                    scheduled = isScheduledSource(author, message.sourceType),
                )
                return ChatReduction(next.resetTurn().appendSource(entry))
            }
            val entry = ChatEntry.User(id = id, text = text, at = clock())
            // 每个用户回合重新统计工具调用与文件汇总，避免上一轮的值挂到这一次回复上。
            return ChatReduction(next.resetTurn().append(entry))
        }

        val entryId = message.uuid?.takeIf { it.isNotBlank() } ?: "assistant-${eventId ?: chat.messages.size}"
        val existing = chat.messages.firstOrNull { it.id == entryId } as? ChatEntry.Assistant

        // 工具次数：partial 与 final 会重复携带同一批 tool_use，按 id 去重。
        // 同时按发生顺序记录每次调用：turn 级用于汇总，message 级挂到当前助手消息上供逐条工具卡渲染。
        var tools = chat.tools
        var toolCalls = chat.toolCalls
        val counted = chat.countedToolIds.toMutableSet()
        val messageTools = mutableListOf<ToolCall>()
        message.toolUses.forEachIndexed { index, tool ->
            val key = tool.id?.takeIf { it.isNotBlank() }
                ?: "evt-${eventId ?: "x"}-$index-${tool.name}"
            if (counted.add(key)) {
                tools = tools.record(tool.name)
                val call = ToolCall(
                    id = key,
                    name = tool.name,
                    path = tool.filePath,
                    at = clock(),
                )
                toolCalls = toolCalls + call
                messageTools += call
            }
        }

        // 修改类工具的文件进入本轮汇总；Read 只用于正文补全，不算改动。
        var turnFiles = chat.turnFiles
        message.toolUses.forEach { tool ->
            val path = tool.filePath ?: return@forEach
            if (tool.name.lowercase() !in MUTATING_TOOLS) return@forEach
            val toolId = tool.id?.takeIf { it.isNotBlank() } ?: path
            if (turnFiles.none { it.toolId == toolId }) {
                turnFiles = turnFiles + TurnFile(toolId, path)
            }
        }

        val text = message.text
        val incomingThinking = message.reasoning?.takeIf { it.isNotBlank() }
        val streaming = message.partial
        val messages = when {
            existing != null -> {
                // partial 是累计全文；乱序到达时不回退正文，也不回退思考。
                val nextText = when {
                    !streaming -> text.ifEmpty { existing.text }
                    text.length >= existing.text.length -> text
                    else -> existing.text
                }
                val existingThinking = existing.thinking
                val nextThinking = when {
                    incomingThinking == null -> existingThinking
                    existingThinking == null -> incomingThinking
                    // final 的思考窗口比 partial 大，按长度取更完整的一份。
                    incomingThinking.length >= existingThinking.length -> incomingThinking
                    else -> existingThinking
                }
                chat.messages.map {
                    if (it.id == entryId) {
                        ChatEntry.Assistant(
                            id = entryId,
                            text = nextText,
                            streaming = streaming,
                            at = existing.at,
                            thinking = nextThinking,
                            images = existing.images,
                            // 同一条助手消息的工具只增不减：后续帧重复携带旧调用不会重复入列。
                            toolCalls = (existing.toolCalls + messageTools).distinctBy { call -> call.id },
                        )
                    } else {
                        it
                    }
                }
            }

            // 只有思考、还没出正文的 assistant 也必须先落在列表里，
            // 否则移动端在模型「想」的这几秒里看不到任何变化。
            // 纯工具回合（没有正文也没有思考）也要建条目，否则工具卡没有挂载点。
            text.isNotBlank() || incomingThinking != null || messageTools.isNotEmpty() ->
                chat.messages + ChatEntry.Assistant(
                    id = entryId,
                    text = text,
                    streaming = streaming,
                    at = clock(),
                    thinking = incomingThinking,
                    toolCalls = messageTools,
                )

            else -> chat.messages
        }

        return ChatReduction(
            chat = chat.copy(
                messages = messages,
                tools = tools,
                toolCalls = toolCalls,
                countedToolIds = counted,
                turnFiles = turnFiles,
                running = true,
                // 最新 assistant usage 就是当前上下文占用；旧消息不回退（partial 没有 usage）。
                contextUsage = message.usage?.let { usage ->
                    ContextUsage(
                        inputTokens = usage.inputTokens,
                        outputTokens = usage.outputTokens,
                        cacheReadInputTokens = usage.cacheReadInputTokens,
                        cacheCreationInputTokens = usage.cacheCreationInputTokens,
                        updatedAt = clock(),
                    )
                } ?: chat.contextUsage,
            ),
        )
    }

    private fun reduceProfer(
        chat: SessionChat,
        sessionId: String,
        eventId: Long?,
        event: ProferEventView,
    ): ChatReduction {
        val now = clock()
        return when (event) {
            is ProferEventView.PermissionRequest -> ChatReduction(
                chat = chat.copy(
                    running = true,
                    pendingInteraction = PendingInteraction.Permission(
                        id = event.requestId,
                        toolName = event.toolName,
                        description = event.description,
                        command = event.command,
                        dangerLevel = event.dangerLevel,
                    ),
                ),
                notifications = listOf(
                    PocketNotification(
                        title = "需要权限确认",
                        body = event.description.ifBlank {
                            if (event.toolName.isBlank()) "Agent 需要你的权限确认" else "Agent 请求使用工具：${event.toolName}"
                        },
                        sessionId = sessionId,
                        kind = NotificationKind.Permission,
                        at = now,
                    ),
                ),
            )

            is ProferEventView.PermissionResolved -> ChatReduction(
                chat = clearPending(chat, event.requestId),
            )

            is ProferEventView.AskUserRequest -> ChatReduction(
                chat = chat.copy(
                    running = true,
                    pendingInteraction = PendingInteraction.Question(event.requestId, event.questions),
                ),
                notifications = listOf(
                    PocketNotification(
                        title = "Agent 需要你的输入",
                        body = event.questions.firstOrNull()?.question ?: "Agent 正在等待你的回答",
                        sessionId = sessionId,
                        kind = NotificationKind.Question,
                        at = now,
                    ),
                ),
            )

            is ProferEventView.AskUserResolved -> ChatReduction(clearPending(chat, event.requestId))

            is ProferEventView.ExitPlanModeRequest -> ChatReduction(
                chat = chat.copy(
                    running = true,
                    pendingInteraction = PendingInteraction.PlanApproval(event.requestId, event.summary),
                ),
                notifications = listOf(
                    PocketNotification(
                        title = "Agent 计划待审批",
                        body = "Agent 已完成计划，等待你的审批",
                        sessionId = sessionId,
                        kind = NotificationKind.PlanApproval,
                        at = now,
                    ),
                ),
            )

            is ProferEventView.ExitPlanModeResolved -> ChatReduction(clearPending(chat, event.requestId))

            is ProferEventView.RunCompleted -> {
                val errors = event.resultErrors.filter { it.isNotBlank() }
                val errorEntry = if (errors.isEmpty()) null else ChatEntry.Notice(
                    id = "run-error-${eventId ?: now}",
                    text = errors.joinToString("\n"),
                    tone = NoticeTone.Warning,
                    at = now,
                )
                val notify = !event.stoppedByUser && !event.backgroundTasksPending
                ChatReduction(
                    chat = finishStreaming(chat).let { base ->
                        if (errorEntry == null) base else base.append(errorEntry)
                    }.copy(
                        running = false,
                        // 后台仍有任务在跑：插话只注入不打断（与桌面 backgroundWaiting 同口径）。
                        backgroundWaiting = event.backgroundTasksPending,
                        pendingInteraction = null,
                        lastError = errors.firstOrNull(),
                    ),
                    notifications = if (notify) {
                        listOf(
                            PocketNotification(
                                title = "任务已完成",
                                body = "Agent 任务已完成",
                                sessionId = sessionId,
                                kind = NotificationKind.RunCompleted,
                                at = now,
                            ),
                        )
                    } else {
                        emptyList()
                    },
                )
            }

            is ProferEventView.RunIdle -> ChatReduction(
                chat = finishStreaming(chat).copy(
                    running = false,
                    backgroundWaiting = false,
                    pendingInteraction = null,
                ),
                notifications = listOf(
                    PocketNotification(
                        title = "任务已完成",
                        body = "Agent 任务已完成",
                        sessionId = sessionId,
                        kind = NotificationKind.RunCompleted,
                        at = now,
                    ),
                ),
            )

            is ProferEventView.TitleUpdated -> ChatReduction(
                chat = chat.copy(title = event.title),
                signals = listOf(SessionSignal.Title(sessionId, event.title)),
            )

            is ProferEventView.SessionUpdated -> {
                val summary = event.session
                ChatReduction(
                    chat = chat.copy(
                        title = summary.title.ifBlank { chat.title },
                        modelId = summary.modelId ?: chat.modelId,
                        permissionMode = summary.permissionMode ?: chat.permissionMode,
                    ),
                    signals = listOf(SessionSignal.Upsert(summary)),
                )
            }

            is ProferEventView.SessionDeleted -> {
                val notice = ChatEntry.Notice(
                    id = "session-deleted-${event.sessionId}",
                    text = "会话已删除",
                    tone = NoticeTone.Info,
                    at = now,
                )
                ChatReduction(
                    chat = chat.append(notice).copy(running = false, pendingInteraction = null),
                    signals = listOf(SessionSignal.Delete(event.sessionId.ifBlank { sessionId })),
                )
            }

            is ProferEventView.PermissionModeChanged -> ChatReduction(
                chat = chat.copy(permissionMode = event.mode),
                signals = listOf(SessionSignal.PermissionMode(sessionId, event.mode)),
            )

            is ProferEventView.ModelResolved -> ChatReduction(
                chat = chat.copy(modelId = event.model),
                signals = listOf(SessionSignal.Model(sessionId, event.model)),
            )

            is ProferEventView.ContextWindow -> ChatReduction(
                chat = chat.copy(contextWindow = event.contextWindow.takeIf { it > 0 }),
            )

            is ProferEventView.ExternalRunStarted -> ChatReduction(
                chat = chat.copy(
                    running = true,
                    title = event.title?.takeIf { it.isNotBlank() } ?: chat.title,
                ),
            )

            ProferEventView.Ignored -> ChatReduction(chat)
        }
    }

    /** 新 run 开始：清空上一轮的工具统计与文件汇总，不把旧值挂到新一轮。 */
    private fun SessionChat.resetTurn(): SessionChat = copy(
        tools = ToolTally(),
        countedToolIds = emptySet(),
        toolCalls = emptyList(),
        completedToolIds = emptySet(),
        turnFiles = emptyList(),
        failedToolIds = emptySet(),
    )

    /**
     * 来源卡去重：触发通知先到、正文随后到；正文接管前一条空的同作者通知，
     * 与桌面 `projectTaskInputTimeline` 同一目的（不把同一件事画成两张卡）。
     */
    private fun SessionChat.appendSource(entry: ChatEntry.Source): SessionChat {
        val last = messages.lastOrNull()
        if (last is ChatEntry.Source && last.text.isBlank() && last.authorLabel == entry.authorLabel) {
            return copy(messages = messages.dropLast(1) + entry)
        }
        return append(entry)
    }

    private fun clearPending(chat: SessionChat, requestId: String): SessionChat {
        val pending = chat.pendingInteraction
        return if (pending != null && pending.id == requestId) {
            chat.copy(pendingInteraction = null)
        } else {
            chat
        }
    }

    /** 把工具产出的图片挂到最近一条助手消息上；按路径去重，重复帧不会重复追加。 */
    private fun attachImages(chat: SessionChat, images: List<ChatImage>): SessionChat {
        if (images.isEmpty()) return chat
        val index = chat.messages.indexOfLast { it is ChatEntry.Assistant }
        if (index < 0) return chat
        val entry = chat.messages[index] as ChatEntry.Assistant
        val merged = (entry.images + images).distinctBy { it.path }
        if (merged.size == entry.images.size) return chat
        return chat.copy(
            messages = chat.messages.toMutableList().also { it[index] = entry.copy(images = merged) },
        )
    }

    private fun finishStreaming(chat: SessionChat): SessionChat {
        if (chat.messages.none { it is ChatEntry.Assistant && it.streaming }) return chat
        return chat.copy(
            messages = chat.messages.map {
                if (it is ChatEntry.Assistant && it.streaming) it.copy(streaming = false) else it
            },
        )
    }
}

private val MUTATING_TOOLS = setOf("edit", "write", "multiedit", "notebookedit")

/**
 * 作者标签：与服务端 `_provenance.author.type` 一一对应，不根据正文内容猜作者。
 * 旧消息没有 provenance 时走「历史任务来源」而不是「用户输入」。
 */
internal fun sourceAuthorLabel(authorType: String?): String = when (authorType) {
    "agent" -> "Agent 任务交接"
    "automation" -> "定时任务触发"
    "system" -> "系统任务接续"
    "external" -> "外部请求"
    "user" -> "用户输入"
    null -> "历史任务来源"
    else -> "未知来源"
}

/** 定时任务来源：作者是 automation，或来源引用直接指向某个定时任务。 */
internal fun isScheduledSource(authorType: String?, sourceType: String?): Boolean =
    authorType == "automation" || sourceType == "automation"
