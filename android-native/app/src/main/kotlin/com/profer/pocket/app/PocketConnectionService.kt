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
        PocketNotifications.ensureChannels(this)
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
            val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
            ServiceCompat.startForeground(
                this,
                PocketNotifications.KEEPALIVE_ID,
                PocketNotifications.keepalive(this),
                type,
            )
        } catch (_: Exception) {
            // 极端情况下（后台启动限制）不崩溃；连接仍由进程内仓库持有。
        }
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
                ContextCompat.startForegroundService(context, intent)
            } catch (_: Exception) {
                // 系统拒绝后台启动时保持静默：界面仍可用，只是切后台后不再保活。
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PocketConnectionService::class.java))
        }
    }
}
