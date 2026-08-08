package dev.leonardo.ocbeacon.domain.model

/**
 * 批量消息合并策略。决定 [dev.leonardo.ocbeacon.data.repository.handler.MessageEventHandler.upsertMessages]
 * 如何合并 incoming 与 existing 状态。
 *
 * - [SSE_PRIORITY]：SSE 累积的流式内容优先；REST 仅补充完成时间。用于 REST 刷新/进入会话。
 * - [REST_AUTHORITY]：REST 视为真相源，覆盖 existing 元数据；parts 仍走"更长文本胜出"。用于 SSE 重连恢复。
 * - [APPEND_ONLY]：仅补充 existing 中缺失的消息/parts。用于翻页加载更早消息。
 */
enum class MergeStrategy { SSE_PRIORITY, REST_AUTHORITY, APPEND_ONLY }
