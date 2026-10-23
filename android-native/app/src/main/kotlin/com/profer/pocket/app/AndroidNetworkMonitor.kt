package com.profer.pocket.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 系统网络可用性（默认网络回调）。
 *
 * 只要求「有网络 + 具备 INTERNET 能力」，不要求 VALIDATED：
 * 局域网直连 `ws://192.168.x.x` 时上游不一定可达，但连接本身是通的；
 * 真正的不可达仍由 socket 失败与退避兜住。
 */
class AndroidNetworkMonitor(context: Context) {

    private val manager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    private val _available = MutableStateFlow(isAvailable())
    val available: StateFlow<Boolean> = _available.asStateFlow()

    private val networks = mutableSetOf<Network>()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            synchronized(networks) { networks += network }
            _available.value = true
        }

        override fun onLost(network: Network) {
            // 只认「还剩几条网络」：onLost 这一刻 activeNetwork 可能还没切换，
            // 再去问 isAvailable() 会把刚断的网当成还在（wifi→蜂窝切换时尤其明显）。
            val remaining = synchronized(networks) {
                networks -= network
                networks.isNotEmpty()
            }
            _available.value = remaining
        }

        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            _available.value = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }
    }

    fun start() {
        val mgr = manager ?: return
        try {
            mgr.registerDefaultNetworkCallback(callback)
        } catch (_: Exception) {
            // 回调注册失败时保持初始值：连接层仍有心跳与退避兜底。
        }
    }

    fun stop() {
        try {
            manager?.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
            // 已注销或系统限制，忽略。
        }
    }

    private fun isAvailable(): Boolean {
        val mgr = manager ?: return true
        val caps = mgr.activeNetwork?.let { mgr.getNetworkCapabilities(it) } ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }
}
