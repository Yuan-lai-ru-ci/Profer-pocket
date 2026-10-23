package com.profer.pocket.data.net

import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.data.protocol.InboundFrame
import com.profer.pocket.data.store.OutgoingQueueStore
import com.profer.pocket.data.store.ResumeStateStore
import com.profer.pocket.remote.RemoteEndpoint
import com.profer.pocket.remote.RemoteProfile
import com.profer.pocket.remote.RemoteValidation
import com.profer.pocket.remote.RemoteValidationException
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.OfflineCause
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import org.json.JSONObject
import java.net.URLEncoder
import java.util.ArrayDeque
import kotlin.random.Random

class PocketDisconnectedException(message: String = "连接未就绪，请稍候重试") : Exception(message)

class PocketTimeoutException(message: String = "指令超时") : Exception(message)

class PocketCommandException(
    val code: String?,
    message: String,
) : Exception(if (code.isNullOrBlank()) message else "$code: $message")

/** 一条待发送的用户消息。重连后按原 cmdId / clientMessageId 重放，服务端幂等去重。 */
data class OutgoingMessage(
    val cmdId: String,
    val clientMessageId: String,
    val frame: String,
)

sealed interface SendOutcome {
    val clientMessageId: String

    data class Queued(override val clientMessageId: String) : SendOutcome

    data class Accepted(
        override val clientMessageId: String,
        val deduped: Boolean,
    ) : SendOutcome

    data class Rejected(
        override val clientMessageId: String,
        val message: String,
    ) : SendOutcome
}

/**
 * WebSocket 连接状态机：全局唯一一条链路。
 *
 * 行为对齐桌面 Pocket 的 ws-client.ts 与旧原生 MessageWebSocketClient：
 * - 25s 心跳 + 75s 无入站判假死；
 * - 断线 2s 起指数退避，上限 60s；
 * - close(4001) 是 token 失效，停止自动重连，避免「连上即被踢」死循环；
 * - hello 之后按游标发送 resume_agent_events，服务端实例变化则游标归零；
 * - eventId 严格递增去重；
 * - send_message 断线入队，重连后按序重放。
 */
