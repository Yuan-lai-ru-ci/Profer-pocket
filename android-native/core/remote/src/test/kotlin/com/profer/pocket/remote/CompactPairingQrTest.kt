package com.profer.pocket.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CompactPairingQrTest {

    private val deviceId = "desktop-123"
    private val code = "a".repeat(64)

    private fun compact(now: Long, x: Long = now / 1000 + 300): JSONObject = JSONObject()
        .put("v", 2)
        .put("d", deviceId)
        .put("h", "192.168.1.2:7788")
        .put("c", code)
        .put("x", x)

    @Test
    fun `parses compact LAN pairing QR and derives TTL`() {
        val now = 1_700_000_000_000L
        val qr = RemoteValidation.parsePairingQr(compact(now).toString(), now)
        assertEquals(deviceId, qr.deviceId)
        assertEquals(code, qr.pairingCode)
        assertEquals(1, qr.endpoints.size)
        assertEquals("ws://192.168.1.2:7788/ws", qr.endpoints[0].url)
        assertEquals(EndpointScope.LAN, qr.endpoints[0].scope)
        assertEquals(PAIRING_TTL_MS, qr.expiresAt - qr.issuedAt)
    }

    @Test
    fun `rejects invalid compact host code expiry and unknown keys`() {
        val now = 1_700_000_000_000L
        val base = compact(now)
        for (host in listOf("", "192.168.1.2", "remote.example.com:7788", "192.168.1.2:0", "192.168.1.2:7788/ws")) {
            val bad = JSONObject(base.toString()).put("h", host)
            assertThrows(RemoteValidationException::class.java) {
                RemoteValidation.parsePairingQr(bad.toString(), now)
            }
        }
        assertThrows(RemoteValidationException::class.java) {
            RemoteValidation.parsePairingQr(JSONObject(base.toString()).put("c", "bad").toString(), now)
        }
        assertThrows(RemoteValidationException::class.java) {
            RemoteValidation.parsePairingQr(JSONObject(base.toString()).put("x", now / 1000 - 1).toString(), now)
        }
        assertThrows(RemoteValidationException::class.java) {
            RemoteValidation.parsePairingQr(JSONObject(base.toString()).put("x", now / 1000 + 400).toString(), now)
        }
        assertThrows(RemoteValidationException::class.java) {
            RemoteValidation.parsePairingQr(JSONObject(base.toString()).put("token", "SECRET").toString(), now)
        }
        assertThrows(RemoteValidationException::class.java) {
            RemoteValidation.parsePairingQr(JSONObject(base.toString()).put("v", 1).toString(), now)
        }
    }
}
