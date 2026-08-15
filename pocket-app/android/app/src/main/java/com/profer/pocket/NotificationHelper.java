package com.profer.pocket;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

/**
 * 系统通知工具：创建通知渠道 + 常驻通知 + 消息通知 + 点击导航
 *
 * - profer_keepalive：常驻通知渠道（IMPORTANCE_LOW，无音无震，可折叠，前台服务使用）
 * - profer_messages：消息提醒渠道（IMPORTANCE_HIGH，可响铃/震动）
 * - 消息通知点击 → PendingIntent 拉起 MainActivity，extra 带 sessionId / 事件类型
 */
public final class NotificationHelper {

    /** 常驻通知渠道（低优先级） */
    public static final String CHANNEL_KEEPALIVE = "profer_keepalive";
    /** 消息提醒渠道（高优先级） */
    public static final String CHANNEL_MESSAGES = "profer_messages";

    /** 常驻通知 ID */
    public static final int NOTIF_KEEPALIVE_ID = 1001;
    /** 消息通知 ID 基数（按会话+事件取模，同一会话同类型事件互相覆盖，避免刷屏） */
    private static final int NOTIF_MESSAGE_BASE = 2001;

    /** 通知点击携带的会话 ID / 事件类型 extra 键（MainActivity 读取） */
    public static final String EXTRA_SESSION_ID = "profer_pocket_session_id";
    public static final String EXTRA_EVENT_TYPE = "profer_pocket_event_type";

    private NotificationHelper() {
    }

    /** 创建两个通知渠道（幂等，重复调用无害） */
    public static void ensureChannels(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null) return;
        NotificationChannel keepalive = new NotificationChannel(
                CHANNEL_KEEPALIVE,
                "Profer 消息连接",
                NotificationManager.IMPORTANCE_LOW);
        keepalive.setDescription("后台消息连接的常驻状态");
        keepalive.enableLights(false);
        keepalive.enableVibration(false);
        keepalive.setShowBadge(false);
        nm.createNotificationChannel(keepalive);

        NotificationChannel messages = new NotificationChannel(
                CHANNEL_MESSAGES,
                "Profer 消息提醒",
                NotificationManager.IMPORTANCE_HIGH);
        messages.setDescription("Agent 权限确认、提问、计划审批、运行完成等事件提醒");
        messages.setShowBadge(true);
        nm.createNotificationChannel(messages);
    }

    /** 常驻通知（前台服务 startForeground 使用） */
    public static Notification keepaliveNotif(Context ctx) {
        return new NotificationCompat.Builder(ctx, CHANNEL_KEEPALIVE)
                .setSmallIcon(R.drawable.ic_stat_profer)
                .setContentTitle("消息通知已开启")
                .setContentText("切到其他应用或熄屏后仍保持连接")
                .setOngoing(true)
                .setSilent(true)
                .setPriority(NotificationCompat.PRIORITY_MIN)
                .setCategory(NotificationCompat.CATEGORY_SERVICE)
                .build();
    }

    /**
     * 发送消息提醒通知；无通知权限时静默失败（服务照常保活，不抛异常）。
     */
    @SuppressLint("MissingPermission")
    public static void notifyMessage(Context ctx, String title, String body,
                                     String sessionId, String type) {
        if (!hasNotificationPermission(ctx)) {
            // 权限被拒：仅保活，不发系统通知
            return;
        }
        Intent intent = new Intent(ctx, MainActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (sessionId != null) intent.putExtra(EXTRA_SESSION_ID, sessionId);
        if (type != null) intent.putExtra(EXTRA_EVENT_TYPE, type);
        PendingIntent contentIntent = PendingIntent.getActivity(
                ctx,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);

        Notification notification = new NotificationCompat.Builder(ctx, CHANNEL_MESSAGES)
                .setSmallIcon(R.drawable.ic_stat_profer)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_EVENT)
                .setAutoCancel(true)
                .setContentIntent(contentIntent)
                .build();

        try {
            NotificationManagerCompat.from(ctx).notify(messageId(sessionId, type), notification);
        } catch (SecurityException e) {
            // 权限检查与通知之间的间隙被吊销等极端情况：静默失败
        }
    }

    /** 移除常驻通知 */
    public static void cancelKeepalive(Context ctx) {
        NotificationManagerCompat.from(ctx).cancel(NOTIF_KEEPALIVE_ID);
    }

    private static boolean hasNotificationPermission(Context ctx) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** 消息通知 ID：同一会话同类型事件用同一 ID（后到覆盖先到，避免通知栏堆积） */
    private static int messageId(String sessionId, String type) {
        int hash = (sessionId == null ? "?" : sessionId).hashCode()
                ^ (type == null ? 0 : type.hashCode());
        return NOTIF_MESSAGE_BASE + Math.abs(hash) % 1000;
    }
}
