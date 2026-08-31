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
}
