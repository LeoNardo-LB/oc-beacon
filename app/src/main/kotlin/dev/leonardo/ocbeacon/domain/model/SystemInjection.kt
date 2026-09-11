package dev.leonardo.ocbeacon.domain.model

/**
 * 上下文注入消息识别（#387，纯函数，无界面/数据依赖）。
 *
 * V2 服务端把 skill catalog / 工作区指令等宿主上下文注入按普通 user 消息投递，
 * **不带 source.kind 标记**（DSH 侧有 source.kind，见 DshEventMapper）。此类注入
 * 正文被 `<system-reminder>…</system-reminder>` 包裹；若按普通用户气泡渲染即成
 * 整屏文字墙。本对象提供「整条是否为纯注入块」判定，供渲染层单点折叠
 * （对齐 [dev.leonardo.ocbeacon.service.sanitizeNotificationText] 的注入识别）。
 *
 * 判定放宽边界：仅**整条闭合块**算注入；「闭合块 + 真问句」的混合消息返回
 * false（保持普通气泡，避免把用户正文一并折叠吞掉）。
 */
object SystemInjection {

    /** 整条正文（允许首尾空白）恰为一个闭合 `<system-reminder>` 块。用 DOT_MATCHES_ALL 跨行。 */
    private val PURE_REMINDER = Regex("(?s)^\\s*<system-reminder>.*?</system-reminder>\\s*$")

    /** 纯注入块判定（大小写敏感——服务端标记固定小写）。 */
    fun isPureReminder(text: String): Boolean = PURE_REMINDER.matches(text)
}
