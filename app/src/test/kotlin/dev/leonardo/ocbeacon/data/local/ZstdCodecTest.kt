package dev.leonardo.ocbeacon.data.local

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class ZstdCodecTest {

    @Test
    fun compressDecompress_roundtrip_returnsOriginal() {
        val original = "Hello, OC Beacon! ".repeat(1000).toByteArray(Charsets.UTF_8)
        val compressed = ZstdCodec.compress(original)
        // 文本重复度高 → 压缩后显著更小
        assert(compressed.size < original.size)
        assertArrayEquals(original, ZstdCodec.decompress(compressed, original.size))
    }

    @Test
    fun compress_emptyArray_roundtrip() {
        val original = ByteArray(0)
        val compressed = ZstdCodec.compress(original)
        assertArrayEquals(original, ZstdCodec.decompress(compressed, original.size))
    }

    @Test
    fun decompress_wrongOriginalSize_throws() {
        val original = "payload".toByteArray(Charsets.UTF_8)
        val compressed = ZstdCodec.compress(original)
        // zstd-jni 仅当 originalSize 小于实际解压大小时抛异常（目标缓冲区过小）；
        // originalSize 偏大时静默返回实际内容（zstd-jni 1.5.7-13 实测）。
        assertThrows(Exception::class.java) { ZstdCodec.decompress(compressed, original.size - 4) }
    }
}
