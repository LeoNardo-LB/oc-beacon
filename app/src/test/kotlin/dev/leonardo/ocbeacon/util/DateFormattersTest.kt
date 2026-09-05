package dev.leonardo.ocbeacon.util

import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * #276 走查 N3（epoch 1970 显示）：DSH 会话无 created 时刻（DshSessionMapper
 * 置 0 哨兵）——SessionDetailsDialog 直显 1970-01-01。formatEpochOrDash 契约：
 * <=0 → "—" 占位（不以 updated 冒充 created）；>0 → 正常格式化。
 */
class DateFormattersTest {

    private val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)

    @Test
    fun `formatEpochOrDash renders dash for zero sentinel`() {
        assertEquals("—", DateFormatters.formatEpochOrDash(format, 0L))
    }

    @Test
    fun `formatEpochOrDash renders dash for negative sentinel`() {
        assertEquals("—", DateFormatters.formatEpochOrDash(format, -1L))
    }

    @Test
    fun `formatEpochOrDash formats positive epoch normally`() {
        // 1788109999000L = 2026-05-02（时区无关断言用固定 Locale/时区不引入——值只验证非占位）
        val rendered = DateFormatters.formatEpochOrDash(format, 1788109999000L)
        assertEquals(format.format(java.util.Date(1788109999000L)), rendered)
        assertEquals(false, rendered == "—")
    }

    // ============ #312① 相对时间戳（timeAgo）纯函数边界规格 ============

    private val now = 1_788_109_999_000L

    /** 秒级（<60s）→ "<1m"；含未来时间（时钟偏移宽容下限）。 */
    @Test
    fun `timeAgo under one minute renders lt-1m`() {
        assertEquals("<1m", DateFormatters.timeAgo(now - 59_999L, now))
        assertEquals("<1m", DateFormatters.timeAgo(now - 1L, now))
        assertEquals("<1m", DateFormatters.timeAgo(now, now))
    }

    @Test
    fun `timeAgo future timestamp falls back to lt-1m`() {
        assertEquals("<1m", DateFormatters.timeAgo(now + 5_000L, now))
    }

    /** 分钟级 → "{n}m"（n 向下取整）；60_000ms 恰为 1m。 */
    @Test
    fun `timeAgo minute bucket boundaries`() {
        assertEquals("1m", DateFormatters.timeAgo(now - 60_000L, now))
        assertEquals("4m", DateFormatters.timeAgo(now - 299_999L, now))
        assertEquals("5m", DateFormatters.timeAgo(now - 300_000L, now))
        assertEquals("59m", DateFormatters.timeAgo(now - 3_599_999L, now))
    }

    /** 小时级（<24h）→ "{n}h"；3_600_000ms 恰为 1h。 */
    @Test
    fun `timeAgo hour bucket boundaries`() {
        assertEquals("1h", DateFormatters.timeAgo(now - 3_600_000L, now))
        assertEquals("1h", DateFormatters.timeAgo(now - 7_199_999L, now))
        assertEquals("2h", DateFormatters.timeAgo(now - 7_200_000L, now))
        assertEquals("23h", DateFormatters.timeAgo(now - 86_399_999L, now))
    }

    /** 天级（<7d）→ "{n}d"；86_400_000ms 恰为 1d。 */
    @Test
    fun `timeAgo day bucket boundaries`() {
        assertEquals("1d", DateFormatters.timeAgo(now - 86_400_000L, now))
        assertEquals("3d", DateFormatters.timeAgo(now - 3 * 86_400_000L, now))
        assertEquals("6d", DateFormatters.timeAgo(now - 7 * 86_400_000L + 1L, now))
    }

    /** ≥7d 回退绝对格式（跨周相对天数失去读价值——messageTimestamp）。 */
    @Test
    fun `timeAgo beyond seven days falls back to absolute format`() {
        val old = now - 7 * 86_400_000L
        assertEquals(DateFormatters.messageTimestamp(old, now), DateFormatters.timeAgo(old, now))
        val muchOlder = now - 30 * 86_400_000L
        assertEquals(DateFormatters.messageTimestamp(muchOlder, now), DateFormatters.timeAgo(muchOlder, now))
    }
}
