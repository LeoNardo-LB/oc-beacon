package dev.leonardo.ocbeacon.data.api

/**
 * 从 REST `GET /session` 轮询响应中提取的轻量级会话状态快照
 * ——仅包含校正 SSE 派生状态所需的字段。
 *
 * 在可能错过 SSE 事件时用于基于 REST 的状态校正。
 *
 * @property type 取值为 `"idle"`、`"busy"`、`"retry"` 之一。
 * @property attempt 重试尝试编号；仅当 [type] == `"retry"` 时非空。
 * @property message 可选的人类可读状态详情。
 * @property next 下次计划尝试的可选 epoch 毫秒时间戳。
 */
data class RestSessionStatusInfo(
    val type: String,          // "idle" | "busy" | "retry"
    val attempt: Int? = null,  // 仅用于 "retry"
    val message: String? = null,
    val next: Long? = null
)
