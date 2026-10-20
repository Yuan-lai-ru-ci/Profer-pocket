package com.profer.pocket.app

/** 进程级前后台标记：前台时抑制系统通知，由界面直接渲染。 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false

    /**
     * 首帧是否已经画出来。
     *
     * 冷启动时主线程连续十几秒在做首次组合，任何 `startForegroundService`
     * 都会因为服务回调排不进主线程而触发 5s 超时（ForegroundServiceDidNotStartInTime）。
     */
    @Volatile
    var firstFrameDrawn: Boolean = false
}
