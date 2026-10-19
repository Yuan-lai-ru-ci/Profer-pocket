package com.profer.pocket.data.net

import com.profer.pocket.data.protocol.FrameCodec
import com.profer.pocket.data.protocol.InboundFrame
import com.profer.pocket.data.store.ResumeStateStore
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.OfflineCause
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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

    private var transport: SocketTransport? = null
    private var endpoint: String? = null
    private var token: String? = null
    private var generation = 0
    private var shouldReconnect = false
    private var attempt = 0
    private var cursor = 0L
    private var serverInstanceId: String? = null
    private var lastInboundAt = 0L

    private var reconnectJob: Job? = null
    private var heartbeatJob: Job? = null
    private var persistJob: Job? = null

    private data class PendingCommand(
        val deferred: CompletableDeferred<InboundFrame.CommandResult>,
        val timeoutJob: Job,
    )

    private val pending = mutableMapOf<String, PendingCommand>()
    private val inflightSends = mutableMapOf<String, OutgoingMessage>()
    private val queue = ArrayDeque<OutgoingMessage>()

    fun connect(endpoint: String, token: String) {
        synchronized(lock) {
            val changed = this.endpoint != endpoint || this.token != token
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
            transport?.let { old ->
                transport = null
                old.close(1000, "switching")
            }
            requeueInflightSendsLocked()
            failPendingLocked(PocketDisconnectedException("连接已切换"))
            if (changed) queue.clear()

            val saved = resumeState.load(endpoint)
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
            transport?.let { old ->
                transport = null
                old.close(1000, "client stop")
            }
            failPendingLocked(PocketDisconnectedException("连接已关闭"))
            inflightSends.clear()
            queue.clear()
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
            transport?.let { old ->
                transport = null
                old.close(1000, "config error")
            }
            failPendingLocked(PocketDisconnectedException(message ?: "连接未配置"))
            queue.clear()
            _status.value = ConnectionStatus.Offline(cause, message)
        }
    }

    /** 页面回前台、用户点重连时调用；已连接/连接中不打断现有链路。 */
    fun reconnectNow() {
        synchronized(lock) {
            if (!shouldReconnect) return
            if (transport != null) return
            reconnectJob?.cancel()
            reconnectJob = null
            attempt = 0
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
            attempt = 0
            lastInboundAt = clock()
            _status.value = ConnectionStatus.Connected(endpoint.orEmpty(), serverInstanceId)
            reconnectJob?.cancel()
            reconnectJob = null
            startHeartbeatLocked(gen)
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
                is InboundFrame.Pong -> Unit
                is InboundFrame.AgentEvent -> handleAgentEventLocked(frame)
                is InboundFrame.AgentEventsResumed -> handleResumedLocked(frame)
                is InboundFrame.CommandResult -> handleCommandResultLocked(frame)
                is InboundFrame.ChatEvent -> chatEventChannel.trySend(frame)
                is InboundFrame.Unknown -> Unit
            }
        }
    }

    private fun handleHelloLocked(frame: InboundFrame.Hello) {
        val incomingInstance = frame.serverInstanceId?.takeIf { it.isNotBlank() }
        if (incomingInstance != null) {
            val changed = serverInstanceId != null && serverInstanceId != incomingInstance
            if (changed && cursor > 0) {
                // 服务端进程重启后 eventId 不再连续，旧游标必须作废。
                cursor = 0
                resumeState.clear(endpoint.orEmpty())
            }
            serverInstanceId = incomingInstance
        }
        // 服务端事件日志被截断（或实例号未轮换）时，latest 小于本地游标说明游标已失效。
        val latest = frame.latestEventId
        if (latest != null && latest < cursor) {
            cursor = latest
            resumeState.clear(endpoint.orEmpty())
        }
        _status.value = ConnectionStatus.Connected(endpoint.orEmpty(), serverInstanceId)
        sendResumeLocked()
        persistCursorLocked()
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
            markDisconnectedLocked(code, reason)
        }
    }

    private fun markDisconnectedLocked(code: Int, reason: String) {
        heartbeatJob?.cancel()
        heartbeatJob = null
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
        val delayMs = backoff.delayFor(attempt)
        _status.value = ConnectionStatus.Reconnecting(attempt, delayMs, reason)
        reconnectJob?.cancel()
        val gen = generation
        reconnectJob = scope.launch {
            delay(delayMs)
            synchronized(lock) {
                if (gen != generation || !shouldReconnect || transport != null) return@launch
                openSocketLocked(gen)
            }
        }
    }

    private fun openSocketLocked(gen: Int) {
        val endpoint = endpoint
        val token = token
        if (endpoint.isNullOrBlank() || token.isNullOrBlank()) {
            shouldReconnect = false
            _status.value = ConnectionStatus.Offline(OfflineCause.MissingCredentials)
            return
        }
        val socket = transports.create()
        transport = socket
        val url = buildUrl(endpoint, token)
        try {
            socket.connect(url, listener(gen))
        } catch (error: Exception) {
            transport = null
            shouldReconnect = false
            _status.value = ConnectionStatus.Offline(
                cause = OfflineCause.Unreachable,
                message = error.message,
            )
        }
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
                if (!transmitSendLocked(socket, message)) break
            }
        }
    }

    private fun transmitSendLocked(socket: SocketTransport, message: OutgoingMessage): Boolean {
        registerPendingLocked(message.cmdId)
        inflightSends[message.cmdId] = message
        if (!socket.send(message.frame)) {
            pending.remove(message.cmdId)?.timeoutJob?.cancel()
            inflightSends.remove(message.cmdId)
            queue.addFirst(message)
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
    }

    private fun onSendTimeoutLocked(cmdId: String) {
        val message = inflightSends.remove(cmdId) ?: return
        if (shouldReconnect) {
            queue.addFirst(message)
            sendOutcomeChannel.trySend(SendOutcome.Queued(message.clientMessageId))
        } else {
            sendOutcomeChannel.trySend(SendOutcome.Rejected(message.clientMessageId, "发送超时"))
        }
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
        val currentEndpoint = endpoint ?: return
        persistJob = scope.launch {
            delay(250)
            synchronized(lock) {
                if (endpoint == currentEndpoint) {
                    resumeState.save(currentEndpoint, cursor, serverInstanceId)
                }
            }
        }
    }

    private fun persistCursorLocked() {
        val currentEndpoint = endpoint ?: return
        resumeState.save(currentEndpoint, cursor, serverInstanceId)
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
