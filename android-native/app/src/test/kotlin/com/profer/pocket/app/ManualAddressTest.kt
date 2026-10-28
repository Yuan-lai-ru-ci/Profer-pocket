package com.profer.pocket.app

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 手动连接表单的地址拼装：显式 `ws://` 必须保留，端口不能丢也不能重复。
 */
class ManualAddressTest {

    @Test
    fun `scheme 与端口分居两个输入框时仍拼出完整地址`() {
        assertEquals(
            "ws://yuan.profer.cn:24221",
            ManualAddress.compose("ws://yuan.profer.cn", "24221"),
        )
    }

    @Test
    fun `已带端口的地址不会被重复拼端口`() {
        assertEquals(
            "ws://yuan.profer.cn:24221",
            ManualAddress.compose("ws://yuan.profer.cn:24221", "9000"),
        )
    }

    @Test
    fun `裸主机与局域网地址保持原行为`() {
        assertEquals("192.168.1.8:7788", ManualAddress.compose("192.168.1.8", "7788"))
        assertEquals("192.168.1.8", ManualAddress.compose("192.168.1.8", ""))
    }

    @Test
    fun `回显保留显式 scheme 以便重连不被升级成 wss`() {
        assertEquals(
            "ws://yuan.profer.cn" to "24221",
            ManualAddress.split("ws://yuan.profer.cn:24221/ws"),
        )
        assertEquals("wss://remote.example.com" to "443", ManualAddress.split("wss://remote.example.com:443/ws"))
        assertEquals("ws://192.168.1.8" to "7788", ManualAddress.split("ws://192.168.1.8:7788/ws"))
    }
}
