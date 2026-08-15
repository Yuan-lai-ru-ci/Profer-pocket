package com.profer.pocket;

import android.content.pm.ActivityInfo;

import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

/**
 * 屏幕方向控制插件（固定横屏 / 固定竖屏 / 跟随系统旋转）
 *
 * 通过 Activity.setRequestedOrientation 动态设置方向，无需修改 AndroidManifest。
 * orientation 取值：landscape（固定横屏）/ portrait（固定竖屏）/ auto（跟随系统）。
 */
@CapacitorPlugin(name = "ScreenOrientation")
public class ScreenOrientationPlugin extends Plugin {

    @PluginMethod
    public void setOrientation(PluginCall call) {
        final String orientation = call.getString("orientation", "auto");
        final int value;
        switch (orientation) {
            case "landscape":
                value = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
                break;
            case "portrait":
                value = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
                break;
            default:
                value = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED;
                break;
        }
        if (getActivity() == null) {
            call.reject("Activity not available");
            return;
        }
        getActivity().setRequestedOrientation(value);
        call.resolve();
    }
}
