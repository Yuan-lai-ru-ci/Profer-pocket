package com.profer.pocket.app

import android.app.Application
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner

class ProferApplication : Application(), DefaultLifecycleObserver {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super<Application>.onCreate()
        container = AppContainer(this)
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        container.start()
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
