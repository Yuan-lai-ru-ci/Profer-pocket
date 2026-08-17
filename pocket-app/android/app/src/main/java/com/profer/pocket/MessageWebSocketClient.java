package com.profer.pocket;

import android.os.Handler;
import android.os.HandlerThread;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

/**
 * 常驻 okhttp WebSocket 消息通道
 *
 * - 心跳：每 25s 发 {type:'ping'}（服务端回 {kind:'pong'}）
 * - 假死检测：75s 无任何入站帧 → 主动断开并重连（Handler postDelayed 驱动，非进程内定时器）
 * - 断网重连：2s 起指数退避，上限 60s
 * - close(4001)（token 失效）→ 回调 onUnauthorized，由服务层停服务
 * - 连接建立后发一次 list_sessions（带 _cmdId 追踪）缓存 会话 id → title
 * - 只收不发（除 ping 与 list_sessions），审批/问答/停止等交互响应由前台 WebView 发起
 */
public final class MessageWebSocketClient {

    /** 心跳周期（ms） */
    private static final long HEARTBEAT_INTERVAL_MS = 25_000;
    /** 假死阈值（ms）：75s 无入站判定半开连接 */
    private static final long DEAD_THRESHOLD_MS = 3 * HEARTBEAT_INTERVAL_MS;
    /** 重连初始延迟（ms），指数退避上限 */
    private static final long RECONNECT_INITIAL_MS = 2_000;
    private static final long RECONNECT_MAX_MS = 60_000;
    /** list_sessions 命令追踪 ID 前缀（响应 command_result 的 requestId 以此为前缀） */
    private static final String CMD_LIST_SESSIONS = "native_list_sessions";

    /** 事件回调（由 MessageService 实现） */
    public interface Listener {
        void onOpen();

        void onAgentEvent(String sessionId, JSONObject payload);

        /** close(4001) token 失效 → 停服务 */
        void onUnauthorized();

        void onClosed();
    }

    private final Listener listener;
    private final HandlerThread heartbeatThread;
    private final Handler heartbeatHandler;
    private final OkHttpClient httpClient;
    private final Map<String, String> sessionTitles = new ConcurrentHashMap<>();
    /** 连接序号：每次开启/断开连接自增，作废旧连接的回调，避免重连竞态产生重复连接 */
    private int connSeq = 0;
    /** 已排期的重连序号；-1 = 无待执行重连（防止 close+cancel 双回调重复排期） */
    private int reconnectScheduledFor = -1;
    private volatile WebSocket webSocket;
    private volatile boolean shouldReconnect = true;
    private long reconnectDelayMs = RECONNECT_INITIAL_MS;
    private volatile long lastInboundAt = 0;
    private String url;
    private String token;
    /** 诊断：当前是否已建立连接（onOpen 置 true，断开置 false） */
    private volatile boolean connected = false;
    /** 诊断：累计收到 agent_event 帧数 */
    private volatile int agentEventCount = 0;
    /** 诊断：最近一次连接失败原因（onFailure 记录） */
    private volatile String lastError = null;
    /** 调试日志队列（前端 HUD 轮询拉取；上限 MAX_LOGS，超出丢弃最旧） */
    private final ConcurrentLinkedQueue<String> logs = new ConcurrentLinkedQueue<>();
    private static final int MAX_LOGS = 60;
    /** 诊断：累计非 4001 断连/失败次数（含重连，反映后台连接稳定性） */
    private volatile int disconnectCount = 0;

    public MessageWebSocketClient(Listener listener) {
        this.listener = listener;
        this.heartbeatThread = new HandlerThread("profer-message-ws");
        this.heartbeatThread.start();
        this.heartbeatHandler = new Handler(heartbeatThread.getLooper());
        // 关键：禁用 readTimeout（默认 10s 会误杀 25s 心跳间隔的连接），
        // 假死检测由下方 75s 心跳 Handler 负责，不用 okhttp 层。
        this.httpClient = new OkHttpClient.Builder()
                .readTimeout(0, TimeUnit.MILLISECONDS)
                .build();
    }

    /** 连接（幂等：已连接同一地址则忽略；地址/令牌变化则重建） */
    public synchronized void connect(String url, String token) {
        WebSocket existing = this.webSocket;
        if (existing != null && this.url != null && this.url.equals(url)
                && this.token != null && this.token.equals(token)) {
            return;
        }
        this.url = url;
        this.token = token;
        this.shouldReconnect = true;
        this.reconnectDelayMs = RECONNECT_INITIAL_MS;
        // 关闭旧连接并作废其回调（connSeq 自增）
        if (existing != null) {
            this.webSocket = null;
            closeQuietly(existing, 1000, "switching");
        }
        openSocketLocked();
    }

