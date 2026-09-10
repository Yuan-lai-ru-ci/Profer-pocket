package com.profer.pocket;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Build;
import android.view.View;
import android.webkit.WebView;

import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

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
        // 注册 APK 更新插件（后台下载、SHA-256 校验与系统安装器桥接）。
        this.registerPlugin(PocketUpdaterPlugin.class);
        super.onCreate(savedInstanceState);
        syncSystemBars();
        dispatchSystemThemeToWebView();
        configureKeyboardInsets();
        // 冷启动由通知点击拉起时，消费 intent 中的导航信息（前端轮询 getPendingNotification 读取）
        consumeNotificationIntent(getIntent());
    }

    /**
     * AndroidManifest 声明了 uiMode configChanges，系统切换深浅色时不会重建 Activity；
     * 因此显式重新应用资源限定的系统栏颜色和图标对比度。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        syncSystemBars();
        dispatchSystemThemeToWebView();
    }

    private void syncSystemBars() {
        getWindow().setBackgroundDrawableResource(R.color.profer_window_background);
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.profer_status_bar));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.profer_navigation_bar));

        int flags = getWindow().getDecorView().getSystemUiVisibility();
        boolean isNight = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        if (isNight) {
            flags &= ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags &= ~View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        } else {
            flags |= View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            }
        }
        getWindow().getDecorView().setSystemUiVisibility(flags);
    }

    private void dispatchSystemThemeToWebView() {
        if (getBridge() == null) return;
        boolean isDark = (getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
        getBridge().getWebView().post(() -> getBridge().getWebView().evaluateJavascript(
                "window.dispatchEvent(new CustomEvent('profer:system-theme-change',{detail:{isDark:"
                        + isDark + "}}))", null));
    }

    /**
     * Android 15 对 targetSdk 35 强制启用 edge-to-edge，adjustResize 不一定会缩小 WebView。
     * 不给 WebView 设置 padding（padding 不一定会改变网页布局视口），而是把 IME 高度
     * 注入 CSS 变量，让 Pocket 根布局按实际可用高度重排。
     */
    private void configureKeyboardInsets() {
        if (getBridge() == null) return;
        WebView webView = getBridge().getWebView();
        if (webView == null) return;
        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            Insets imeInsets = insets.getInsets(WindowInsetsCompat.Type.ime());
            boolean imeVisible = insets.isVisible(WindowInsetsCompat.Type.ime());
            // WindowInsets 使用物理像素，而网页布局使用 CSS px；高密度设备上若直接注入，
            // 键盘高度会被放大 2～3 倍，导致可用高度被错误算成 0。
            float density = webView.getResources().getDisplayMetrics().density;
            int imeHeightPx = imeVisible ? imeInsets.bottom : 0;
            int imeHeightCss = Math.round(imeHeightPx / Math.max(1f, density));
            String script = "document.documentElement.style.setProperty('--pocket-ime-height','" + imeHeightCss + "px');"
                    + "window.dispatchEvent(new Event('resize'));";
            webView.post(() -> webView.evaluateJavascript(script, null));
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);
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
