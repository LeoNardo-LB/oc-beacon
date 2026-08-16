package dev.leonardo.ocbeacon.domain.usecase

import dev.leonardo.ocbeacon.domain.model.PermissionState
import dev.leonardo.ocbeacon.domain.model.QuestionState
import dev.leonardo.ocbeacon.domain.repository.ChatRepository
import javax.inject.Inject

/**
 * Use Case：管理权限和问题请求（回复、拒绝、列出待处理项）。
 * 委托给 ChatRepository。
 */
class ManagePermissionUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    suspend fun listPendingPermissions(serverId: String, directory: String?): List<PermissionState> =
        chatRepository.listPendingPermissions(serverId, directory).getOrThrow()

    suspend fun replyToPermission(serverId: String, sessionId: String, requestId: String, reply: String, directory: String?): Boolean =
        chatRepository.respondPermission(serverId, sessionId, requestId, reply, directory).getOrThrow()

    suspend fun listPendingQuestions(serverId: String, directory: String?): List<QuestionState> =
        chatRepository.listPendingQuestions(serverId, directory).getOrThrow()

    suspend fun replyToQuestion(serverId: String, requestId: String, answers: List<List<String>>, directory: String?): Boolean =
        chatRepository.replyToQuestion(serverId, requestId, answers, directory).getOrThrow()

    suspend fun rejectQuestion(serverId: String, requestId: String, directory: String?): Boolean =
        chatRepository.rejectQuestion(serverId, requestId, directory).getOrThrow()
}
