package com.profer.pocket.data

import com.profer.pocket.data.protocol.EndpointParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class EndpointParserTest {

    @Test
    fun `http 局域网地址转成 ws 并补 ws 路径`() {
        val parsed = EndpointParser.normalize("http://192.168.1.8:7788")
        assertEquals("ws://192.168.1.8:7788/ws", parsed?.wsUrl)
        assertNull(parsed?.token)
    }

    @Test
    fun `https 转 wss`() {
        assertEquals(
            "wss://profer.example.com/ws",
            EndpointParser.normalize("https://profer.example.com")?.wsUrl,
        )
    }

    @Test
    fun `显式 ws 保留明文并标记 hadScheme`() {
        val parsed = EndpointParser.normalize("ws://yuan.profer.cn:24221")
        assertEquals("ws://yuan.profer.cn:24221/ws", parsed?.wsUrl)
        assertEquals(true, parsed?.hadScheme)
        // 没写 scheme 的公网地址仍先归一化成 ws，由仓库层决定是否升 wss。
        assertEquals(false, EndpointParser.normalize("yuan.profer.cn:24221")?.hadScheme)
    }

    @Test
    fun `无 scheme 时补 ws`() {
        assertEquals("ws://10.0.0.2:7788/ws", EndpointParser.normalize("10.0.0.2:7788")?.wsUrl)
    }

    @Test
    fun `query 里的 token 被抽出且不进入地址`() {
        val parsed = EndpointParser.normalize("ws://10.0.0.2:7788/ws?token=abc%20123&foo=bar")
        assertEquals("abc 123", parsed?.token)
        assertEquals("ws://10.0.0.2:7788/ws?foo=bar", parsed?.wsUrl)
    }

    @Test
    fun `显式 token 优先于 query`() {
        val parsed = EndpointParser.normalize("ws://10.0.0.2:7788/ws?token=query", "explicit")
        assertEquals("explicit", parsed?.token)
    }

    @Test
    fun `自定义路径保留`() {
        assertEquals(
            "wss://example.com/gateway/ws",
            EndpointParser.normalize("https://example.com/gateway/ws")?.wsUrl,
        )
    }

    @Test
    fun `空地址与非法 scheme 返回 null`() {
        assertNull(EndpointParser.normalize("   "))
        assertNull(EndpointParser.normalize("ftp://example.com"))
        assertNull(EndpointParser.normalize("ws://"))
    }
}
