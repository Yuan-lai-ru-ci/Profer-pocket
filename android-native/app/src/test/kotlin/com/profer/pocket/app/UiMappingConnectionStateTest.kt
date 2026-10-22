package com.profer.pocket.app

import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.remote.EndpointScope
import com.profer.pocket.remote.RemoteDevice
import com.profer.pocket.remote.RemoteEndpoint
import com.profer.pocket.remote.RemoteProfile
import com.profer.pocket.remote.TransportMode
import org.junit.Assert.assertEquals
import org.junit.Test

/** 连接页已连接卡：设备名取绑定设备的 profile.deviceName，地址仍显示 endpoint。 */
class UiMappingConnectionStateTest {

    private val profile = RemoteProfile(
        schemaVersion = 2,
        deviceId = "desktop-123",
        deviceName = "书房的 Mac",
        updatedAt = 1,
        endpoints = listOf(RemoteEndpoint("ws://192.168.1.2:7788/ws", EndpointScope.LAN)),
    )

    @Test
    fun `已连接卡显示绑定设备名而不是 host`() {
        val state = UiMapping.connectionState(
            status = ConnectionStatus.Connected(profile.endpoints[0].url),
            url = "",
            token = "",
            hasSavedToken = true,
            devices = listOf(RemoteDevice(profile, TransportMode.V1)),
            activeDeviceId = profile.deviceId,
        )
        assertEquals("书房的 Mac", state.deviceName)
        assertEquals(profile.endpoints[0].url, state.deviceAddress)
    }

    @Test
    fun `手动连接没有设备资料时退回 host`() {
        val state = UiMapping.connectionState(
            status = ConnectionStatus.Connected("ws://192.168.1.9:7788/ws"),
            url = "",
            token = "token",
            hasSavedToken = true,
        )
        assertEquals("192.168.1.9", state.deviceName)
    }

    @Test
    fun `手动 legacy 设备仍然显示 host`() {
        val legacy = RemoteDevice(
            profile.copy(deviceId = "legacy-1", deviceName = "手动连接"),
            TransportMode.LEGACY_QUERY,
        )
        val state = UiMapping.connectionState(
            status = ConnectionStatus.Connected("ws://192.168.1.9:7788/ws"),
            url = "",
            token = "token",
            hasSavedToken = true,
            devices = listOf(legacy),
            activeDeviceId = "legacy-1",
        )
        assertEquals("192.168.1.9", state.deviceName)
        assertEquals("ws://192.168.1.9:7788/ws", state.deviceAddress)
    }
}
