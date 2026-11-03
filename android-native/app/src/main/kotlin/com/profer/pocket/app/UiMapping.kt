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
import com.profer.pocket.chat.cachedRichBlocks
import com.profer.pocket.data.SessionCatalog
import com.profer.pocket.connect.ConnectionPhase
import com.profer.pocket.connect.ConnectionUiState
import com.profer.pocket.connect.DeviceUi
import com.profer.pocket.connect.PairingUi
import com.profer.pocket.data.PocketRepository.PairingState
import com.profer.pocket.remote.EndpointScope
import com.profer.pocket.remote.RemoteDevice
import com.profer.pocket.remote.TransportMode
import com.profer.pocket.remote.TrustMode
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

    private val clockFormat = ThreadLocal.withInitial {
        SimpleDateFormat("HH:mm", Locale.getDefault())
    }

    /**
     * 消息投影缓存（只保留当前会话）。
     *
     * 流式 token 每 60ms 到一次，[chatState] 会把整张消息表重算一遍：每条历史消息都要
     * 重新分块 Markdown、重拼时间标签。几百条消息 × 每秒十几次 = 持续的垃圾分配，
     * 滚动和左右侧滑时被 GC 打断。这里按「entry 引用 + 影响投影的上下文」命中，
     * 一次 token 只重建真正变化的那条（通常是正在流式的最后一条）。
     */
    private class MessageProjection(
        val entry: ChatEntry,
        val context: ProjectionContext,
        val message: ChatMessage,
    )

    /** 影响单条投影结果的外部状态；工具 id 集合只增不改，引用相同即内容相同。 */
    private class ProjectionContext(
        val online: Boolean,
        val running: Boolean,
        val failedToolIds: Set<String>,
        val completedToolIds: Set<String>,
    ) {
        fun matches(other: ProjectionContext): Boolean =
            online == other.online &&
                running == other.running &&
                failedToolIds === other.failedToolIds &&
                completedToolIds === other.completedToolIds
    }

    private val projectionLock = Any()
    private var projectionSessionId: String? = null
    private val messageProjections = HashMap<String, MessageProjection>()
    private val liveEntryIds = HashSet<String>()
    private var lastProjectedMessages: List<ChatMessage> = emptyList()

    /** Chat 模式消息投影缓存：与 [messageProjections] 同因同解。 */
    private class ConversationProjection(
        val source: DomainChatMessage,
        val message: ConversationMessage,
    )

    private val conversationLock = Any()
    private var conversationProjectionId: String? = null
    private val conversationProjections = HashMap<String, ConversationProjection>()
    private val liveConversationIds = HashSet<String>()
    private var lastProjectedConversation: List<ConversationMessage> = emptyList()

    fun connectionState(
        status: ConnectionStatus,
        url: String,
        port: String = "",
        token: String,
        pin: String = "",
        hasSavedToken: Boolean,
        latencyMs: Int? = null,
        devices: List<RemoteDevice> = emptyList(),
        activeDeviceId: String? = null,
        pairing: PairingState = PairingState.Idle,
        endpointFailures: Map<String, String> = emptyMap(),
        registryError: String? = null,
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
                // 鉴权失败既可能是 Token 失效，也可能是证书轮换；有具体原因就照实说。
                com.profer.pocket.model.OfflineCause.Unauthorized -> status.message ?: "连接凭据已失效，请重新扫码或输入 Token"
                com.profer.pocket.model.OfflineCause.Unreachable -> status.message ?: "连接失败"
                com.profer.pocket.model.OfflineCause.MissingCredentials -> status.message ?: "请填写连接 Token"
                com.profer.pocket.model.OfflineCause.UserDisconnected -> null
            }

            is ConnectionStatus.Reconnecting -> "连接中断，正在重连…"
            else -> null
        }
        val host = hostOf(endpoint)
        // 已连接卡显示的是「设备名」：优先当前绑定设备的 profile.deviceName；
        // 手动 legacy 连接没有设备资料，才退回 endpoint host。
        val activeDevice = devices.firstOrNull { it.profile.deviceId == activeDeviceId }
        // 扫码设备显示 profile.deviceName；手动 legacy 的注册名只是「手动连接」，仍退回 host。
        val deviceName = activeDevice
            ?.takeIf { it.transportMode != TransportMode.LEGACY_QUERY }
            ?.profile?.deviceName?.takeIf { it.isNotBlank() }
            ?: host.ifBlank { "电脑" }
        return ConnectionUiState(
            phase = phase,
            url = url,
            port = port,
            token = token,
            pin = pin,
            hasSavedToken = hasSavedToken,
            deviceName = deviceName,
            deviceAddress = endpoint,
            latencyMs = latencyMs ?: 0,
            daemonVersion = "远程",
            networkLabel = if (endpoint.startsWith("wss://")) "加密网络" else "局域网",
            protocolLabel = "Pocket WS v1",
            lastSyncLabel = if (status is ConnectionStatus.Connected) "已连接" else "—",
            errorMessage = registryError ?: error,
            devices = devices.map { deviceUi(it, activeDeviceId) },
            activeDeviceId = activeDeviceId,
            trustLabel = activeDevice?.let(::trustLabel).orEmpty(),
            pairing = when (pairing) {
                is PairingState.Failed -> PairingUi.Failed(pairing.message)
                is PairingState.Succeeded -> PairingUi.Succeeded(pairing.deviceName)
                PairingState.Pairing -> PairingUi.Pairing
                PairingState.Idle -> PairingUi.Idle
            },
            endpointFailures = endpointFailures.map { (endpoint, reason) -> "${endpointLabel(endpoint)}：$reason" },
        )
    }

    private fun deviceUi(device: RemoteDevice, activeDeviceId: String?): DeviceUi {
        val endpoint = device.profile.endpoints.firstOrNull()
        return DeviceUi(
            id = device.profile.deviceId,
            // 手动设备一律叫「手动连接」，列表里两台就重名了；用主机名代替，地址仍单列一行。
            name = device.profile.deviceName
                .takeIf { device.transportMode != TransportMode.LEGACY_QUERY }
                ?.takeIf { it.isNotBlank() }
                ?: hostOf(endpoint?.url.orEmpty()).ifBlank { "手动连接" },
            address = endpoint?.url.orEmpty(),
            scopeLabel = if (endpoint?.scope == EndpointScope.PUBLIC) "公网" else "局域网",
            trustLabel = trustLabel(device),
            isActive = device.profile.deviceId == activeDeviceId,
            isManual = device.transportMode == TransportMode.LEGACY_QUERY,
        )
    }

    private fun trustLabel(device: RemoteDevice): String = when {
        device.transportMode == TransportMode.LEGACY_QUERY -> "手动 · 系统证书"
        device.profile.endpoints.any { it.trustMode == TrustMode.PINNED } -> "证书固定"
        device.profile.endpoints.any { it.scope == EndpointScope.PUBLIC } -> "系统证书"
        else -> "局域网"
    }

    private fun endpointLabel(url: String): String = when {
        url.contains("://") -> url.substringAfter("://").substringBefore("/ws")
        else -> url
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
        presets: List<com.profer.pocket.model.AgentPresetSummary> = emptyList(),
        /** 当前模型支持的思考档位；null = 拿不到能力，回退强度档。 */
        capability: com.profer.pocket.model.ReasoningCapability? = null,
        capabilityLoading: Boolean = false,
        now: Long = System.currentTimeMillis(),
    ): ChatUiState {
        val online = status is ConnectionStatus.Connected
        val summary = sessions.firstOrNull { it.id == sessionId }
        // 子会话（委派 / 探索分支）：顶栏给一条返回母会话的面包屑，否则用户看不出自己在哪一层。
        val parentSession = summary?.relatedParentSessionId?.let { parentId ->
            sessions.firstOrNull { it.id == parentId }
        }
        val subSessionLabel = summary?.takeIf { it.isSubSession }?.let { sub ->
            val role = subSessionLabel(sub) ?: "子会话"
            val parentTitle = parentSession?.title?.takeIf { it.isNotBlank() }
            if (parentTitle != null) "$role · 来自 $parentTitle" else role
        }
        val title = chat?.title?.takeIf { it.isNotBlank() }
            ?: summary?.title?.takeIf { it.isNotBlank() }
            ?: "新会话"
        val workspaceId = chat?.workspaceId ?: summary?.workspaceId
        val workspaceName = workspaces.firstOrNull { it.id == workspaceId }?.name
        val channelId = chat?.channelId ?: summary?.channelId
        val modelId = chat?.modelId ?: summary?.modelId
        val permissionMode = chat?.permissionMode ?: summary?.permissionMode
        // 思考强度：档位来自会话覆盖，默认档来自预设 effort；能力由电脑端按模型算。
        val reasoning = com.profer.pocket.model.ReasoningResolver.resolve(
            capability = capability,
            sessionLevel = summary?.thinkingLevel,
            sessionEffort = summary?.agentEffort,
            presetEffort = presets.firstOrNull { it.id == summary?.presetId }?.effort,
        )
        val messages = projectedMessages(
            sessionId = sessionId,
            chat = chat,
            sessions = sessions,
            online = online,
        )
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
            thinkingLabel = reasoning.label,
            thinkingSourceLabel = reasoning.sourceLabel,
            thinkingOptions = reasoning.options,
            thinkingSelectedValue = reasoning.selectedValue,
            thinkingUsesCapability = reasoning.usesCapability,
            // 只有「还不知道档位」才算加载中：已知能力时后台刷新不该把选项禁用掉。
            thinkingLoading = capabilityLoading && capability == null,
            contextUsage = chat?.contextUsage,
            contextWindow = chat?.contextWindow,
            messages = messages,
            streaming = chat?.running == true || chat?.messages?.any { it is ChatEntry.Assistant && it.streaming } == true,
            offline = !online,
            offlineLabel = offlineLabel(status, queuedSends),
            pendingPermission = chat?.pendingInteraction?.toUiPermission(),
            pendingQuestionId = (chat?.pendingInteraction as? PendingInteraction.Question)?.id,
            pendingQuestions = (chat?.pendingInteraction as? PendingInteraction.Question)?.questions.orEmpty(),
            turnFiles = chat?.visibleTurnFiles.orEmpty(),
            parentSessionId = parentSession?.id ?: summary?.relatedParentSessionId,
            subSessionLabel = subSessionLabel,
        )
    }

    /**
     * 整张消息表的投影：命中缓存的历史消息直接复用上一次的 [ChatMessage]，
     * 只有内容变化的那条重新分块/格式化。表本身没变时连 List 实例都复用，
     * 让 StateFlow 的 equals 去重和 `remember(messages)` 的派生状态都不必重跑。
     */
    private fun projectedMessages(
        sessionId: String?,
        chat: SessionChat?,
        sessions: List<SessionSummary>,
        online: Boolean,
    ): List<ChatMessage> {
        val entries = chat?.messages.orEmpty()
        val context = ProjectionContext(
            online = online,
            running = chat?.running == true,
            failedToolIds = chat?.failedToolIds.orEmpty(),
            completedToolIds = chat?.completedToolIds.orEmpty(),
        )
        synchronized(projectionLock) {
            if (projectionSessionId != sessionId) {
                messageProjections.clear()
                lastProjectedMessages = emptyList()
                projectionSessionId = sessionId
            }
            val out = ArrayList<ChatMessage>(entries.size + 1)
            liveEntryIds.clear()
            entries.forEach { entry ->
                liveEntryIds.add(entry.id)
                // 来源卡要按会话标题现算，条数很少，不进缓存。
                if (entry is ChatEntry.Source) {
                    projectEntry(entry, chat, sessions, online)?.let(out::add)
                    return@forEach
                }
                val cached = messageProjections[entry.id]
                if (cached != null && cached.entry === entry && cached.context.matches(context)) {
                    out.add(cached.message)
                    return@forEach
                }
                val message = projectEntry(entry, chat, sessions, online)
                if (message == null) {
                    messageProjections.remove(entry.id)
                } else {
                    messageProjections[entry.id] = MessageProjection(entry, context, message)
                    out.add(message)
                }
            }
            // 重发/截断后旧 id 不会再出现：缓存只保留当前表里的，不跟着长会话只增不减。
            if (messageProjections.size > out.size) {
                messageProjections.keys.retainAll(liveEntryIds)
            }
            (chat?.pendingInteraction as? PendingInteraction.Question)?.let { question ->
                out.add(questionNotice(question))
            }
            val previous = lastProjectedMessages
            val unchanged = previous.size == out.size && out.indices.all { out[it] === previous[it] }
            val result = if (unchanged) previous else out
            lastProjectedMessages = result
            return result
        }
    }

    /** 单条 entry → UI 模型；内部注入的用户文本不上屏（与桌面同口径）。 */
    private fun projectEntry(
        entry: ChatEntry,
        chat: SessionChat?,
        sessions: List<SessionSummary>,
        online: Boolean,
    ): ChatMessage? = when (entry) {
        is ChatEntry.User -> if (isInternalUserText(entry.text)) {
            null
        } else {
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
            )
        }

        is ChatEntry.Assistant -> ChatMessage.Assistant(
            id = entry.id,
            // 有思考就渲染折叠卡：运行中显示「思考中…」，结束后回到「思考过程」。
            thinking = entry.thinking?.takeIf { it.isNotBlank() }?.let { body ->
                ThinkingBlock(
                    durationLabel = if (entry.streaming) "思考中…" else "思考过程",
                    body = body,
                )
            },
            // 工具卡恢复逐条显示：挂在这条助手消息自己身上，不再汇总成图标条。
            tools = if (chat == null) {
                emptyList()
            } else {
                entry.toolCalls.map { call -> toolBlock(call, chat) }
            },
            blocks = cachedRichBlocks(entry.text),
            streaming = entry.streaming,
            images = entry.images,
        )

        is ChatEntry.Source -> if (isInternalUserText(entry.text)) {
            null
        } else {
            ChatMessage.Source(
                id = entry.id,
                authorLabel = entry.authorLabel,
                // 只展示可读来源；找不到来源会话时说明不可访问，不回显内部 ID。
                sourceLabel = when (entry.sourceType) {
                    "session" -> sessions.firstOrNull { it.id == entry.sourceId }
                        ?.title?.takeIf { it.isNotBlank() }
                        ?.let { "来自会话「$it」" }
                        ?: "来源会话不可访问"

                    "automation" -> "来自定时任务"
                    "task" -> "来自上级任务"
                    "request" -> "来自外部请求"
                    "message" -> "关联到来源消息"
                    else -> null
                },
                text = entry.text,
                scheduled = entry.scheduled,
                timeLabel = timeLabel(entry.at),
            )
        }

        is ChatEntry.Notice -> ChatMessage.Notice(
            id = entry.id,
            text = entry.text,
            tone = when (entry.tone) {
                DomainNoticeTone.Info -> NoticeTone.Info
                DomainNoticeTone.Success -> NoticeTone.Success
                DomainNoticeTone.Warning -> NoticeTone.Warning
            },
        )
    }

    private fun questionNotice(question: PendingInteraction.Question): ChatMessage.Notice {
        val first = question.questions.firstOrNull()?.question
        return ChatMessage.Notice(
            id = "question-${question.id}",
            text = if (first.isNullOrBlank()) {
                "Agent 在等待你的回答（请在电脑端回答）"
            } else {
                "Agent 在等待你的回答：$first"
            },
            tone = NoticeTone.Info,
        )
    }

    /**
     * 单条工具调用 → 卡片模型：图标分类与状态。
     * 投影目前只透传 file_path，input/output/error 恒为 null，卡片因此不可展开；
     * 字段契约见 docs/tool-detail-projection-2026-11-02.md。
     */
    private fun toolBlock(
        call: com.profer.pocket.model.ToolCall,
        chat: SessionChat,
    ): ToolBlock {
        val status = when {
            call.id in chat.failedToolIds -> ToolStatus.Error
            call.id in chat.completedToolIds -> ToolStatus.Success
            chat.running -> ToolStatus.Running
            else -> ToolStatus.Waiting
        }
        return ToolBlock(
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
        waitingSessionIds: Set<String> = emptySet(),
        deviceLabel: String? = null,
        endpoint: String? = null,
        refreshing: Boolean = false,
        pinnedProjectIds: Set<String> = emptySet(),
        now: Long = System.currentTimeMillis(),
    ): SessionListState {
        val groups = SessionCatalog.groups(
            sessions = sessions,
            workspaces = workspaces,
            query = query,
            runningIds = runningSessionIds,
            pinnedProjectIds = pinnedProjectIds,
        )
        val projectNames = workspaces.filter { it.isVisible }.associate { it.id to it.displayName }
        return SessionListState(
            projects = groups.map { group ->
                ProjectGroup(
                    id = group.id,
                    name = group.name,
                    branchLabel = null,
                    kind = when (group.kind) {
                        com.profer.pocket.data.SessionGroupKind.Project -> SessionGroupKind.Project
                        com.profer.pocket.data.SessionGroupKind.Unassigned -> SessionGroupKind.Unassigned
                    },
                    workspaceId = group.workspaceId,
                    pinned = group.pinned,
                    // 根行带自己的子会话：抽屉不再把委派子会话平铺成「陌生会话」。
                    sessions = group.items.map { item ->
                        sessionItem(
                            session = item.session,
                            currentSessionId = currentSessionId,
                            running = item.session.isActive ||
                                item.session.id in runningSessionIds ||
                                item.session.isDelegationRunning,
                            waiting = item.session.id in waitingSessionIds,
                            now = now,
                            projectName = item.session.workspaceId?.let(projectNames::get),
                        ).copy(
                            children = item.children.map { child ->
                                sessionItem(
                                    session = child,
                                    currentSessionId = currentSessionId,
                                    running = child.isActive ||
                                        child.id in runningSessionIds ||
                                        child.isDelegationRunning,
                                    waiting = child.id in waitingSessionIds,
                                    now = now,
                                    projectName = child.workspaceId?.let(projectNames::get),
                                )
                            },
                        )
                    },
                )
            },
            query = query,
            connected = online,
            deviceLabel = deviceLabel ?: when {
                !online -> "未连接"
                !endpoint.isNullOrBlank() -> "${hostOf(endpoint)} · 已连接"
                else -> "电脑 · 已连接"
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
        status: ConnectionStatus,
        queuedSends: Int = 0,
    ): ConversationUiState = ConversationUiState(
        conversationId = conversation?.id,
        title = conversation?.displayTitle.orEmpty(),
        channelId = conversation?.channelId,
        modelId = conversation?.modelId,
        messages = projectedConversationMessages(conversation?.id, messages),
        streamText = stream?.text.orEmpty(),
        streamReasoning = stream?.reasoning.orEmpty(),
        streaming = stream?.running == true,
        error = stream?.error,
        offline = status !is ConnectionStatus.Connected,
        offlineLabel = offlineLabel(status, queuedSends),
    )

    /**
     * Chat 模式消息投影：与 [projectedMessages] 同因同解。
     * 每次 token 都重建全部气泡（含每条时间标签的 SimpleDateFormat）会持续制造垃圾。
     */
    private fun projectedConversationMessages(
        conversationId: String?,
        messages: List<DomainChatMessage>,
    ): List<ConversationMessage> {
        synchronized(conversationLock) {
            if (conversationProjectionId != conversationId) {
                conversationProjections.clear()
                lastProjectedConversation = emptyList()
                conversationProjectionId = conversationId
            }
            val out = ArrayList<ConversationMessage>(messages.size)
            liveConversationIds.clear()
            messages.forEach { message ->
                liveConversationIds.add(message.id)
                val cached = conversationProjections[message.id]
                if (cached != null && cached.source === message) {
                    out.add(cached.message)
                    return@forEach
                }
                val projected = if (message.isUser) {
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
                conversationProjections[message.id] = ConversationProjection(message, projected)
                out.add(projected)
            }
            if (conversationProjections.size > out.size) {
                conversationProjections.keys.retainAll(liveConversationIds)
            }
            val previous = lastProjectedConversation
            val unchanged = previous.size == out.size && out.indices.all { out[it] === previous[it] }
            val result = if (unchanged) previous else out
            lastProjectedConversation = result
            return result
        }
    }

    private fun timeLabel(timestamp: Long): String =
        if (timestamp <= 0) "" else clockFormat.get()!!.format(Date(timestamp))

    private fun sessionItem(
        session: SessionSummary,
        currentSessionId: String?,
        running: Boolean,
        waiting: Boolean,
        now: Long,
        projectName: String?,
    ): SessionItem {
        val minute = now / 60_000
        val key = SessionItemKey(session, currentSessionId, minute, projectName)
        val cached = synchronized(sessionCacheLock) { sessionItemCache[key] }
        if (cached != null) {
            // 运行态/等人态不缓存在 key 里（每 60ms 变一次），命中后直接覆写。
            return if (cached.running == running && cached.waiting == waiting) {
                cached
            } else {
                cached.copy(running = running, waiting = waiting)
            }
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
            waiting = waiting,
            // 当前正打开的会话不显示未读状态：用户已经看过了。
            unread = session.isUnread && session.id != currentSessionId,
            pinned = session.isPinned,
            draft = session.isDraft,
            workspaceId = session.workspaceId,
            projectName = projectName,
            // 子会话以根行身份出现时（搜索）靠这两个字段保留层级信息。
            isSubSession = session.isSubSession,
            roleLabel = subSessionLabel(session),
            delegationStatus = session.delegationStatus,
            isExploration = session.isExplorationChild,
            sourcedByAutomation = session.isAutomationSourced,
        )
        synchronized(sessionCacheLock) { sessionItemCache[key] = item }
        return item
    }

    /**
     * 子会话角色标签。委派角色直接用桌面下发值，探索分支单独一档；
     * 不认识的角色不回退成英文，统一叫「子会话」，避免抽屉里出现 role 字面量。
     */
    private fun subSessionLabel(session: SessionSummary): String? = when {
        session.isExplorationChild -> "探索分支"
        !session.isSubSession -> null
        else -> when (session.delegationRole) {
            "explore" -> "探索"
            "research" -> "调研"
            "implement" -> "实现"
            "review" -> "审查"
            "custom" -> "协作"
            else -> "子会话"
        }
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
