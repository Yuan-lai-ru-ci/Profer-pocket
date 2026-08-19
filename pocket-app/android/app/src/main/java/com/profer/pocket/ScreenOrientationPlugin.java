package com.profer.pocket;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 屏幕方向控制插件（固定横屏 / 固定竖屏 / 跟随系统旋转）
 *
 * 通过 Activity.setRequestedOrientation 动态设置方向，无需修改 AndroidManifest。
 * orientation 取值：landscape（固定横屏）/ portrait（固定竖屏）/ auto（跟随系统）。
 *
 * 持久化：方向选择写入 SharedPreferences（原生层，app 覆盖安装 / 进程重启 / 强杀均保留），
 * load() 时（Activity 创建期间、WebView 加载前）读取并应用，保证重启 / 更新后方向锁定不失效。
 * 不依赖 WebView localStorage —— Capacitor 官方视为瞬态存储，重启 / 更新 / 系统回收空间时可能被清空。
 */
@CapacitorPlugin(name = "ScreenOrientation")
public class ScreenOrientationPlugin extends Plugin {

    private static final String PREFS_NAME = "profer_screen_orientation";
    private static final String KEY_ORIENTATION = "orientation";

    /** 读取持久化的方向（从未设置过时返回 auto） */
    private String getPersistedOrientation() {
        return getPersistedOrientation(getContext());
    }

    private static String getPersistedOrientation(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_ORIENTATION, "auto");
    }

    /**
     * 在 BridgeActivity 创建前应用方向，避免 WebView/Capacitor 初始化期间出现旋转窗口。
     * 仅读取原生持久化值，不会创建新的配置记录。
     */
    public static void applyPersistedOrientation(Activity activity) {
        if (activity != null) {
            activity.setRequestedOrientation(toRequestedOrientation(getPersistedOrientation(activity)));
        }
    }

    /** 持久化当前方向选择（apply 异步落盘即可，无需等待） */
    private void persistOrientation(String orientation) {
        getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_ORIENTATION, orientation)
                .apply();
    }

    /** 把方向映射到 ActivityInfo 常量 */
    private static int toRequestedOrientation(String orientation) {
        switch (orientation) {
            case "landscape":
                return ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
            case "portrait":
                return ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
            default:
                return ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
        }
    }

    /** 把方向映射到 ActivityInfo 常量并应用到当前 Activity */
    private void applyOrientation(String orientation) {
        if (getActivity() != null) {
            getActivity().setRequestedOrientation(toRequestedOrientation(orientation));
        }
    }

    /** 插件加载时（Activity 创建期间）应用持久化的方向，实现「重启保持」 */
    @Override
    public void load() {
        super.load();
        applyOrientation(getPersistedOrientation());
    }

    /** 设置屏幕方向：持久化到 SharedPreferences 并立即应用到当前 Activity */
    @PluginMethod
    public void setOrientation(PluginCall call) {
        final String orientation = call.getString("orientation", "auto");
        persistOrientation(orientation);
        applyOrientation(orientation);
        call.resolve();
    }

    /** 返回当前持久化的方向（auto / landscape / portrait），供 JS 启动时回读同步 UI */
    @PluginMethod
    public void getOrientation(PluginCall call) {
        SharedPreferences prefs = getContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        JSObject ret = new JSObject();
        ret.put("orientation", prefs.getString(KEY_ORIENTATION, "auto"));
        ret.put("configured", prefs.contains(KEY_ORIENTATION));
        call.resolve(ret);
    }
}
