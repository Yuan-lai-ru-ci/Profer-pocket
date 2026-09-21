package com.profer.pocket;

import android.content.Intent;
import android.content.res.Configuration;
import android.os.Bundle;
import android.view.View;
import android.webkit.WebView;

import androidx.core.splashscreen.SplashScreen;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.getcapacitor.BridgeActivity;
import com.getcapacitor.WebViewListener;

public class MainActivity extends BridgeActivity {

    /** 最近一次换算好的 CSS px 值（-1 表示尚未收到 WindowInsets）。 */
    private int insetsSafeTopCss = -1;
    private int insetsSafeBottomCss = -1;
    private int insetsImeHeightCss = 0;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        // AndroidX Core SplashScreen 必须在 super.onCreate 前安装，保证 Android 12+ 与旧版本
        // 使用同一深色首帧；WebView 动态层随后接管并在有界时长内淡出。
        SplashScreen.installSplashScreen(this);
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
        configureWindowInsets();
        // 冷启动由通知点击拉起时，消费 intent 中的导航信息（前端轮询 getPendingNotification 读取）
        consumeNotificationIntent(getIntent());
    }

    /**
     * AndroidManifest 声明了 uiMode configChanges，系统切换深浅色时不会重建 Activity；
     * 因此显式重新应用系统栏配色与图标对比度。
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        syncSystemBars();
        dispatchSystemThemeToWebView();
    }

    /**
     * 系统栏外观。
     *
     * windowBackground / statusBarColor / navigationBarColor 三色固定为 Profer 深色
     * {@code #0b0d0e}（见 values{,-night}/colors.xml），不再跟随系统深浅色：pocket 页面主题
     * 跟随的是「App 内」设置（默认深色），若窗口跟随系统亮色，就会出现「App 深色 + 系统亮色」
     * 时窗口/导航栏泛白（鸿蒙卓易通底部白边）。
     *
     * 深色底 ⇒ 状态栏/导航栏图标必须始终为浅色，因此不再按 uiMode 分支判断对比度，
     * 统一设置为浅色图标（与 {@code @bool/profer_light_system_bars=false} 一致）。
     */
    private void syncSystemBars() {
        getWindow().setBackgroundDrawableResource(R.color.profer_window_background);
        // Android 15（targetSdk 35）强制 edge-to-edge 后这两个调用不再生效，系统栏区域直接由
        // windowBackground/页面背景呈现（同为 #0b0d0e）；保留调用以兼容 Android 14 及以下。
        getWindow().setStatusBarColor(ContextCompat.getColor(this, R.color.profer_status_bar));
        getWindow().setNavigationBarColor(ContextCompat.getColor(this, R.color.profer_navigation_bar));

        WindowInsetsControllerCompat controller = WindowCompat.getInsetsController(getWindow(), getWindow().getDecorView());
        controller.setAppearanceLightStatusBars(false);
        controller.setAppearanceLightNavigationBars(false);
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
     * 把窗口安全区（状态栏 / 导航栏）与键盘高度注入 WebView 根元素 CSS 变量。
     *
     * Android WebView 的 {@code env(safe-area-inset-*)} 恒为 0，前端无法据此避让系统栏
     * （表现为顶部内容被状态栏遮挡）；这里沿用 {@code configureKeyboardInsets} 的既有范式，
     * 把 WindowInsets 从物理 px 换算为 CSS px 后注入：
     * <ul>
     *   <li>{@code --pocket-safe-top}：顶部安全区（状态栏/刘海，CSS px）</li>
     *   <li>{@code --pocket-safe-bottom}：底部安全区（导航栏，CSS px）</li>
     *   <li>{@code --pocket-ime-height}：键盘高度（沿用既有契约，不回归）</li>
     * </ul>
     * 变量名与单位是与前端约定好的接口契约，缺失时前端以 0 退化；因此这里只在值 &gt; 0 时写入
     * 安全区变量（--pocket-ime-height 保持每次写入，含 0）。
     *
     * Android 15 对 targetSdk 35 强制 edge-to-edge，adjustResize 不一定会缩小 WebView；
     * 因此不给 WebView 设置 padding（padding 不一定改变网页布局视口），而是交给 CSS 变量重排。
     */
    private void configureWindowInsets() {
        if (getBridge() == null) return;
        WebView webView = getBridge().getWebView();
        if (webView == null) return;

        // 每次页面加载完成后重放一次：navigation commit 会替换 Document，旧文档 documentElement
        // 上注入过的 CSS 变量会一并丢失（否则冷启动/跳转后安全区变量失效）。WebView 此时已就绪。
        getBridge().addWebViewListener(new WebViewListener() {
            @Override
            public void onPageLoaded(WebView loadedWebView) {
                applyInsetsToWebView();
            }
        });

        ViewCompat.setOnApplyWindowInsetsListener(webView, (view, insets) -> {
            updateInsetsCache(view, insets);
            applyInsetsToWebView();
            return insets;
        });
        ViewCompat.requestApplyInsets(webView);
    }

    /**
     * 把 WindowInsets（物理 px）换算为 CSS px 并缓存。
     *
     * statusBars/navigationBars 的 inset 只有在「窗口确实延伸到系统栏下方」（edge-to-edge）时
     * 才代表真实遮挡区域：Android 15 强制 edge-to-edge，Android 14 及以下由 DecorView 让位。
     * 因此按 WebView 相对根视图的位置把「已经让位」的部分减掉，避免在非 edge-to-edge 设备上
     * 重复留白（顶部多出一条状态栏高度的空白）。
     * WindowInsets 使用物理像素，而网页布局使用 CSS px；高密度设备上若直接注入，会被放大
     * 2～3 倍，故统一除以 density。
     */
    private void updateInsetsCache(View view, WindowInsetsCompat insets) {
        float density = Math.max(1f, view.getResources().getDisplayMetrics().density);

        Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        Insets cutout = insets.getInsets(WindowInsetsCompat.Type.displayCutout());

        int[] location = new int[2];
        view.getLocationInWindow(location);
        View root = view.getRootView();
        int rootHeight = root != null ? root.getHeight() : view.getHeight();
        int viewBottomInWindow = location[1] + view.getHeight();

        int topPx = Math.max(bars.top, cutout.top) - Math.max(0, location[1]);
        int bottomPx = Math.max(bars.bottom, cutout.bottom) - Math.max(0, rootHeight - viewBottomInWindow);
        int imePx = insets.isVisible(WindowInsetsCompat.Type.ime())
                ? insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
                : 0;

        this.insetsSafeTopCss = Math.max(0, Math.round(topPx / density));
        this.insetsSafeBottomCss = Math.max(0, Math.round(bottomPx / density));
        this.insetsImeHeightCss = Math.max(0, Math.round(imePx / density));
    }

    /**
     * 按缓存值向 documentElement 注入 CSS 变量。只在有安全区值（&gt; 0）时写入，
     * 未写入时前端以 {@code var(--pocket-safe-top, 0px)} 退化为 0；写入后派发 resize，
     * 让依赖这些变量的布局重新计算。
     */
    private void applyInsetsToWebView() {
        if (getBridge() == null || insetsSafeTopCss < 0) return;
        WebView webView = getBridge().getWebView();
        if (webView == null) return;

        StringBuilder script = new StringBuilder();
        script.append("(function(){var r=document.documentElement;if(!r)return;");
        if (insetsSafeTopCss > 0) {
            script.append("r.style.setProperty('--pocket-safe-top','").append(insetsSafeTopCss).append("px');");
        }
        if (insetsSafeBottomCss > 0) {
            script.append("r.style.setProperty('--pocket-safe-bottom','").append(insetsSafeBottomCss).append("px');");
        }
        script.append("r.style.setProperty('--pocket-ime-height','").append(insetsImeHeightCss).append("px');");
        script.append("window.dispatchEvent(new Event('resize'));})();");

        final String javascript = script.toString();
        webView.post(() -> {
            if (getBridge() == null) return;
            WebView current = getBridge().getWebView();
            if (current != null) current.evaluateJavascript(javascript, null);
        });
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
