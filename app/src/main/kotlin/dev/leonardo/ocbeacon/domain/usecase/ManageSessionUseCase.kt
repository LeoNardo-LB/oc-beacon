package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.MessageWithParts
import dev.leonardo.ocbeacon.domain.model.Session
import dev.leonardo.ocbeacon.domain.repository.SessionRepository
import javax.inject.Inject

/**
 * Use Case：管理会话生命周期（加载、刷新、创建、分叉、重命名、中断、删除消息/内容块、归档/取消归档、导入）。
 * 委托给 SessionRepository。
 */
class ManageSessionUseCase @Inject constructor(
    private val sessionRepository: SessionRepository
) {
    suspend fun getSession(serverId: String, sessionId: String): Session =
        sessionRepository.getSession(serverId, sessionId).getOrThrow()

    suspend fun listMessages(serverId: String, sessionId: String, limit: Int): List<MessageWithParts> =
        sessionRepository.listMessages(serverId, sessionId, limit).getOrThrow().messages

    suspend fun createSession(serverId: String, directory: String?): Session {
        val opts = dev.leonardo.ocbeacon.domain.model.CreateSessionOpts(directory = directory)
        return sessionRepository.createSession(serverId, opts).getOrThrow()
    }

    suspend fun forkSession(serverId: String, sessionId: String): Session =
        sessionRepository.fork(serverId, sessionId).getOrThrow()

    suspend fun renameSession(serverId: String, sessionId: String, title: String) {
        sessionRepository.rename(serverId, sessionId, title).getOrThrow()
    }

    suspend fun abortSession(serverId: String, sessionId: String, directory: String?) {
        sessionRepository.abort(serverId, sessionId, directory).getOrThrow()
    }

    suspend fun deleteMessage(serverId: String, sessionId: String, messageId: String): Boolean =
        sessionRepository.deleteMessage(serverId, sessionId, messageId).getOrThrow()

    suspend fun deleteMessagePart(serverId: String, sessionId: String, messageId: String, partIndex: Int): Boolean =
        sessionRepository.deleteMessagePart(serverId, sessionId, messageId, partIndex).getOrThrow()

    suspend fun archiveSession(serverId: String, sessionId: String): Session =
        sessionRepository.archive(serverId, sessionId).getOrThrow()

    suspend fun unarchiveSession(serverId: String, sessionId: String): Session =
        sessionRepository.unarchive(serverId, sessionId).getOrThrow()

    suspend fun importSession(serverId: String, shareUrl: String): Session =
        sessionRepository.importSession(serverId, shareUrl).getOrThrow()
}
