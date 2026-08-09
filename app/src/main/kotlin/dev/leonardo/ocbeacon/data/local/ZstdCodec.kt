package dev.leonardo.ocbeacon.data.local

import com.github.luben.zstd.Zstd

/**
 * zstd 压缩编解码。解压需要原始大小（zstd API 约束），
 * 调用方负责持久化 [decompress] 的 originalSize（归档桶表存 uncompressedSize）。
 */
object ZstdCodec {
    fun compress(bytes: ByteArray): ByteArray = Zstd.compress(bytes)

    fun decompress(bytes: ByteArray, originalSize: Int): ByteArray = Zstd.decompress(bytes, originalSize)
}
