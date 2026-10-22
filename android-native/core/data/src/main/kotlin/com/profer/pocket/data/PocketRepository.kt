package com.profer.pocket.data

import com.profer.pocket.data.net.OutgoingMessage
import com.profer.pocket.data.net.PocketCommandException
import com.profer.pocket.data.net.PocketConnection
import com.profer.pocket.data.net.PocketDisconnectedException
import com.profer.pocket.data.net.PocketTimeoutException
import com.profer.pocket.data.net.SendOutcome
import com.profer.pocket.data.protocol.AgentPayload
import com.profer.pocket.data.protocol.ChatReducer
import com.profer.pocket.data.protocol.EndpointParser
import com.profer.pocket.data.protocol.ChatCodec
import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.data.protocol.HistoryMapper
import com.profer.pocket.data.protocol.InboundFrame
import com.profer.pocket.data.protocol.SessionSignal
import com.profer.pocket.data.settings.SettingsStore
import com.profer.pocket.data.store.CredentialStore
import com.profer.pocket.data.store.SessionSnapshotStore
import com.profer.pocket.remote.DeviceRegistry
import com.profer.pocket.remote.PairingClient
import com.profer.pocket.remote.PairingException
import com.profer.pocket.remote.RemoteDevice
import com.profer.pocket.remote.RemoteRegistryException
import com.profer.pocket.remote.TransportMode
import com.profer.pocket.model.AgentPresetSummary
import com.profer.pocket.model.AgentAttachment
import com.profer.pocket.model.ChannelSummary
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.ChatMessage
import com.profer.pocket.model.ChatToolSummary
import com.profer.pocket.model.ChatStream
import com.profer.pocket.model.ConversationSummary
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.NoticeTone
import com.profer.pocket.model.NotificationKind
import com.profer.pocket.model.OfflineCause
import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.PocketNotification
import com.profer.pocket.model.PocketSettings
import com.profer.pocket.model.RemoteFilePreview
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.WorkspaceSummary
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.util.UUID

/**
 * 移动端数据层唯一入口。
 *
 * - 连接状态只来自 [connection]，页面不持有第二条链路；
 * - 会话事件在这里归约成 [SessionChat]，流式 token 以 60ms 窗口合并后再推给 UI；
 * - 工具调用只保留次数统计（见 ChatReducer）；
 * - 会话列表/游标/凭据/设置各自持久化，冷启动先展示离线快照。
 */
