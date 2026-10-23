package com.profer.pocket.app

/** 进程级前后台标记：前台时抑制系统通知，由界面直接渲染。 */
object AppForeground {
    @Volatile
    var isForeground: Boolean = false
}
