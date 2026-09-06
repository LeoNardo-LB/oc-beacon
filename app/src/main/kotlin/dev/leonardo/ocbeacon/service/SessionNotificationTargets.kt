package dev.leonardo.ocbeacon.service

import dev.leonardo.ocbeacon.domain.model.Session

/**
 * #337：子智能体会话通知目标冒泡——发布（SessionNotificationCoordinator）、
 * 退后台补发（#336 PendingInteractionBackgroundNotifier）、撤除
 * （PendingInteractionNotificationRevoker）三侧共用的同一映射：查得
 * parentID → 冒泡到父会话 id 槽；非子会话/查无父原样返回。
 *
 * 通知 id 走 (server, session, kind) 稳定槽位（SessionNotificationIds.of）——
 * 发布与撤除必须解析到同一目标 id；撤除侧若用原始子 sessionId 算槽，
 * 父槽通知将不被撤（#337 根因）。
 */
internal fun bubbleToParentSessionTarget(sessionId: String, sessions: List<Session>): String =
    parentSessionIdOf(sessionId, sessions) ?: sessionId

/**
 * 会话的父归属（携带 parentID 即子智能体会话——不直接触发面向用户的
 * 通知）；非子会话/查无此行返回 null。
 */
internal fun parentSessionIdOf(sessionId: String, sessions: List<Session>): String? =
    sessions.firstOrNull { it.id == sessionId }?.parentId
