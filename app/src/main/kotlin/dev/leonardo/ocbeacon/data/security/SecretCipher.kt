package dev.leonardo.ocbeacon.data.security

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * 解密结果记忆化（StrictMode 首轮发现 P2，2026-08-19）：
     * 同一密文重复解密直接命中——每次 AndroidKeystore 解密在主线程产生
     * 3 类 StrictMode 违规（KeyStore slow-call/DiskRead/DiskWrite），且
     * resolveConnection 在聊天会话存活期每 ~5s 触发一轮（125/165 条违规来源）。
     *
     * 安全性：解密后的明文本就随 ServerConfig 常驻内存（servers Flow、
     * 网络层 Auth 状态），缓存不扩大明文暴露面；密码变更/重加密产生新密文
     * （AES/GCM 随机 IV），新 key 自动 miss——不存在陈旧命中路径。
     * encrypt() 时整体清空（旧条目不可达，防缓慢积累）。
     */
    private val decryptCache = ConcurrentHashMap<String, String>()

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
        // 密码写入路径：清空记忆化（新密文将重新解密，旧条目不可达防积累）
        decryptCache.clear()
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
        // 记忆化命中：零 Keystore 交互（主线程高频路径的重复解密直接消除）
        decryptCache[encrypted]?.let { return it }
        val payload = encrypted.removePrefix(PREFIX)
        val parts = payload.split(SEPARATOR, limit = 2)
        require(parts.size == 2) { "Malformed encrypted payload" }
        val iv = Base64.decode(parts[0], Base64.NO_WRAP)
        val data = Base64.decode(parts[1], Base64.NO_WRAP)
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(GCM_TAG_LENGTH_BITS, iv))
        val plain = cipher.doFinal(data).toString(Charsets.UTF_8)
        // 解密失败（密钥失效等）不缓存——异常向上抛由调用方降级
        decryptCache[encrypted] = plain
        return plain
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
