package dev.leonardo.octether.domain.usecase

import dev.leonardo.octether.domain.model.PermissionState
import dev.leonardo.octether.domain.repository.ChatRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject

// TODO: Add filtering/transformation logic or consider removing this UseCase if it remains a pure delegate
class PermissionHandlerUseCase @Inject constructor(
    private val chatRepository: ChatRepository
) {
    fun observePermissions(sessionId: String): Flow<List<PermissionState>> =
        chatRepository.getPermissionsFlow(sessionId)

    suspend fun respond(serverId: String, permissionId: String, approved: Boolean, message: String?): Result<Boolean> {
        val reply = if (approved) "allow" else "deny"
        return chatRepository.respondPermission(serverId, permissionId, reply, message)
    }
}
