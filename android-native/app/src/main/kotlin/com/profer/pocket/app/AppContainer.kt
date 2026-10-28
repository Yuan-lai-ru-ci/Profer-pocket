package com.profer.pocket.app

import android.content.Context
import com.profer.pocket.data.PocketRepository
import com.profer.pocket.data.net.OkHttpSocketTransport
import com.profer.pocket.data.net.PocketConnection
import com.profer.pocket.data.settings.AndroidSettingsStore
import com.profer.pocket.data.store.AndroidCredentialStore
import com.profer.pocket.data.store.AndroidDraftStore
import com.profer.pocket.data.store.AndroidLastSessionStore
import com.profer.pocket.data.store.AndroidOutgoingQueueStore
import com.profer.pocket.data.store.AndroidProjectPinStore
import com.profer.pocket.data.store.AndroidReadStateStore
import com.profer.pocket.data.store.AndroidRegistryStorage
import com.profer.pocket.data.store.KeystoreRegistryCipher
import com.profer.pocket.data.store.AndroidResumeStateStore
import com.profer.pocket.data.store.AndroidSessionSnapshotStore
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.remote.DeviceRegistry
import com.profer.pocket.remote.OkHttpRemoteSocketFactory
import com.profer.pocket.remote.PairingClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 手动依赖容器（不引入 DI 框架）：连接、仓库、存储各一份，Application 持有。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = AndroidSettingsStore(appContext)
    val draftStore = AndroidDraftStore(appContext)
    val readStateStore = AndroidReadStateStore(appContext)
    val lastSessionStore = AndroidLastSessionStore(appContext)
    val projectPinStore = AndroidProjectPinStore(appContext)

    private val connection = PocketConnection(
        transports = OkHttpSocketTransport.factory(),
        scope = appScope,
        resumeState = AndroidResumeStateStore(appContext),
        // 断线队列落盘：进程被系统回收后重放，服务端按 clientMessageId 幂等。
        queueStore = AndroidOutgoingQueueStore(appContext),
    )

    private val networkMonitor = AndroidNetworkMonitor(appContext)

    /** 多设备注册表：资料与每设备凭据分开存，凭据走 Keystore AES-GCM。 */
    private val deviceRegistry = DeviceRegistry(
        storage = AndroidRegistryStorage(appContext),
        cipher = KeystoreRegistryCipher(),
    )

    val repository = PocketRepository(
        connection = connection,
        credentials = AndroidCredentialStore(appContext),
        registry = deviceRegistry,
        pairing = PairingClient(OkHttpRemoteSocketFactory()),
        snapshots = AndroidSessionSnapshotStore(appContext),
        settingsStore = settingsStore,
        scope = appScope,
    )

    /** 前台服务状态去重：每次 onStart / 每个连接状态变化都会触发同步。 */
    private val serviceDesired = AtomicBoolean(false)
    private val serviceSyncQueued = AtomicBoolean(false)

    init {
        appScope.launch {
            repository.connectionStatus.collect { syncBackgroundService() }
        }
        // 预热 Keystore 凭据：避免首次读凭据落在主线程（首帧路径上）。
        appScope.launch { repository.preloadCredentials() }
        networkMonitor.start()
        appScope.launch {
            networkMonitor.available.collect { repository.onNetworkChanged(it) }
        }
    }

    /** Compose 首帧完成：此时才允许启动前台服务（见 [AppForeground.firstFrameDrawn]）。 */
    fun onUiReady() {
        AppForeground.firstFrameDrawn = true
        syncBackgroundService()
    }

    fun start() {
        // 先把首帧让给 Compose：连接后立刻刷新 800+ 会话目录会在低端设备上和
        // 首次组合抢 CPU（实测主线程首帧被拖到十几秒），延迟 800ms 不影响体感。
        appScope.launch {
            delay(800)
            repository.start()
            syncBackgroundService()
        }
    }

    /**
     * 前台服务只在「已配置连接 + 用户开启后台连接 + 不是明确的凭据失效」时运行。
     * 启动前要求进程在前台，避免后台启动前台服务被系统拒绝。
     */
    fun syncBackgroundService() {
        // 调用方可能是主线程（Application.onStart / 状态流）。startForegroundService
        // 是跨进程 binder 调用，系统繁忙时能把主线程顶住几秒，直接把首帧推后。
        // 这里只排队，真正的判断与调用放到后台调度器，并且同一时刻只排一次。
        if (!serviceSyncQueued.compareAndSet(false, true)) return
        appScope.launch {
            try {
                applyBackgroundService()
            } finally {
                serviceSyncQueued.set(false)
            }
        }
    }

    private fun applyBackgroundService() {
        val settings = repository.settings.value
        val status = repository.connectionStatus.value
        val configured = repository.savedEndpoint() != null
        // 只在链路确实存在（连接中 / 已连接 / 等待重连）时保活；
        // 用户主动断开、凭据失效、关闭自动重连后都停止前台服务。
        val active = status is ConnectionStatus.Connecting ||
            status is ConnectionStatus.Connected ||
            status is ConnectionStatus.Reconnecting
        val shouldRun = settings.backgroundConnection && configured && active
        if (shouldRun) {
            // 后台启动前台服务会被系统拒绝；首帧没好时启动会被 5s 超时掐死。
            // 两种情况都不记录状态，等回前台 / 首帧完成后再来一次。
            if (!AppForeground.isForeground || !AppForeground.firstFrameDrawn) return
            if (serviceDesired.getAndSet(true)) return
            PocketConnectionService.start(appContext)
        } else {
            if (!serviceDesired.getAndSet(false)) return
            PocketConnectionService.stop(appContext)
        }
    }
}
