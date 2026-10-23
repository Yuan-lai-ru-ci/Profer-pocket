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
    /** 已绑定设备（含手动连接）；当前设备高亮。 */
    val devices: List<DeviceUi> = emptyList(),
    val activeDeviceId: String? = null,
    /** 已扫码设备的信任模式文案；手动连接为空。 */
    val trustLabel: String = "",
    val pairing: PairingUi = PairingUi.Idle,
    /** 每个端点最近一次失败原因，用于「公网也连不上」的排障。 */
    val endpointFailures: List<String> = emptyList(),
)

@Immutable
data class DeviceUi(
    val id: String,
    val name: String,
    val address: String,
    val scopeLabel: String,
    val trustLabel: String,
    val isActive: Boolean,
    val isManual: Boolean,
)

sealed interface PairingUi {
    data object Idle : PairingUi

    data object Pairing : PairingUi

    data class Succeeded(val deviceName: String) : PairingUi

    data class Failed(val message: String) : PairingUi
}

enum class ConnectionPhase { Connected, Disconnected, Connecting, Failed }

object ConnectionPreviewData {
    val devices = listOf(
        DeviceUi(
            id = "desktop-123",
            name = "MacBook Pro",
            address = "wss://203.0.113.10:8443/ws",
            scopeLabel = "公网",
            trustLabel = "证书固定",
            isActive = true,
            isManual = false,
        ),
        DeviceUi(
            id = "legacy-1",
            name = "手动连接",
            address = "ws://192.168.1.2:7788/ws",
            scopeLabel = "局域网",
            trustLabel = "系统证书",
            isActive = false,
            isManual = true,
        ),
    )
    val connected = ConnectionUiState(phase = ConnectionPhase.Connected, devices = devices, activeDeviceId = "desktop-123")
    val disconnected = ConnectionUiState(phase = ConnectionPhase.Disconnected, devices = devices, activeDeviceId = "desktop-123")
    val connecting = ConnectionUiState(phase = ConnectionPhase.Connecting)
    val failed = ConnectionUiState(
        phase = ConnectionPhase.Failed,
        errorMessage = "连接被拒绝：请确认 Mac 上的 Profer 已开启远程访问。",
    )
    val pairingFailed = ConnectionUiState(
        phase = ConnectionPhase.Disconnected,
        pairing = PairingUi.Failed("TLS 信任锚变化，请在电脑重新扫码"),
    )
}
