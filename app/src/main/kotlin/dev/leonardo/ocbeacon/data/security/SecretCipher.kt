package dev.leonardo.ocbeacon.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 基于 Android Keystore 的对称加密工具（AES/GCM），用于加密存储服务器密码。
 *
 * - 密钥由 AndroidKeyStore 生成并保存在硬件/系统安全区，无法被导出
 * - 密文格式：`v1:<base64(iv)>:<base64(ciphertext)>`
 * - 旧明文数据无 `v1:` 前缀，[decrypt] 原样返回（透明兼容，下次保存时自动加密）
 */
@Singleton
class SecretCipher @Inject constructor() {

    private val keyStore = KeyStore.getInstance(KEYSTORE_PROVIDER).apply { load(null) }

    private fun getOrCreateKey(): SecretKey {
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE_PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build()
        )
        return generator.generateKey()
    }

    fun encrypt(plain: String): String {
        if (plain.isEmpty()) return plain
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val iv = cipher.iv
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return PREFIX +
            Base64.encodeToString(iv, Base64.NO_WRAP) + SEPARATOR +
            Base64.encodeToString(encrypted, Base64.NO_WRAP)
    }

    /**
     * 解密。无 `v1:` 前缀的旧明文数据原样返回。
     * 密钥失效（如恢复出厂/备份还原）等无法解密场景抛异常，由调用方降级处理。
     */
    fun decrypt(encrypted: String): String {
        if (!encrypted.startsWith(PREFIX)) return encrypted
        val payload = encrypted.removePrefix(PREFIX)
        val parts = payload.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Malformed encrypted payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        return cipher.doFinal(data).toString(Charsets.UTF_8)
    }

    private companion object {
        const val KEYSTORE_PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "oc_beacon_secret_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val PREFIX = "v1:"
        const val SEPARATOR = ":"
        const val GCM_TAG_LENGTH_BITS = 128
    }
}
