package com.profer.pocket.remote

import android.annotation.SuppressLint
import okhttp3.OkHttpClient
import java.security.MessageDigest
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import java.security.interfaces.ECPublicKey
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.X509TrustManager

/**
 * 仅对 `trustMode=pinned` 的公网端点使用的信任锚。
 *
 * 约束（与旧原生实现和桌面 `RemoteTlsClientFactory` 一致）：
 * - 只接受单叶自签证书，链上多于一张直接拒绝；
 * - 证书必须自签（`cert.verify(cert.publicKey)`）、ECDSA P-256、签名算法 ecdsa-with-SHA256；
 * - 必须带 serverAuth EKU 与 digitalSignature keyUsage；
 * - 有效期必须有效，SPKI SHA-256 与 pin 常量时间比较；
 * - 任何异常一律 fail closed，绝不放行。
 *
 * 不安装全局 SSLContext；主机名校验只在 [RemoteTls.clientFor] 里对「pinned + 非 IP 主机」
 * 逐个 client 关闭（桌面自签证书的 SAN 只有 IP，域名场景由 pin 取代主机名校验）。
 * IP 端点仍要求证书自带对应 SAN。
 */
// lint 对自定义 TrustManager 默认视为「关闭了证书校验」；这里是反向的：
// 只信任唯一一张白名单叶证书，比系统 CA 集更严，因此显式压制并保留理由。
@SuppressLint("CustomX509TrustManager")
class PinnedTrustManager(pin: RemotePin) : X509TrustManager {

    private val expected: ByteArray

    init {
        val value = pin.value
        require(value.startsWith("sha256/")) { "pin 格式无效" }
        val decoded = decodeBase64(value.substring(7))
        require(decoded.size == 32) { "pin 长度无效" }
        // 规范编码校验：base64 重新编码后必须与原文一致，避免等价值绕过。
        require("sha256/${encodeBase64(decoded)}" == value) { "pin 编码无效" }
        expected = decoded
    }

    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()

    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        throw CertificateException("client certificates are not supported")
    }

    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
        if (chain == null || chain.size != 1) {
            throw CertificateException("pinned self-signed leaf required")
        }
        val cert = chain[0]
        try {
            cert.checkValidity()
            val digest = MessageDigest.getInstance("SHA-256").digest(cert.publicKey.encoded)
            if (!MessageDigest.isEqual(expected, digest)) throw CertificateException("pin mismatch")
            cert.verify(cert.publicKey)
            val key = cert.publicKey
            if (cert.basicConstraints != -1 ||
                key !is ECPublicKey ||
                key.params.curve.field.fieldSize != 256 ||
                cert.sigAlgOID != ECDSA_WITH_SHA256
            ) {
                throw CertificateException("invalid key or signature algorithm")
            }
            val usage = cert.keyUsage
            if (usage == null || usage.size == 0 || !usage[0]) throw CertificateException("digitalSignature required")
            val eku = cert.extendedKeyUsage
            if (eku == null || SERVER_AUTH !in eku) throw CertificateException("serverAuth required")
            if (cert.hasUnsupportedCriticalExtension()) throw CertificateException("unsupported critical extension")
        } catch (error: CertificateException) {
            throw error
        } catch (error: Exception) {
            throw CertificateException("TLS identity rejected", error)
        }
    }

    private companion object {
        const val ECDSA_WITH_SHA256 = "1.2.840.10045.4.3.2"
        const val SERVER_AUTH = "1.3.6.1.5.5.7.3.1"

        fun decodeBase64(value: String): ByteArray = try {
            java.util.Base64.getDecoder().decode(value)
        } catch (error: IllegalArgumentException) {
            throw IllegalArgumentException("pin 编码无效", error)
        }

        fun encodeBase64(value: ByteArray): String = java.util.Base64.getEncoder().encodeToString(value)
    }
}

/**
 * 按端点构造 OkHttpClient：system 走系统默认校验，pinned 只给该 client 挂自定义 TrustManager。
 *
 * readTimeout 置 0 与旧实现一致：默认 10s 会误杀 25s 心跳的空闲连接，假死判定
 * 交给连接状态机。connectTimeout 取 8s（公网握手预算）。
 */
object RemoteTls {

    fun clientFor(endpoint: RemoteEndpoint?): OkHttpClient {
        val builder = OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            .connectTimeout(8, TimeUnit.SECONDS)
            .retryOnConnectionFailure(true)
        if (endpoint?.trustMode != TrustMode.PINNED) return builder.build()
        val pin = endpoint.pin ?: throw IllegalArgumentException("missing pin")
        val trust = PinnedTrustManager(pin)
        val context = SSLContext.getInstance("TLS")
        context.init(null, arrayOf<X509TrustManager>(trust), null)
        builder.sslSocketFactory(context.socketFactory, trust)
        // 桌面自签证书的 SAN 只有公网 IP（`remote-tls-identity.ts` 只写 IP），用户把同一条
        // 链路挂在自己的域名/穿透后面时，主机名必然对不上。此时身份已由 SPKI pin 锚定，
        // 比主机名更强，所以只对「pinned + 非 IP 主机」这一个 client 关闭主机名校验；
        // 系统可信端点与 IP 端点仍按 OkHttp 默认严格校验。
        if (!isIpLiteral(endpoint.url)) builder.hostnameVerifier { _, _ -> true }
        return builder.build()
    }

    private val IPV4 = Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$")

    private fun isIpLiteral(url: String): Boolean {
        val host = try {
            java.net.URI(url).host?.removePrefix("[")?.removeSuffix("]")
        } catch (_: Exception) {
            null
        } ?: return false
        return IPV4.matches(host) || host.contains(':')
    }
}
