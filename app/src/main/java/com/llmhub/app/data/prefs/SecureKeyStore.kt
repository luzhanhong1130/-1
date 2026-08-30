package com.llmhub.app.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * API Key 加密存储。
 *
 * - Key 明文绝不进入 Room 数据库，仅以 `apikey_${id}` 为键加密存放于此。
 * - 底层使用 EncryptedSharedPreferences（AES-256-GCM + AES-256-SIV），
 *   密钥由 Android Keystore 托管，不导出根机的设备无法解密。
 */
@Singleton
class SecureKeyStore @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val prefs = run {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            FILE_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    /**
     * 同步落盘（commit）以保证调用方拿到成功返回后，下一次 loadKey 一定能读到。
     * 此前用 apply() 异步写盘，存在「立即发送消息 → loadKey 返回 null」的竞态。
     */
    fun storeKey(id: Long, value: String): Boolean =
        prefs.edit().putString(keyOf(id), value.trim()).commit()

    fun loadKey(id: Long): String? = prefs.getString(keyOf(id), null)?.takeIf { it.isNotEmpty() }

    fun deleteKey(id: Long): Boolean = prefs.edit().remove(keyOf(id)).commit()

    private fun keyOf(id: Long): String = "apikey_$id"

    private companion object {
        const val FILE_NAME = "llmhub_secure_prefs.xml"
    }
}
