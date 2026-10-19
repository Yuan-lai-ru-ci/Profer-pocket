package com.profer.pocket.app

import android.content.Context
import com.profer.pocket.data.PocketRepository
import com.profer.pocket.data.net.OkHttpSocketTransport
import com.profer.pocket.data.net.PocketConnection
import com.profer.pocket.data.settings.AndroidSettingsStore
import com.profer.pocket.data.store.AndroidCredentialStore
import com.profer.pocket.data.store.AndroidDraftStore
import com.profer.pocket.data.store.AndroidReadStateStore
import com.profer.pocket.data.store.AndroidResumeStateStore
import com.profer.pocket.data.store.AndroidSessionSnapshotStore
import com.profer.pocket.model.ConnectionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 手动依赖容器（不引入 DI 框架）：连接、仓库、存储各一份，Application 持有。
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext
    private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val settingsStore = AndroidSettingsStore(appContext)
    val draftStore = AndroidDraftStore(appContext)
    val readStateStore = AndroidReadStateStore(appContext)

    private val connection = PocketConnection(
        transports = OkHttpSocketTransport.factory(),
        scope = appScope,
        resumeState = AndroidResumeStateStore(appContext),
    )

    val repository = PocketRepository(
        connection = connection,
        credentials = AndroidCredentialStore(appContext),
        snapshots = AndroidSessionSnapshotStore(appContext),
        settingsStore = settingsStore,
        scope = appScope,
    )

    init {
        appScope.launch {
            repository.connectionStatus.collect { syncBackgroundService() }
        }
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
        val settings = repository.settings.value
        val status = repository.connectionStatus.value
        val configured = repository.savedEndpoint() != null
        // 只在链路确实存在（连接中 / 已连接 / 等待重连）时保活；
        // 用户主动断开、凭据失效、关闭自动重连后都停止前台服务。
        val active = status is ConnectionStatus.Connecting ||
            status is ConnectionStatus.Connected ||
            status is ConnectionStatus.Reconnecting
        val shouldRun = settings.backgroundConnection && configured && active
        if (!shouldRun) {
            PocketConnectionService.stop(appContext)
            return
        }
        if (AppForeground.isForeground) {
            PocketConnectionService.start(appContext)
        }
    }
}
