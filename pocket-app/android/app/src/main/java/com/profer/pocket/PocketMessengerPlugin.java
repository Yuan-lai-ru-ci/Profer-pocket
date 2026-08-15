package com.profer.pocket;

import android.Manifest;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.content.ContextCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;

/**
 * 后台消息通道插件（原生前台服务 + 系统通知 + 通知点击导航）
 *
 * 前端通过 window.Capacitor.Plugins.PocketMessenger.* 调用：
 *  - startService({ url, token })：启动前台服务 + 建立 WS 消息通道
 *  - stopService()：停止服务、移除常驻通知
 *  - setForegroundState({ foreground })：前台(true)时抑制消息通知（去重关键）
 *  - getPendingNotification()：返回点击通知携带的 { sessionId, type }（读取后清空）
 *  - requestPermissions()：请求 POST_NOTIFICATIONS（Android 13+）
 */
@CapacitorPlugin(
        name = "PocketMessenger",
        permissions = {
                @Permission(alias = "notifications", strings = {Manifest.permission.POST_NOTIFICATIONS})
        }
)
public class PocketMessengerPlugin extends Plugin {

    @PluginMethod
    public void startService(PluginCall call) {
        String url = call.getString("url");
        String token = call.getString("token");
        if (url == null || url.isEmpty() || token == null || token.isEmpty()) {
            call.reject("url 与 token 必填");
            return;
        }
        MessageService.start(getActivity().getApplicationContext(), url, token);
        call.resolve();
    }

    @PluginMethod
    public void stopService(PluginCall call) {
        MessageService.stop(getActivity().getApplicationContext());
        call.resolve();
    }

    @PluginMethod
    public void setForegroundState(PluginCall call) {
        boolean foreground = call.getBoolean("foreground", false);
        MessageService.setForeground(foreground);
        call.resolve();
    }

    @PluginMethod
    public void getPendingNotification(PluginCall call) {
        String[] pending = MessageService.takePendingNotification();
        JSObject ret = new JSObject();
        if (pending[0] != null) ret.put("sessionId", pending[0]);
        if (pending[1] != null) ret.put("type", pending[1]);
        call.resolve(ret);
    }

    /** 诊断：返回后台通道状态（服务运行 / WS 连接 / 收到事件数 / 最近错误 / 前台状态），排查用 */
    @PluginMethod
    public void getStatus(PluginCall call) {
        JSObject ret = new JSObject();
        ret.put("diagnostic", MessageService.getDiagnostic());
        call.resolve(ret);
    }

    @PluginMethod
    public void requestPermissions(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            // Android 13 以下无需运行时通知权限
            resolveGranted(call, true);
            return;
        }
        if (ContextCompat.checkSelfPermission(getContext(), Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED) {
            resolveGranted(call, true);
            return;
        }
        // 走 Capacitor 注解权限流，弹系统权限对话框，结果回调 permissionResult
        requestPermissionForAlias("notifications", call, "permissionResult");
    }

    /** requestPermissionForAlias 的权限回调方法名（Capacitor 按此名称反射调用） */
    private void permissionResult(PluginCall call) {
        boolean granted = getPermissionState("notifications") == PermissionState.GRANTED;
        resolveGranted(call, granted);
    }

    private void resolveGranted(PluginCall call, boolean granted) {
        JSObject ret = new JSObject();
        ret.put("granted", granted);
        call.resolve(ret);
    }
}
