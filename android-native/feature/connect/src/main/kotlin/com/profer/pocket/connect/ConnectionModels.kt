package com.profer.pocket.connect

import androidx.compose.runtime.Immutable

@Immutable
data class ConnectionUiState(
    val phase: ConnectionPhase = ConnectionPhase.Disconnected,
    val url: String = "",
    val token: String = "",
    val hasSavedToken: Boolean = false,
    val deviceName: String = "Mac",
    val deviceAddress: String = "",
    val latencyMs: Int = 0,
    val daemonVersion: String = "远程",
    val networkLabel: String = "局域网",
    val protocolLabel: String = "Pocket WS v1",
    val lastSyncLabel: String = "—",
    val errorMessage: String? = null,
)

enum class ConnectionPhase { Connected, Disconnected, Connecting, Failed }

object ConnectionPreviewData {
    val connected = ConnectionUiState(phase = ConnectionPhase.Connected)
    val disconnected = ConnectionUiState(phase = ConnectionPhase.Disconnected)
    val connecting = ConnectionUiState(phase = ConnectionPhase.Connecting)
    val failed = ConnectionUiState(
        phase = ConnectionPhase.Failed,
        errorMessage = "连接被拒绝：请确认 Mac 上的 Profer 已开启远程访问。",
    )
}
