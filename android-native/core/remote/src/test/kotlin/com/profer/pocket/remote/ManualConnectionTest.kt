package com.profer.pocket.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * 手动连接通道的「域名 + 自签指纹」能力。
 *
 * 背景：桌面自签证书的 SAN 只有公网 IP（`remote-tls-identity.ts` 只写 IP），
 * 用户把同一条链路挂在自己的域名/穿透后面时，只能靠 pin 认身份。
 * 这条放宽只属于手动连接；二维码与 v1 资料必须与桌面保持同一套严格规则。
 */
class ManualConnectionTest {

    private val pin = "sha256/AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="

    private fun endpointJson(url: String, trustMode: String?, pinValue: String? = null) = JSONObject().apply {
        put("url", url)
        put("scope", "public")
        if (trustMode != null) put("trustMode", trustMode)
        if (pinValue != null) {
            put("pin", JSONObject().put("algorithm", RemotePin.ALGORITHM).put("value", pinValue))
        }
    }

    @Test
    fun `manual endpoint accepts domain with pinned self-signed identity`() {
        val endpoint = RemoteValidation.validateManualEndpoint(
            endpointJson("wss://yuan.profer.cn/ws", TrustMode.PINNED.wire, pin),
        )
        assertEquals("wss://yuan.profer.cn/ws", endpoint.url)
        assertEquals(TrustMode.PINNED, endpoint.trustMode)
        assertEquals(pin, endpoint.pin?.value)
        assertEquals(EndpointScope.PUBLIC, endpoint.scope)
    }

    @Test
    fun `pairing profile still rejects domain pinned endpoint`() {
        try {
            RemoteValidation.validateEndpoint(endpointJson("wss://yuan.profer.cn/ws", TrustMode.PINNED.wire, pin))
            fail("二维码/配对资料不能接受域名 + pin")
        } catch (error: RemoteValidationException) {
            assertEquals("Pinned 地址必须是 IP", error.message)
        }
    }

    @Test
    fun `manual endpoint accepts explicit public cleartext ws for tunnels`() {
        // 现场：frp / SSH 隧道把桌面 7788 映射到公网端口，链路上没有 TLS。
        val endpoint = RemoteValidation.validateManualEndpoint(
            endpointJson("ws://yuan.profer.cn:24221/ws", trustMode = null),
        )
        assertEquals("ws://yuan.profer.cn:24221/ws", endpoint.url)
        assertEquals(EndpointScope.PUBLIC, endpoint.scope)
        assertEquals(null, endpoint.trustMode)

        // 二维码/配对资料仍要求公网 wss。
        try {
            RemoteValidation.validateEndpoint(endpointJson("ws://yuan.profer.cn:24221/ws", trustMode = null))
            fail("配对资料不能接受公网明文 ws")
        } catch (error: RemoteValidationException) {
            assertEquals("公网连接必须使用可信 WSS", error.message)
        }
    }

    @Test
    fun `manual endpoint keeps ip pinned rule and wss requirement`() {
        val ip = RemoteValidation.validateManualEndpoint(
            endpointJson("wss://203.0.113.10:8443/ws", TrustMode.PINNED.wire, pin),
        )
        assertEquals("wss://203.0.113.10:8443/ws", ip.url)

        // 明文 ws 可以连，但不能带 TLS 信任字段。
        try {
            RemoteValidation.validateManualEndpoint(endpointJson("ws://yuan.profer.cn:24221/ws", TrustMode.PINNED.wire, pin))
            fail("明文 ws 不能带 pin")
        } catch (error: RemoteValidationException) {
            assertEquals("Pin 仅用于公网 WSS", error.message)
        }

        try {
            RemoteValidation.validateManualEndpoint(endpointJson("wss://yuan.profer.cn/ws", TrustMode.PINNED.wire, "sha256/short"))
            fail("pin 格式必须校验")
        } catch (_: RemoteValidationException) {
            // 期望：非法 pin 仍然拒绝
        }
    }

    @Test
    fun `pinned domain client skips hostname check while ip and system stay strict`() {
        val pinnedDomain = RemoteEndpoint(
            url = "wss://yuan.profer.cn/ws",
            scope = EndpointScope.PUBLIC,
            trustMode = TrustMode.PINNED,
            pin = RemotePin(RemotePin.ALGORITHM, pin),
        )
        assertTrue(RemoteTls.clientFor(pinnedDomain).hostnameVerifier.verify("yuan.profer.cn", null))

        val pinnedIp = RemoteEndpoint(
            url = "wss://203.0.113.10:8443/ws",
            scope = EndpointScope.PUBLIC,
            trustMode = TrustMode.PINNED,
            pin = RemotePin(RemotePin.ALGORITHM, pin),
        )
        val systemDomain = RemoteEndpoint(
            url = "wss://yuan.profer.cn/ws",
            scope = EndpointScope.PUBLIC,
            trustMode = TrustMode.SYSTEM,
        )
        // IP 端点与系统可信端点仍用 OkHttp 默认的 OkHostnameVerifier（共享单例），
        // 只有「pinned + 非 IP 主机」拿到自定义校验器。
        val ipVerifier = RemoteTls.clientFor(pinnedIp).hostnameVerifier
        val systemVerifier = RemoteTls.clientFor(systemDomain).hostnameVerifier
        assertSame(systemVerifier, ipVerifier)
        assertNotSame(systemVerifier, RemoteTls.clientFor(pinnedDomain).hostnameVerifier)
    }
}
