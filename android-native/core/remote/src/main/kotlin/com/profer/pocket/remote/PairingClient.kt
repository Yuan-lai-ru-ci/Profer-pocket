package com.profer.pocket.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import org.json.JSONObject
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** 配对失败的分类：UI 只按分类给文案，不解析字符串。 */
enum class PairingFailureKind {
    /** 端点连不上 / 证书校验失败。 */
    Unreachable,

    /** 设备身份（deviceId/protocolVersion）不匹配。 */
    DeviceMismatch,

    /** 服务端明确拒绝（close 4001）。 */
    Rejected,

    /** 已发出 pair 但没等到可用的 paired，要求刷新二维码重试。 */
    Unconfirmed,

    /** 二维码本身无效或已过期。 */
    InvalidQr,
}

class PairingException(
    val kind: PairingFailureKind,
    message: String,
) : Exception(message)

data class PairedDevice(
    val profile: RemoteProfile,
    val credential: String,
)

/**
 * 扫码配对客户端：`remote_hello → pair → paired`，与桌面 `pairing-client.ts` 一致。
 *
 * - 端点顺序：LAN 优先、公网回退；LAN 2.5s、公网 8s；
 * - `配对被拒绝` / `设备身份不匹配` / `配对结果未确认` 视为终态，不再换端点；
 * - 配对成功后只返回 profile 与 credential，pairingCode 不进日志、不落盘。
 */
class PairingClient(
    private val sockets: RemoteSocketFactory,
    private val deviceName: String = "Pocket",
    private val clock: () -> Long = System::currentTimeMillis,
    private val lanTimeoutMs: Long = 2_500,
    private val publicTimeoutMs: Long = 8_000,
) {

    /** 解析并配对；二维码解析失败直接抛 [PairingException]（InvalidQr）。 */
    suspend fun pair(qrText: String): PairedDevice {
        val qr = try {
            RemoteValidation.parsePairingQr(qrText, clock())
        } catch (error: RemoteValidationException) {
            throw PairingException(PairingFailureKind.InvalidQr, error.message ?: "二维码无效")
        }
        return pair(qr)
    }

    suspend fun pair(qr: PairingQr): PairedDevice {
        var last: PairingException = PairingException(PairingFailureKind.Unreachable, "所有端点均不可达")
        for (endpoint in qr.profile.orderedEndpoints()) {
            try {
                return pairEndpoint(qr, endpoint)
            } catch (error: PairingException) {
                last = error
                if (error.kind == PairingFailureKind.DeviceMismatch ||
                    error.kind == PairingFailureKind.Rejected ||
                    error.kind == PairingFailureKind.Unconfirmed
                ) {
                    throw error
                }
            }
        }
        throw last
    }

    private suspend fun pairEndpoint(qr: PairingQr, endpoint: RemoteEndpoint): PairedDevice {
        var sent = false
        try {
            return withTimeout(if (endpoint.scope == EndpointScope.LAN) lanTimeoutMs else publicTimeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    var settled = false
                    var socketRef: RemoteSocket? = null

                    // 幂等收尾：终态后由客户端主动关掉 socket，不等服务端 8s 鉴权超时。
                    fun closeSocket() {
                        val socket = socketRef ?: return
                        socketRef = null
                        socket.close(1000)
                    }

                    fun finish(result: Result<PairedDevice>) {
                        if (settled) return
                        settled = true
                        closeSocket()
                        result.fold(continuation::resume, continuation::resumeWithException)
                    }

                    val listener = object : RemoteSocket.Listener {
                            override fun onOpen() = Unit

                            override fun onFailure(error: String) {
                                finish(Result.failure(PairingException(PairingFailureKind.Unreachable, "端点连接或证书验证失败")))
                            }

                            override fun onClosed(code: Int, reason: String) {
                                val failure = if (code == 4001) {
                                    PairingException(PairingFailureKind.Rejected, "配对被拒绝")
                                } else {
                                    PairingException(PairingFailureKind.Unreachable, "连接已关闭")
                                }
                                finish(Result.failure(if (sent) unconfirmed(failure) else failure))
                            }

                            override fun onText(text: String) {
                                try {
                                    val message = JSONObject(text)
                                    when (message.optString("kind", "")) {
                                        "remote_hello" -> {
                                            val deviceId = message.optString("deviceId", "")
                                            val protocolVersion = message.opt("protocolVersion")
                                            if (deviceId != qr.deviceId || protocolVersion != 1) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.DeviceMismatch,
                                                            "设备身份不匹配",
                                                        ),
                                                    ),
                                                )
                                                return
                                            }
                                            if (qr.isExpired(clock())) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.InvalidQr,
                                                            "二维码已过期，请在电脑刷新",
                                                        ),
                                                    ),
                                                )
                                                return
                                            }
                                            if (sent) return
                                            sent = true
                                            val pair = JSONObject()
                                                .put("type", "pair")
                                                .put("deviceId", qr.deviceId)
                                                .put("pairingCode", qr.pairingCode)
                                                .put("deviceName", deviceName)
                                            if (socketRef?.send(pair.toString()) != true) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.Unreachable,
                                                            "端点连接或证书验证失败",
                                                        ),
                                                    ),
                                                )
                                            }
                                        }

                                        "paired" -> {
                                            if (!sent) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.Unconfirmed,
                                                            "配对结果未确认，请刷新二维码重试",
                                                        ),
                                                    ),
                                                )
                                                return
                                            }
                                            val profile = try {
                                                RemoteValidation.parseProfile(message.opt("profile"))
                                            } catch (error: RemoteValidationException) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.Unconfirmed,
                                                            "配对结果未确认，请刷新二维码重试",
                                                        ),
                                                    ),
                                                )
                                                return
                                            }
                                            try {
                                                RemoteValidation.assertProfileTrustUnchanged(qr.profile, profile)
                                            } catch (error: RemoteValidationException) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.Unconfirmed,
                                                            error.message ?: "配对结果未确认，请刷新二维码重试",
                                                        ),
                                                    ),
                                                )
                                                return
                                            }
                                            val credential = message.opt("credential")
                                            if (profile.deviceId != qr.deviceId || !RemoteValidation.isValidCredential(credential as? String)) {
                                                finish(
                                                    Result.failure(
                                                        PairingException(
                                                            PairingFailureKind.DeviceMismatch,
                                                            "设备身份不匹配",
                                                        ),
                                                    ),
                                                )
                                                return
                                            }
                                            finish(Result.success(PairedDevice(profile, credential as String)))
                                        }
                                    }
                                } catch (_: Exception) {
                                    finish(
                                        Result.failure(
                                            PairingException(
                                                PairingFailureKind.Unconfirmed,
                                                "配对结果未确认，请刷新二维码重试",
                                            ),
                                        ),
                                    )
                                }
                            }
                    }
                    val socket = sockets.open(endpoint, listener)
                    socketRef = socket
                    // open() 若同步回调失败（已结算），补一次关闭，避免漏关。
                    if (settled) closeSocket()
                    continuation.invokeOnCancellation { closeSocket() }
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw if (sent) {
                PairingException(PairingFailureKind.Unconfirmed, "配对结果未确认，请刷新二维码重试")
            } else {
                PairingException(PairingFailureKind.Unreachable, "端点探测超时")
            }
        }
    }

    private fun unconfirmed(error: PairingException): PairingException =
        if (error.kind == PairingFailureKind.Rejected || error.kind == PairingFailureKind.DeviceMismatch) {
            error
        } else {
            PairingException(PairingFailureKind.Unconfirmed, "配对结果未确认，请刷新二维码重试")
        }
}
