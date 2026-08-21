package dev.leonardo.ocbeacon.data.github

import android.content.Context
import androidx.datastore.preferences.core.edit
import dagger.hilt.android.qualifiers.ApplicationContext
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dev.leonardo.ocbeacon.data.security.SecretCipher
import dev.leonardo.ocbeacon.logging.AppLogger
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "GitHubTokenStore"
private val Context.githubDataStore by preferencesDataStore(name = "github_report")

private object Keys {
    val ENCRYPTED_TOKEN = stringPreferencesKey("encrypted_token")
    val INSTALL_ID = stringPreferencesKey("install_id")
}

/**
 * GitHub 上报凭据存储（#151）：token 经 [SecretCipher] 加密（与服务器密码同款
 * AES/GCM + v1: 前缀密文）；install-id（UUID）首次上报时生成持久化。
 */
@Singleton
class GitHubTokenStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val cipher: SecretCipher,
) {

    val hasToken: Flow<Boolean> = context.githubDataStore.data
        .map { it[Keys.ENCRYPTED_TOKEN]?.isNotBlank() == true }

    suspend fun saveToken(accessToken: String) {
        val encrypted = runCatching { cipher.encrypt(accessToken) }
            .getOrElse { e ->
                AppLogger.e(TAG, "token encrypt failed", e)
                accessToken // 降级明文（与服务器密码同款优雅降级语义）
            }
        context.githubDataStore.edit { it[Keys.ENCRYPTED_TOKEN] = encrypted }
    }

    suspend fun loadToken(): String? {
        val stored = context.githubDataStore.data.first()[Keys.ENCRYPTED_TOKEN] ?: return null
        if (stored.isBlank()) return null
        if (!stored.startsWith("v1:")) return stored // 降级明文兼容
        return runCatching { cipher.decrypt(stored) }
            .getOrElse { e ->
                AppLogger.e(TAG, "token decrypt failed", e)
                null
            }
    }

    suspend fun clearToken() {
        context.githubDataStore.edit { it.remove(Keys.ENCRYPTED_TOKEN) }
    }

    /** 随机 install-id：首次调用时生成并持久化（统计独立报告者数）。 */
    suspend fun installId(): String {
        context.githubDataStore.data.first()[Keys.INSTALL_ID]?.let { return it }
        val id = UUID.randomUUID().toString()
        context.githubDataStore.edit { it[Keys.INSTALL_ID] = id }
        return id
    }
}
