package com.profer.pocket.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.profer.pocket.chat.ChatUiState
import com.profer.pocket.chat.ConversationUiState
import com.profer.pocket.connect.ConnectionUiState
import com.profer.pocket.data.toPocketMessage
import com.profer.pocket.model.ChannelSummary
import com.profer.pocket.model.ChatEntry
import com.profer.pocket.model.AgentAttachment
import com.profer.pocket.model.ChatMessage as DomainChatMessage
import com.profer.pocket.model.ChatStream
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.ConversationSummary
import com.profer.pocket.model.HomeMode
import com.profer.pocket.model.PendingInteraction
import com.profer.pocket.model.PocketSettings
import com.profer.pocket.model.SessionChat
import com.profer.pocket.model.SessionSummary
import com.profer.pocket.model.ThemeMode
import com.profer.pocket.model.WorkspaceSummary
import com.profer.pocket.sessions.ConversationListState
import com.profer.pocket.sessions.ProjectOption
import com.profer.pocket.sessions.SessionItem
import com.profer.pocket.sessions.SessionListState
import com.profer.pocket.settings.ThemePreference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class PocketViewModel(application: Application) : AndroidViewModel(application) {

    private val container = (application as ProferApplication).container
    private val repository = container.repository

    private val _selectedSessionId = MutableStateFlow<String?>(null)
    val selectedSessionId: StateFlow<String?> = _selectedSessionId.asStateFlow()

    /** 上次打开的会话是否已从本地读回；读回前不要用「目录第一条」抢先覆盖。 */
    private val _selectionRestored = MutableStateFlow(false)

    private val _homeMode = MutableStateFlow(HomeMode.Agent)
    val homeMode: StateFlow<HomeMode> = _homeMode.asStateFlow()

    private val _activeConversationId = MutableStateFlow<String?>(null)
    val activeConversationId: StateFlow<String?> = _activeConversationId.asStateFlow()

    private val _conversationQuery = MutableStateFlow("")
    private val _conversationDrafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private val _conversationsRefreshing = MutableStateFlow(false)

    private val _urlInput = MutableStateFlow("")
    private val _portInput = MutableStateFlow("")
    private val _tokenInput = MutableStateFlow("")
    private val _pinInput = MutableStateFlow("")
    private val _sessionQuery = MutableStateFlow("")
    private val _drafts = MutableStateFlow<Map<String, String>>(emptyMap())
    private var draftSaveJob: Job? = null

    /** 本机阅读位置：sessionId → 最后一条已读消息 id。 */
    private val _readPositions = MutableStateFlow<Map<String, String>>(emptyMap())
    val readPositions: StateFlow<Map<String, String>> = _readPositions.asStateFlow()
    private val _pendingNavigation = MutableStateFlow<String?>(null)

    val pendingNavigation: StateFlow<String?> = _pendingNavigation.asStateFlow()
    val settings: StateFlow<PocketSettings> = repository.settings
    val channels: StateFlow<List<ChannelSummary>> = repository.channels
    val presets: StateFlow<List<com.profer.pocket.model.AgentPresetSummary>> = repository.presets
    val chatTools: StateFlow<List<com.profer.pocket.model.ChatToolSummary>> = repository.chatTools

    /** 各会话待发送的图片附件（已保存到电脑端会话目录）。 */
    private val _attachments = MutableStateFlow<Map<String, List<AgentAttachment>>>(emptyMap())
    val attachments: StateFlow<Map<String, List<AgentAttachment>>> = _attachments.asStateFlow()

    private data class ChatInputs(
        val chats: Map<String, SessionChat>,
        val sessions: List<SessionSummary>,
        val workspaces: List<WorkspaceSummary>,
        val status: ConnectionStatus,
        val pendingSends: Int,
    )

    private val chatInputs = combine(
        repository.chats,
        repository.sessions,
        repository.workspaces,
        repository.connectionStatus,
        repository.pendingSendCount,
    ) { chats, sessions, workspaces, status, pendingSends ->
        ChatInputs(chats, sessions, workspaces, status, pendingSends)
    }

    val chatUiState: StateFlow<ChatUiState> = combine(
        chatInputs,
        _selectedSessionId,
        _selectionRestored,
        _drafts,
        repository.presets,
    ) { inputs, selected, restored, drafts, presets ->
        // 恢复完成前不要用「目录第一条」冒充当前会话，避免启动时闪一下别的会话。
        val id = if (restored) selected ?: inputs.sessions.firstOrNull()?.id else null
        UiMapping.chatState(
            sessionId = id,
            chat = inputs.chats[id],
            sessions = inputs.sessions,
            workspaces = inputs.workspaces,
            status = inputs.status,
            queuedSends = inputs.pendingSends,
            draft = drafts[id].orEmpty(),
            presets = presets,
        )
    }
        // 消息 Markdown 分块与整表映射都不该占用主线程：流式时主线程只负责重组。
        .flowOn(Dispatchers.Default)
        .stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        ChatUiState(
            title = "会话",
            project = "Profer",
            modelLabel = "Profer",
            modeLabel = "执行",
            messages = emptyList(),
            streaming = false,
            offline = true,
            pendingPermission = null,
        ),
    )

    private data class CatalogInputs(
        val sessions: List<SessionSummary>,
        val workspaces: List<WorkspaceSummary>,
        val status: ConnectionStatus,
        val runningIds: Set<String>,
    )

    /**
     * 流式 token 每 60ms 更新一次 chats；只有「运行集合变化」才驱动目录重算，
     * 否则整张 875 条会话列表会跟着每次 token 重新映射，主线程直接掉帧。
     */
    private val runningSessionIds = repository.chats
        .map { chats ->
            chats.values.asSequence().filter { it.running }.map { it.sessionId }.toSet()
        }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    private val catalogInputs = combine(
        repository.sessions,
        repository.workspaces,
        repository.connectionStatus,
        runningSessionIds,
    ) { sessions, workspaces, status, runningIds ->
        CatalogInputs(
            sessions = sessions,
            workspaces = workspaces,
            status = status,
            runningIds = runningIds,
        )
    }

    private data class ConversationInputs(
        val conversations: List<ConversationSummary>,
        val messages: Map<String, List<DomainChatMessage>>,
        val streams: Map<String, ChatStream>,
        val status: ConnectionStatus,
        val pendingSends: Int,
    )

    private val conversationInputs = combine(
        repository.conversations,
        repository.conversationMessages,
        repository.chatStreams,
        repository.connectionStatus,
        repository.pendingSendCount,
    ) { conversations, messages, streams, status, pendingSends ->
        ConversationInputs(conversations, messages, streams, status, pendingSends)
    }

    val conversationListState: StateFlow<ConversationListState> = combine(
        conversationInputs,
        _activeConversationId,
        _conversationQuery,
        _conversationsRefreshing,
    ) { inputs, active, query, refreshing ->
        UiMapping.conversationListState(inputs.conversations, active, query, refreshing)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConversationListState())

    val conversationUiState: StateFlow<ConversationUiState> = combine(
        conversationInputs,
        _activeConversationId,
        _conversationDrafts,
    ) { inputs, active, drafts ->
        UiMapping.conversationUiState(
            conversation = inputs.conversations.firstOrNull { it.id == active },
            messages = inputs.messages[active].orEmpty(),
            stream = inputs.streams[active],
            draft = drafts[active].orEmpty(),
            status = inputs.status,
            queuedSends = inputs.pendingSends,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConversationUiState())

    private val _refreshing = MutableStateFlow(false)
    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy.asStateFlow()

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message.asStateFlow()

    val sessionListState: StateFlow<SessionListState> = combine(
        catalogInputs,
        _selectedSessionId,
        _sessionQuery,
        _refreshing,
    ) { inputs, selected, query, refreshing ->
        UiMapping.sessionListState(
            sessions = inputs.sessions,
            workspaces = inputs.workspaces,
            currentSessionId = selected,
            query = query,
            online = inputs.status is ConnectionStatus.Connected,
            runningSessionIds = inputs.runningIds,
            endpoint = (inputs.status as? ConnectionStatus.Connected)?.endpoint,
            refreshing = refreshing,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            SessionListState(projects = emptyList(), connected = false),
        )

    /** 完整项目目录：不受抽屉搜索过滤影响，供新建/移动会话选择。 */
    val projectOptions: StateFlow<List<ProjectOption>> = combine(
        repository.workspaces,
        repository.sessions,
    ) { workspaces, sessions ->
        workspaces
            .filterNot { it.isTeam }
            .map { workspace ->
                ProjectOption(
                    id = workspace.id,
                    name = workspace.displayName,
                    sessionCount = sessions.count {
                        it.workspaceId == workspace.id && !it.isArchived
                    },
                )
            }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(
            viewModelScope,
            SharingStarted.Eagerly,
            emptyList(),
        )

    private data class ConnectionInputs(
        val status: ConnectionStatus,
        val url: String,
        val port: String,
        val token: String,
        val pin: String,
        val latencyMs: Int?,
    )

    val connectionUiState: StateFlow<ConnectionUiState> = combine(
        combine(
            combine(repository.connectionStatus, _urlInput, _portInput) { status, url, port -> Triple(status, url, port) },
            combine(_tokenInput, _pinInput, repository.latencyMs) { token, pin, latency -> Triple(token, pin, latency) },
        ) { (status, url, port), (token, pin, latency) -> ConnectionInputs(status, url, port, token, pin, latency) },
        repository.devices,
        repository.pairingState,
        repository.endpointFailures,
        repository.registryError,
    ) { inputs, devices, pairing, failures, registryError ->
        UiMapping.connectionState(
            status = inputs.status,
            url = inputs.url,
            port = inputs.port,
            token = inputs.token,
            pin = inputs.pin,
            hasSavedToken = repository.hasSavedToken(),
            latencyMs = inputs.latencyMs,
            devices = devices.devices,
            activeDeviceId = devices.activeId,
            pairing = pairing,
            endpointFailures = failures,
            registryError = registryError,
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.Eagerly,
        UiMapping.connectionState(
            status = repository.connectionStatus.value,
            url = _urlInput.value,
            port = _portInput.value,
            token = "",
            pin = "",
            hasSavedToken = repository.hasSavedToken(),
            devices = repository.devices.value.devices,
            activeDeviceId = repository.devices.value.activeId,
            pairing = repository.pairingState.value,
            endpointFailures = repository.endpointFailures.value,
            registryError = repository.registryError.value,
        ),
    )

    init {
        // 本地状态读盘放到后台：SharedPreferences / Keystore 首次访问不应该卡住首帧。
        viewModelScope.launch(Dispatchers.IO) {
            val saved = repository.savedEndpoint().orEmpty()
            if (saved.isNotEmpty() && _urlInput.value.isEmpty()) {
                val (host, port) = splitEndpoint(saved)
                _urlInput.value = host
                _portInput.value = port
            }
        }
        viewModelScope.launch(Dispatchers.IO) {
            _drafts.value = container.draftStore.load()
        }
        viewModelScope.launch(Dispatchers.IO) {
            _readPositions.value = container.readStateStore.load()
        }
        viewModelScope.launch(Dispatchers.IO) {
            val last = runCatching { container.lastSessionStore.load() }
                .getOrDefault(com.profer.pocket.data.store.LastSessionStore.State())
            last.agentSessionId?.let { _selectedSessionId.value = it }
            last.conversationId?.let { _activeConversationId.value = it }
            _selectionRestored.value = true
        }
        viewModelScope.launch {
            repository.connectionStatus.collect { status ->
                if (status is ConnectionStatus.Connected) {
                    _selectedSessionId.value?.let { repository.openSession(it) }
                }
            }
        }
        viewModelScope.launch {
            // 目录加载与「上次会话」恢复是两条异步线：都到位后再决定选中项，
            // 否则快照先到时会先用第一条会话覆盖掉用户上次打开的那条。
            combine(
                _selectionRestored,
                repository.sessions,
                repository.catalogLoaded,
            ) { restored, sessions, loaded -> Triple(restored, sessions, loaded) }
                .collect { (restored, sessions, loaded) ->
                    if (!restored) return@collect
                    val current = _selectedSessionId.value
                    if (current != null && sessions.any { it.id == current }) {
                        if (repository.chats.value[current]?.loaded != true) repository.openSession(current)
                        return@collect
                    }
                    // 目录还没读到内容时保留恢复值，避免「加载中」被当成「会话已删除」。
                    if (!loaded && sessions.isEmpty()) return@collect
                    val next = sessions.firstOrNull()?.id
                    _selectedSessionId.value = next
                    if (next != null) repository.openSession(next)
                    persistLastSession()
                }
        }
    }

    /** 记住上次打开的 Agent 会话与 Chat 对话，供下次冷启动恢复。 */
    private fun persistLastSession() {
        val state = com.profer.pocket.data.store.LastSessionStore.State(
            agentSessionId = _selectedSessionId.value,
            conversationId = _activeConversationId.value,
        )
        viewModelScope.launch(Dispatchers.IO) {
            container.lastSessionStore.save(state)
        }
    }

    // ===== 会话 =====

    fun selectSession(sessionId: String) {
        if (sessionId == _selectedSessionId.value) {
            viewModelScope.launch { repository.markSessionReadIfUnread(sessionId) }
            return
        }
        _selectedSessionId.value = sessionId
        persistLastSession()
        viewModelScope.launch {
            repository.openSession(sessionId)
            // 预设是工作区级配置：切会话时按新会话所属项目重拉。
            repository.refreshPresets(workspaceSlugFor(sessionId))
        }
    }

    private fun workspaceSlugFor(sessionId: String): String? {
        val workspaceId = repository.sessions.value.firstOrNull { it.id == sessionId }?.workspaceId
            ?: return null
        return repository.workspaces.value.firstOrNull { it.id == workspaceId }?.slug
    }

    /** 当前操作目标：显式选中的会话；冷启动自动选中时回退到列表首个，与 chatUiState 同口径。 */
    private fun currentSessionId(): String? =
        _selectedSessionId.value ?: repository.sessions.value.firstOrNull()?.id

    /**
     * 新建会话：项目内走草稿复用（已有草稿直接打开），不传项目则创建独立会话。
     * 成功后才切换选中，失败只提示，不留下空会话。
     */
    fun newSession(workspaceId: String? = null) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.createSession(workspaceId)
                    .onSuccess { created ->
                        _selectedSessionId.value = created.id
                        persistLastSession()
                        repository.openSession(created.id)
                        repository.refreshPresets(workspaceSlugFor(created.id))
                    }
                    .onFailure { _message.value = it.toPocketMessage() }
            } finally {
                _busy.value = false
            }
        }
    }

    // ===== 会话操作 =====

    fun renameSession(sessionId: String, title: String) {
        runOperation { repository.renameSession(sessionId, title) }
    }

    fun togglePin(session: SessionItem) = togglePin(session.id)

    fun togglePin(sessionId: String) {
        runOperation { repository.toggleSessionPin(sessionId) }
    }

    fun toggleRead(session: SessionItem) {
        runOperation {
            if (session.unread) {
                repository.markSessionRead(session.id)
            } else {
                repository.markSessionUnread(session.id)
            }
        }
    }

    fun moveSession(sessionId: String, targetWorkspaceId: String) {
        runOperation { repository.moveSessionToWorkspace(sessionId, targetWorkspaceId) }
    }

    fun deleteSession(sessionId: String) {
        runOperation { repository.deleteSession(sessionId) }
    }

    fun setHomeMode(mode: HomeMode) {
        _homeMode.value = mode
        if (mode == HomeMode.Chat) {
            viewModelScope.launch {
                _conversationsRefreshing.value = true
                // Chat 工具开关是全局配置，进 Chat 时拉一次，避免用到过期快照。
                repository.refreshChatTools()
                val ok = repository.refreshConversations()
                // 冷启动刚连上时第一次 list_conversations 可能超时：重试一次，避免 Chat 停在空态。
                if (!ok && repository.conversations.value.isEmpty()) {
                    kotlinx.coroutines.delay(800)
                    repository.refreshConversations()
                }
                _conversationsRefreshing.value = false
                // 恢复上次打开的 Chat 对话；已被删除时回落到第一条。
                val conversations = repository.conversations.value
                val active = _activeConversationId.value
                when {
                    active != null && conversations.any { it.id == active } -> openConversation(active)
                    conversations.isNotEmpty() -> openConversation(conversations.first().id)
                    // 还没拉到列表：保留恢复值，等下一次进入 Chat 再校验。
                }
            }
        }
    }

    fun openConversation(conversationId: String) {
        _activeConversationId.value = conversationId
        persistLastSession()
        viewModelScope.launch {
            if (repository.conversations.value.none { it.id == conversationId }) {
                repository.refreshConversations()
            }
            repository.openConversation(conversationId)
        }
    }

    fun newConversation() {
        viewModelScope.launch {
            _conversationsRefreshing.value = true
            val (channelId, modelId) = currentChatModel()
            val id = repository.createConversation(
                channelId = channelId,
                modelId = modelId,
            )
            _conversationsRefreshing.value = false
            if (id == null) {
                _message.value = "新建对话失败，先确认电脑端在线"
            } else {
                _homeMode.value = HomeMode.Chat
                _activeConversationId.value = id
                persistLastSession()
            }
        }
    }

    fun refreshConversations() {
        viewModelScope.launch {
            _conversationsRefreshing.value = true
            repository.refreshConversations()
            _conversationsRefreshing.value = false
        }
    }

    fun updateConversationQuery(query: String) {
        _conversationQuery.value = query
    }

    fun onConversationInputChange(text: String) {
        val id = _activeConversationId.value ?: return
        _conversationDrafts.update { it + (id to text) }
    }

    fun sendConversation() {
        val id = _activeConversationId.value ?: return
        val text = _conversationDrafts.value[id].orEmpty()
        if (text.isBlank()) return
        _conversationDrafts.update { it - id }
        viewModelScope.launch {
            val (channelId, modelId) = currentChatModel()
            repository.sendConversationMessage(
                conversationId = id,
                text = text,
                channelIdFallback = channelId,
                modelIdFallback = modelId,
            )
        }
    }

    /** 新对话以当前 Agent 模型为初始值；已绑定模型的对话用自己的 channel/model 发送。 */
    private fun currentChatModel(): Pair<String?, String?> {
        val state = chatUiState.value
        if (state.channelId != null) return state.channelId to state.modelId
        val channel = repository.channels.value.firstOrNull { it.enabled && it.firstModelId != null }
        return channel?.id to channel?.firstModelId
    }

    fun selectConversationModel(channelId: String, modelId: String) {
        val id = _activeConversationId.value ?: return
        viewModelScope.launch {
            if (!repository.updateConversationModel(id, channelId, modelId)) {
                _message.value = "切换模型失败，先确认电脑端在线"
            }
        }
    }

    fun deleteConversation(conversationId: String) {
        viewModelScope.launch {
            val ok = repository.deleteConversation(conversationId)
            if (!ok) {
                _message.value = "删除对话失败"
            } else if (_activeConversationId.value == conversationId) {
                _activeConversationId.value = null
                persistLastSession()
            }
        }
    }

    fun stopConversation() {
        val id = _activeConversationId.value ?: return
        viewModelScope.launch { repository.stopConversation(id) }
    }

    fun selectModel(channelId: String, modelId: String) {
        val id = currentSessionId() ?: return
        runOperation { repository.updateSessionModel(id, channelId, modelId) }
    }

    fun selectPermissionMode(mode: String) {
        val id = currentSessionId() ?: return
        runOperation { repository.updatePermissionMode(id, mode) }
    }

    fun selectPreset(presetId: String) {
        val id = currentSessionId() ?: return
        runOperation { repository.updateSessionPreset(id, presetId) }
    }

    /** 打开预设菜单时补拉一次：冷启动或旧服务端首次返回空时，下次打开能自愈。 */
    fun refreshCurrentPresets() {
        val id = currentSessionId() ?: return
        viewModelScope.launch { repository.refreshPresets(workspaceSlugFor(id)) }
    }

    fun toggleChatTool(toolId: String, enabled: Boolean) {
        viewModelScope.launch {
            repository.updateChatTool(toolId, enabled)
                .onFailure { _message.value = it.toPocketMessage() }
        }
    }

    fun refreshChatTools() {
        viewModelScope.launch { repository.refreshChatTools() }
    }

    fun refreshCatalog() {
        if (_refreshing.value) return
        viewModelScope.launch {
            _refreshing.value = true
            try {
                repository.refreshCatalog()
                repository.refreshChatTools()
                // 冷启动不会走 selectSession：目录就绪后给当前会话补一次预设。
                // 自动选中的第一个会话没有写进 selectedSessionId，这里用同一套回退口径。
                val presetSessionId = _selectedSessionId.value ?: repository.sessions.value.firstOrNull()?.id
                presetSessionId?.let { id ->
                    repository.refreshPresets(workspaceSlugFor(id))
                }
            } finally {
                _refreshing.value = false
            }
        }
    }

    fun consumeMessage() {
        _message.value = null
    }

    private fun <T> runOperation(
        onSuccess: (T) -> Unit = {},
        result: suspend () -> Result<T>,
    ) {
        if (_busy.value) return
        viewModelScope.launch {
            _busy.value = true
            try {
                result()
                    .onSuccess(onSuccess)
                    .onFailure { _message.value = it.toPocketMessage() }
            } finally {
                _busy.value = false
            }
        }
    }

    fun onInputChange(text: String) {
        val id = currentSessionId() ?: return
        _drafts.update { it + (id to text) }
        persistDraftsSoon()
    }

    /** 草稿落盘防抖：连续输入不每次写 SharedPreferences。 */
    private fun persistDraftsSoon() {
        draftSaveJob?.cancel()
        draftSaveJob = viewModelScope.launch {
            delay(300)
            container.draftStore.save(_drafts.value)
        }
    }

    fun send() {
        val id = currentSessionId() ?: return
        val text = _drafts.value[id].orEmpty()
        // 只有附件、没有文字也允许发送（与桌面 canSend 同口径）。
        val files = _attachments.value[id].orEmpty()
        if (text.isBlank() && files.isEmpty()) return
        _drafts.update { it + (id to "") }
        persistDraftsSoon()
        // 附件按桌面协议拼到正文前：Agent 用 Read 读取（图片同样走这条链路）。
        val payload = if (files.isEmpty()) {
            text
        } else {
            buildString {
                append("<attached_files>\n")
                files.forEach { append("- ${it.filename}: ${it.path}\n") }
                append("</attached_files>\n\n")
                append(text)
            }
        }
        _attachments.update { it - id }
        // 运行中的发送默认排队（queue_message），不打断当前 turn；停止键仍是显式动作。
        val running = repository.chats.value[id]?.running == true
        viewModelScope.launch {
            if (running) {
                repository.queueMessage(id, payload).onFailure { _message.value = it.toPocketMessage() }
            } else {
                repository.sendMessage(id, payload)
            }
        }
    }

    /**
     * 手动压缩上下文：与桌面 ContextUsageBadge 同语义，向 Agent 发送 `/compact`。
     * 运行中不压缩，避免与活跃 turn 撞车（桌面同样限制）。
     */
    fun compactContext() {
        val id = currentSessionId() ?: return
        if (repository.chats.value[id]?.running == true) {
            _message.value = "运行中无法压缩上下文"
            return
        }
        viewModelScope.launch {
            repository.sendMessage(id, "/compact")
        }
    }

    fun attachImage(sessionId: String, filename: String, base64: String) {
        viewModelScope.launch {
            repository.saveAgentAttachment(sessionId, filename, base64)
                .onSuccess { file ->
                    _attachments.update { it + (sessionId to (it[sessionId].orEmpty() + file)) }
                }
                .onFailure { _message.value = it.toPocketMessage() }
        }
    }

    fun removeAttachment(sessionId: String, path: String) {
        _attachments.update { map ->
            val next = map[sessionId].orEmpty().filterNot { it.path == path }
            if (next.isEmpty()) map - sessionId else map + (sessionId to next)
        }
    }

    /** 滚到底部时记录本机阅读位置；只增不减，避免回滚后重复提示新消息。 */
    fun markReadUpTo(sessionId: String, messageId: String) {
        if (messageId.isBlank()) return
        if (_readPositions.value[sessionId] == messageId) return
        _readPositions.update { it + (sessionId to messageId) }
        container.readStateStore.save(_readPositions.value)
    }

    suspend fun loadFilePreview(
        sessionId: String,
        path: String,
        filename: String,
    ): Result<com.profer.pocket.model.RemoteFilePreview> =
        repository.readFilePreview(sessionId, path, filename)

    /**
     * 消息内图片缩略图：只拉一次，按路径缓存。
     * 字节不在 WS 帧里，由 `read_file_as_data_url` 按需取，桌面端二次校验会话授权范围。
     */
    private val messageImageCache = mutableMapOf<String, androidx.compose.ui.graphics.ImageBitmap>()

    suspend fun loadMessageImage(
        sessionId: String,
        path: String,
    ): androidx.compose.ui.graphics.ImageBitmap? {
        messageImageCache[path]?.let { return it }
        val preview = repository
            .readFilePreview(sessionId, path, path.substringAfterLast('/'))
            .getOrNull()
            ?: return null
        val dataUrl = preview.dataUrl ?: return null
        val bitmap = withContext(Dispatchers.Default) { decodeImageDataUrl(dataUrl) } ?: return null
        // 简单上限：会话图片不多，超过就整体清空，避免长期占用内存。
        if (messageImageCache.size >= MAX_CACHED_MESSAGE_IMAGES) messageImageCache.clear()
        messageImageCache[path] = bitmap
        return bitmap
    }

    /**
     * 修改重发（对齐桌面 Agent）：回退到该用户消息之前最近的 assistant 锚点，再发送新内容。
     * 破坏性：锚点之后的对话会被截断，工作区文件按快照恢复。
     */
    fun rewriteAndResend(messageId: String, newText: String) {
        val id = currentSessionId() ?: return
        val chat = repository.chats.value[id] ?: return
        if (chat.running) {
            _message.value = "Agent 正在运行，先停止再修改"
            return
        }
        val text = newText.trim()
        if (text.isEmpty()) return
        val index = chat.messages.indexOfFirst { it.id == messageId }
        if (index < 0) return
        val anchor = chat.messages.subList(0, index)
            .lastOrNull { it is ChatEntry.Assistant } as? ChatEntry.Assistant
        if (anchor != null && !looksLikeUuid(anchor.id)) {
            _message.value = "这条历史缺少定位信息，请在电脑端修改"
            return
        }
        viewModelScope.launch {
            _busy.value = true
            try {
                repository.rewindSession(id, anchor?.id)
                    .onSuccess { repository.sendMessage(id, text) }
                    .onFailure { _message.value = it.toPocketMessage() }
            } finally {
                _busy.value = false
            }
        }
    }

    private fun looksLikeUuid(value: String): Boolean =
        value.length == 36 && value.count { it == '-' } == 4

    /** 发送失败的单条重试：复用原消息 uuid，不新增气泡。 */
    fun retrySend(messageId: String) {
        val id = currentSessionId() ?: return
        viewModelScope.launch { repository.retrySend(id, messageId) }
    }

    fun resend(text: String) {
        val id = currentSessionId() ?: return
        val running = repository.chats.value[id]?.running == true
        viewModelScope.launch {
            if (running) {
                repository.queueMessage(id, text).onFailure { _message.value = it.toPocketMessage() }
            } else {
                repository.sendMessage(id, text)
            }
        }
    }

    fun answerQuestion(answers: Map<String, String>) {
        val id = currentSessionId() ?: return
        val pending = currentChat()?.pendingInteraction as? PendingInteraction.Question ?: return
        viewModelScope.launch { repository.respondAskUser(id, pending.id, answers) }
    }

    fun stop() {
        val id = currentSessionId() ?: return
        viewModelScope.launch { repository.stopSession(id) }
    }

    fun allowPermission() {
        val id = currentSessionId() ?: return
        val pending = currentChat()?.pendingInteraction ?: return
        viewModelScope.launch {
            when (pending) {
                is PendingInteraction.Permission ->
                    repository.respondPermission(id, pending.id, allow = true)

                is PendingInteraction.PlanApproval ->
                    repository.respondPlan(id, pending.id, approve = true)

                is PendingInteraction.Question -> Unit
            }
        }
    }

    fun alwaysAllowPermission() {
        val id = currentSessionId() ?: return
        val pending = currentChat()?.pendingInteraction ?: return
        viewModelScope.launch {
            when (pending) {
                is PendingInteraction.Permission ->
                    repository.respondPermission(id, pending.id, allow = true, alwaysAllow = true)

                is PendingInteraction.PlanApproval ->
                    repository.respondPlan(id, pending.id, approve = true, alwaysAllow = true)

                is PendingInteraction.Question -> Unit
            }
        }
    }

    fun denyPermission() {
        val id = currentSessionId() ?: return
        val pending = currentChat()?.pendingInteraction ?: return
        viewModelScope.launch {
            when (pending) {
                is PendingInteraction.Permission ->
                    repository.respondPermission(id, pending.id, allow = false)

                is PendingInteraction.PlanApproval ->
                    repository.respondPlan(id, pending.id, approve = false)

                is PendingInteraction.Question -> Unit
            }
        }
    }

    fun updateSessionQuery(query: String) {
        _sessionQuery.value = query
    }

    // ===== 连接 =====

    fun connect() {
        val ok = repository.connect(
            endpointInput = composeEndpoint(_urlInput.value, _portInput.value),
            tokenInput = _tokenInput.value,
            pinInput = _pinInput.value.ifBlank { null },
        )
        if (ok) {
            _tokenInput.value = ""
            _pinInput.value = ""
            val (host, port) = splitEndpoint(repository.savedEndpoint().orEmpty())
            _urlInput.value = host
            _portInput.value = port
        }
        container.syncBackgroundService()
    }

    fun updateUrl(url: String) {
        _urlInput.value = url
    }

    fun updatePort(port: String) {
        _portInput.value = port.filter { it.isDigit() }.take(5)
    }

    fun updatePin(pin: String) {
        _pinInput.value = pin.trim()
    }

    /** 地址 + 端口拼成连接地址：已带 scheme 时原样使用，方便直接粘贴完整 URL。 */
    private fun composeEndpoint(host: String, port: String): String {
        val trimmed = host.trim()
        if (trimmed.contains("://")) return trimmed
        val p = port.trim()
        return if (p.isEmpty()) trimmed else "$trimmed:$p"
    }

    /** 回显已保存地址：拆成「主机」与「端口」两个输入框。 */
    private fun splitEndpoint(url: String): Pair<String, String> {
        if (url.isBlank()) return "" to ""
        val uri = runCatching { java.net.URI(url) }.getOrNull() ?: return url to ""
        val host = uri.host?.removePrefix("[")?.removeSuffix("]") ?: return url to ""
        return host to if (uri.port > 0) uri.port.toString() else ""
    }

    /** 仅 debug：验收/自动化用启动参数直接配置连接，不经过输入框。 */
    fun connectWith(endpoint: String, token: String) {
        val (host, port) = splitEndpoint(endpoint)
        _urlInput.value = host
        _portInput.value = port
        if (repository.connect(endpoint, token)) {
            _tokenInput.value = ""
            _pinInput.value = ""
            val (savedHost, savedPort) = splitEndpoint(repository.savedEndpoint().orEmpty())
            _urlInput.value = savedHost
            _portInput.value = savedPort
            container.syncBackgroundService()
        }
    }

    fun updateToken(token: String) {
        _tokenInput.value = token
    }

    /** 首帧完成：允许启动前台服务。 */
    fun onUiReady() {
        container.onUiReady()
    }

    fun retryConnection() {
        if (repository.savedEndpoint() != null) {
            repository.onForeground()
        } else {
            connect()
        }
    }

    fun disconnect() {
        repository.disconnect()
        container.syncBackgroundService()
    }

    // ===== 扫码配对与多设备 =====

    /** 扫码结果交给仓库：解析 → 配对 → 保存凭据 → 立即连接。 */
    fun pairWithQr(text: String) {
        repository.pairWithQr(text)
        container.syncBackgroundService()
    }

    fun dismissPairing() {
        repository.consumePairingState()
    }

    fun selectDevice(deviceId: String) {
        repository.selectDevice(deviceId)
        container.syncBackgroundService()
    }

    fun removeDevice(deviceId: String) {
        repository.removeDevice(deviceId)
        container.syncBackgroundService()
    }

    // ===== 设置 =====

    fun updateTheme(theme: ThemePreference) {
        repository.updateSettings {
            it.copy(
                theme = when (theme) {
                    ThemePreference.System -> ThemeMode.System
                    ThemePreference.Light -> ThemeMode.Light
                    ThemePreference.Dark -> ThemeMode.Dark
                },
            )
        }
    }

    fun updateNotifications(enabled: Boolean) = updateSettings { it.copy(notificationsEnabled = enabled) }

    fun updatePermissionAlerts(enabled: Boolean) = updateSettings { it.copy(permissionAlertsEnabled = enabled) }

    fun updateAutoReconnect(enabled: Boolean) = updateSettings { it.copy(autoReconnect = enabled) }

    fun updateBackgroundConnection(enabled: Boolean) = updateSettings { it.copy(backgroundConnection = enabled) }

    private fun updateSettings(transform: (PocketSettings) -> PocketSettings) {
        repository.updateSettings(transform)
        container.syncBackgroundService()
    }

    // ===== 通知跳转 =====

    fun openFromNotification(sessionId: String) {
        _selectedSessionId.value = sessionId
        _pendingNavigation.value = sessionId
        viewModelScope.launch { repository.openSession(sessionId) }
    }

    fun consumeNavigation() {
        _pendingNavigation.value = null
    }

    fun onForeground() {
        repository.onForeground()
        repository.refreshCatalogIfOnline()
    }

    private fun currentChat(): SessionChat? {
        val id = currentSessionId() ?: return null
        return repository.chats.value[id]
    }
}

/** 消息图片缓存上限：图片不多，超过就整体清空。 */
private const val MAX_CACHED_MESSAGE_IMAGES = 24
