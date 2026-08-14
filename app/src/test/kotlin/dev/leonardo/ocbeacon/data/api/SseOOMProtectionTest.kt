package dev.leonardo.ocbeacon.data.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 测试 [appendDataLine] —— SSE 事件级别的 OOM 保护。
 *
 * 使用较小的 [maxEventSize] 参数（而非生产环境中的 1MB 常量），
 * 以便在不分配大缓冲区的情况下快速验证边界行为。
 *
 * 覆盖范围：
 * - 正常追加（低于上限）
 * - 溢出触发清空 + 丢弃
 * - 清空后的恢复（下一帧恢复正常）
 * - 多行累积与分隔符计数
 * - 边界：恰好等于上限（应被接受，防止 off-by-one）
 * - 边界情况：空负载
 */
class SseOOMProtectionTest {

    @Test
    fun `appends payload when under limit`() {
        val buffer = mutableListOf<ByteArray>()
        appendDataLine(buffer, byteArrayOf(1, 2, 3), maxEventSize = 10)

        assertEquals(1, buffer.size)
        assertTrue(byteArrayOf(1, 2, 3).contentEquals(buffer[0]))
    }

    @Test
    fun `clears buffer and drops payload when single payload exceeds limit`() {
        val buffer = mutableListOf<ByteArray>()
        // 负载大小 3 > maxEventSize 2 → 清空 + 丢弃
        appendDataLine(buffer, byteArrayOf(1, 2, 3), maxEventSize = 2)

        assertTrue("buffer should be empty after overflow", buffer.isEmpty())
    }

    @Test
    fun `clears buffer when accumulated size exceeds limit`() {
        val buffer = mutableListOf<ByteArray>()
        appendDataLine(buffer, byteArrayOf(1, 2, 3), maxEventSize = 10)  // 预计 = 3
        appendDataLine(buffer, byteArrayOf(4, 5, 6, 7, 8), maxEventSize = 10)  // 预计 = 3 + 1 + 5 = 9
        assertEquals(2, buffer.size)

        // 第三次追加：预计 = 3 + 5 + 1 + 3 = 12 > 10 → 全部清空
        appendDataLine(buffer, byteArrayOf(9, 10, 11), maxEventSize = 10)
        assertTrue("buffer should be cleared when accumulated size exceeds limit", buffer.isEmpty())
    }

    @Test
    fun `resumes normally after clear`() {
        val buffer = mutableListOf<ByteArray>()
        appendDataLine(buffer, byteArrayOf(1, 2, 3), maxEventSize = 2)  // 溢出 → 清空
        appendDataLine(buffer, byteArrayOf(1), maxEventSize = 10)       // 正常恢复

        assertEquals("should resume normal operation after clear", 1, buffer.size)
    }

    @Test
    fun `accumulates multiple lines with separator accounting`() {
        val buffer = mutableListOf<ByteArray>()
        // 第 1 行：预计 = 0 + 2 + 0 = 2（缓冲区为空，无分隔符）
        appendDataLine(buffer, byteArrayOf(1, 2), maxEventSize = 10)
        // 第 2 行：预计 = 2 + 2 + 1 = 5（缓冲区已有 1 行 → +1 分隔符）
        appendDataLine(buffer, byteArrayOf(3, 4), maxEventSize = 10)
        // 第 3 行：预计 = 4 + 2 + 2 = 8（缓冲区已有 2 行 → +2 分隔符）
        appendDataLine(buffer, byteArrayOf(5, 6), maxEventSize = 10)

        assertEquals(3, buffer.size)
    }

    @Test
    fun `boundary exactly at limit is accepted`() {
        val buffer = mutableListOf<ByteArray>()
        // 预计 = 0 + 3 + 0 = 3，maxEventSize = 3 → 未超过（>），边界被接受
        appendDataLine(buffer, byteArrayOf(1, 2, 3), maxEventSize = 3)

        assertEquals("boundary exactly at limit should be accepted (off-by-one guard)", 1, buffer.size)
    }

    @Test
    fun `empty payload under limit is appended`() {
        val buffer = mutableListOf<ByteArray>()
        appendDataLine(buffer, ByteArray(0), maxEventSize = 1)
        // 预计 = 0 + 0 + 0 = 0 ≤ 1 → 追加
        assertEquals(1, buffer.size)
    }

    @Test
    fun `empty payload on empty buffer with zero limit is accepted`() {
        val buffer = mutableListOf<ByteArray>()
        // 预计 = 0，maxEventSize = 0 → 0 > 0 为 false → 追加
        appendDataLine(buffer, ByteArray(0), maxEventSize = 0)
        assertEquals("zero projected should pass zero-limit boundary", 1, buffer.size)
    }
}
