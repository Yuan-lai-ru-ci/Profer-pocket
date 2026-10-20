package com.profer.pocket.app

import com.profer.pocket.chat.ChatMessage
import com.profer.pocket.chat.ChatUiState
import com.profer.pocket.chat.ConversationMessage
import com.profer.pocket.chat.ConversationUiState
import com.profer.pocket.chat.NoticeTone
import com.profer.pocket.chat.PermissionRequest
import com.profer.pocket.chat.ThinkingBlock
import com.profer.pocket.chat.ToolBlock
import com.profer.pocket.chat.ToolKind
import com.profer.pocket.chat.parseRichBlocks
import com.profer.pocket.data.SessionCatalog
import com.profer.pocket.connect.ConnectionPhase
import com.profer.pocket.connect.ConnectionUiState
import com.profer.pocket.designsystem.component.ToolStatus
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ChatMessage as DomainChatMessage
import com.profer.pocket.model.ChatStream
import com.profer.pocket.model.ConversationSummary
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.NoticeTone as DomainNoticeTone
import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary
import com.profer.pocket.sessions.ConversationItem
import com.profer.pocket.sessions.ConversationListState
import com.profer.pocket.sessions.ProjectGroup
import com.profer.pocket.sessions.SessionGroupKind
import com.profer.pocket.sessions.SessionItem
import com.profer.pocket.sessions.SessionListState
import java.net.URI
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 领域状态 → 既有 UI 模型。界面层不改结构，只消费这里的结果。 */
object UiMapping {

    /**
     * 会话行映射缓存：抽屉/搜索/切换选中都会重算整张列表，
     * 875 条会话每次重新格式化日期 + 构造模型会直接卡住主线程。
     * key 带分钟与当前选中项，保证未读点/时间标签仍然准确。
     */
    private data class SessionItemKey(
        val session: SessionSummary,
        val currentSessionId: String?,
        val minute: Long,
        val projectName: String?,
    )

    private val sessionCacheLock = Any()

