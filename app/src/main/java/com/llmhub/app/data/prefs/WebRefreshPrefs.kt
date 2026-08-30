package com.llmhub.app.data.prefs

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Web 登录对账相关的持久化小偏好（与 SecureKeyStore 共用同一个 EncryptedSharedPreferences 文件，
 * 不再多造一份 Keystore 初始化）。
 *
 * - `refreshMode_{configId}`：`1=WEB优先, 0=API优先(默认)`；null=未选过，默认 API。
 *
 * 为什么用 EncryptedSharedPreferences 而不是普通 SP：这些偏好虽然不是高敏感的 Key，
 * 但仍然属于"用户的对账方式选择"，沿用现有加密存储更合规且不增加新依赖/新文件风险。
 */
@Singleton
class WebRefreshPrefs @Inject constructor(
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

    fun getDefaultMode(configId: Long): RefreshMode =
        when (prefs.getInt(keyOf(configId), -1)) {
            1 -> RefreshMode.PREFER_WEB
            else -> RefreshMode.PREFER_API
        }

    fun setDefaultMode(configId: Long, mode: RefreshMode) {
        val value = when (mode) {
            RefreshMode.PREFER_WEB -> 1
            RefreshMode.PREFER_API -> 0
        }
        prefs.edit().putInt(keyOf(configId), value).apply()
    }

    private fun keyOf(configId: Long): String = "web_refresh_mode_$configId"

    companion object {
        /** 与 SecureKeyStore 共用同一份加密 SP 文件。 */
        private const val FILE_NAME = "llmhub_secure_prefs.xml"
    }
}

enum class RefreshMode {
    /** 默认：API Key 直连，失败再引导用户去 Web。 */
    PREFER_API,
    /** 默认：打开就弹 Web 面板。 */
    PREFER_WEB,
}
