package com.profer.pocket;

import android.content.Intent;
import android.os.Bundle;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {
    @Override
    public void onCreate(Bundle savedInstanceState) {
        // 先于 Capacitor/WebView 初始化应用 SharedPreferences 中的方向，消除启动时序窗口。
        ScreenOrientationPlugin.applyPersistedOrientation(this);
        // 注册屏幕方向控制插件；必须在 super.onCreate 之前生效（BridgeActivity 的
        // registerPlugin 是 bridgeBuilder.addPlugin，load() 时才应用）
        this.registerPlugin(ScreenOrientationPlugin.class);
        // 注册后台消息通道插件（原生前台服务 + 系统通知 + 通知点击导航）
        this.registerPlugin(PocketMessengerPlugin.class);
        super.onCreate(savedInstanceState);
        // 冷启动由通知点击拉起时，消费 intent 中的导航信息（前端轮询 getPendingNotification 读取）
        consumeNotificationIntent(getIntent());
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        // singleTask 复用：通知点击热启动时消费导航信息
        setIntent(intent);
        consumeNotificationIntent(intent);
    }

    /** 读取通知点击携带的 sessionId/type，交给插件侧待前端消费（读取后从 intent 移除防重复） */
    private void consumeNotificationIntent(Intent intent) {
        if (intent == null) return;
        String sessionId = intent.getStringExtra(NotificationHelper.EXTRA_SESSION_ID);
        String type = intent.getStringExtra(NotificationHelper.EXTRA_EVENT_TYPE);
        if (sessionId == null && type == null) return;
        MessageService.storePendingNotification(sessionId, type);
        // 配置变更重建 Activity 时 getIntent() 仍返回原 intent，清除 extra 避免重复消费
        intent.removeExtra(NotificationHelper.EXTRA_SESSION_ID);
        intent.removeExtra(NotificationHelper.EXTRA_EVENT_TYPE);
    }
}
