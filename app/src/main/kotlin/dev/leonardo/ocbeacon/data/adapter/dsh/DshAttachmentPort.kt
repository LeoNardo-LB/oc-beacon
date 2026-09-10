package dev.leonardo.ocbeacon.data.adapter.dsh

import dev.leonardo.ocbeacon.data.api.attachment.AttachmentApi
import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * DSH 附件端口实现（#391 切片9）——薄委托；协议客户端内部已完成失败降级 null。
 */
class DshAttachmentPort(
    private val dsh: DshApiClient,
) : AttachmentApi {

    override suspend fun readAttachment(
        conn: ServerConnection,
        sessionId: String,
        attachmentId: String,
    ): Pair<String, String>? = dsh.readAttachment(conn, sessionId, attachmentId)
}
