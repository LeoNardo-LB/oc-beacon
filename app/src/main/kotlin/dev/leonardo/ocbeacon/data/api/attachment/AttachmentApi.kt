package dev.leonardo.ocbeacon.data.api.attachment

import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * 附件字节拉取端口（#391 切片9）。
 *
 * 端口在场 = 该类型可拉取会话附件字节；值 = (mediaType, base64)，不可用/失败一律
 * 返回 null（不抛）。缺席时调用方按 null 降级，不做服务器类型判断。
 */
interface AttachmentApi {
    suspend fun readAttachment(
        conn: ServerConnection,
        sessionId: String,
        attachmentId: String,
    ): Pair<String, String>?
}
