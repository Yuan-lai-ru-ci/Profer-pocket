package com.profer.pocket.connect

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 连接页编排回归：扫码只是连接方式之一。
 *
 * 之前已连接阶段只有「扫码连接另一台电脑」，手动地址 + Token 的入口消失，
 * 连第二台电脑时用户找不到任何输入位置。这里把「任何阶段都能添加设备」
 * 和「已连接时能看到并切换设备列表」钉成断言。
 */
class ConnectionLayoutTest {

    @Test
    fun `稳定阶段都保留添加设备入口`() {
        // 连接中不排添加区块（中途换设备会让「取消连接」指向不明），其余阶段一律可添加。
        listOf(ConnectionPhase.Connected, ConnectionPhase.Disconnected, ConnectionPhase.Failed).forEach { phase ->
            assertTrue(
                "阶段 $phase 缺少添加设备入口",
                ConnectionSection.AddDevice in ConnectionLayout.sections(phase),
            )
        }
    }

    @Test
    fun `已连接时也能看到设备列表并切换`() {
        val sections = ConnectionLayout.sections(ConnectionPhase.Connected)
        assertTrue(ConnectionSection.Devices in sections)
        // 设备列表排在添加设备之前：换机、切回旧设备是已连接时最常做的事。
        assertTrue(sections.indexOf(ConnectionSection.Devices) < sections.indexOf(ConnectionSection.AddDevice))
    }

    @Test
    fun `已连接页不再重复展示连接详情`() {
        // 网络范围在设备行已有，协议是内部细节，「最后同步」永远是 —。
        assertEquals(
            listOf(ConnectionSection.Status, ConnectionSection.Devices, ConnectionSection.AddDevice),
            ConnectionLayout.sections(ConnectionPhase.Connected),
        )
    }

    @Test
    fun `未连接与失败时先给添加入口`() {
        listOf(ConnectionPhase.Disconnected, ConnectionPhase.Failed).forEach { phase ->
            val sections = ConnectionLayout.sections(phase)
            assertTrue(
                "阶段 $phase 应先给添加入口再列设备",
                sections.indexOf(ConnectionSection.AddDevice) < sections.indexOf(ConnectionSection.Devices),
            )
        }
    }

    @Test
    fun `连接中只保留状态区块，避免中途切设备`() {
        assertEquals(listOf(ConnectionSection.Status), ConnectionLayout.sections(ConnectionPhase.Connecting))
    }

    @Test
    fun `失败阶段额外给出端点失败记录`() {
        assertTrue(ConnectionSection.EndpointFailures in ConnectionLayout.sections(ConnectionPhase.Failed))
        assertFalse(ConnectionSection.EndpointFailures in ConnectionLayout.sections(ConnectionPhase.Disconnected))
    }

    @Test
    fun `手动表单只在已连接时默认收起`() {
        assertFalse(ConnectionLayout.manualExpandedByDefault(ConnectionPhase.Connected))
        assertTrue(ConnectionLayout.manualExpandedByDefault(ConnectionPhase.Disconnected))
        assertTrue(ConnectionLayout.manualExpandedByDefault(ConnectionPhase.Failed))
    }

    @Test
    fun `粘贴配对二维码 JSON 走配对而不是当地址`() {
        assertTrue(ConnectionPaste.isPairingPayload("{\"v\":2,\"d\":\"abc\",\"h\":\"192.168.1.8:7788\"}"))
        assertTrue(ConnectionPaste.isPairingPayload("  {\"kind\":\"profer-pairing\"}"))
        assertFalse(ConnectionPaste.isPairingPayload("192.168.1.8:7788"))
        assertFalse(ConnectionPaste.isPairingPayload("wss://remote.example.com:8443/ws"))
    }
}
