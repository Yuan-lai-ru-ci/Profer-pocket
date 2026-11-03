package com.profer.pocket.app

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.NotificationKind
import com.profer.pocket.model.OfflineCause
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 前台服务只做两件事：把进程留在后台 + 在后台投递事件通知。
 * 连接与数据归约都在 Application 持有的仓库里，避免服务重建时产生第二条链路。
 */
class PocketConnectionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var notificationJob: Job? = null

    /** 完成态的定时收起；新任务开始时取消。 */
    private var completionJob: Job? = null

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
                // 完成态不受前台限制：切走时用户看不到界面，胶囊是他唯一的反馈。
                if (notification.kind == NotificationKind.RunCompleted) {
                    showCompletion(notification.sessionId)
                }
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
                if (status !is ConnectionStatus.Connected) {
                    // 断线后状态未知：胶囊不该继续挂着「执行中」，等重连校准。
                    completionJob?.cancel()
                    PocketNotifications.cancelLiveUpdate(this@PocketConnectionService)
                }
            }
        }
        // 定时校准：WS 增量一旦漏掉某轮终态，本地 running 会卡在 true，胶囊一直「执行中」。
        // 没有 running 会话时 syncRunningSessions 直接返回，不发请求。
        scope.launch {
            while (true) {
                delay(RUNNING_SYNC_INTERVAL_MS)
                repository.syncRunningSessions()
            }
        }
        // 对话运行中把状态挂到状态栏：Android 16 是实时活动胶囊，低版本是静音常驻通知。
        // 只在「会话 / 状态」变化时更新，流式正文的每一帧都不触发通知重建。
        // 追问优先：等人回答比还在跑更需要被看见。
        scope.launch {
            repository.chats
                .map { selectLiveState(it.values) }
                .distinctUntilChanged()
                .collect { state ->
                    if (state == null) {
                        // 完成态还在展示时不要抢着收起；否则直接收起。
                        if (completionJob?.isActive != true) {
                            PocketNotifications.cancelLiveUpdate(this@PocketConnectionService)
                        }
                        return@collect
                    }
                    // 新任务开始了，完成态让位。
                    completionJob?.cancel()
                    val (text, shortText) = liveText(state)
                    PocketNotifications.postLiveUpdate(
                        context = this@PocketConnectionService,
                        sessionId = state.sessionId,
                        title = "Profer · ${state.title}",
                        text = text,
                        shortText = shortText,
                    )
                }
        }
    }

    /**
     * 任务结束：胶囊改成「已完成 / 有错误」，停 [COMPLETION_HOLD_MS] 再收起。
     *
     * 还有别的会话在跑或等人时不显示完成态，避免拿旧结果盖掉正在进行的会话。
     */
    private fun showCompletion(sessionId: String) {
        val repository = (application as ProferApplication).container.repository
        if (selectLiveState(repository.chats.value.values) != null) return
        val chat = repository.chats.value[sessionId]
        val title = chat?.title?.ifBlank { "会话" } ?: "会话"
        val shortText = when (completionKind(chat?.lastError)) {
            LiveKind.Failed -> getString(R.string.notification_live_short_failed)
            else -> getString(R.string.notification_live_short_completed)
        }
        completionJob?.cancel()
        // 完成态的展开卡片：标题写结果，正文带会话名，比只写「已完成」信息量更大。
        val failed = completionKind(chat?.lastError) == LiveKind.Failed
        PocketNotifications.postLiveUpdate(
            context = this,
            sessionId = sessionId,
            title = if (failed) "Profer · 任务结束" else "Profer · 任务完成",
            text = if (failed) "$title 已结束，有错误" else "$title 已完成",
            shortText = shortText,
        )
        completionJob = scope.launch {
            delay(COMPLETION_HOLD_MS)
            PocketNotifications.cancelLiveUpdate(this@PocketConnectionService)
        }
        vibrateCompletion(failed)
    }

    /**
     * 完成时轻震一下。
     *
     * 用户正在看屏幕时不震：界面里已经能看到结果，震动只会变成干扰。
     * 失败用两短震，和正常完成的单次短震区分开。
     */
    private fun vibrateCompletion(failed: Boolean) {
        if (AppForeground.isForeground) return
        val effect = if (failed) {
            VibrationEffect.createWaveform(longArrayOf(0, 25, 90, 25), -1)
        } else {
            VibrationEffect.createOneShot(25, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            getSystemService(VibratorManager::class.java)?.defaultVibrator?.vibrate(effect)
        } else {
            @Suppress("DEPRECATION")
            getSystemService(Vibrator::class.java)?.vibrate(effect)
        }
    }

    /** 胶囊文案：短文案最多 3 个汉字，正文可以长一些。 */
    private fun liveText(state: LiveState): Pair<String, String> {
        val (text, short) = when (val kind = state.kind) {
            is LiveKind.Working -> {
                if (kind.toolName != null) {
                    getString(R.string.notification_live_tool, kind.toolName) to
                        getString(R.string.notification_live_short_tool)
                } else {
                    getString(R.string.notification_live_reply) to
                        getString(R.string.notification_live_short_reply)
                }
            }

            LiveKind.WaitingPermission -> getString(R.string.notification_live_permission) to
                getString(R.string.notification_live_short_permission)

            LiveKind.WaitingAnswer -> getString(R.string.notification_live_question) to
                getString(R.string.notification_live_short_question)

            LiveKind.WaitingPlan -> getString(R.string.notification_live_plan) to
                getString(R.string.notification_live_short_plan)

            LiveKind.Completed -> getString(R.string.notification_live_completed) to
                getString(R.string.notification_live_short_completed)

            LiveKind.Failed -> getString(R.string.notification_live_failed) to
                getString(R.string.notification_live_short_failed)
        }
        val body = if (state.others > 0) {
            getString(R.string.notification_live_others, text, state.others)
        } else {
            text
        }
        return body to short
    }

    companion object {
        /** 完成态在状态栏停留的时长；太短看不清，太长又像卡住了。 */
        private const val COMPLETION_HOLD_MS = 10_000L

        /** 活跃状态的校准周期。 */
        private const val RUNNING_SYNC_INTERVAL_MS = 90_000L

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
