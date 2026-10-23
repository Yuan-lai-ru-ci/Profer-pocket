package com.profer.pocket.remote

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.Principal
import java.security.PublicKey
import java.security.cert.CertificateExpiredException
import java.security.cert.CertificateNotYetValidException
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.util.Base64
import java.util.Date
import javax.net.ssl.HttpsURLConnection
import javax.security.auth.x500.X500Principal

/**
 * pinned 信任锚的对抗用例：错误 pin、换证、非 ECDSA、缺 serverAuth、过期、多证书链
 * 必须全部 fail closed，且在发送任何凭据之前失败。
 *
 * 证书用 [FakeCertificate] 精确控制各字段，避免依赖外部证书文件的有效期。
 */
class PinnedTrustManagerTest {

    private val ecPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    private val otherEcPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()
    }
    private val rsaPair: KeyPair by lazy {
        KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    }

    @Test
    fun `matching leaf is accepted`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        trust.checkServerTrusted(arrayOf(FakeCertificate(ecPair.public)), "ECDHE_ECDSA")
    }

    @Test
    fun `wrong pin is rejected`() {
        val trust = PinnedTrustManager(pinOf(otherEcPair.public))
        assertRejected("pin mismatch") {
            trust.checkServerTrusted(arrayOf(FakeCertificate(ecPair.public)), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `rotated certificate is rejected`() {
        // 桌面换证：同一个地址、不同密钥，pin 必须不再匹配。
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("rotated") {
            trust.checkServerTrusted(arrayOf(FakeCertificate(otherEcPair.public)), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `non ecdsa key is rejected even with matching pin`() {
        val trust = PinnedTrustManager(pinOf(rsaPair.public))
        assertRejected("rsa") {
            trust.checkServerTrusted(arrayOf(FakeCertificate(rsaPair.public)), "ECDHE_RSA")
        }
    }

    @Test
    fun `missing server auth eku is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("eku") {
            trust.checkServerTrusted(
                arrayOf(FakeCertificate(ecPair.public, eku = listOf("1.3.6.1.5.5.7.3.2"))),
                "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `expired certificate is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("expired") {
            trust.checkServerTrusted(
                arrayOf(
                    FakeCertificate(
                        ecPair.public,
                        notBefore = Date(System.currentTimeMillis() - 86_400_000),
                        notAfter = Date(System.currentTimeMillis() - 60_000),
                    ),
                ),
                "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `not yet valid certificate is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("not yet valid") {
            trust.checkServerTrusted(
                arrayOf(
                    FakeCertificate(
                        ecPair.public,
                        notBefore = Date(System.currentTimeMillis() + 60_000),
                        notAfter = Date(System.currentTimeMillis() + 86_400_000),
                    ),
                ),
                "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `missing digital signature key usage is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("keyUsage") {
            trust.checkServerTrusted(arrayOf(FakeCertificate(ecPair.public, keyUsage = null)), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `multi certificate chain is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("chain") {
            trust.checkServerTrusted(
                arrayOf(FakeCertificate(ecPair.public), FakeCertificate(otherEcPair.public)),
                "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `ca certificate is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("ca") {
            trust.checkServerTrusted(arrayOf(FakeCertificate(ecPair.public, basicConstraints = 0)), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `unsupported critical extension is rejected`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("critical") {
            trust.checkServerTrusted(
                arrayOf(FakeCertificate(ecPair.public, unsupportedCritical = true)),
                "ECDHE_ECDSA",
            )
        }
    }

    @Test
    fun `self signature must verify`() {
        val trust = PinnedTrustManager(pinOf(ecPair.public))
        assertRejected("self signed") {
            trust.checkServerTrusted(arrayOf(FakeCertificate(ecPair.public, verifyOk = false)), "ECDHE_ECDSA")
        }
    }

    @Test
    fun `non canonical pin is rejected at construction`() {
        try {
            PinnedTrustManager(RemotePin(RemotePin.ALGORITHM, "sha256/AAAA="))
            fail("预期拒绝非规范 pin")
        } catch (error: IllegalArgumentException) {
            assertTrue(error.message.orEmpty().isNotBlank())
        }
    }

    @Test
    fun `pinned client does not install a global ssl context`() {
        val before = HttpsURLConnection.getDefaultSSLSocketFactory()
        val client = RemoteTls.clientFor(
            RemoteEndpoint(
                url = "wss://203.0.113.10:8443/ws",
                scope = EndpointScope.PUBLIC,
                trustMode = TrustMode.PINNED,
                pin = pinOf(ecPair.public),
            ),
        )
        assertNotNull(client)
        assertSame(before, HttpsURLConnection.getDefaultSSLSocketFactory())
        // system 端点继续使用系统默认校验，不复用 pinned 的放宽逻辑。
        assertNotNull(RemoteTls.clientFor(null))
    }

    private fun pinOf(key: PublicKey): RemotePin {
        val digest = MessageDigest.getInstance("SHA-256").digest(key.encoded)
        return RemotePin(RemotePin.ALGORITHM, "sha256/" + Base64.getEncoder().encodeToString(digest))
    }

    private fun assertRejected(name: String, block: () -> Unit) {
        try {
            block()
            fail("$name: 预期拒绝，但通过了")
        } catch (error: java.security.cert.CertificateException) {
            assertTrue("$name: ${error.message}", error.message.orEmpty().isNotBlank())
        }
    }
}

/** 精确控制每个判定字段的假证书；只用于验证 TrustManager 的决策，不参与真实握手。 */
private class FakeCertificate(
    private val publicKey: PublicKey,
    private val notBefore: Date = Date(System.currentTimeMillis() - 60_000),
    private val notAfter: Date = Date(System.currentTimeMillis() + 3_600_000),
    private val keyUsage: BooleanArray? = booleanArrayOf(true, false, false, false, false, false, false, false, false),
    private val eku: List<String>? = listOf("1.3.6.1.5.5.7.3.1"),
    private val basicConstraints: Int = -1,
    private val sigAlgOid: String = "1.2.840.10045.4.3.2",
    private val verifyOk: Boolean = true,
    private val unsupportedCritical: Boolean = false,
) : X509Certificate() {

    override fun checkValidity() = checkValidity(Date())

    override fun checkValidity(date: Date) {
        if (date.before(notBefore)) throw CertificateNotYetValidException("not yet valid")
        if (date.after(notAfter)) throw CertificateExpiredException("expired")
    }

    override fun getVersion(): Int = 3

    override fun getSerialNumber(): BigInteger = BigInteger.ONE

    override fun getIssuerDN(): Principal = X500Principal("CN=test")

    override fun getSubjectDN(): Principal = X500Principal("CN=test")

    override fun getNotBefore(): Date = notBefore

    override fun getNotAfter(): Date = notAfter

    override fun getTBSCertificate(): ByteArray = byteArrayOf(1)

    override fun getSignature(): ByteArray = byteArrayOf(2)

    override fun getSigAlgName(): String = "SHA256withECDSA"

    override fun getSigAlgOID(): String = sigAlgOid

    override fun getSigAlgParams(): ByteArray? = null

    override fun getIssuerUniqueID(): BooleanArray? = null

    override fun getSubjectUniqueID(): BooleanArray? = null

    override fun getKeyUsage(): BooleanArray? = keyUsage

    override fun getExtendedKeyUsage(): List<String>? = eku

    override fun getBasicConstraints(): Int = basicConstraints

    override fun getEncoded(): ByteArray = byteArrayOf(3)

    override fun verify(key: PublicKey) {
        if (!verifyOk) throw java.security.SignatureException("bad signature")
    }

    override fun verify(key: PublicKey, sigProvider: String) = verify(key)

    override fun toString(): String = "FakeCertificate"

    override fun getPublicKey(): PublicKey = publicKey

    override fun hasUnsupportedCriticalExtension(): Boolean = unsupportedCritical

    override fun getCriticalExtensionOIDs(): Set<String>? = null

    override fun getNonCriticalExtensionOIDs(): Set<String>? = null

    override fun getExtensionValue(oid: String): ByteArray? = null
}