    /** 断开并停止重连（解绑 / 关闭开关 / token 失效时调用） */
    public synchronized void disconnect() {
        this.shouldReconnect = false;
        this.connSeq++;
        this.reconnectScheduledFor = -1;
        this.heartbeatHandler.removeCallbacksAndMessages(null);
        WebSocket ws = this.webSocket;
        this.webSocket = null;
        if (ws != null) closeQuietly(ws, 1000, "client stop");
        this.sessionTitles.clear();
    }

    /** 释放线程资源（服务销毁时调用） */
    public synchronized void shutdown() {
        disconnect();
        heartbeatThread.quitSafely();
    }

    /** 会话标题缓存（解析事件时使用；失败/未返回时为空，正文省略会话名） */
    public Map<String, String> getSessionTitles() {
        return sessionTitles;
    }

    /** 诊断：是否已建立连接 */
    public boolean isConnected() {
        return connected;
    }

    /** 诊断：累计收到 agent_event 帧数 */
    public int getAgentEventCount() {
        return agentEventCount;
    }

    /** 诊断：最近一次连接失败原因（无则 null） */
    public String getLastError() {
        return lastError;
    }

    /** 记录一条调试日志（带时间戳，供前端 HUD 展示） */
    public void addLog(String msg) {
        java.text.SimpleDateFormat sdf = new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault());
        logs.add(sdf.format(new java.util.Date()) + " " + msg);
        while (logs.size() > MAX_LOGS) logs.poll();
    }

    /** 取出并清空日志队列（前端轮询调用） */
    public List<String> takeLogs() {
        List<String> out = new ArrayList<>();
        String s;
        while ((s = logs.poll()) != null) out.add(s);
        return out;
    }

    private void openSocketLocked() {
        final int seq = ++connSeq;
        reconnectScheduledFor = -1;
        try {
            String wsUrl = url + (url.contains("?") ? "&" : "?")
                    + "token=" + encodeQuery(token);
            addLog("发起连接 " + url);
            Request request = new Request.Builder().url(wsUrl).build();
            WebSocket ws = httpClient.newWebSocket(request, new WebSocketListener() {
                @Override
                public void onOpen(WebSocket ws, Response response) {
                    if (seq != connSeq) {
                        closeQuietly(ws, 1000, "stale");
                        return;
                    }
                    lastInboundAt = System.currentTimeMillis();
                    connected = true;
                    lastError = null;
                    addLog("WS 已连接");
                    scheduleHeartbeat();
                    sendListSessions();
                    if (listener != null) listener.onOpen();
                }

                @Override
                public void onMessage(WebSocket ws, String text) {
                    if (seq != connSeq) return;
                    // 任何入站帧都算存活信号，刷新假死计时
                    lastInboundAt = System.currentTimeMillis();
                    handleInbound(text);
                }

                @Override
                public void onClosing(WebSocket ws, int code, String reason) {
                    if (seq != connSeq) return;
                    closeQuietly(ws, code, reason);
                }

                @Override
                public void onClosed(WebSocket ws, int code, String reason) {
                    if (seq != connSeq) return;
                    connected = false;
                    disconnectCount++;
                    addLog("WS 断开 code=" + code + " reason=" + reason + "（第 " + disconnectCount + " 次断连）");
                    // 置空当前 socket：心跳 tick 不再向已关闭连接发 ping，
                    // 也避免 4001 后前端立即重新 startService 时被幂等检查跳过
                    webSocket = null;
                    heartbeatHandler.removeCallbacksAndMessages(null);
                    if (code == 4001) {
                        shouldReconnect = false;
                        if (listener != null) listener.onUnauthorized();
                        return;
                    }
                    if (listener != null) listener.onClosed();
                    scheduleReconnect(seq);
                }

                @Override
                public void onFailure(WebSocket ws, Throwable t, Response response) {
                    if (seq != connSeq) return;
                    connected = false;
                    lastError = (t != null && t.getMessage() != null) ? t.getMessage() : ("失败: " + (response != null ? response.code() : "无响应"));
                    disconnectCount++;
                    addLog("WS 失败 " + lastError + "（第 " + disconnectCount + " 次断连）");
                    heartbeatHandler.removeCallbacksAndMessages(null);
                    if (!shouldReconnect) return;
                    if (listener != null) listener.onClosed();
                    scheduleReconnect(seq);
                }
            });
            this.webSocket = ws;
        } catch (Exception e) {
            // URL 构造失败：安排重连
            scheduleReconnect(seq);
        }
    }

    /** 指数退避重连（2s 起，上限 60s）；连接序号变化则放弃，同一序号只排期一次 */
    private void scheduleReconnect(final int seq) {
        final long delay;
        synchronized (this) {
            if (!shouldReconnect || seq != connSeq) return;
            if (reconnectScheduledFor == seq) return; // 已排期，避免重复
            reconnectScheduledFor = seq;
            delay = reconnectDelayMs;
            reconnectDelayMs = Math.min(reconnectDelayMs * 2, RECONNECT_MAX_MS);
        }
        heartbeatHandler.postDelayed(() -> {
            synchronized (this) {
                if (reconnectScheduledFor == seq) reconnectScheduledFor = -1;
                if (!shouldReconnect || seq != connSeq) return;
                openSocketLocked();
            }
        }, delay);
    }

    private void scheduleHeartbeat() {
        heartbeatHandler.removeCallbacksAndMessages(null);
        heartbeatHandler.postDelayed(this::heartbeatTick, HEARTBEAT_INTERVAL_MS);
    }

    /** 心跳 tick：发 ping + 假死检测 */
    private void heartbeatTick() {
        WebSocket ws = this.webSocket;
        if (ws == null) {
            scheduleHeartbeat();
            return;
        }
        try {
            ws.send("{\"type\":\"ping\"}");
        } catch (Exception e) {
            // 发送失败：交由 onFailure 处理
        }
        // 假死检测：超过 75s 未收到任何入站帧（含 pong），判定半开连接，主动断开触发重连
        if (System.currentTimeMillis() - lastInboundAt > DEAD_THRESHOLD_MS) {
            final int seq = this.connSeq;
            addLog("假死检测：75s 无入站，主动断开重连");
            closeQuietly(ws, 1001, "ws dead");
            // close+cancel 可能双回调，scheduleReconnect 内部按 seq 去重
            scheduleReconnect(seq);
            return;
        }
        scheduleHeartbeat();
    }

    private void handleInbound(String text) {
        try {
            JSONObject json = new JSONObject(text);
            String kind = json.optString("kind", "");
            switch (kind) {
                case "hello":
                case "pong":
                    // 存活信号已在 onMessage 统一刷新
                    break;
                case "agent_event": {
                    agentEventCount++;
                    String sessionId = json.optString("sessionId", null);
                    addLog("收到 agent_event session=" + sessionId);
                    JSONObject payload = json.optJSONObject("payload");
                    if (sessionId != null && payload != null && listener != null) {
                        listener.onAgentEvent(sessionId, payload);
                    }
                    break;
                }
                case "chat_event":
                    // 本期只做 Agent 事件，chat_event 忽略
                    break;
                case "command_result":
                    handleCommandResult(json);
                    break;
                default:
                    // 未知帧忽略
                    break;
            }
        } catch (Exception e) {
            // 解析失败忽略，不崩溃
        }
    }

    /** 处理 command_result：仅消费 list_sessions 响应，缓存 会话 id → title */
    private void handleCommandResult(JSONObject json) {
        String requestId = json.optString("requestId", "");
        if (!requestId.startsWith(CMD_LIST_SESSIONS)) return;
        if (!json.optBoolean("ok", false)) return;
        JSONArray sessions = json.optJSONArray("data");
        if (sessions == null) return;
        for (int i = 0; i < sessions.length(); i++) {
            JSONObject s = sessions.optJSONObject(i);
            if (s == null) continue;
            String id = s.optString("id", "");
            String title = s.optString("title", "");
            if (!id.isEmpty()) sessionTitles.put(id, title);
        }
    }

    /** 连接建立后拉一次会话列表（带 _cmdId 追踪），失败则标题缓存为空、通知省略会话名 */
    private void sendListSessions() {
        WebSocket ws = this.webSocket;
        if (ws == null) return;
        try {
            String cmdId = CMD_LIST_SESSIONS + "_" + System.currentTimeMillis();
            ws.send("{\"type\":\"list_sessions\",\"_cmdId\":\"" + cmdId + "\"}");
        } catch (Exception e) {
            // 忽略
        }
    }

    private static String encodeQuery(String value) {
        try {
            // 与前端 encodeURIComponent 对齐：空格应为 %20 而非 +
            return URLEncoder.encode(value, "UTF-8").replace("+", "%20");
        } catch (Exception e) {
            return value == null ? "" : value;
        }
    }

    private static void closeQuietly(WebSocket ws, int code, String reason) {
        try {
            ws.close(code, reason);
        } catch (Exception e) {
            // ignore
        }
        try {
            ws.cancel();
        } catch (Exception e) {
            // ignore
        }
    }
}
