package com.profer.pocket.data.store

import android.content.Context
import com.profer.pocket.remote.RegistryCipher
import com.profer.pocket.remote.RegistryStorage

/**
 * 设备注册表的 Android 持久化。
 *
 * 用 `commit()` 而不是 `apply()`：注册表写入失败必须能被上层感知并回滚凭据，
 * 静默失败会造成「列表里有设备、凭据却是孤儿」。
 */
class AndroidRegistryStorage(context: Context) : RegistryStorage {

    private val prefs = context.applicationContext
        .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    override fun read(key: String): String? = prefs.getString(key, null)

    override fun write(key: String, value: String) {
        if (!prefs.edit().putString(key, value).commit()) {
            throw IllegalStateException("设备资料写入失败")
        }
    }

    override fun remove(key: String) {
        prefs.edit().remove(key).apply()
    }

    private companion object {
        const val PREFS_NAME = "profer_remote_devices"
    }
}

/** 复用既有 Keystore AES-GCM 实现，凭据明文不落盘。 */
class KeystoreRegistryCipher(
    private val delegate: SecretCipher = KeystoreSecretCipher(),
) : RegistryCipher {

    override fun encrypt(plain: String): String? = delegate.encrypt(plain)

    override fun decrypt(stored: String): String? = delegate.decrypt(stored)
}
