package com.profer.pocket.app

import android.app.Application
import android.os.StrictMode
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class ProferApplication : Application(), DefaultLifecycleObserver {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        enableStrictModeInDebug()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        container.start()
    }

    /**
     * Debug 下把主线程磁盘/网络访问、未关闭的 Closeable 全部打回日志。
     * 只 penaltyLog，不崩溃：抓问题但不阻断调试。
     */
    private fun enableStrictModeInDebug() {
        if (!BuildConfig.DEBUG) return
        StrictMode.setThreadPolicy(
            StrictMode.ThreadPolicy.Builder()
                .detectDiskReads()
                .detectDiskWrites()
                .detectNetwork()
                .penaltyLog()
                .build(),
        )
        StrictMode.setVmPolicy(
            StrictMode.VmPolicy.Builder()
                .detectLeakedClosableObjects()
                .detectLeakedRegistrationObjects()
                .penaltyLog()
                .build(),
        )
    }

    override fun onStart(owner: LifecycleOwner) {
        AppForeground.isForeground = true
        container.repository.onForeground()
        container.syncBackgroundService()
    }

    override fun onStop(owner: LifecycleOwner) {
        AppForeground.isForeground = false
    }
}