    private val sessionItemCache = object : LinkedHashMap<SessionItemKey, SessionItem>(
        /* initialCapacity = */ 256,
        /* loadFactor = */ 0.75f,
        /* accessOrder = */ true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<SessionItemKey, SessionItem>,
        ): Boolean = size > 1500
    }

    private val dayFormat = ThreadLocal.withInitial {
        SimpleDateFormat("M月d日", Locale.getDefault())
    }

    fun connectionState(
        status: ConnectionStatus,
        url: String,
        token: String,
        hasSavedToken: Boolean,
        latencyMs: Int? = null,
    ): ConnectionUiState {
        val endpoint = (status as? ConnectionStatus.Connected)?.endpoint
            ?: url.takeIf { it.isNotBlank() }
            ?: ""
        val phase = when (status) {
            ConnectionStatus.Idle,
            is ConnectionStatus.Offline,
            -> when ((status as? ConnectionStatus.Offline)?.cause) {
                // 只有网络不可达才进入「失败重试」；凭据失效要回到可编辑表单换 Token。
                com.profer.pocket.model.OfflineCause.Unreachable -> ConnectionPhase.Failed
                else -> ConnectionPhase.Disconnected
            }

            ConnectionStatus.Connecting -> ConnectionPhase.Connecting
            is ConnectionStatus.Connected -> ConnectionPhase.Connected
            is ConnectionStatus.Reconnecting -> ConnectionPhase.Connecting
        }
        val error = when (status) {
            is ConnectionStatus.Offline -> when (status.cause) {
                com.profer.pocket.model.OfflineCause.Unauthorized -> "连接 Token 已失效，请重新输入"
                com.profer.pocket.model.OfflineCause.Unreachable -> status.message ?: "连接失败"
                com.profer.pocket.model.OfflineCause.MissingCredentials -> "请填写连接 Token"
                com.profer.pocket.model.OfflineCause.UserDisconnected -> null
            }

            is ConnectionStatus.Reconnecting -> "连接中断，正在重连…"
            else -> null
        }
        val host = hostOf(endpoint)
        return ConnectionUiState(
            phase = phase,
            url = url,
            token = token,
            hasSavedToken = hasSavedToken,
            deviceName = host.ifBlank { "Mac" },
            deviceAddress = endpoint,
            latencyMs = latencyMs ?: 0,
            daemonVersion = "远程",
            networkLabel = if (endpoint.startsWith("wss://")) "加密网络" else "局域网",
            protocolLabel = "Pocket WS v1",
            lastSyncLabel = if (status is ConnectionStatus.Connected) "已连接" else "—",
            errorMessage = error,
        )
    }

    /**
     * 离线条文案：把「为什么没连上 / 还要等多久 / 有几条没发出去」说清楚。
     * 连接正常时返回 null——正常不是信息。
     */
    fun offlineLabel(status: ConnectionStatus, queuedSends: Int = 0): String? {
        val base = when (status) {
            ConnectionStatus.Idle -> "还没有连接"
            ConnectionStatus.Connecting -> "正在连接电脑…"
            is ConnectionStatus.Connected -> null
            is ConnectionStatus.Reconnecting -> {
                val seconds = ((status.delayMs + 999) / 1000).coerceAtLeast(1)
                "连接中断 · 约 ${seconds}s 后重试"
            }

            is ConnectionStatus.Offline -> when (status.cause) {
                com.profer.pocket.model.OfflineCause.Unreachable ->
                    status.message ?: "无法连接电脑"

                com.profer.pocket.model.OfflineCause.Unauthorized -> "连接 Token 已失效"
                com.profer.pocket.model.OfflineCause.MissingCredentials -> "还没有配置连接"
                com.profer.pocket.model.OfflineCause.UserDisconnected -> "已断开连接"
            }
        } ?: return null
        return if (queuedSends > 0) "$base · $queuedSends 条待发送" else base
    }

    fun chatState(
        sessionId: String?,
        chat: SessionChat?,
        sessions: List<SessionSummary>,
        workspaces: List<WorkspaceSummary>,
        status: ConnectionStatus,
        queuedSends: Int = 0,
        draft: String,
        presets: List<com.profer.pocket.model.AgentPresetSummary> = emptyList(),
        now: Long = System.currentTimeMillis(),
    ): ChatUiState {
        val online = status is ConnectionStatus.Connected
        val summary = sessions.firstOrNull { it.id == sessionId }
        val title = chat?.title?.takeIf { it.isNotBlank() }
            ?: summary?.title?.takeIf { it.isNotBlank() }
            ?: "新会话"
        val workspaceId = chat?.workspaceId ?: summary?.workspaceId
        val workspaceName = workspaces.firstOrNull { it.id == workspaceId }?.name
        val channelId = chat?.channelId ?: summary?.channelId
        val modelId = chat?.modelId ?: summary?.modelId
        val permissionMode = chat?.permissionMode ?: summary?.permissionMode
        val messages = buildList {
            chat?.messages?.forEach { entry ->
                when (entry) {
                    is ChatEntry.User -> if (!isInternalUserText(entry.text)) {
                        add(
                            ChatMessage.User(
                                id = entry.id,
                                text = com.profer.pocket.chat.stripAttachedFiles(entry.text),
                                meta = when (entry.status) {
                                    com.profer.pocket.model.SendStatus.Sending -> "发送中…"
                                    com.profer.pocket.model.SendStatus.Failed -> "发送失败"
                                    com.profer.pocket.model.SendStatus.Sent ->
                                        "${timeLabel(entry.at)} · ${if (online) "已送达" else "等待连接"}"
                                },
                                status = entry.status,
                                failure = entry.failure,
                                attachments = com.profer.pocket.chat.parseAttachedFiles(entry.text),
                            ),
                        )
                    }

                    is ChatEntry.Assistant -> add(
                        ChatMessage.Assistant(
                            id = entry.id,
                            // 有思考就渲染折叠卡：运行中显示「思考中…」，结束后回到「思考过程」。
                            thinking = entry.thinking?.takeIf { it.isNotBlank() }?.let { body ->
                                ThinkingBlock(
                                    durationLabel = if (entry.streaming) "思考中…" else "思考过程",
                                    body = body,
                                )
                            },
                            blocks = parseRichBlocks(entry.text),
                            streaming = entry.streaming,
                            images = entry.images,
                        ),
                    )

                    is ChatEntry.Notice -> add(
                        ChatMessage.Notice(
                            id = entry.id,
                            text = entry.text,
                            tone = when (entry.tone) {
                                DomainNoticeTone.Info -> NoticeTone.Info
                                DomainNoticeTone.Success -> NoticeTone.Success
                                DomainNoticeTone.Warning -> NoticeTone.Warning
                            },
                        ),
                    )
                }
            }
            val question = chat?.pendingInteraction as? PendingInteraction.Question
            if (question != null) {
                val first = question.questions.firstOrNull()?.question
                add(
                    ChatMessage.Notice(
                        id = "question-${question.id}",
                        text = if (first.isNullOrBlank()) {
                            "Agent 在等待你的回答（请在电脑端回答）"
                        } else {
                            "Agent 在等待你的回答：$first"
                        },
                        tone = NoticeTone.Info,
                    ),
                )
            }
        }
        return ChatUiState(
            title = title,
            project = workspaceName ?: "Profer",
            sessionId = sessionId,
            pinned = summary?.isPinned == true,
            modelLabel = shortModelLabel(modelId),
            modeLabel = permissionLabel(permissionMode),
            channelId = channelId,
            modelId = modelId,
            permissionMode = permissionMode,
            presetId = summary?.presetId,
            presetLabel = presets.firstOrNull { it.id == summary?.presetId }?.displayName ?: "默认",
            contextUsage = chat?.contextUsage,
            contextWindow = chat?.contextWindow,
            messages = messages,
            streaming = chat?.running == true || chat?.messages?.any { it is ChatEntry.Assistant && it.streaming } == true,
            offline = !online,
            offlineLabel = offlineLabel(status, queuedSends),
            pendingPermission = chat?.pendingInteraction?.toUiPermission(),
            pendingQuestionId = (chat?.pendingInteraction as? PendingInteraction.Question)?.id,
            pendingQuestions = (chat?.pendingInteraction as? PendingInteraction.Question)?.questions.orEmpty(),
            draft = draft,
            turnFiles = chat?.visibleTurnFiles.orEmpty(),
            toolCalls = chat?.let { current ->
                current.toolCalls.map { call ->
                    val status = when {
                        call.id in current.failedToolIds -> ToolStatus.Error
                        call.id in current.completedToolIds -> ToolStatus.Success
                        current.running -> ToolStatus.Running
                        else -> ToolStatus.Waiting
                    }
                    ToolBlock(
                        id = call.id,
                        kind = toolKind(call.name),
                        title = toolTitle(call.name),
                        // 入参只透传了 file_path；拿不到路径时用状态文案，
                        // 不伪造命令/结果，也不把工具名重复写两遍。
                        subtitle = call.path ?: when (status) {
                            ToolStatus.Running -> "运行中…"
                            ToolStatus.Success -> "已完成"
                            ToolStatus.Error -> "执行失败"
                            ToolStatus.Waiting -> "未返回结果"
                        },
                        status = status,
                    )
                }
            }.orEmpty(),
        )
    }

    /** 工具名 → 移动端图标分类；未知工具统一走 Other，不把它归到 Bash。 */
    private fun toolKind(name: String): ToolKind = when (name.lowercase()) {
        "read", "notebookread" -> ToolKind.Read
        "grep", "glob", "search", "websearch", "webfetch", "ls", "list" -> ToolKind.Search
        "bash", "shell", "terminal", "execute" -> ToolKind.Bash
        "edit", "write", "multiedit", "notebookedit", "str_replace_editor" -> ToolKind.Edit
        else -> ToolKind.Other
    }

    /** 卡片标题说人话；没有路径时用工具名当副标题，保持信息诚实。 */
    private fun toolTitle(name: String): String = when (name.lowercase()) {
        "read", "notebookread" -> "读取文件"
        "write" -> "写入文件"
        "edit", "multiedit", "notebookedit", "str_replace_editor" -> "修改文件"
        "bash", "shell", "terminal", "execute" -> "执行命令"
        "grep", "glob" -> "搜索内容"
        "search", "websearch" -> "搜索网页"
        "webfetch" -> "抓取网页"
        else -> name
    }

    fun sessionListState(
        sessions: List<SessionSummary>,
        workspaces: List<WorkspaceSummary>,
        currentSessionId: String?,
        query: String,
        online: Boolean,
        runningSessionIds: Set<String> = emptySet(),
        deviceLabel: String? = null,
        endpoint: String? = null,
        refreshing: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): SessionListState {
        val groups = SessionCatalog.groups(
            sessions = sessions,
            workspaces = workspaces,
            query = query,
            runningIds = runningSessionIds,
        )
        val projectNames = workspaces.filter { it.isVisible }.associate { it.id to it.displayName }
        return SessionListState(
            projects = groups.map { group ->
                ProjectGroup(
                    id = group.id,
                    name = group.name,
                    branchLabel = null,
                    kind = when (group.kind) {
                        com.profer.pocket.data.SessionGroupKind.Pinned -> SessionGroupKind.Pinned
                        com.profer.pocket.data.SessionGroupKind.Project -> SessionGroupKind.Project
                        com.profer.pocket.data.SessionGroupKind.Unassigned -> SessionGroupKind.Unassigned
                    },
                    workspaceId = group.workspaceId,
                    sessions = group.sessions.map { session ->
                        sessionItem(
                            session = session,
                            currentSessionId = currentSessionId,
                            running = session.isActive || session.id in runningSessionIds,
                            now = now,
                            projectName = session.workspaceId?.let(projectNames::get),
                        )
                    },
                )
            },
            query = query,
            connected = online,
            deviceLabel = deviceLabel ?: when {
                !online -> "未连接"
                !endpoint.isNullOrBlank() -> "${hostOf(endpoint)} · 已连接"
                else -> "Mac · 已连接"
            },
            currentSessionId = currentSessionId,
            refreshing = refreshing,
            hasProjects = workspaces.any { it.isVisible },
        )
    }

    private fun PendingInteraction.toUiPermission(): PermissionRequest? = when (this) {
        is PendingInteraction.Permission -> PermissionRequest(
            id = id,
            title = "需要执行许可",
            description = description.ifBlank { "Agent 请求使用 $toolName" },
            command = command,
            contextLabel = buildString {
                append("来自 ")
                append(toolName.ifBlank { "Agent" })
                if (!dangerLevel.isNullOrBlank()) {
                    append(" · 风险 ")
                    append(dangerLevel)
                }
            },
        )

        is PendingInteraction.PlanApproval -> PermissionRequest(
            id = id,
            title = "计划待审批",
            description = summary?.takeIf { it.isNotBlank() } ?: "Agent 已完成计划，等待你的审批",
            command = null,
            contextLabel = "来自 计划模式",
        )

        is PendingInteraction.Question -> null
    }

    /** 运行时注入的内部消息不渲染成用户气泡（quoted_context 等）。 */
    private fun isInternalUserText(text: String): Boolean {
        val trimmed = text.trimStart()
        return INTERNAL_USER_PREFIXES.any { trimmed.startsWith(it) }
    }

    private val INTERNAL_USER_PREFIXES = listOf(
        "<quoted_context",
        "<system-reminder",
        "<local-command",
        "<command-name",
    )

    private fun permissionLabel(mode: String?): String = when (mode) {
        "auto" -> "自动"
        "plan" -> "计划"
        "bypassPermissions" -> "放行"
        null, "" -> "执行"
        else -> mode
    }

    private fun shortModelLabel(modelId: String?): String {
        if (modelId.isNullOrBlank()) return "Profer"
        return modelId.substringAfterLast('/').substringAfterLast(':').ifBlank { modelId }
    }

    private fun hostOf(endpoint: String): String = try {
        URI(endpoint).host.orEmpty()
    } catch (_: Exception) {
        ""
    }

    fun conversationListState(
        conversations: List<ConversationSummary>,
        activeId: String?,
        query: String,
        refreshing: Boolean,
        now: Long = System.currentTimeMillis(),
    ): ConversationListState {
        val keyword = query.trim()
        val items = conversations
            .asSequence()
            .filterNot { it.archived }
            .filter { keyword.isBlank() || it.displayTitle.contains(keyword, ignoreCase = true) }
            .sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })
            .map { conversation ->
                ConversationItem(
                    id = conversation.id,
                    title = conversation.displayTitle,
                    timeLabel = relativeTime(conversation.updatedAt, now),
                    pinned = conversation.pinned,
                    selected = conversation.id == activeId,
                )
            }
            .toList()
        return ConversationListState(items = items, query = query, refreshing = refreshing)
    }

    fun conversationUiState(
        conversation: ConversationSummary?,
        messages: List<DomainChatMessage>,
        stream: ChatStream?,
        draft: String,
        status: ConnectionStatus,
        queuedSends: Int = 0,
    ): ConversationUiState = ConversationUiState(
        conversationId = conversation?.id,
        title = conversation?.displayTitle.orEmpty(),
        channelId = conversation?.channelId,
        modelId = conversation?.modelId,
        messages = messages.map { message ->
            if (message.isUser) {
                ConversationMessage.User(
                    id = message.id,
                    text = message.content,
                    meta = timeLabel(message.createdAt),
                )
            } else {
                ConversationMessage.Assistant(
                    id = message.id,
                    text = message.content,
                    reasoning = message.reasoning,
                    error = message.error,
                    stopped = message.stopped,
                )
            }
        },
        streamText = stream?.text.orEmpty(),
        streamReasoning = stream?.reasoning.orEmpty(),
        streaming = stream?.running == true,
        error = stream?.error,
        draft = draft,
        offline = status !is ConnectionStatus.Connected,
        offlineLabel = offlineLabel(status, queuedSends),
    )

    private fun timeLabel(timestamp: Long): String =
        if (timestamp <= 0) "" else SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(timestamp))

    private fun sessionItem(
        session: SessionSummary,
        currentSessionId: String?,
        running: Boolean,
        now: Long,
        projectName: String?,
    ): SessionItem {
        val minute = now / 60_000
        val key = SessionItemKey(session, currentSessionId, minute, projectName)
        val cached = synchronized(sessionCacheLock) { sessionItemCache[key] }
        if (cached != null) {
            return if (cached.running == running) cached else cached.copy(running = running)
        }
        val draftTitle = session.title.takeIf { it.isNotBlank() && it != "新 Agent 会话" }
        val item = SessionItem(
            id = session.id,
            title = when {
                session.isDraft -> draftTitle ?: "新会话"
                else -> session.title.ifBlank { "未命名会话" }
            },
            timeLabel = if (session.isDraft) "草稿" else relativeTime(session.updatedAt, now),
            running = running,
            // 当前正打开的会话不显示未读点：用户已经看过了。
            unread = session.isUnread && session.id != currentSessionId,
            pinned = session.isPinned,
            draft = session.isDraft,
            workspaceId = session.workspaceId,
            projectName = projectName,
        )
        synchronized(sessionCacheLock) { sessionItemCache[key] = item }
        return item
    }

    private fun relativeTime(timestamp: Long, now: Long): String {
        if (timestamp <= 0) return ""
        val diff = (now - timestamp).coerceAtLeast(0)
        // 会话行是单行布局，时间用短标签，避免把标题挤掉。
        return when {
            diff < 60_000 -> "刚刚"
            diff < 3_600_000 -> "${diff / 60_000} 分"
            diff < 86_400_000 -> "${diff / 3_600_000} 小时"
            diff < 7 * 86_400_000L -> "${diff / 86_400_000} 天"
            else -> dayFormat.get()!!.format(Date(timestamp))
        }
    }
}
