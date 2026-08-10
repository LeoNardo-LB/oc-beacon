package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.Draft

/**
 * 草稿仓库接口。
 *
 * 所有方法均为 `suspend`——草稿底层持久化（DataStore/文件 IO）是异步的，
 * 调用方必须在协程中调用。这避免了实现层用 `runBlocking` 在主线程桥接
 * 同步/异步边界导致的 ANR 风险（backlog #38 根因修复）。
 */
interface DraftRepository {
    suspend fun getDraft(sessionId: String): Draft?
    suspend fun saveDraft(sessionId: String, draft: Draft)
    suspend fun clearDraft(sessionId: String)
    suspend fun getDraftSessionIds(): Set<String>
}
