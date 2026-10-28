package com.profer.pocket.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.OfflineCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 前台服务只做两件事：把进程留在后台 + 在后台投递事件通知。
 * 连接与数据归约都在 Application 持有的仓库里，避免服务重建时产生第二条链路。
 */
class PocketConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        // 通道必须先于 startForeground 建好：API 35 上引用不存在的通道会抛
        // CannotPostForegroundServiceNotificationException（首次安装必现）。
        PocketNotifications.ensureChannels(this)
        // startForegroundService 只给 5s：onCreate 里第一件事就是满足契约，
        // 不能等 onStartCommand，更不能等通知渠道与仓库订阅准备好。
        startForegroundSafely()
        observeRepository()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundSafely()
        return START_STICKY
    }

    override fun onDestroy() {
        notificationJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun startForegroundSafely() {
        try {
            ServiceCompat.startForeground(
                this,
                PocketNotifications.KEEPALIVE_ID,
                PocketNotifications.keepalive(this),
                foregroundServiceType(),
            )
        } catch (_: Exception) {
            // 极端情况下（后台启动限制）不崩溃；连接仍由进程内仓库持有。
        }
    }

    /**
     * 前台服务类型：API 34+ 用 remoteMessaging，其余回落 dataSync。
     *
     * targetSdk 35 上 dataSync 有「24 小时内累计 6 小时」的硬上限，到点系统会回调
     * onTimeout 并停服务，长连接直接被掐断；remoteMessaging 没有这个上限，
     * 语义也正是「与另一台设备保持消息通道」。
     */
    private fun foregroundServiceType(): Int = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING

        Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ->
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC

        else -> 0
    }

    private fun observeRepository() {
        val repository = (application as ProferApplication).container.repository
        notificationJob = scope.launch {
            repository.notifications.collect { notification ->
                if (AppForeground.isForeground) return@collect
                if (!PocketNotifications.allowed(notification.kind, repository.settings.value)) return@collect
                PocketNotifications.notifyMessage(this@PocketConnectionService, notification)
            }
        }
        scope.launch {
            repository.connectionStatus.collect { status ->
                if (status is ConnectionStatus.Offline && status.cause == OfflineCause.Unauthorized) {
                    stopSelf()
                }
            }
        }
    }

    companion object {
        fun start(context: Context) {
            val intent = Intent(context, PocketConnectionService::class.java)
            try {
                if (AppForeground.isForeground) {
                    // 前台用 startService：startForegroundService 有 5s 内必须 startForeground
                    // 的硬约束，冷启动主线程被首次组合占满时必然超时（系统会直接杀掉服务并抛
                    // ForegroundServiceDidNotStartInTimeException）。服务自己在 onCreate 里
                    // 调 startForeground，同样能升格成前台服务。
                    context.startService(intent)
                } else {
                    ContextCompat.startForegroundService(context, intent)
                }
            } catch (_: Exception) {
                // 系统拒绝后台启动时保持静默：界面仍可用，只是切后台后不再保活。
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PocketConnectionService::class.java))
        }
    }
}
