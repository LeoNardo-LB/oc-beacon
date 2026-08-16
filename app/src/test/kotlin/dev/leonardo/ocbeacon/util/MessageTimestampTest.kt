package dev.leonardo.ocbeacon.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.Locale

/**
 * messageTimestamp（消息气泡标题栏条件时间戳）单测——2026-08-16。
 * 用 Locale.US 固定格式避免环境差异；时间用 Calendar 构造边界。
 */
class MessageTimestampTest {

    private fun ms(y: Int, mo: Int, d: Int, h: Int, mi: Int, s: Int): Long =
        Calendar.getInstance(Locale.US).run {
            set(y, mo, d, h, mi, s)
            set(Calendar.MILLISECOND, 0)
            timeInMillis
        }

    @Test
    fun `当天消息只显示时分秒`() {
        val now = ms(2026, Calendar.AUGUST, 16, 14, 0, 0)
        val msg = ms(2026, Calendar.AUGUST, 16, 9, 5, 7)
        assertEquals("09:05:07", DateFormatters.messageTimestamp(msg, now, Locale.US))
    }

    @Test
    fun `昨天消息显示完整年月日时分秒`() {
        val now = ms(2026, Calendar.AUGUST, 16, 0, 30, 0)
        // 跨零点：仅 30 分钟前但已是昨天（自然日语义，非 24h 滚动窗口）
        val msg = ms(2026, Calendar.AUGUST, 15, 23, 58, 59)
        assertEquals("2026-08-15 23:58:59", DateFormatters.messageTimestamp(msg, now, Locale.US))
    }

    @Test
    fun `跨年消息显示完整日期`() {
        val now = ms(2027, Calendar.JANUARY, 1, 8, 0, 0)
        val msg = ms(2026, Calendar.DECEMBER, 31, 23, 59, 59)
        assertEquals("2026-12-31 23:59:59", DateFormatters.messageTimestamp(msg, now, Locale.US))
    }

    @Test
    fun `同一天零点边界`() {
        val now = ms(2026, Calendar.AUGUST, 16, 0, 0, 1)
        val msg = ms(2026, Calendar.AUGUST, 16, 0, 0, 0)
        assertEquals("00:00:00", DateFormatters.messageTimestamp(msg, now, Locale.US))
    }
}
