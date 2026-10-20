package com.profer.pocket.app

import com.profer.pocket.model.ConnectionStatus
import com.profer.pocket.model.OfflineCause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class UiMappingOfflineLabelTest {

    @Test
    fun `已连接不显示离线条`() {
        val label = UiMapping.offlineLabel(ConnectionStatus.Connected("ws://host/ws"), queuedSends = 3)
        assertNull(label)
    }

    @Test
    fun `没网时说清楚是手机没网`() {
        val label = UiMapping.offlineLabel(
            ConnectionStatus.Offline(OfflineCause.Unreachable, "手机网络已断开"),
        )
        assertEquals("手机网络已断开", label)
    }

    @Test
    fun `重连中给出下次重试时间与待发条数`() {
        val label = UiMapping.offlineLabel(
            ConnectionStatus.Reconnecting(attempt = 2, delayMs = 4_000, reason = "连接已断开"),
            queuedSends = 2,
        )
        assertEquals("连接中断 · 约 4s 后重试 · 2 条待发送", label)
    }

    @Test
    fun `凭据失效不给出误导性的重连提示`() {
        val label = UiMapping.offlineLabel(ConnectionStatus.Offline(OfflineCause.Unauthorized))
        assertEquals("连接 Token 已失效", label)
    }
}
