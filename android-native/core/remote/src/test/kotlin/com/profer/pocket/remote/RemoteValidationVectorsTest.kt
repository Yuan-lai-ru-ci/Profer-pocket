package com.profer.pocket.remote

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 共享向量：`src/test/resources/remote-validation-vectors.json` 与桌面
 * `packages/shared/src/remote-connection.ts` 的规则同源（文件头注明来源）。
 *
 * 同一份向量必须同时被 TS 与 Kotlin 接受/拒绝，否则说明两端规则已经漂移。
 */
class RemoteValidationVectorsTest {

    private val vectors = JSONObject(
        javaClass.classLoader!!.getResourceAsStream("remote-validation-vectors.json")!!
            .readBytes().toString(Charsets.UTF_8),
    )
    private val now = vectors.getLong("now")

    @Test
    fun `pairing qr vectors`() {
        val list = vectors.getJSONArray("pairing")
        for (index in 0 until list.length()) {
            val vector = list.getJSONObject(index)
            val name = vector.getString("name")
            val input = vector.get("input").toString()
            val expectedOk = vector.getBoolean("ok")
            if (expectedOk) {
                RemoteValidation.parsePairingQr(input, now)
            } else {
                val error = assertRejected(name) { RemoteValidation.parsePairingQr(input, now) }
                assertTrue("$name: $error", error.contains(vector.getString("error")))
            }
        }
    }

    @Test
    fun `profile vectors`() {
        val list = vectors.getJSONArray("profile")
        for (index in 0 until list.length()) {
            val vector = list.getJSONObject(index)
            val name = vector.getString("name")
            if (vector.getBoolean("ok")) {
                RemoteValidation.parseProfile(vector.get("input"))
            } else {
                val error = assertRejected(name) { RemoteValidation.parseProfile(vector.get("input")) }
                assertTrue("$name: $error", error.contains(vector.getString("error")))
            }
        }
    }

    @Test
    fun `endpoint vectors`() {
        val list = vectors.getJSONArray("endpoint")
        for (index in 0 until list.length()) {
            val vector = list.getJSONObject(index)
            val name = vector.getString("name")
            if (vector.getBoolean("ok")) {
                RemoteValidation.validateEndpoint(vector.get("input"))
            } else {
                val error = assertRejected(name) { RemoteValidation.validateEndpoint(vector.get("input")) }
                assertTrue("$name: $error", error.contains(vector.getString("error")))
            }
        }
    }

    @Test
    fun `trust assertion vectors`() {
        val list = vectors.getJSONArray("trust")
        for (index in 0 until list.length()) {
            val vector = list.getJSONObject(index)
            val name = vector.getString("name")
            val previous = RemoteValidation.parseProfile(vector.get("previous"))
            val next = RemoteValidation.parseProfile(vector.get("next"))
            if (vector.getBoolean("ok")) {
                RemoteValidation.assertProfileTrustUnchanged(previous, next)
            } else {
                val error = assertRejected(name) {
                    RemoteValidation.assertProfileTrustUnchanged(previous, next)
                }
                assertTrue("$name: $error", error.contains(vector.getString("error")))
            }
        }
    }

    @Test
    fun `oversized qr is rejected before parsing`() {
        val error = assertRejected("oversized") {
            RemoteValidation.parsePairingQr("{\"pad\":\"" + "a".repeat(9000) + "\"}", now)
        }
        assertTrue(error, error.contains("二维码过大"))
    }

    @Test
    fun `broken json is rejected`() {
        val error = assertRejected("broken") { RemoteValidation.parsePairingQr("not-json", now) }
        assertTrue(error, error.contains("二维码损坏"))
    }

    @Test
    fun `endpoint canonicalisation drops default port and lowercases host`() {
        val endpoint = RemoteValidation.validateEndpoint(
            JSONObject().put("url", "ws://LOCALHOST:80/ws").put("scope", "lan"),
        )
        assertEquals("ws://localhost/ws", endpoint.url)
        assertEquals(EndpointScope.LAN, endpoint.scope)
    }

    private fun assertRejected(name: String, block: () -> Unit): String = try {
        block()
        throw AssertionError("$name: 预期拒绝，但校验通过了")
    } catch (error: RemoteValidationException) {
        error.message.orEmpty()
    }
}
