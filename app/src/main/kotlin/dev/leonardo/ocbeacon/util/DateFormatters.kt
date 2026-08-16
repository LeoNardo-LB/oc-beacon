package dev.leonardo.ocbeacon.util

import java.text.SimpleDateFormat
import java.util.Locale

/**
 * 统一日期格式化入口（audit D2-L15：全库 14 处 SimpleDateFormat、8 种格式、Locale 混用）。
 * 每处使用命名函数，保证格式/Locale 一致；SimpleDateFormat 非线程安全，调用方按需新建/remember。
 */
object DateFormatters {
    /** "MMM d, HH:mm"（Locale.getDefault()）——会话行/分享目标选择器的相对时间。 */
    fun monthDayTime(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        SimpleDateFormat("MMM d, HH:mm", locale)

    /** "HH:mm"（Locale.getDefault()）——消息气泡/任务卡片的时刻。 */
    fun timeOnly(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        SimpleDateFormat("HH:mm", locale)

    /**
     * 消息气泡标题栏的条件时间戳（2026-08-16 用户需求）：
     * 当天 → "HH:mm:ss"；非当天 → "yyyy-MM-dd HH:mm:ss"。
     * 实现：两条格式化器 + 当天判断（按本地时区自然日，非 24h 滚动窗口——
     * 跨零点后前一天的立即显示完整日期，符合"看日期才知道是哪天"直觉）。
     * 调用方 remember(timeMs) 按时间戳缓存结果。
     */
    fun messageTimestamp(timeMs: Long, nowMs: Long = System.currentTimeMillis(), locale: Locale = Locale.getDefault()): String {
        val cal = java.util.Calendar.getInstance(locale).apply { timeInMillis = timeMs }
        val now = java.util.Calendar.getInstance(locale).apply { timeInMillis = nowMs }
        val sameDay = cal.get(java.util.Calendar.YEAR) == now.get(java.util.Calendar.YEAR) &&
            cal.get(java.util.Calendar.DAY_OF_YEAR) == now.get(java.util.Calendar.DAY_OF_YEAR)
        return if (sameDay) {
            SimpleDateFormat("HH:mm:ss", locale).format(java.util.Date(timeMs))
        } else {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", locale).format(java.util.Date(timeMs))
        }
    }

    /** "MM-dd HH:mm"（Locale.getDefault()）——快速导航/上下文详情。 */
    fun monthDayHourMinute(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        SimpleDateFormat("MM-dd HH:mm", locale)

    /** "MM-dd HH:mm:ss.SSS"（Locale.getDefault()）——诊断日志行时间。 */
    fun diagnostics(locale: Locale = Locale.getDefault()): SimpleDateFormat =
        SimpleDateFormat("MM-dd HH:mm:ss.SSS", locale)

    /** "yyyyMMdd_HHmmss_SSS"（Locale.US）——崩溃日志文件名时间戳。 */
    fun crashFileName(): SimpleDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US)

    /** "yyyy-MM-dd HH:mm:ss.SSS"（Locale.US）——崩溃详情时间戳。 */
    fun crashDetail(): SimpleDateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    /** "yyyyMMdd_HHmmss"（Locale.US）——崩溃日志文件名解析。 */
    fun crashFileNameParse(): SimpleDateFormat = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)
}
