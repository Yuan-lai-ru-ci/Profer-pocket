package com.profer.pocket;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * 前台消息服务：常驻通知 + okhttp WS 消息通道
 *
 * - 启动即 startForeground（常驻通知），后台时保持与电脑的消息连接
 * - 收到 agent_event → 前台时抑制通知（由 WebView 全量渲染，去重关键），后台时发系统通知
 * - 解绑 / 关闭开关 / token 失效(4001) → 停止服务并移除常驻通知
 * - 权限被拒：服务照常启动（进程保活），仅通知静默失败
 */
public class MessageService extends Service implements MessageWebSocketClient.Listener {

    /** 当前服务实例（前台状态读取用） */
    private static volatile MessageService instance;
    /** 前台状态：true 时抑制消息通知（去重关键） */
    private static volatile boolean foreground = false;
    /** 最近一次启动参数（START_STICKY 系统重建时恢复） */
    private static volatile String lastUrl;
    private static volatile String lastToken;
    /** 点击通知待消费的导航信息 { sessionId, type }，读取后清空 */
    private static volatile String pendingSessionId;
    private static volatile String pendingType;

    private MessageWebSocketClient wsClient;

    /** 启动前台服务（App 在前台时调用，满足 Android 8+ 后台启动限制） */
    public static void start(Context ctx, String url, String token) {
        lastUrl = url;
        lastToken = token;
        Intent intent = new Intent(ctx, MessageService.class);
        intent.putExtra("url", url);
        intent.putExtra("token", token);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            ctx.startForegroundService(intent);
        } else {
            ctx.startService(intent);
        }
    }

    /** 停止前台服务（解绑 / 关闭开关 / token 失效） */
    public static void stop(Context ctx) {
        ctx.stopService(new Intent(ctx, MessageService.class));
    }

    /** 设置前台状态：true 时抑制消息通知 */
    public static void setForeground(boolean value) {
        foreground = value;
    }

    /** 是否前台（前台时不发系统通知） */
    public static boolean isForeground() {
        return foreground;
    }

    /** 记录点击通知带来的导航信息（MainActivity 调用），getPendingNotification 读取后清空 */
    public static void storePendingNotification(String sessionId, String type) {
        pendingSessionId = sessionId;
        pendingType = type;
    }

    /** 取出待消费的导航信息并清空；返回 { sessionId, type }（可为 null） */
    public static String[] takePendingNotification() {
        String[] result = { pendingSessionId, pendingType };
        pendingSessionId = null;
        pendingType = null;
        return result;
    }

    /** 取出并清空 WS 调试日志（前端 HUD 轮询调用） */
    public static List<String> takeLogs() {
        MessageService svc = instance;
        if (svc == null || svc.wsClient == null) return new ArrayList<>();
        return svc.wsClient.takeLogs();
    }

    /** 诊断信息：服务是否运行 + WS 连接状态 + 收到事件数 + 最近错误 + 前台状态（排查用） */
    public static String getDiagnostic() {
        MessageService svc = instance;
        if (svc == null) return "服务未运行";
        MessageWebSocketClient c = svc.wsClient;
        if (c == null) return "WS 客户端未初始化";
        return "connected=" + c.isConnected()
                + ", events=" + c.getAgentEventCount()
                + ", error=" + (c.getLastError() == null ? "无" : c.getLastError())
                + ", foreground=" + foreground;
    }

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        foreground = false;
        NotificationHelper.ensureChannels(this);
        wsClient = new MessageWebSocketClient(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String url = null;
        String token = null;
        if (intent != null) {
            url = intent.getStringExtra("url");
            token = intent.getStringExtra("token");
        }
        if (url == null) url = lastUrl;
        if (token == null) token = lastToken;

        NotificationHelper.ensureChannels(this);
        try {
            // Android 13+ 无通知权限时 startForeground 仍可调用（通知不可见，进程保活不受影响）
            startForeground(NotificationHelper.NOTIF_KEEPALIVE_ID,
                    NotificationHelper.keepaliveNotif(this));
        } catch (Exception e) {
            // 极端情况（如 ForegroundServiceStartNotAllowedException）：保活优先，不崩溃
        }

        if (url != null && token != null) {
            wsClient.connect(url, token);
        }
        return START_STICKY;
    }

    @Override
    public void onDestroy() {
        if (wsClient != null) wsClient.shutdown();
        NotificationHelper.cancelKeepalive(this);
        instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    // ===== MessageWebSocketClient.Listener =====

    @Override
    public void onOpen() {
        // 无需特殊处理
    }

    @Override
    public void onAgentEvent(String sessionId, JSONObject payload) {
        // 前台时 WebView 全量渲染，原生抑制通知（去重关键）
        if (isForeground()) {
            wsClient.addLog("前台状态，抑制系统通知");
            return;
        }
        MessageEventParser.NotifyInfo info =
                MessageEventParser.parse(sessionId, payload, wsClient.getSessionTitles());
        if (info == null) {
            wsClient.addLog("事件无需提醒（未知/解析失败/用户主动停止）");
            return;
        }
        wsClient.addLog("发出系统通知: " + info.title);
        NotificationHelper.notifyMessage(this, info.title, info.body, sessionId, info.type);
    }

    @Override
    public void onUnauthorized() {
        // token 失效(4001)：停服务并移除常驻通知；前端联动提示重新输入
        stopSelf();
    }

    @Override
    public void onClosed() {
        // 重连逻辑在 WS 客户端内部处理，服务层无需干预
    }
}
