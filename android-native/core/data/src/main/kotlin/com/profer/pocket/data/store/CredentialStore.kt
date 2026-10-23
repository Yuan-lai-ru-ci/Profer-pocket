package com.profer.pocket.data.store

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * 连接凭据：地址明文保存，token 用 Android Keystore 的 AES/GCM 加密后落盘。
 *
 * token 等同连接凭据，禁止进入日志、崩溃上报与对话内容；解密失败按「无凭据」处理，
 * 由用户重新输入，而不是回退到明文。
 */
interface CredentialStore {
    data class SavedConnection(
        val endpoint: String,
        val token: String,
    )

    fun load(): SavedConnection?

    fun save(endpoint: String, token: String)

    fun clear()
}

interface SecretCipher {
    fun encrypt(plain: String): String?

    fun decrypt(stored: String): String?
}

class AndroidCredentialStore(
    context: Context,
    private val cipher: SecretCipher = KeystoreSecretCipher(),
) : CredentialStore {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * 解密结果缓存：Keystore 首次访问是一次跨进程调用，在低端机/高负载下能达到数百毫秒。
     * 连接页每次重组、每个连接状态变化都会问一次「有没有保存过 token」，不能每次都进 Keystore。
     */
    @Volatile
    private var cached: CredentialStore.SavedConnection? = null

    @Volatile
    private var cacheLoaded = false

    override fun load(): CredentialStore.SavedConnection? {
        if (cacheLoaded) return cached
        synchronized(this) {
            if (cacheLoaded) return cached
            val result = readFromDisk()
            cached = result
            cacheLoaded = true
            return result
        }
    }

    private fun readFromDisk(): CredentialStore.SavedConnection? {
        val endpoint = prefs.getString(KEY_ENDPOINT, null)?.takeIf { it.isNotBlank() } ?: return null
        val storedToken = prefs.getString(KEY_TOKEN, null)?.takeIf { it.isNotBlank() } ?: return null
        val token = cipher.decrypt(storedToken)?.takeIf { it.isNotBlank() }
        if (token == null) {
            prefs.edit().remove(KEY_ENDPOINT).remove(KEY_TOKEN).apply()
            return null
        }
        return CredentialStore.SavedConnection(endpoint = endpoint, token = token)
    }

    override fun save(endpoint: String, token: String) {
        val encrypted = cipher.encrypt(token) ?: return
        prefs.edit()
            .putString(KEY_ENDPOINT, endpoint)
            .putString(KEY_TOKEN, encrypted)
            .apply()
        synchronized(this) {
            cached = CredentialStore.SavedConnection(endpoint = endpoint, token = token)
            cacheLoaded = true
        }
    }

    override fun clear() {
        prefs.edit().remove(KEY_ENDPOINT).remove(KEY_TOKEN).apply()
        synchronized(this) {
            cached = null
            cacheLoaded = true
        }
    }

    private companion object {
        const val PREFS_NAME = "profer_pocket_credentials"
        const val KEY_ENDPOINT = "endpoint"
        const val KEY_TOKEN = "token"
    }
}

/** Android Keystore 里的单把 AES 密钥；只有本应用可解密。 */
class KeystoreSecretCipher : SecretCipher {

    override fun encrypt(plain: String): String? = try {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        "${Base64.encodeToString(iv, Base64.NO_WRAP)}:${Base64.encodeToString(encrypted, Base64.NO_WRAP)}"
    } catch (_: Exception) {
        null
    }

    override fun decrypt(stored: String): String? {
        return try {
            val parts = stored.split(':', limit = 2)
            if (parts.size != 2) return null
            val iv = Base64.decode(parts[0], Base64.NO_WRAP)
            val encrypted = Base64.decode(parts[1], Base64.NO_WRAP)
            val cipher = Cipher.getInstance(TRANSFORMATION)
            cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), Charsets.UTF_8)
        } catch (_: Exception) {
            null
        }
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "profer.pocket.credentials"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