class PocketRepository(
    private val connection: PocketConnection,
    /** 旧版单 endpoint + token 存储：只作为迁移来源，新连接一律走 [registry]。 */
    private val credentials: CredentialStore,
    private val registry: DeviceRegistry,
    private val pairing: PairingClient,
    private val snapshots: SessionSnapshotStore,
    private val settingsStore: SettingsStore,
    private val scope: CoroutineScope,
    private val clock: () -> Long = System::currentTimeMillis,
    private val streamMergeWindowMs: Long = 60,
    private val newId: () -> String = { UUID.randomUUID().toString() },
    /** 冷启动快照读取所用调度器；测试注入可控调度器。 */
    private val snapshotDispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val reducer = ChatReducer(clock)
    private val stateMutex = Mutex()

    /**
     * 冷启动快照在后台读取：875 条会话的 JSON 解析属于磁盘 IO，
     * 不能堵在 Application.onCreate 的主线程上。服务端目录先到时以服务端为准。
     */
    private var serverCatalogLoaded = false

    private val _sessions = MutableStateFlow<List<SessionSummary>>(emptyList())
    val sessions: StateFlow<List<SessionSummary>> = _sessions.asStateFlow()

    /** 目录是否已有可信内容（本地快照或服务端列表），用于区分「还没读到」与「真的为空」。 */
    private val _catalogLoaded = MutableStateFlow(false)
    val catalogLoaded: StateFlow<Boolean> = _catalogLoaded.asStateFlow()

    private val _workspaces = MutableStateFlow<List<WorkspaceSummary>>(emptyList())
    val workspaces: StateFlow<List<WorkspaceSummary>> = _workspaces.asStateFlow()

    private val _channels = MutableStateFlow<List<ChannelSummary>>(emptyList())
    val channels: StateFlow<List<ChannelSummary>> = _channels.asStateFlow()

    private val _presets = MutableStateFlow<List<AgentPresetSummary>>(emptyList())
    val presets: StateFlow<List<AgentPresetSummary>> = _presets.asStateFlow()

    /** 预设按工作区隔离；记录当前缓存对应的 slug，避免跨项目串用。 */
    private var presetsWorkspaceSlug: String? = null

    private val _chatTools = MutableStateFlow<List<ChatToolSummary>>(emptyList())
    val chatTools: StateFlow<List<ChatToolSummary>> = _chatTools.asStateFlow()

    private val _chats = MutableStateFlow<Map<String, SessionChat>>(emptyMap())
    val chats: StateFlow<Map<String, SessionChat>> = _chats.asStateFlow()

    private val _conversations = MutableStateFlow<List<ConversationSummary>>(emptyList())
    val conversations: StateFlow<List<ConversationSummary>> = _conversations.asStateFlow()

    private val _conversationMessages = MutableStateFlow<Map<String, List<ChatMessage>>>(emptyMap())
    val conversationMessages: StateFlow<Map<String, List<ChatMessage>>> = _conversationMessages.asStateFlow()

    /**
     * Chat 乐观消息（尚未被服务端历史确认）。
     *
     * chat_send_message 是异步执行：WS 先回 accepted，用户消息随后才写入 JSONL。
     * 这期间任何一次 openConversation 都可能读到「还没有这条用户消息」的历史；
     * 若直接整体覆盖，就会把刚发出的气泡吞掉（桌面已显示、手机端只剩助手回复）。
     */
    private val pendingConversationMessages = mutableMapOf<String, MutableList<ChatMessage>>()

    private val _chatStreams = MutableStateFlow<Map<String, ChatStream>>(emptyMap())
    val chatStreams: StateFlow<Map<String, ChatStream>> = _chatStreams.asStateFlow()

    private val _settings = MutableStateFlow(settingsStore.load())
    val settings: StateFlow<PocketSettings> = _settings.asStateFlow()

    private val _devices = MutableStateFlow(DeviceRegistry.Snapshot())
    val devices: StateFlow<DeviceRegistry.Snapshot> = _devices.asStateFlow()

    /** 注册表损坏 / 凭据不可用：UI 提示重新扫码，不静默清空。 */
    private val _registryError = MutableStateFlow<String?>(null)
    val registryError: StateFlow<String?> = _registryError.asStateFlow()

    private val _pairingState = MutableStateFlow<PairingState>(PairingState.Idle)
    val pairingState: StateFlow<PairingState> = _pairingState.asStateFlow()

    private val notificationChannel = Channel<PocketNotification>(Channel.UNLIMITED)
    val notifications: Flow<PocketNotification> = notificationChannel.receiveAsFlow()

    val connectionStatus: StateFlow<ConnectionStatus> = connection.status

    /** 待发送队列长度：断网时告诉用户还有几条消息在等。 */
    val pendingSendCount: StateFlow<Int> = connection.pendingSendCount

    /** 最近一次 ping 往返；未测到为 null，连接页据此显示「在线」还是「42ms」。 */
    val latencyMs: StateFlow<Int?> = connection.latencyMs

    /** 多端点最近一次失败原因（端点 URL → 原因），供连接页排障。 */
    val endpointFailures: StateFlow<Map<String, String>> = connection.endpointFailures

    private val pendingPartial = mutableMapOf<String, InboundFrame.AgentEvent>()
    private val flushJobs = mutableMapOf<String, Job>()
    private val outgoingSessionIds = mutableMapOf<String, String>()
    private val loadingSessions = mutableSetOf<String>()
    private val lastCompletionNotifyAt = mutableMapOf<String, Long>()
    private var snapshotJob: Job? = null

    /** 已删除会话的 revision 墓碑：迟到的 projection 不能把会话复活。 */
    private val deletedSessionRevisions = mutableMapOf<String, Int>()
    private var lastCatalogRefreshAt = 0L

    init {
        scope.launch {
            val loaded = withContext(snapshotDispatcher) { snapshots.load() }
            stateMutex.withLock {
                if (serverCatalogLoaded) return@withLock
                if (_sessions.value.isEmpty()) {
                    // 旧快照里可能还留着归档会话：移动端不展示归档，加载时直接丢弃。
                    _sessions.value = sortSessions(loaded.sessions.filterNot { it.isArchived })
                }
                if (_workspaces.value.isEmpty()) _workspaces.value = loaded.workspaces
                _catalogLoaded.value = true
            }
        }
        connection.autoReconnect = _settings.value.autoReconnect
        // 服务端连接资料刷新：信任断言在连接层已经通过，这里只负责落盘。
        scope.launch {
            connection.profileUpdates.collect { updated ->
                try {
                    _devices.value = registry.updateProfile(updated)
                    _registryError.value = null
                } catch (error: Exception) {
                    _registryError.value = error.message
                }
            }
        }
        scope.launch { connection.events.collect(::onAgentEvent) }
        scope.launch { connection.chatEvents.collect(::onChatEvent) }
        scope.launch { connection.resumeResults.collect(::onResumeResult) }
        scope.launch { connection.sendOutcomes.collect(::onSendOutcome) }
        scope.launch {
            var previous: ConnectionStatus? = null
            connection.status.collect { current ->
                val last = previous
                previous = current
                onConnectionStatus(last, current)
            }
        }
    }

    // ===== 生命周期 =====

    /**
     * 应用启动时调用：先迁移旧版单连接，再连当前设备；都没有则保持 Idle。
     *
     * 迁移失败不丢原配置：旧 CredentialStore 原样保留，仍可手动连接。
     */
    fun start() {
        refreshDevices()
        if (_devices.value.devices.isEmpty()) migrateLegacyConnection()
        val active = _devices.value.active()
        if (active == null) {
            val legacy = credentials.load()
            if (legacy != null) {
                connection.connect(legacy.endpoint, legacy.token)
                return
            }
            connection.reportConfigurationError(
                OfflineCause.MissingCredentials,
                _registryError.value ?: "还没有配置连接",
            )
            return
        }
        connectDevice(active)
    }

    /** 回前台时立即重连，不等退避定时器；长时间无入站帧的半死链路直接重建。 */
    fun onForeground() {
        connection.reconnectNow(force = true)
    }

    /** 预热凭据缓存：Keystore 首次解密是跨进程调用，不能落在主线程。 */
    fun preloadCredentials() {
        refreshDevices()
        runCatching { _devices.value.devices.forEach { registry.credential(it.profile.deviceId) } }
        credentials.load()
    }

    /**
     * 系统网络可用性（ConnectivityManager）变化。
     * 断网不再等 75s 心跳判死，恢复后也不再等退避定时器。
     */
    fun onNetworkChanged(available: Boolean) {
        connection.onNetworkChanged(available)
    }

    /** 返回当前设备的首个地址（不含 token），供连接页回显。 */
    fun savedEndpoint(): String? = _devices.value.active()?.profile?.endpoints?.firstOrNull()?.url

    /** 是否已有当前设备的凭据（只返回布尔值，不把凭据暴露给 UI）。 */
    fun hasSavedToken(): Boolean {
        val active = _devices.value.active() ?: return credentials.load()?.token?.isNotBlank() == true
        return runCatching { !registry.credential(active.profile.deviceId).isNullOrBlank() }.getOrDefault(false)
    }

    /**
     * 手动连接（局域网地址 + token）：登记为 `legacy-<uuid>` 设备，
     * 走旧的 `?token=` 私网路径，不改变任何已扫码绑定的信任锚。
     */
    fun connect(endpointInput: String, tokenInput: String?): Boolean {
        val normalized = EndpointParser.normalize(
            raw = endpointInput,
            fallbackToken = tokenInput?.trim()?.takeIf { it.isNotEmpty() },
        ) ?: run {
            connection.reportConfigurationError(OfflineCause.Unreachable, "地址格式不正确")
            return false
        }
        val existing = _devices.value.devices.firstOrNull {
            it.transportMode == TransportMode.LEGACY_QUERY &&
                it.profile.endpoints.firstOrNull()?.url == normalized.wsUrl
        }
        val legacy = credentials.load()
        val token = normalized.token
            ?: existing?.let { runCatching { registry.credential(it.profile.deviceId) }.getOrNull() }
            ?: legacy?.token?.takeIf { legacy.endpoint == normalized.wsUrl }
        if (token.isNullOrBlank()) {
            connection.reportConfigurationError(OfflineCause.MissingCredentials, "请填写连接 Token")
            return false
        }
        val profile = existing?.profile
            ?: runCatching { registry.manualProfile(normalized.wsUrl, "legacy-${UUID.randomUUID()}") }
                .getOrElse {
                    connection.reportConfigurationError(OfflineCause.Unreachable, it.message)
                    return false
                }
        try {
            _devices.value = registry.save(profile, token, TransportMode.LEGACY_QUERY)
            _registryError.value = null
        } catch (error: RemoteRegistryException) {
            _registryError.value = error.message
            connection.reportConfigurationError(OfflineCause.Unreachable, error.message)
            return false
        }
        connection.autoReconnect = _settings.value.autoReconnect
        connection.connect(normalized.wsUrl, token)
        return true
    }

    // ===== 设备与扫码配对 =====

    /**
     * 扫码配对：解析 QR → WS 配对 → 保存凭据 → 立即连接。
     * 结果通过 [pairingState] 上报，UI 不需要等待挂起函数。
     */
    fun pairWithQr(qrText: String) {
        if (_pairingState.value is PairingState.Pairing) return
        _pairingState.value = PairingState.Pairing
        scope.launch {
            try {
                val paired = pairing.pair(qrText)
                val snapshot = registry.save(paired.profile, paired.credential, TransportMode.V1)
                _devices.value = snapshot
                _registryError.value = null
                _pairingState.value = PairingState.Succeeded(paired.profile.deviceName)
                connection.autoReconnect = _settings.value.autoReconnect
                connection.connect(paired.profile, paired.credential)
            } catch (error: PairingException) {
                _pairingState.value = PairingState.Failed(error.message ?: "配对失败")
            } catch (error: RemoteRegistryException) {
                _pairingState.value = PairingState.Failed(error.message ?: "保存设备失败")
            } catch (error: CancellationException) {
                throw error
            } catch (error: Exception) {
                _pairingState.value = PairingState.Failed(error.message ?: "配对失败")
            }
        }
    }

    fun consumePairingState() {
        _pairingState.value = PairingState.Idle
    }

    fun selectDevice(deviceId: String) {
        val snapshot = try {
            registry.select(deviceId)
        } catch (error: RemoteRegistryException) {
            _registryError.value = error.message
            return
        }
        _devices.value = snapshot
        _registryError.value = null
        snapshot.active()?.let(::connectDevice)
    }

    fun removeDevice(deviceId: String) {
        val wasActive = _devices.value.activeId == deviceId
        val snapshot = try {
            registry.remove(deviceId)
        } catch (error: RemoteRegistryException) {
            _registryError.value = error.message
            return
        }
        _devices.value = snapshot
        // 移除的不是当前设备：保持现有链路，不打断正在使用的连接。
        if (!wasActive) return
        val next = snapshot.active()
        if (next == null) {
            connection.disconnect()
        } else {
            connectDevice(next)
        }
    }

    private fun connectDevice(device: RemoteDevice) {
        val credential = try {
            registry.credential(device.profile.deviceId)
        } catch (error: RemoteRegistryException) {
            _registryError.value = error.message
            null
        }
        if (credential.isNullOrBlank()) {
            connection.reportConfigurationError(OfflineCause.MissingCredentials, "凭据不可用，请重新扫码")
            return
        }
        connection.autoReconnect = _settings.value.autoReconnect
        when (device.transportMode) {
            TransportMode.V1 -> connection.connect(device.profile, credential)
            TransportMode.LEGACY_QUERY -> {
                val url = device.profile.endpoints.firstOrNull()?.url
                if (url.isNullOrBlank()) {
                    connection.reportConfigurationError(OfflineCause.Unreachable, "连接资料没有可用端点")
                } else {
                    connection.connect(url, credential)
                }
            }
        }
    }

    private fun migrateLegacyConnection() {
        val legacy = credentials.load() ?: return
        try {
            _devices.value = registry.migrateLegacy(legacy.endpoint, legacy.token)
            _registryError.value = null
            // 注册表已落盘才清理旧键：迁移失败时旧配置原样保留。
            credentials.clear()
        } catch (error: Exception) {
            _registryError.value = error.message
        }
    }

    private fun refreshDevices() {
        try {
            _devices.value = registry.load()
            _registryError.value = null
        } catch (error: RemoteRegistryException) {
            _registryError.value = error.message
        }
    }

    fun disconnect() {
        connection.disconnect()
    }

    fun updateSettings(transform: (PocketSettings) -> PocketSettings) {
        val next = transform(_settings.value)
        settingsStore.save(next)
        _settings.value = next
        connection.autoReconnect = next.autoReconnect
    }

    // ===== 会话 =====

    private suspend fun onConnectionStatus(previous: ConnectionStatus?, current: ConnectionStatus) {
        if (current !is ConnectionStatus.Connected) return
        if (previous is ConnectionStatus.Connected) return
        refreshCatalog()
        _chats.value.values
            .filter { it.loaded || it.running }
            .forEach { openSession(it.sessionId, force = true) }
    }

    private suspend fun onResumeResult(result: InboundFrame.AgentEventsResumed) {
        if (!result.requiresSnapshot) return
        refreshCatalog()
        _chats.value.values
            .filter { it.loaded || it.running }
            .forEach { openSession(it.sessionId, force = true) }
    }

    // ===== 目录（会话 + 项目 + 渠道） =====

    /**
     * 连接建立/重连/快照失效后刷新目录。
     * 请求成功即以服务端为准（包括空列表）；失败保留本地快照，不做“假刷新”。
     */
    suspend fun refreshCatalog(): Boolean {
        val sessionsOk = refreshSessions()
        val workspacesOk = refreshWorkspaces()
        refreshChannels()
        if (sessionsOk || workspacesOk) lastCatalogRefreshAt = clock()
        return sessionsOk || workspacesOk
    }

    /** 回前台时轻量刷新目录；15s 内重复回前台不重复拉 800+ 会话。 */
    fun refreshCatalogIfOnline(minIntervalMs: Long = CATALOG_REFRESH_MIN_INTERVAL_MS) {
        if (connection.status.value !is ConnectionStatus.Connected) return
        if (clock() - lastCatalogRefreshAt < minIntervalMs) return
        scope.launch { refreshCatalog() }
    }

    suspend fun refreshSessions(): Boolean {
        val result = try {
            connection.request("list_sessions")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        // 服务端已过滤归档；这里再兜底一次，兼容尚未升级的桌面端。
        val list = FrameCodec.parseSessionList(result.data).filterNot { it.isArchived }
        stateMutex.withLock {
            val previous = _sessions.value.associateBy { it.id }
            _sessions.value = sortSessions(
                list.mapNotNull { incoming ->
                    val tombstone = deletedSessionRevisions[incoming.id]
                    if (tombstone != null && incoming.revision <= tombstone) {
                        null
                    } else {
                        // 快照请求可能早于本地刚执行的命令；旧 revision 不能把置顶/归档回退。
                        previous[incoming.id]?.merge(incoming) ?: incoming
                    }
                },
            )
            // 桌面侧重命名/改模型后，快照刷新也要同步到已打开的对话页。
            _sessions.value.forEach(::updateChatMetaLocked)
            serverCatalogLoaded = true
            _catalogLoaded.value = true
            scheduleSnapshotSaveLocked()
        }
        return true
    }

    suspend fun refreshWorkspaces(): Boolean {
        val result = try {
            connection.request("list_workspaces")
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            return false
        }
        val list = FrameCodec.parseWorkspaceList(result.data).filterNot { it.isTeam }
        stateMutex.withLock {
            _workspaces.value = list
            serverCatalogLoaded = true
            scheduleSnapshotSaveLocked()
        }
        return true
    }

    suspend fun refreshChannels() {
        val result = requestOrNull("list_channels") ?: return
        val list = FrameCodec.parseChannelList(result.data).filter { it.enabled }
        if (list.isNotEmpty()) _channels.value = list
    }

    /** 拉取当前工作区可见的 Agent 预设；切换项目后重新拉取，避免串用。 */
    suspend fun refreshPresets(workspaceSlug: String?) {
        if (presetsWorkspaceSlug == workspaceSlug && _presets.value.isNotEmpty()) return
        val result = requestOrNull("list_presets") {
            if (!workspaceSlug.isNullOrBlank()) put("workspaceSlug", workspaceSlug)
        } ?: return
        _presets.value = FrameCodec.parsePresetList(result.data)
        presetsWorkspaceSlug = workspaceSlug
    }

    /** 切换会话预设（岗位）；服务端校验预设存在与工作区可见性。 */
    suspend fun updateSessionPreset(
        sessionId: String,
        presetId: String,
    ): Result<SessionSummary> = sessionCommand(
        type = "update_session_preset",
        sessionId = sessionId,
        fallback = { it.copy(presetId = presetId) },
    ) {
        put("presetId", presetId)
    }

    /** Chat 工具开关与电脑端共享（~/.profer/chat-tools.json）。 */
    suspend fun refreshChatTools() {
        val result = requestOrNull("list_chat_tools") ?: return
        _chatTools.value = FrameCodec.parseChatToolList(result.data)
    }

    suspend fun updateChatTool(toolId: String, enabled: Boolean): Result<Unit> = runCatching {
        val result = connection.request("update_chat_tool") {
            put("toolId", toolId)
            put("enabled", enabled)
        }
        _chatTools.value = FrameCodec.parseChatToolList(result.data)
    }

    /**
     * 打开（水合）一个会话：元数据 + 历史消息 + 待处理审批。
     * 已加载则直接返回；[force] 用于服务端重启/游标失效后的快照重建。
     */
    suspend fun openSession(sessionId: String, force: Boolean = false) {
        if (sessionId.isBlank()) return
        val current = _chats.value[sessionId]
        if (!force && current?.loaded == true) {
            // 已加载的会话再次被打开也算读过：不能因为跳过水合而漏掉未读清除。
            markSessionReadIfUnread(sessionId)
            return
        }
        synchronized(loadingSessions) {
            if (!loadingSessions.add(sessionId)) return
        }
        try {
            val metaResult = requestOrNull("session_detail") { put("sessionId", sessionId) }
            val meta = metaResult?.let { FrameCodec.sessionMetaFrom(it.data) }
            val messagesResult = requestOrNull("get_sdk_messages") { put("sessionId", sessionId) }
            val history = messagesResult?.let {
                HistoryMapper.map(FrameCodec.sdkMessagesFrom(it.data), clock())
            }
            val pendingResult = requestOrNull("get_pending_interactions") { put("sessionId", sessionId) }
            val pending = pendingResult?.let { parsePendingInteraction(it.data) }

            stateMutex.withLock {
                val base = _chats.value[sessionId] ?: newChatLocked(sessionId)
                var next = base.copy(
                    loaded = true,
                    title = meta?.title?.ifBlank { base.title } ?: base.title,
                    workspaceId = meta?.workspaceId ?: base.workspaceId,
                    channelId = meta?.channelId ?: base.channelId,
                    modelId = meta?.modelId ?: base.modelId,
                    permissionMode = meta?.permissionMode ?: base.permissionMode,
                    running = meta?.active ?: base.running,
                )
                if (history != null) {
                    val historyIds = history.entries.map { it.id }.toSet()
                    val live = next.messages.filter { it.id !in historyIds }
                    // 实时流已经产生本轮工具时以实时为准；否则用历史恢复本轮工具列表。
                    val liveHasTools = next.countedToolIds.isNotEmpty()
                    val tools = if (liveHasTools) next.tools else history.tools
                    val toolCalls = if (liveHasTools) next.toolCalls else history.toolCalls
                    next = next.copy(
                        messages = history.entries + live,
                        tools = tools,
                        toolCalls = toolCalls,
                        countedToolIds = next.countedToolIds + history.countedToolIds,
                        completedToolIds = next.completedToolIds + history.completedToolIds,
                        failedToolIds = next.failedToolIds + history.failedToolIds,
                        // 历史里的 usage 是打开会话时唯一可用的上下文来源；
                        // 拿不到就保留内存里已有的值（实时帧可能更新过）。
                        contextUsage = history.contextUsage ?: next.contextUsage,
                        contextWindow = history.contextWindow ?: next.contextWindow,
                    )
                }
                // 服务端返回的待处理列表是权威快照：为空时必须清掉重连前残留的旧审批。
                if (pendingResult != null) {
                    next = next.copy(pendingInteraction = pending)
                }
                _chats.value = _chats.value + (sessionId to next)
                if (meta != null) applySignalLocked(SessionSignal.Upsert(meta))
                scheduleSnapshotSaveLocked()
            }
            // 用户已经打开会话，视为显式阅读；manual 未读策略下也必须带 explicit=true。
            markSessionReadIfUnread(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            if (connection.status.value is ConnectionStatus.Connected) {
                appendNotice(sessionId, "会话加载失败：${error.readableMessage()}", NoticeTone.Warning)
            }
        } finally {
            synchronized(loadingSessions) { loadingSessions.remove(sessionId) }
        }
    }

    /**
     * 新建会话。
     *
     * 项目内使用桌面同款 `ensure_project_draft_session`：项目已有草稿就复用，
     * 不再每次点击都制造一条空会话；全局新建走 `create_session`。
     */
    suspend fun createSession(workspaceId: String? = null): Result<SessionSummary> = runCatching {
        // 刚连接就点新建时渠道可能还没落库：先补一次，避免创建出没有模型的空会话。
        if (_channels.value.isEmpty()) refreshChannels()
        val channel = _channels.value.firstOrNull { it.enabled }
        val result = connection.request(
            if (workspaceId != null) "ensure_project_draft_session" else "create_session",
        ) {
            if (workspaceId != null) {
                put("workspaceId", workspaceId)
            } else {
                put("title", "新会话")
            }
            if (channel != null) put("channelId", channel.id)
            channel?.firstModelId?.let { put("modelId", it) }
        }
        val created = FrameCodec.parseCreatedSession(result.data)
            ?: throw PocketCommandException(null, "服务端未返回新会话")
        val now = clock()
        val summary = created.copy(
            workspaceId = created.workspaceId ?: workspaceId,
            channelId = created.channelId ?: channel?.id,
            modelId = created.modelId ?: channel?.firstModelId,
            createdAt = created.createdAt.takeIf { it > 0 } ?: now,
            updatedAt = created.updatedAt.takeIf { it > 0 } ?: now,
            draft = created.draft ?: (workspaceId != null),
        )
        stateMutex.withLock {
            upsertSessionLocked(summary, forceAll = true)
        }
        summary
    }

    // ===== 会话操作（全部以服务端返回的权威 item 回填本地） =====

    suspend fun renameSession(sessionId: String, title: String): Result<SessionSummary> =
        sessionCommand("rename_session", sessionId, { it.copy(title = title) }) {
            put("title", title.trim())
        }

    suspend fun toggleSessionPin(sessionId: String): Result<SessionSummary> =
        sessionCommand("toggle_session_pin", sessionId, { it.copy(pinned = !it.isPinned, archived = false) })

    suspend fun markSessionRead(sessionId: String): Result<SessionSummary> =
        sessionCommand(
            type = "mark_session_read",
            sessionId = sessionId,
            fallback = { it.copy(unread = false) },
            // 旧服务端的 item 没有未读字段：本地已明确的阅读状态不能被 null 抵消。
            normalize = { it.copy(unread = false) },
        ) {
            // manual 未读策略下，只有显式阅读才允许清除桌面未读。
            put("explicit", true)
        }

    suspend fun markSessionUnread(sessionId: String): Result<SessionSummary> =
        sessionCommand(
            type = "mark_session_unread",
            sessionId = sessionId,
            fallback = { it.copy(unread = true) },
            normalize = { it.copy(unread = true) },
        )

    suspend fun moveSessionToWorkspace(
        sessionId: String,
        targetWorkspaceId: String,
    ): Result<SessionSummary> = sessionCommand(
        "move_session_to_workspace",
        sessionId,
        { it.copy(workspaceId = targetWorkspaceId) },
    ) {
        put("targetWorkspaceId", targetWorkspaceId)
    }

    suspend fun deleteSession(sessionId: String): Result<Unit> = runCatching {
        connection.request("delete_session") { put("sessionId", sessionId) }
        stateMutex.withLock {
            val revision = _sessions.value.firstOrNull { it.id == sessionId }?.revision ?: 0
            deletedSessionRevisions[sessionId] =
                maxOf(deletedSessionRevisions[sessionId] ?: 0, revision)
            _sessions.value = _sessions.value.filterNot { it.id == sessionId }
            _chats.value = _chats.value - sessionId
            scheduleSnapshotSaveLocked()
        }
    }

    suspend fun createWorkspace(name: String): Result<WorkspaceSummary> = runCatching {
        val clean = name.trim()
        if (clean.isEmpty()) throw PocketCommandException(null, "项目名称不能为空")
        val result = connection.request("create_workspace") { put("name", clean) }
        val created = FrameCodec.parseWorkspaceItem(result.data)
            ?: throw PocketCommandException(null, "服务端未返回新项目")
        stateMutex.withLock {
            _workspaces.value = _workspaces.value.filterNot { it.id == created.id } + created
            scheduleSnapshotSaveLocked()
        }
        created
    }

    private suspend fun sessionCommand(
        type: String,
        sessionId: String,
        fallback: (SessionSummary) -> SessionSummary,
        normalize: (SessionSummary) -> SessionSummary = { it },
        build: JSONObject.() -> Unit = {},
    ): Result<SessionSummary> = runCatching {
        val result = connection.request(type) {
            put("sessionId", sessionId)
            build()
        }
        val current = _sessions.value.firstOrNull { it.id == sessionId }
            ?: SessionSummary(id = sessionId, title = "")
        val incoming = (FrameCodec.parseSessionItem(result.data) ?: fallback(current)).let(normalize)
        stateMutex.withLock {
            upsertSessionLocked(incoming)
            updateChatMetaLocked(incoming)
        }
        _sessions.value.firstOrNull { it.id == sessionId } ?: incoming
    }

    /** 切换会话模型；服务端要求模型与渠道一起提交，运行中的会话会被拒绝。 */
    suspend fun updateSessionModel(
        sessionId: String,
        channelId: String,
        modelId: String,
    ): Result<SessionSummary> = sessionCommand(
        type = "update_session_model",
        sessionId = sessionId,
        fallback = { it.copy(channelId = channelId, modelId = modelId) },
    ) {
        put("channelId", channelId)
        put("modelId", modelId)
    }

    /** 切换权限模式：auto / plan / bypassPermissions。运行中允许切换。 */
    suspend fun updatePermissionMode(
        sessionId: String,
        mode: String,
    ): Result<SessionSummary> = sessionCommand(
        type = "update_permission_mode",
        sessionId = sessionId,
        fallback = { it.copy(permissionMode = mode) },
    ) {
        put("mode", mode)
    }

    /** 打开/切回会话时的已读收口：只有本地确实未读才发命令，避免每次切换都多一条指令。 */
    suspend fun markSessionReadIfUnread(sessionId: String) {
        if (_sessions.value.firstOrNull { it.id == sessionId }?.unread != true) return
        runCatching { markSessionRead(sessionId) }
    }

    // ===== 对话操作 =====

    suspend fun sendMessage(sessionId: String, rawText: String) {
        val text = rawText.trim()
        if (text.isEmpty()) return

        var missingChannel = false
        val frame = stateMutex.withLock {
            val base = _chats.value[sessionId] ?: newChatLocked(sessionId)
            val summary = _sessions.value.firstOrNull { it.id == sessionId }
            val channelId = base.channelId
                ?: summary?.channelId
                ?: _channels.value.firstOrNull()?.id
            if (channelId == null) {
                missingChannel = true
                return@withLock null
            }
            val uuid = newId()
            val cmdId = newId()
            val frame = FrameCodec.sendMessage(
                cmdId = cmdId,
                clientMessageId = uuid,
                sessionId = sessionId,
                userMessage = text,
                channelId = channelId,
                modelId = base.modelId ?: summary?.modelId,
                workspaceId = base.workspaceId ?: summary?.workspaceId,
                uuid = uuid,
                startedAt = clock(),
            )
            val entry = ChatEntry.User(id = uuid, text = text, at = clock(), status = com.profer.pocket.model.SendStatus.Sending)
            _chats.value = _chats.value + (sessionId to base.append(entry).copy(
                channelId = channelId,
                running = true,
                lastError = null,
                tools = com.profer.pocket.model.ToolTally(),
                countedToolIds = emptySet(),
                toolCalls = emptyList(),
                completedToolIds = emptySet(),
                turnFiles = emptyList(),
                failedToolIds = emptySet(),
            ))
            // 用户正在该会话里发消息，未读已失去提醒意义；服务端随后也会广播 projection。
            _sessions.value = _sessions.value.map {
                if (it.id == sessionId) it.copy(unread = false) else it
            }
            synchronized(outgoingSessionIds) { outgoingSessionIds[uuid] = sessionId }
            OutgoingMessage(cmdId = cmdId, clientMessageId = uuid, frame = frame)
        }

        if (missingChannel) {
            appendNotice(sessionId, "缺少可用渠道：请先在电脑端配置模型渠道", NoticeTone.Warning)
            return
        }
        if (frame != null) connection.sendMessage(frame)
    }

    /**
     * 读取电脑端文件预览：图片走 data URL，其余走文本。
     * 只允许当前会话授权范围内的路径（服务端二次校验）。
     */
    suspend fun readFilePreview(
        sessionId: String,
        path: String,
        filename: String,
    ): Result<RemoteFilePreview> = runCatching {
        val imageResult = connection.request("read_file_as_data_url") {
            put("sessionId", sessionId)
            put("filePath", path)
        }
        if (imageResult.ok) {
            val json = imageResult.data as? JSONObject
            val dataUrl = json?.optString("dataUrl", "").orEmpty()
            if (dataUrl.startsWith("data:image/")) {
                return@runCatching RemoteFilePreview(filename, isImage = true, dataUrl = dataUrl)
            }
        }
        val textResult = connection.request("resolve_and_read_file") {
            put("sessionId", sessionId)
            put("filePath", path)
        }
        if (!textResult.ok) {
            throw PocketCommandException(textResult.conflictCode, textResult.error ?: "文件读取失败")
        }
        val json = textResult.data as? JSONObject
        RemoteFilePreview(
            filename = filename,
            isImage = false,
            text = json?.optString("content", "").orEmpty(),
        )
    }

    /** 保存图片/文件到会话工作目录（与桌面 saveFilesToAgentSession 同源）。 */
    suspend fun saveAgentAttachment(
        sessionId: String,
        filename: String,
        base64: String,
    ): Result<AgentAttachment> = runCatching {
        val result = connection.request("save_agent_attachment") {
            put("sessionId", sessionId)
            put("filename", filename)
            put("base64", base64)
        }
        if (!result.ok) {
            throw PocketCommandException(result.conflictCode, result.error ?: "附件保存失败")
        }
        val json = result.data as? JSONObject ?: throw PocketCommandException(null, "附件响应为空")
        val path = json.optString("path", "")
        if (path.isBlank()) throw PocketCommandException(null, "附件路径为空")
        AgentAttachment(
            filename = json.optString("filename", filename),
            path = path,
        )
    }

    /**
     * 运行中插话：消息注入当前 run，uuid 与乐观气泡一致，服务端广播时自动去重。
     * 会话已结束（未运行）时降级为普通发送。
     */
    suspend fun queueMessage(sessionId: String, rawText: String): Result<Unit> = runCatching {
        val text = rawText.trim()
        if (text.isEmpty()) return@runCatching
        val uuid = newId()
        stateMutex.withLock {
            val base = _chats.value[sessionId] ?: newChatLocked(sessionId)
            _chats.value = _chats.value + (sessionId to base.append(
                ChatEntry.User(id = uuid, text = text, at = clock()),
            ))
        }
        val result = connection.request("queue_message") {
            put("sessionId", sessionId)
            put("userMessage", text)
            put("uuid", uuid)
        }
        if (!result.ok) {
            // 回滚乐观气泡，避免“本地有、服务端没有”。
            stateMutex.withLock {
                val base = _chats.value[sessionId]
                if (base != null) {
                    _chats.value = _chats.value + (sessionId to base.copy(
                        messages = base.messages.filterNot { it.id == uuid },
                    ))
                }
            }
            val error = result.error.orEmpty()
            if (error.contains("未运行")) {
                // 当前 run 已结束：按普通发送处理。
                sendMessage(sessionId, text)
                return@runCatching
            }
            throw PocketCommandException(result.conflictCode, error.ifBlank { "插话失败" })
        }
        // 已注入并落盘：本条气泡从「发送中」转为已发送。
        updateSendStatus(sessionId, uuid, com.profer.pocket.model.SendStatus.Sent)
    }

    /** 回退到指定 assistant 锚点（null = 清空整段），成功后强制重新水合。 */
    suspend fun rewindSession(
        sessionId: String,
        assistantMessageUuid: String?,
    ): Result<Unit> = runCatching {
        val result = connection.request("rewind_session") {
            put("sessionId", sessionId)
            if (!assistantMessageUuid.isNullOrBlank()) put("assistantMessageUuid", assistantMessageUuid)
        }
        if (!result.ok) {
            throw PocketCommandException(result.conflictCode, result.error ?: "回退失败")
        }
        openSession(sessionId, force = true)
    }

    suspend fun stopSession(sessionId: String) {
        try {
            connection.request("stop_agent") { put("sessionId", sessionId) }
            stateMutex.withLock {
                val base = _chats.value[sessionId] ?: return@withLock
                _chats.value = _chats.value + (sessionId to base.copy(running = false))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendNotice(sessionId, "停止失败：${error.readableMessage()}", NoticeTone.Warning)
        }
    }

    suspend fun respondPermission(
        sessionId: String,
        requestId: String,
        allow: Boolean,
        alwaysAllow: Boolean = false,
    ) {
        try {
            connection.request("respond_permission") {
                put("requestId", requestId)
                put("behavior", if (allow) "allow" else "deny")
                put("alwaysAllow", alwaysAllow)
                put("sessionId", sessionId)
            }
            stateMutex.withLock {
                val base = _chats.value[sessionId] ?: return@withLock
                val pending = base.pendingInteraction
                val next = if (pending != null && pending.id == requestId) {
                    base.copy(pendingInteraction = null)
                } else {
                    base
                }
                val notice = ChatEntry.Notice(
                    id = "allow-${requestId}",
                    text = if (allow) "已允许本次执行" else "已拒绝本次执行",
                    tone = if (allow) NoticeTone.Success else NoticeTone.Warning,
                    at = clock(),
                )
                _chats.value = _chats.value + (sessionId to next.append(notice))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendNotice(sessionId, "回应失败：${error.readableMessage()}", NoticeTone.Warning)
        }
    }

    suspend fun respondAskUser(
        sessionId: String,
        requestId: String,
        answers: Map<String, String>,
    ) {
        try {
            connection.request("respond_ask_user") {
                put("requestId", requestId)
                put("answers", JSONObject(answers as Map<*, *>))
            }
            stateMutex.withLock {
                val base = _chats.value[sessionId] ?: return@withLock
                val pending = base.pendingInteraction
                val next = if (pending != null && pending.id == requestId) {
                    base.copy(pendingInteraction = null)
                } else {
                    base
                }
                val notice = ChatEntry.Notice(
                    id = "answer-${requestId}",
                    text = "已提交回答",
                    tone = NoticeTone.Success,
                    at = clock(),
                )
                _chats.value = _chats.value + (sessionId to next.append(notice))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendNotice(sessionId, "回答失败：${error.readableMessage()}", NoticeTone.Warning)
        }
    }

    suspend fun respondPlan(
        sessionId: String,
        requestId: String,
        approve: Boolean,
        alwaysAllow: Boolean = false,
    ) {
        try {
            connection.request("respond_exit_plan_mode") {
                put("requestId", requestId)
                // approve_edit 切到自动审批（常规允许）；approve_auto 切到完全放行（会话始终允许）。
                put("action", if (!approve) "deny" else if (alwaysAllow) "approve_auto" else "approve_edit")
                put("sessionId", sessionId)
            }
            stateMutex.withLock {
                val base = _chats.value[sessionId] ?: return@withLock
                val pending = base.pendingInteraction
                val next = if (pending != null && pending.id == requestId) {
                    base.copy(pendingInteraction = null)
                } else {
                    base
                }
                val notice = ChatEntry.Notice(
                    id = "plan-${requestId}",
                    text = if (approve) "已批准计划" else "已拒绝计划",
                    tone = if (approve) NoticeTone.Success else NoticeTone.Warning,
                    at = clock(),
                )
                _chats.value = _chats.value + (sessionId to next.append(notice))
            }
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            appendNotice(sessionId, "审批失败：${error.readableMessage()}", NoticeTone.Warning)
        }
    }

    // ===== 事件归约 =====

    private suspend fun onAgentEvent(event: InboundFrame.AgentEvent) {
        val payload = event.payload
        if (payload is AgentPayload.SdkMessage && payload.message.partial) {
            // 流式累计正文：窗口内只保留最后一条，合并后再推给 UI。
            pendingPartial[event.sessionId] = event
            if (flushJobs[event.sessionId]?.isActive != true) {
                flushJobs[event.sessionId] = scope.launch {
                    delay(streamMergeWindowMs)
                    val buffered = pendingPartial.remove(event.sessionId) ?: return@launch
                    applyEvent(buffered)
                }
            }
            return
        }
        pendingPartial.remove(event.sessionId)
        flushJobs.remove(event.sessionId)?.cancel()
        applyEvent(event)
    }

    private suspend fun applyEvent(event: InboundFrame.AgentEvent) {
        stateMutex.withLock {
            val current = _chats.value[event.sessionId] ?: newChatLocked(event.sessionId)
            val reduction = reducer.reduce(current, event.sessionId, event.eventId, event.payload)
            _chats.value = _chats.value + (event.sessionId to reduction.chat)
            reduction.signals.forEach(::applySignalLocked)
            reduction.notifications.forEach(::emitNotificationLocked)
        }
    }

    private fun applySignalLocked(signal: SessionSignal) {
        when (signal) {
            is SessionSignal.Upsert -> {
                upsertSessionLocked(signal.summary)
                updateChatMetaLocked(signal.summary)
            }

            is SessionSignal.Delete -> {
                val localRevision = _sessions.value.firstOrNull { it.id == signal.sessionId }?.revision ?: 0
                deletedSessionRevisions[signal.sessionId] = maxOf(
                    deletedSessionRevisions[signal.sessionId] ?: 0,
                    localRevision,
                    signal.revision,
                )
                _sessions.value = _sessions.value.filterNot { it.id == signal.sessionId }
                _chats.value = _chats.value - signal.sessionId
                scheduleSnapshotSaveLocked()
            }

            is SessionSignal.Title -> {
                _sessions.value = _sessions.value.map {
                    if (it.id == signal.sessionId) it.copy(title = signal.title) else it
                }
            }

            is SessionSignal.Model -> {
                _sessions.value = _sessions.value.map {
                    if (it.id == signal.sessionId) it.copy(modelId = signal.modelId) else it
                }
            }

            is SessionSignal.PermissionMode -> {
                _sessions.value = _sessions.value.map {
                    if (it.id == signal.sessionId) it.copy(permissionMode = signal.mode) else it
                }
            }
        }
        scheduleSnapshotSaveLocked()
    }

    /**
     * 会话快照合并 + 排序。调用方必须持有 [stateMutex]。
     * projection 不含运行态时由 [SessionSummary.merge] 保留本地值；revision 更旧的投影直接丢弃。
     */
    private fun upsertSessionLocked(summary: SessionSummary, forceAll: Boolean = false) {
        if (summary.id.isBlank()) return
        if (summary.isArchived) {
            // 归档 = 从移动端目录移出。记下 revision，迟到的 archived=false 只要更新就能恢复。
            deletedSessionRevisions[summary.id] = maxOf(
                deletedSessionRevisions[summary.id] ?: 0,
                summary.revision,
            )
            _sessions.value = _sessions.value.filterNot { it.id == summary.id }
            _chats.value = _chats.value - summary.id
            scheduleSnapshotSaveLocked()
            return
        }
        val tombstone = deletedSessionRevisions[summary.id]
        if (!forceAll && tombstone != null && summary.revision <= tombstone) return
        val existing = _sessions.value.firstOrNull { it.id == summary.id }
        val merged = existing?.merge(summary, forceAll = forceAll) ?: summary
        _sessions.value = sortSessions(_sessions.value.filterNot { it.id == merged.id } + merged)
        scheduleSnapshotSaveLocked()
    }

    private fun updateChatMetaLocked(summary: SessionSummary) {
        val chat = _chats.value[summary.id] ?: return
        _chats.value = _chats.value + (summary.id to chat.copy(
            title = summary.title.ifBlank { chat.title },
            workspaceId = summary.workspaceId ?: chat.workspaceId,
            channelId = summary.channelId ?: chat.channelId,
            modelId = summary.modelId ?: chat.modelId,
            permissionMode = summary.permissionMode ?: chat.permissionMode,
        ))
    }

    private fun emitNotificationLocked(notification: PocketNotification) {
        val now = clock()
        if (notification.kind == NotificationKind.RunCompleted) {
            val last = lastCompletionNotifyAt[notification.sessionId]
            // run_completed 与 run_idle 可能先后到达，3s 窗口内同会话只提醒一次。
            if (last != null && now - last < COMPLETION_DEDUP_WINDOW_MS) return
            lastCompletionNotifyAt[notification.sessionId] = now
        }
        val title = _chats.value[notification.sessionId]?.title.orEmpty()
        val enriched = if (notification.kind == NotificationKind.RunCompleted && title.isNotBlank()) {
            notification.copy(body = "$title 已完成")
        } else {
            notification
        }
        notificationChannel.trySend(enriched)
    }

    private suspend fun onSendOutcome(outcome: SendOutcome) {
        val sessionId = synchronized(outgoingSessionIds) {
            when (outcome) {
                is SendOutcome.Accepted -> outgoingSessionIds.remove(outcome.clientMessageId)
                is SendOutcome.Rejected -> outgoingSessionIds.remove(outcome.clientMessageId)
                is SendOutcome.Queued -> outgoingSessionIds[outcome.clientMessageId]
            }
        } ?: return
        when (outcome) {
            is SendOutcome.Accepted -> updateSendStatus(sessionId, outcome.clientMessageId, com.profer.pocket.model.SendStatus.Sent)
            is SendOutcome.Rejected -> updateSendStatus(
                sessionId,
                outcome.clientMessageId,
                com.profer.pocket.model.SendStatus.Failed,
                outcome.message,
            )
            // 断线队列：消息还没发出去，保持「发送中」由全局连接横幅表达。
            is SendOutcome.Queued -> Unit
        }
    }

    /** 更新单条用户消息的发送状态；失败时同时结束 running，避免界面一直转。 */
    private suspend fun updateSendStatus(
        sessionId: String,
        clientMessageId: String,
        status: com.profer.pocket.model.SendStatus,
        failure: String? = null,
    ) {
        stateMutex.withLock {
            val base = _chats.value[sessionId] ?: return@withLock
            val messages = base.messages.map { entry ->
                if (entry is ChatEntry.User && entry.id == clientMessageId) {
                    entry.copy(status = status, failure = failure)
                } else {
                    entry
                }
            }
            _chats.value = _chats.value + (sessionId to base.copy(
                messages = messages,
                running = if (status == com.profer.pocket.model.SendStatus.Failed) false else base.running,
            ))
        }
    }

    /** 重试一条失败的用户消息：复用原 uuid，避免重复气泡，服务端仍按 clientMessageId 幂等。 */
    suspend fun retrySend(sessionId: String, messageId: String) {
        var frame: OutgoingMessage? = null
        stateMutex.withLock {
            val base = _chats.value[sessionId] ?: return@withLock
            val entry = base.messages.firstOrNull { it is ChatEntry.User && it.id == messageId } as? ChatEntry.User
                ?: return@withLock
            val summary = _sessions.value.firstOrNull { it.id == sessionId }
            val channelId = base.channelId ?: summary?.channelId ?: _channels.value.firstOrNull()?.id
                ?: return@withLock
            val cmdId = newId()
            val payload = FrameCodec.sendMessage(
                cmdId = cmdId,
                clientMessageId = entry.id,
                sessionId = sessionId,
                userMessage = entry.text,
                channelId = channelId,
                modelId = base.modelId ?: summary?.modelId,
                workspaceId = base.workspaceId ?: summary?.workspaceId,
                uuid = entry.id,
                startedAt = clock(),
            )
            frame = OutgoingMessage(cmdId = cmdId, clientMessageId = entry.id, frame = payload)
            _chats.value = _chats.value + (sessionId to base.copy(
                messages = base.messages.map {
                    if (it is ChatEntry.User && it.id == entry.id) {
                        it.copy(status = com.profer.pocket.model.SendStatus.Sending, failure = null)
                    } else {
                        it
                    }
                },
                running = true,
            ))
            synchronized(outgoingSessionIds) { outgoingSessionIds[entry.id] = sessionId }
        }
        frame?.let(connection::sendMessage)
    }

    // ===== 辅助 =====

    private suspend fun appendNotice(sessionId: String, text: String, tone: NoticeTone) {
        stateMutex.withLock {
            val base = _chats.value[sessionId] ?: newChatLocked(sessionId)
            val notice = ChatEntry.Notice(id = "notice-${clock()}-${text.hashCode()}", text = text, tone = tone, at = clock())
            _chats.value = _chats.value + (sessionId to base.append(notice))
        }
    }

    private fun newChatLocked(sessionId: String): SessionChat {
        val summary = _sessions.value.firstOrNull { it.id == sessionId }
        return SessionChat(
            sessionId = sessionId,
            title = summary?.title.orEmpty(),
            workspaceId = summary?.workspaceId,
            channelId = summary?.channelId,
            modelId = summary?.modelId,
            permissionMode = summary?.permissionMode,
        )
    }

    private fun scheduleSnapshotSaveLocked() {
        if (snapshotJob?.isActive == true) return
        snapshotJob = scope.launch {
            // 重放大量 projection 时目录会连续变化，1s 防抖只落一次盘。
            delay(1_000)
            val snapshot = SessionSnapshotStore.Snapshot(
                sessions = _sessions.value,
                workspaces = _workspaces.value,
            )
            withContext(Dispatchers.IO) { snapshots.save(snapshot) }
        }
    }

    private fun parsePendingInteraction(data: Any?): PendingInteraction? {
        val json = data as? JSONObject ?: return null
        val permissions = json.optJSONArray("permissions")
        permissions?.optJSONObject(0)?.let { request ->
            val id = request.optString("requestId", "")
            if (id.isBlank()) return null
            return PendingInteraction.Permission(
                id = id,
                toolName = request.optString("toolName", ""),
                description = request.optString("description", ""),
                command = request.optString("command").takeIf { it.isNotBlank() },
                dangerLevel = request.optString("dangerLevel").takeIf { it.isNotBlank() },
            )
        }
        val plans = json.optJSONArray("exitPlans")
        plans?.optJSONObject(0)?.let { request ->
            val id = request.optString("requestId", "")
            if (id.isBlank()) return null
            val input = request.optJSONObject("toolInput")
            return PendingInteraction.PlanApproval(
                id = id,
                summary = input?.optString("plan")?.takeIf { it.isNotBlank() },
            )
        }
        val asks = json.optJSONArray("askUsers")
        asks?.optJSONObject(0)?.let { request ->
            val id = request.optString("requestId", "")
            if (id.isBlank()) return null
            return PendingInteraction.Question(id, FrameCodec.parseAskQuestions(request))
        }
        return null
    }

    private suspend fun requestOrNull(
        type: String,
        build: JSONObject.() -> Unit = {},
    ): InboundFrame.CommandResult? = try {
        connection.request(type, build)
    } catch (error: CancellationException) {
        throw error
    } catch (_: Exception) {
        null
    }

    // ===== Chat（普通对话）=====

    suspend fun refreshConversations(): Boolean {
        val result = requestOrNull("list_conversations") ?: return false
        if (!result.ok) return false
        val list = ChatCodec.conversations(result.data).filterNot { it.archived }
        _conversations.value = sortConversations(list)
        return true
    }

    suspend fun openConversation(conversationId: String) {
        val meta = _conversations.value.firstOrNull { it.id == conversationId }
        val result = requestOrNull("get_conversation_messages") {
            put("conversationId", conversationId)
        } ?: return
        if (!result.ok) return
        val messages = ChatCodec.activeBranch(
            ChatCodec.messages(result.data),
            meta?.activePath.orEmpty(),
        )
        val merged = mergePendingConversationMessages(conversationId, messages)
        _conversationMessages.update { it + (conversationId to merged) }
    }

    /**
     * 把服务端历史还没确认的乐观用户消息补回尾部。
     *
     * 按「角色 + 正文」消耗式匹配：服务端历史里出现同内容用户消息即视为已确认并让位；
     * 未匹配的保留在末尾，等下一次水合再确认。这样重复发送同一句话也不会互相顶掉。
     */
    private fun mergePendingConversationMessages(
        conversationId: String,
        serverMessages: List<ChatMessage>,
    ): List<ChatMessage> {
        val pending = synchronized(pendingConversationMessages) {
            pendingConversationMessages[conversationId]?.toList().orEmpty()
        }
        if (pending.isEmpty()) return serverMessages

        val unmatched = serverMessages
            .filter { it.isUser }
            .map { it.content.trim() }
            .toMutableList()
        val remaining = mutableListOf<ChatMessage>()
        for (message in pending) {
            val index = unmatched.indexOf(message.content.trim())
            if (index >= 0) {
                unmatched.removeAt(index)
            } else {
                remaining += message
            }
        }
        synchronized(pendingConversationMessages) {
            if (remaining.isEmpty()) {
                pendingConversationMessages.remove(conversationId)
            } else {
                pendingConversationMessages[conversationId] = remaining.toMutableList()
            }
        }
        return if (remaining.isEmpty()) serverMessages else serverMessages + remaining
    }

    /** 新建对话；channel/model 由当前选中的模型决定（桌面 Chat 也要求二者之一）。 */
    suspend fun createConversation(channelId: String?, modelId: String?): String? {
        val result = requestOrNull("create_conversation") {
            if (!channelId.isNullOrBlank()) put("channelId", channelId)
            if (!modelId.isNullOrBlank()) put("modelId", modelId)
        } ?: return null
        if (!result.ok) return null
        val meta = (result.data as? JSONObject)?.let { json ->
            ChatCodec.conversations(JSONArray().put(json)).firstOrNull()
        } ?: return null
        _conversations.update { sortConversations(it + meta) }
        _conversationMessages.update { it + (meta.id to emptyList()) }
        return meta.id
    }

    /** 切换对话绑定的模型；服务端同时更新 channelId/modelId，下一轮发送才会生效。 */
    suspend fun updateConversationModel(
        conversationId: String,
        channelId: String,
        modelId: String,
    ): Boolean {
        val result = requestOrNull("update_conversation_model") {
            put("conversationId", conversationId)
            put("channelId", channelId)
            put("modelId", modelId)
        } ?: return false
        if (!result.ok) return false
        val meta = ChatCodec.conversationMeta(result.data)
        _conversations.update { list ->
            val updated = meta ?: list.firstOrNull { it.id == conversationId }?.copy(
                channelId = channelId,
                modelId = modelId,
                updatedAt = clock(),
            ) ?: return@update list
            sortConversations(list.map { if (it.id == conversationId) updated else it })
        }
        return true
    }

    suspend fun sendConversationMessage(
        conversationId: String,
        text: String,
        channelIdFallback: String? = null,
        modelIdFallback: String? = null,
    ): Boolean {
        val body = text.trim()
        if (body.isEmpty()) return false
        val conversation = _conversations.value.firstOrNull { it.id == conversationId } ?: return false
        val channelId = conversation.channelId ?: channelIdFallback
        if (channelId.isNullOrBlank()) {
            _chatStreams.update {
                it + (conversationId to ChatStream(conversationId, error = "这个对话没有绑定模型，先在电脑端打开一次"))
            }
            return false
        }
        val modelId = conversation.modelId ?: modelIdFallback
        // 乐观上屏：用户消息先出现，和 Agent 会话一致。
        val localId = "local-" + newId()
        val userMessage = ChatMessage(
            id = localId,
            role = "user",
            content = body,
            createdAt = clock(),
        )
        _conversationMessages.update {
            it + (conversationId to ((it[conversationId] ?: emptyList()) + userMessage))
        }
        synchronized(pendingConversationMessages) {
            pendingConversationMessages.getOrPut(conversationId) { mutableListOf() } += userMessage
        }
        _chatStreams.update { it + (conversationId to ChatStream(conversationId, running = true)) }

        val result = requestOrNull("chat_send_message") {
            put("conversationId", conversationId)
            put("userMessage", body)
            put("channelId", channelId)
            if (!modelId.isNullOrBlank()) put("modelId", modelId)
            // 只传「已开启且凭据可用」的工具；空列表交给服务端按默认处理。
            val enabledToolIds = _chatTools.value
                .filter { it.enabled && it.available }
                .map { it.id }
            if (enabledToolIds.isNotEmpty()) put("enabledToolIds", JSONArray(enabledToolIds))
        }
        val error = when {
            result == null -> "发送失败，连接已断开"
            !result.ok -> result.error ?: "发送失败"
            else -> null
        }
        if (error != null) {
            // 发送失败：撤销乐观登记，避免服务端永远不会出现的消息被反复补回。
            synchronized(pendingConversationMessages) {
                val list = pendingConversationMessages[conversationId] ?: mutableListOf()
                list.removeAll { it.id == localId }
                if (list.isEmpty()) pendingConversationMessages.remove(conversationId)
            }
            _chatStreams.update {
                it + (conversationId to ChatStream(conversationId, running = false, error = error))
            }
            return false
        }
        return true
    }

    suspend fun deleteConversation(conversationId: String): Boolean {
        val result = requestOrNull("delete_conversation") {
            put("conversationId", conversationId)
        } ?: return false
        if (!result.ok) return false
        _conversations.update { list -> list.filterNot { it.id == conversationId } }
        _conversationMessages.update { it - conversationId }
        _chatStreams.update { it - conversationId }
        synchronized(pendingConversationMessages) { pendingConversationMessages.remove(conversationId) }
        return true
    }

    suspend fun stopConversation(conversationId: String) {
        requestOrNull("chat_stop_generation") { put("conversationId", conversationId) }
        _chatStreams.update { streams ->
            streams[conversationId]?.let { streams + (conversationId to it.copy(running = false)) } ?: streams
        }
    }

    private fun onChatEvent(event: InboundFrame.ChatEvent) {
        val payload = event.payload ?: return
        val id = event.conversationId.ifBlank { payload.optString("conversationId") }
        if (id.isBlank()) return
        when (event.channel) {
            "chat:stream:chunk" -> {
                val delta = payload.optString("delta")
                if (delta.isEmpty()) return
                _chatStreams.update { streams ->
                    val current = streams[id] ?: ChatStream(id)
                    streams + (id to current.copy(text = current.text + delta, running = true, error = null))
                }
            }

            "chat:stream:reasoning" -> {
                val delta = payload.optString("delta")
                if (delta.isEmpty()) return
                _chatStreams.update { streams ->
                    val current = streams[id] ?: ChatStream(id)
                    streams + (id to current.copy(reasoning = current.reasoning + delta, running = true))
                }
            }

            "chat:stream:complete" -> finishChatStream(id, payload.optString("messageId"))

            "chat:stream:user" -> onChatUserMessage(id, payload.optJSONObject("message"))

            "chat:stream:error" -> {
                val error = payload.optString("error").ifBlank { "生成失败" }
                _chatStreams.update {
                    it + (id to ChatStream(id, running = false, error = error))
                }
            }
        }
    }

    /**
     * 服务端已持久化的用户消息（桌面端发起，或本机发送的确认回执）。
     *
     * 会话尚未水合时不插入：等 openConversation 一次性拉全量，避免只挂一条孤立消息。
     * 已水合时按 id 去重；本机乐观副本（local- 前缀且正文相同）原位换成真实 id，
     * 后续历史水合按 id 合并也不会重复。
     */
    private fun onChatUserMessage(conversationId: String, message: JSONObject?) {
        if (message == null) return
        val id = message.optString("id").takeIf { it.isNotBlank() } ?: return
        val content = message.optString("content")
        val createdAt = message.optLong("createdAt", clock())
        val incoming = ChatMessage(id = id, role = "user", content = content, createdAt = createdAt)
        _conversationMessages.update { current ->
            val list = current[conversationId] ?: return@update current
            if (list.any { it.id == id }) return@update current
            val optimisticIndex = list.indexOfLast {
                it.isUser && it.id.startsWith("local-") && it.content == content
            }
            val next = if (optimisticIndex >= 0) {
                list.toMutableList().also { it[optimisticIndex] = incoming }
            } else {
                list + incoming
            }
            current + (conversationId to next)
        }
        // 服务端已确认：消耗式撤销乐观登记，避免后续水合再补一遍。
        synchronized(pendingConversationMessages) {
            val pending = pendingConversationMessages[conversationId] ?: return@synchronized
            val index = pending.indexOfFirst { it.isUser && it.content == content }
            if (index >= 0) {
                pending.removeAt(index)
                if (pending.isEmpty()) pendingConversationMessages.remove(conversationId)
            }
        }
    }

    private fun finishChatStream(conversationId: String, messageId: String) {        val stream = _chatStreams.value[conversationId]
        val text = stream?.text.orEmpty()
        if (text.isNotBlank()) {
            val message = ChatMessage(
                id = messageId.ifBlank { "local-" + newId() },
                role = "assistant",
                content = text,
                createdAt = clock(),
            )
            _conversationMessages.update {
                it + (conversationId to ((it[conversationId] ?: emptyList()) + message))
            }
            _chatStreams.update { it - conversationId }
        } else {
            // 没收到增量（重连/补帧）时，用服务端历史兜底。
            _chatStreams.update { it - conversationId }
            scope.launch { openConversation(conversationId) }
        }
        _conversations.update { list ->
            sortConversations(list.map { if (it.id == conversationId) it.copy(updatedAt = clock()) else it })
        }
    }

    private fun sortConversations(list: List<ConversationSummary>): List<ConversationSummary> =
        list.sortedWith(compareByDescending<ConversationSummary> { it.pinned }.thenByDescending { it.updatedAt })

    private fun sortSessions(list: List<SessionSummary>): List<SessionSummary> =
        list.sortedWith(compareByDescending<SessionSummary> { it.pinned }.thenByDescending { it.updatedAt })

    private fun Exception.readableMessage(): String = toPocketMessage()

    sealed interface PairingState {
        data object Idle : PairingState

        data object Pairing : PairingState

        data class Succeeded(val deviceName: String) : PairingState

        data class Failed(val message: String) : PairingState
    }

    private companion object {
        const val COMPLETION_DEDUP_WINDOW_MS = 3_000L
        const val CATALOG_REFRESH_MIN_INTERVAL_MS = 15_000L
    }
}