class PocketConnection(
    private val transports: SocketTransportFactory,
    private val scope: CoroutineScope,
    private val resumeState: ResumeStateStore,
    private val clock: () -> Long = System::currentTimeMillis,
    private val heartbeat: HeartbeatPolicy = HeartbeatPolicy(),
    private val backoff: BackoffPolicy = BackoffPolicy(),
    private val requestTimeoutMs: Long = 15_000,
    private val newId: () -> String = ::defaultId,
    /** 待发队列落盘：进程被杀后仍能重放；为空时只保留内存队列（单测默认如此）。 */
    private val queueStore: OutgoingQueueStore? = null,
) {

    private val lock = Any()

    private val _status = MutableStateFlow<ConnectionStatus>(ConnectionStatus.Idle)
    val status: StateFlow<ConnectionStatus> = _status.asStateFlow()

    private val eventChannel = Channel<InboundFrame.AgentEvent>(Channel.UNLIMITED)
    val events: Flow<InboundFrame.AgentEvent> = eventChannel.receiveAsFlow()

    private val resumeChannel = Channel<InboundFrame.AgentEventsResumed>(Channel.UNLIMITED)
    val resumeResults: Flow<InboundFrame.AgentEventsResumed> = resumeChannel.receiveAsFlow()

    private val sendOutcomeChannel = Channel<SendOutcome>(Channel.UNLIMITED)
    val sendOutcomes: Flow<SendOutcome> = sendOutcomeChannel.receiveAsFlow()

    /** Chat 流式事件：与 Agent 事件分开，避免两条链路互相阻塞。 */
    private val chatEventChannel = Channel<InboundFrame.ChatEvent>(Channel.UNLIMITED)
    val chatEvents: Flow<InboundFrame.ChatEvent> = chatEventChannel.receiveAsFlow()

    /** 用户关闭「自动重连」后，断线直接进入 Offline，不再排期。 */
    @Volatile
    var autoReconnect: Boolean = true

    /** 待发送队列长度：界面用它表达「N 条消息等待发送」。 */
    private val _pendingSendCount = MutableStateFlow(0)
    val pendingSendCount: StateFlow<Int> = _pendingSendCount.asStateFlow()

    /** 最近一次 ping/pong 的往返时间；没测到就是 null，不编造「在线」。 */
    private val _latencyMs = MutableStateFlow<Int?>(null)
    val latencyMs: StateFlow<Int?> = _latencyMs.asStateFlow()

    /**
     * 宿主（ConnectivityManager）推来的网络可用性。连接层不自己监听系统，只做决策：
     * 断网立刻进入 Offline，不再等 75s 心跳假死；恢复后立刻重连，跳过剩余退避。
     */
    private var networkAvailable = true

    private var transport: SocketTransport? = null
    private var endpoint: String? = null
    private var token: String? = null
    private var generation = 0
    private var shouldReconnect = false
    private var attempt = 0
    private var cursor = 0L
    private var serverInstanceId: String? = null
    private var lastInboundAt = 0L
    private var pingSentAt: Long? = null

    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var persistJob: Job? = null
    private var queuePersistJob: Job? = null

    /** 连接目标：手动 `?token=` 旧私网路径，或扫码绑定的 v1 资料。 */
    private sealed interface Target {
        data class Legacy(val url: String, val token: String) : Target

        data class Paired(val profile: RemoteProfile, val credential: String) : Target
    }

    /** v1 握手阶段；只有 [Ready] 才允许发指令。legacy 模式恒为 [Ready]。 */
    private enum class Handshake { AwaitingRemoteHello, AwaitingAuthHello, Ready }

    private var target: Target? = null
    private var profile: RemoteProfile? = null
    private var endpoints: List<RemoteEndpoint> = emptyList()
    private var endpointIndex = 0
    private var round = 0
    private var handshake = Handshake.Ready
    private var endpointTimeoutJob: Job? = null

    private val _endpointFailures = MutableStateFlow<Map<String, String>>(emptyMap())

    /** 最近一轮每个端点失败的原因：连接页用它说明「为什么公网也连不上」。 */
    val endpointFailures: StateFlow<Map<String, String>> = _endpointFailures.asStateFlow()

    private val _profileUpdates = MutableSharedFlow<RemoteProfile>(extraBufferCapacity = 4)

    /** 服务端 `connection_profile` 通过信任断言后的新资料，由仓库落盘。 */
    val profileUpdates: Flow<RemoteProfile> = _profileUpdates.asSharedFlow()

    private data class PendingCommand(
        val deferred: CompletableDeferred<InboundFrame.CommandResult>,
        val timeoutJob: Job,
    )

    private val pending = mutableMapOf<String, PendingCommand>()
    private val inflightSends = mutableMapOf<String, OutgoingMessage>()
    private val queue = ArrayDeque<OutgoingMessage>()

    /** 手动连接（局域网地址 + `?token=`），保留为排障与旧配置回退路径。 */
    fun connect(endpoint: String, token: String) {
        synchronized(lock) {
            val previousEndpoint = this.endpoint
            val changed = previousEndpoint != endpoint || this.token != token
            this.target = Target.Legacy(endpoint, token)
            this.profile = null
            this.endpoints = emptyList()
            this.endpointIndex = 0
            this.round = 0
            this.handshake = Handshake.Ready
            this.endpoint = endpoint
            this.token = token
            shouldReconnect = true
            attempt = 0
            generation += 1
            val gen = generation
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            endpointTimeoutJob?.cancel()
            endpointTimeoutJob = null
            transport?.let { old ->
                transport = null
                old.close(1000, "switching")
            }
            requeueInflightSendsLocked()
            failPendingLocked(PocketDisconnectedException("连接已切换"))
            if (previousEndpoint != null && previousEndpoint != endpoint) {
                // 换了一台电脑：旧队列不能投到新地址上。
                queue.clear()
                queuePersistJob?.cancel()
                queuePersistJob = null
                queueStore?.clear()
            } else if (queue.isEmpty()) {
                restoreQueueLocked(resumeKey())
            }
            publishQueueLocked()

            val saved = resumeState.load(resumeKey())
            if (changed || saved == null) {
                cursor = saved?.lastEventId ?: 0L
                serverInstanceId = saved?.serverInstanceId
            } else {
                cursor = saved.lastEventId
                serverInstanceId = saved.serverInstanceId
            }
            _status.value = ConnectionStatus.Connecting
            openSocketLocked(gen)
        }
    }

    /**
     * 扫码绑定的 v1 连接：先 `remote_hello` 校验设备身份，再 `authenticate`，
     * 收到带 profile 的 `hello` 且信任断言通过后才进入 Connected。
     */
    fun connect(profile: RemoteProfile, credential: String) {
        synchronized(lock) {
            val previousDevice = this.profile?.deviceId
            val nextEndpoints = profile.orderedEndpoints()
            if (nextEndpoints.isEmpty()) {
                shouldReconnect = false
                _status.value = ConnectionStatus.Offline(OfflineCause.MissingCredentials, "连接资料没有可用端点")
                return
            }
            this.target = Target.Paired(profile, credential)
            this.profile = profile
            this.endpoints = nextEndpoints
            this.endpointIndex = 0
            this.round = 0
            this.handshake = Handshake.AwaitingRemoteHello
            this.endpoint = nextEndpoints.first().url
            this.token = credential
            shouldReconnect = true
            attempt = 0
            generation += 1
            val gen = generation
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            endpointTimeoutJob?.cancel()
            endpointTimeoutJob = null
            transport?.let { old ->
                transport = null
                old.close(1000, "switching")
            }
            requeueInflightSendsLocked()
            failPendingLocked(PocketDisconnectedException("连接已切换"))
            if (previousDevice != null && previousDevice != profile.deviceId) {
                // 换了一台电脑：旧队列不能投到新设备上。
                queue.clear()
                queuePersistJob?.cancel()
                queuePersistJob = null
                queueStore?.clear()
            } else if (queue.isEmpty()) {
                restoreQueueLocked(resumeKey())
            }
            publishQueueLocked()
            val saved = resumeState.load(resumeKey())
            cursor = saved?.lastEventId ?: 0L
            serverInstanceId = saved?.serverInstanceId
            _endpointFailures.value = emptyMap()
            _status.value = ConnectionStatus.Connecting
            openSocketLocked(gen)
        }
    }

    fun disconnect() {
        synchronized(lock) {
            shouldReconnect = false
            generation += 1
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            persistJob?.cancel()
            persistJob = null
            endpointTimeoutJob?.cancel()
            endpointTimeoutJob = null
            transport?.let { old ->
                transport = null
                old.close(1000, "client stop")
            }
            failPendingLocked(PocketDisconnectedException("连接已关闭"))
            inflightSends.clear()
            queue.clear()
            queuePersistJob?.cancel()
            queuePersistJob = null
            queueStore?.clear()
            publishQueueLocked()
            pingSentAt = null
            _latencyMs.value = null
            persistCursorLocked()
            _status.value = ConnectionStatus.Offline(OfflineCause.UserDisconnected)
        }
    }

    /** 地址/Token 不可用时由仓库调用：停止一切重试，把原因暴露给 UI。 */
    fun reportConfigurationError(cause: OfflineCause, message: String? = null) {
        synchronized(lock) {
            shouldReconnect = false
            generation += 1
            reconnectJob?.cancel()
            reconnectJob = null
            heartbeatJob?.cancel()
            heartbeatJob = null
            endpointTimeoutJob?.cancel()
            endpointTimeoutJob = null
            transport?.let { old ->
                transport = null
                old.close(1000, "config error")
            }
            failPendingLocked(PocketDisconnectedException(message ?: "连接未配置"))
            queue.clear()
            queuePersistJob?.cancel()
            queuePersistJob = null
            queueStore?.clear()
            publishQueueLocked()
            pingSentAt = null
            _latencyMs.value = null
            _status.value = ConnectionStatus.Offline(cause, message)
        }
    }

    /**
     * 网络可用性变更（由宿主 ConnectivityManager 推送）。
     *
     * - 断网：立即关掉半死链路并进入 Offline，不再等 75s 心跳超时；
     * - 恢复：跳过剩余退避立刻重建，用户不用等到 60s 上限。
     */
    fun onNetworkChanged(available: Boolean) {
        synchronized(lock) {
            if (networkAvailable == available) return
            networkAvailable = available
            if (!available) {
                reconnectJob?.cancel()
                reconnectJob = null
                heartbeatJob?.cancel()
                heartbeatJob = null
                generation += 1
                transport?.let { old ->
                    transport = null
                    old.close(1000, "network lost")
                }
                requeueInflightSendsLocked()
                failPendingLocked(PocketDisconnectedException("手机网络已断开"))
                pingSentAt = null
                _latencyMs.value = null
                when {
                    !shouldReconnect -> Unit
                    _status.value is ConnectionStatus.Idle -> Unit
                    else -> _status.value =
                        ConnectionStatus.Offline(OfflineCause.Unreachable, "手机网络已断开")
                }
                return
            }
            if (!shouldReconnect) return
            if (!autoReconnect) return
            if (transport != null && _status.value is ConnectionStatus.Connected) return
            reconnectJob?.cancel()
            reconnectJob = null
            attempt = 0
            endpointIndex = 0
            round = 0
            handshake = if (target is Target.Paired) Handshake.AwaitingRemoteHello else Handshake.Ready
            _status.value = ConnectionStatus.Connecting
            openSocketLocked(generation)
        }
    }

    /**
     * 页面回前台、用户点重连、网络恢复时调用。
     *
     * [force] 为 true 时，已连接但超过心跳死阈值的链路会被判为半死并重建：
     * 从 Doze/后台回来时 socket 往往已经断开但回调还没到，等心跳要 25~75s。
     */
    fun reconnectNow(force: Boolean = false) {
        synchronized(lock) {
            if (!shouldReconnect) return
            val socket = transport
            if (socket != null) {
                if (!force) return
                if (_status.value is ConnectionStatus.Connecting) return
                if (_status.value is ConnectionStatus.Connected &&
                    clock() - lastInboundAt <= heartbeat.deadThresholdMs
                ) {
                    return
                }
                generation += 1
                transport = null
                heartbeatJob?.cancel()
                heartbeatJob = null
                socket.close(1001, "stale link")
                requeueInflightSendsLocked()
                failPendingLocked(PocketDisconnectedException("连接已刷新"))
            }
            reconnectJob?.cancel()
            reconnectJob = null
            attempt = 0
            endpointIndex = 0
            round = 0
            handshake = if (target is Target.Paired) Handshake.AwaitingRemoteHello else Handshake.Ready
            _status.value = ConnectionStatus.Connecting
            openSocketLocked(generation)
        }
    }

    /**
     * 发送一条指令并等待 command_result。
     * 链路未就绪或发送失败抛 [PocketDisconnectedException]。
     */
    suspend fun request(
        type: String,
        build: JSONObject.() -> Unit = {},
    ): InboundFrame.CommandResult {
        val (cmdId, deferred) = synchronized(lock) {
            val socket = transport
            if (socket == null || _status.value !is ConnectionStatus.Connected) {
                throw PocketDisconnectedException()
            }
            val id = newId()
            val pendingCommand = registerPendingLocked(id)
            if (!socket.send(FrameCodec.command(type, id, build))) {
                removePendingLocked(id)?.deferred?.completeExceptionally(
                    PocketDisconnectedException("发送失败"),
                )
                throw PocketDisconnectedException("发送失败")
            }
            id to pendingCommand.deferred
        }
        val result = try {
            deferred.await()
        } finally {
            synchronized(lock) { removePendingLocked(cmdId) }
        }
        if (!result.ok) {
            throw PocketCommandException(result.conflictCode, result.error ?: "指令失败")
        }
        return result
    }

    /**
     * 发送用户消息：链路可用立即发送；断线则入队，重连后原样重放。
     * 结果通过 [sendOutcomes] 异步上报，不阻塞 UI。
     */
    fun sendMessage(message: OutgoingMessage) {
        synchronized(lock) {
            val socket = transport
            if (socket == null || _status.value !is ConnectionStatus.Connected) {
                queue.addLast(message)
                publishQueueLocked()
                schedulePersistQueueLocked()
                sendOutcomeChannel.trySend(SendOutcome.Queued(message.clientMessageId))
                return
            }
            transmitSendLocked(socket, message)
        }
    }

    // ===== socket 回调 =====

    private fun listener(gen: Int): SocketTransport.Listener = object : SocketTransport.Listener {
        override fun onOpen() = handleOpen(gen)

        override fun onText(text: String) = handleText(gen, text)

        override fun onClosed(code: Int, reason: String) = handleDisconnected(gen, code, reason)

        override fun onFailure(error: String) = handleDisconnected(gen, 0, error)
    }

    private fun handleOpen(gen: Int) {
        var shouldFlush = false
        synchronized(lock) {
            if (gen != generation) return
            if (target is Target.Paired) {
                // v1：WS 建立后仍要 remote_hello → authenticate → 带 profile 的 hello，
                // 只有信任断言通过才允许进入 Connected。
                return
            }
            attempt = 0
            lastInboundAt = clock()
            _status.value = ConnectionStatus.Connected(endpoint.orEmpty(), serverInstanceId)
            reconnectJob?.cancel()
            reconnectJob = null
            startHeartbeatLocked(gen)
            // 连上就测一次 RTT：连接页不必等第一个心跳周期才有数字。
            pingSentAt = clock()
            transport?.send(FrameCodec.ping())
            shouldFlush = true
        }
        if (shouldFlush) flushQueue()
    }

    private fun handleText(gen: Int, raw: String) {
        val frame = FrameCodec.parseInbound(raw) ?: return
        synchronized(lock) {
            if (gen != generation) return
            lastInboundAt = clock()
            when (frame) {
                is InboundFrame.Hello -> handleHelloLocked(frame)
                is InboundFrame.RemoteHello -> handleRemoteHelloLocked(frame)
                is InboundFrame.ConnectionProfile -> handleConnectionProfileLocked(frame)
                is InboundFrame.Pong -> handlePongLocked()
                is InboundFrame.AgentEvent -> handleAgentEventLocked(frame)
                is InboundFrame.AgentEventsResumed -> handleResumedLocked(frame)
                is InboundFrame.CommandResult -> handleCommandResultLocked(frame)
                is InboundFrame.ChatEvent -> chatEventChannel.trySend(frame)
                is InboundFrame.Unknown -> Unit
            }
        }
    }

    private fun handleHelloLocked(frame: InboundFrame.Hello) {
        if (target is Target.Paired) {
            // v1 只认「remote_hello → authenticate」之后的 hello：
            // - AwaitingRemoteHello 收到 hello 属于服务端顺序异常，忽略，绝不能因此进 Connected；
            // - Ready 收到重复 hello 也忽略，避免重复 resume / 重复 flush 队列。
            if (handshake != Handshake.AwaitingAuthHello) return
            if (!verifyAuthenticatedHelloLocked(frame)) return
        }
        val incomingInstance = frame.serverInstanceId?.takeIf { it.isNotBlank() }
        if (incomingInstance != null) {
            val changed = serverInstanceId != null && serverInstanceId != incomingInstance
            if (changed && cursor > 0) {
                // 服务端进程重启后 eventId 不再连续，旧游标必须作废。
                cursor = 0
                resumeState.clear(resumeKey())
            }
            serverInstanceId = incomingInstance
        }
        // 服务端事件日志被截断（或实例号未轮换）时，latest 小于本地游标说明游标已失效。
        val latest = frame.latestEventId
        if (latest != null && latest < cursor) {
            cursor = latest
            resumeState.clear(resumeKey())
        }
        _status.value = ConnectionStatus.Connected(endpoint.orEmpty(), serverInstanceId)
        sendResumeLocked()
        persistCursorLocked()
        if (target is Target.Paired) flushQueue()
    }

    /**
     * v1 鉴权前的 `remote_hello`：服务端只报身份与协议版本。
     * 设备不匹配或协议不对时立刻换下一个端点，绝不先发 credential。
     */
    private fun handleRemoteHelloLocked(frame: InboundFrame.RemoteHello) {
        if (target !is Target.Paired || handshake != Handshake.AwaitingRemoteHello) return
        val bound = profile ?: return
        if (frame.deviceId != bound.deviceId || frame.protocolVersion != 1) {
            advanceEndpointLocked(generation, endpoint.orEmpty(), "设备身份不匹配")
            return
        }
        val credential = (target as? Target.Paired)?.credential.orEmpty()
        if (credential.isBlank()) {
            fatalTrustChangeLocked("凭据缺失，请重新扫码")
            return
        }
        val payload = JSONObject()
            .put("type", "authenticate")
            .put("deviceId", bound.deviceId)
            .put("credential", credential)
        handshake = Handshake.AwaitingAuthHello
        if (transport?.send(payload.toString()) != true) {
            advanceEndpointLocked(generation, endpoint.orEmpty(), "鉴权发送失败")
        }
    }

    /**
     * 鉴权后的 `hello`：必须带 profile，且 pin 集合与 deviceId 与扫码绑定的一致。
     * 任一不符都是信任锚变化，断开并要求重新扫码。
     */
    private fun verifyAuthenticatedHelloLocked(frame: InboundFrame.Hello): Boolean {
        val bound = profile ?: return false
        if (frame.deviceId != null && frame.deviceId != bound.deviceId) {
            fatalTrustChangeLocked("设备身份变化，请重新扫码")
            return false
        }
        val raw = frame.profileJson
        if (raw == null) {
            advanceEndpointLocked(generation, endpoint.orEmpty(), "服务端未返回连接资料")
            return false
        }
        val next = try {
            RemoteValidation.parseProfileJson(raw)
        } catch (error: RemoteValidationException) {
            fatalTrustChangeLocked(error.message ?: "连接资料无效，请重新扫码")
            return false
        }
        if (next.deviceId != bound.deviceId) {
            fatalTrustChangeLocked("设备身份变化，请重新扫码")
            return false
        }
        try {
            RemoteValidation.assertProfileTrustUnchanged(bound, next)
        } catch (error: RemoteValidationException) {
            fatalTrustChangeLocked(error.message ?: "TLS 信任锚变化，请在电脑重新扫码")
            return false
        }
        adoptProfileLocked(next)
        handshake = Handshake.Ready
        endpointTimeoutJob?.cancel()
        endpointTimeoutJob = null
        _endpointFailures.value = emptyMap()
        _profileUpdates.tryEmit(next)
        return true
    }

    /** 服务端每 15s 推送连接资料：只允许改地址，pin 或设备身份变化立即断开。 */
    private fun handleConnectionProfileLocked(frame: InboundFrame.ConnectionProfile) {
        val bound = profile ?: return
        val next = try {
            RemoteValidation.parseProfileJson(frame.profileJson)
        } catch (error: RemoteValidationException) {
            fatalTrustChangeLocked("连接资料无效，请在电脑重新扫码")
            return
        }
        if (next.deviceId != bound.deviceId) {
            fatalTrustChangeLocked("设备身份变化，请重新扫码")
            return
        }
        try {
            RemoteValidation.assertProfileTrustUnchanged(bound, next)
        } catch (error: RemoteValidationException) {
            fatalTrustChangeLocked(error.message ?: "TLS 信任锚变化，请在电脑重新扫码")
            return
        }
        adoptProfileLocked(next)
        _profileUpdates.tryEmit(next)
    }

    /** 采用新资料：保留当前已连端点的下标；端点被移除时回到第一个。 */
    private fun adoptProfileLocked(next: RemoteProfile) {
        val current = endpoint
        val ordered = next.orderedEndpoints()
        profile = next
        endpoints = ordered
        val index = ordered.indexOfFirst { it.url == current }
        endpointIndex = if (index >= 0) index else 0
    }

    /** 信任锚变化：停止自动重连，把「请重新扫码」暴露给 UI。 */
    private fun fatalTrustChangeLocked(message: String) {
        shouldReconnect = false
        generation += 1
        reconnectJob?.cancel()
        reconnectJob = null
        heartbeatJob?.cancel()
        heartbeatJob = null
        endpointTimeoutJob?.cancel()
        endpointTimeoutJob = null
        transport?.let { socket ->
            transport = null
            socket.close(4001, "trust changed")
        }
        failPendingLocked(PocketDisconnectedException(message))
        pingSentAt = null
        _latencyMs.value = null
        _status.value = ConnectionStatus.Offline(OfflineCause.Unauthorized, message)
    }

    private fun handlePongLocked() {
        val sentAt = pingSentAt ?: return
        pingSentAt = null
        _latencyMs.value = ((clock() - sentAt).coerceAtLeast(0)).toInt()
    }

    private fun handleAgentEventLocked(frame: InboundFrame.AgentEvent) {
        val eventId = frame.eventId
        if (eventId != null) {
            if (eventId <= cursor) return
            cursor = eventId
            schedulePersistLocked()
        }
        eventChannel.trySend(frame)
    }

    private fun handleResumedLocked(frame: InboundFrame.AgentEventsResumed) {
        if (frame.requiresSnapshot) {
            frame.latestEventId?.let { cursor = it }
            schedulePersistLocked()
        }
        resumeChannel.trySend(frame)
    }

    private fun handleCommandResultLocked(frame: InboundFrame.CommandResult) {
        val id = frame.commandId?.takeIf { it.isNotBlank() } ?: frame.requestId?.takeIf { it.isNotBlank() }
        if (id != null) {
            val command = pending.remove(id)
            if (command != null) {
                command.timeoutJob.cancel()
                command.deferred.complete(frame)
            }
            val send = inflightSends.remove(id)
            if (send != null) settleSend(send, frame)
        }
    }

    private fun handleDisconnected(gen: Int, code: Int, reason: String) {
        synchronized(lock) {
            if (gen != generation) return
            if (transport == null) return
            transport = null
            // 4001 是明确的鉴权拒绝：停止重连，不再轮询其它端点。
            if (code != 4001 && target is Target.Paired && shouldReconnect) {
                advanceEndpointLocked(gen, endpoint.orEmpty(), reason.takeIf { it.isNotBlank() } ?: "连接已断开")
                return
            }
            markDisconnectedLocked(code, reason)
        }
    }

    private fun markDisconnectedLocked(code: Int, reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
        pingSentAt = null
        _latencyMs.value = null
        requeueInflightSendsLocked()
        failPendingLocked(PocketDisconnectedException("连接已关闭"))
        if (code == 4001) {
            shouldReconnect = false
            _status.value = ConnectionStatus.Offline(
                cause = OfflineCause.Unauthorized,
                message = reason.takeIf { it.isNotBlank() },
            )
            return
        }
        scheduleReconnectLocked(reason.takeIf { it.isNotBlank() } ?: "连接已断开")
    }

    private fun scheduleReconnectLocked(reason: String) {
        if (!shouldReconnect) {
            _status.value = ConnectionStatus.Offline(OfflineCause.UserDisconnected)
            return
        }
        if (!autoReconnect) {
            _status.value = ConnectionStatus.Offline(OfflineCause.Unreachable, "已关闭自动重连")
            return
        }
        attempt += 1
        if (!networkAvailable) {
            // 没网时不用指数退避刷重试；保留一个 60s 兜底，网络回调到了会立即重连。
            _status.value = ConnectionStatus.Offline(OfflineCause.Unreachable, "手机网络已断开")
            reconnectJob?.cancel()
            scheduleReconnectJobLocked(backoff.maxMs)
            return
        }
        val delayMs = backoff.delayFor(attempt)
        _status.value = ConnectionStatus.Reconnecting(attempt, delayMs, reason)
        reconnectJob?.cancel()
        scheduleReconnectJobLocked(delayMs)
    }

    private fun scheduleReconnectJobLocked(delayMs: Long) {
        val gen = generation
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                if (gen != generation || !shouldReconnect || transport != null) return@launch
                if (target is Target.Paired) handshake = Handshake.AwaitingRemoteHello
                openSocketLocked(gen)
            }
        }
    }

    private fun openSocketLocked(gen: Int) {
        when (val current = target) {
            is Target.Legacy -> openLegacySocketLocked(gen, current)
            is Target.Paired -> openPairedSocketLocked(gen, current)
            null -> {
                shouldReconnect = false
                _status.value = ConnectionStatus.Offline(OfflineCause.MissingCredentials)
            }
        }
    }

    private fun openLegacySocketLocked(gen: Int, target: Target.Legacy) {
        if (target.url.isBlank() || target.token.isBlank()) {
            shouldReconnect = false
            _status.value = ConnectionStatus.Offline(OfflineCause.MissingCredentials)
            return
        }
        handshake = Handshake.Ready
        endpoint = target.url
        val socket = transports.create()
        transport = socket
        try {
            socket.connect(ConnectTarget(buildUrl(target.url, target.token)), listener(gen))
        } catch (error: Exception) {
            transport = null
            shouldReconnect = false
            _status.value = ConnectionStatus.Offline(
                cause = OfflineCause.Unreachable,
                message = error.message,
            )
        }
    }

    private fun openPairedSocketLocked(gen: Int, target: Target.Paired) {
        if (target.credential.isBlank()) {
            shouldReconnect = false
            _status.value = ConnectionStatus.Offline(OfflineCause.MissingCredentials, "凭据缺失，请重新扫码")
            return
        }
        val candidate = endpoints.getOrNull(endpointIndex)
        if (candidate == null) {
            endpointIndex = 0
            finishRoundLocked(gen, endpoint.orEmpty(), "没有可用端点")
            return
        }
        endpoint = candidate.url
        handshake = Handshake.AwaitingRemoteHello
        val socket = transports.create()
        transport = socket
        try {
            socket.connect(ConnectTarget(candidate.url, candidate), listener(gen))
        } catch (error: Exception) {
            transport = null
            advanceEndpointLocked(gen, candidate.url, error.message ?: "端点不可达")
            return
        }
        scheduleEndpointTimeoutLocked(gen, candidate)
    }

    /**
     * 端点级超时：LAN 2.5s / 公网 8s。
     * 「TCP 连上」不算成功，必须走完 v1 握手（hello + authenticate + 带 profile 的 hello）。
     */
    private fun scheduleEndpointTimeoutLocked(gen: Int, endpoint: RemoteEndpoint) {
        endpointTimeoutJob?.cancel()
        endpointTimeoutJob = scope.launch {
            delay(endpoint.timeoutMs)
            synchronized(lock) {
                if (gen != generation) return@launch
                if (handshake == Handshake.Ready) return@launch
                val socket = transport ?: return@launch
                transport = null
                socket.close(1001, "endpoint timeout")
                val reason = if (handshake == Handshake.AwaitingAuthHello) "鉴权超时" else "端点探测超时"
                advanceEndpointLocked(gen, endpoint.url, reason)
            }
        }
    }

    /** 当前端点失败：记录原因，立刻尝试下一个；整轮失败后按指数退避重来。 */
    private fun advanceEndpointLocked(gen: Int, url: String, reason: String) {
        if (gen != generation) return
        endpointTimeoutJob?.cancel()
        endpointTimeoutJob = null
        _endpointFailures.value = _endpointFailures.value + (url to reason)
        transport?.let { socket ->
            transport = null
            socket.close(1001, "next endpoint")
        }
        requeueInflightSendsLocked()
        failPendingLocked(PocketDisconnectedException("连接已切换"))
        pingSentAt = null
        _latencyMs.value = null
        endpointIndex += 1
        if (endpointIndex < endpoints.size) {
            _status.value = ConnectionStatus.Connecting
            openSocketLocked(gen)
            return
        }
        endpointIndex = 0
        round += 1
        finishRoundLocked(gen, url, reason)
    }

    private fun finishRoundLocked(gen: Int, url: String, reason: String) {
        if (gen != generation) return
        if (!shouldReconnect) {
            _status.value = ConnectionStatus.Offline(OfflineCause.UserDisconnected)
            return
        }
        if (!autoReconnect) {
            _status.value = ConnectionStatus.Offline(OfflineCause.Unreachable, "已关闭自动重连")
            return
        }
        attempt += 1
        if (!networkAvailable) {
            _status.value = ConnectionStatus.Offline(OfflineCause.Unreachable, "手机网络已断开")
            reconnectJob?.cancel()
            scheduleReconnectJobLocked(backoff.maxMs)
            return
        }
        val delayMs = backoff.delayFor(attempt)
        _status.value = ConnectionStatus.Reconnecting(attempt, delayMs, reason)
        reconnectJob?.cancel()
        scheduleReconnectJobLocked(delayMs)
    }

    private fun startHeartbeatLocked(gen: Int) {
        heartbeatJob?.cancel()
        heartbeatJob = scope.launch {
            while (true) {
                delay(heartbeat.intervalMs)
                var dead = false
                synchronized(lock) {
                    if (gen != generation || transport == null) return@launch
                    if (clock() - lastInboundAt > heartbeat.deadThresholdMs) {
                        dead = true
                    } else {
                        pingSentAt = clock()
                        transport?.send(FrameCodec.ping())
                    }
                }
                if (dead) {
                    forceReconnect(gen, "75s 无入站，判定假死")
                    return@launch
                }
            }
        }
    }

    private fun forceReconnect(gen: Int, reason: String) {
        synchronized(lock) {
            if (gen != generation) return
            val socket = transport ?: return
            transport = null
            socket.close(1001, reason)
            markDisconnectedLocked(1001, reason)
        }
    }

    // ===== 发送队列 =====

    private fun flushQueue() {
        synchronized(lock) {
            val socket = transport ?: return
            if (_status.value !is ConnectionStatus.Connected) return
            // 发送失败会把消息放回队首；用剩余条数作上界，避免同一条消息在循环里打转。
            var remaining = queue.size
            while (queue.isNotEmpty() && remaining-- > 0) {
                val message = queue.removeFirst()
                publishQueueLocked()
                if (!transmitSendLocked(socket, message)) break
            }
            schedulePersistQueueLocked()
        }
    }

    private fun transmitSendLocked(socket: SocketTransport, message: OutgoingMessage): Boolean {
        registerPendingLocked(message.cmdId)
        inflightSends[message.cmdId] = message
        if (!socket.send(message.frame)) {
            pending.remove(message.cmdId)?.timeoutJob?.cancel()
            inflightSends.remove(message.cmdId)
            queue.addFirst(message)
            publishQueueLocked()
            schedulePersistQueueLocked()
            sendOutcomeChannel.trySend(SendOutcome.Queued(message.clientMessageId))
            return false
        }
        return true
    }

    private fun settleSend(message: OutgoingMessage, result: InboundFrame.CommandResult) {
        if (result.ok) {
            val deduped = (result.data as? JSONObject)?.optBoolean("deduped", false) == true
            sendOutcomeChannel.trySend(SendOutcome.Accepted(message.clientMessageId, deduped))
        } else {
            sendOutcomeChannel.trySend(
                SendOutcome.Rejected(message.clientMessageId, result.error ?: "发送失败"),
            )
        }
    }

    private fun requeueInflightSendsLocked() {
        if (inflightSends.isEmpty()) return
        val messages = inflightSends.values.toList()
        inflightSends.clear()
        messages.asReversed().forEach { message ->
            pending.remove(message.cmdId)?.timeoutJob?.cancel()
            queue.addFirst(message)
            sendOutcomeChannel.trySend(SendOutcome.Queued(message.clientMessageId))
        }
        publishQueueLocked()
        schedulePersistQueueLocked()
    }

    private fun onSendTimeoutLocked(cmdId: String) {
        val message = inflightSends.remove(cmdId) ?: return
        if (shouldReconnect) {
            queue.addFirst(message)
            publishQueueLocked()
            schedulePersistQueueLocked()
            sendOutcomeChannel.trySend(SendOutcome.Queued(message.clientMessageId))
        } else {
            sendOutcomeChannel.trySend(SendOutcome.Rejected(message.clientMessageId, "发送超时"))
        }
    }

    // ===== 队列持久化 =====

    private fun publishQueueLocked() {
        _pendingSendCount.value = queue.size
    }

    /** 小文件 + 防抖：连续发多条消息时只写一次，且不占主线程。 */
    private fun schedulePersistQueueLocked() {
        val store = queueStore ?: return
        val key = resumeKey()
        queuePersistJob?.cancel()
        val snapshot = queue.toList()
        queuePersistJob = scope.launch {
            delay(150)
            store.save(key, snapshot)
        }
    }

    private fun restoreQueueLocked(targetEndpoint: String) {
        val store = queueStore ?: return
        val stored = store.load() ?: return
        if (stored.endpoint != targetEndpoint) {
            // 上一次连的是另一台电脑：旧消息不会投到新地址。
            if (stored.items.isNotEmpty()) store.clear()
            return
        }
        if (stored.items.isEmpty()) return
        queue.addAll(stored.items)
    }

    // ===== pending 管理 =====

    private fun registerPendingLocked(cmdId: String): PendingCommand {
        val deferred = CompletableDeferred<InboundFrame.CommandResult>()
        val timeoutJob = scope.launch {
            delay(requestTimeoutMs)
            synchronized(lock) {
                val command = pending.remove(cmdId) ?: return@launch
                command.deferred.completeExceptionally(PocketTimeoutException())
                onSendTimeoutLocked(cmdId)
            }
        }
        return PendingCommand(deferred, timeoutJob).also { pending[cmdId] = it }
    }

    private fun removePendingLocked(cmdId: String): PendingCommand? {
        val command = pending.remove(cmdId) ?: return null
        command.timeoutJob.cancel()
        return command
    }

    private fun failPendingLocked(error: Exception) {
        val entries = pending.values.toList()
        pending.clear()
        entries.forEach {
            it.timeoutJob.cancel()
            it.deferred.completeExceptionally(error)
        }
    }

    // ===== 游标与地址 =====

    private fun schedulePersistLocked() {
        if (persistJob?.isActive == true) return
        val key = resumeKey()
        persistJob = scope.launch {
            delay(250)
            synchronized(lock) {
                if (resumeKey() == key) {
                    resumeState.save(key, cursor, serverInstanceId)
                }
            }
        }
    }

    private fun persistCursorLocked() {
        resumeState.save(resumeKey(), cursor, serverInstanceId)
    }

    /**
     * 游标与待发队列的归属键：v1 用设备身份（换 IP 不应丢游标），
     * 手动连接沿用地址。
     */
    private fun resumeKey(): String = when (val current = target) {
        is Target.Paired -> "device:${current.profile.deviceId}"
        is Target.Legacy -> current.url
        null -> endpoint.orEmpty()
    }

    private fun sendResumeLocked() {
        val socket = transport ?: return
        socket.send(FrameCodec.resume(newId(), cursor.takeIf { it > 0 }))
    }

    private fun buildUrl(endpoint: String, token: String): String {
        val separator = if ('?' in endpoint) '&' else '?'
        val encoded = try {
            URLEncoder.encode(token, "UTF-8").replace("+", "%20")
        } catch (_: Exception) {
            token
        }
        return "$endpoint$separator" + "token=$encoded"
    }

    private companion object {
        fun defaultId(): String = "${System.currentTimeMillis()}_${Random.nextInt(100_000, 999_999)}"
    }
}
