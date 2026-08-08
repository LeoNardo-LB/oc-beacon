package dev.leonardo.ocbeacon.data.repository

import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 多服务器流式会话所有权注册表。
 *
 * 当两个服务器配置指向同一后端（同一 OpenCode serve 实例）时，
 * 两条 SSE 连接都会投递相同的全局事件。若不做所有权跟踪，
 * 追加式事件（如 MessagePartDelta）会被应用两次，流式文本输出翻倍。
 *
 * 首个为会话投递事件的服务器获得所有权；其他服务器的同会话事件被跳过。
 * 所有权在 [release]（SessionDeleted）、[releaseAllForServer]（clearForServer）
 * 或 [clearAll] 时释放。
 */
@Singleton
class StreamingOwnershipRegistry @Inject constructor() {

    private val owners = ConcurrentHashMap<String, String>()

    /** @return true 表示调用方获得/持有所有权（可处理事件）；false 表示被其他服务器持有（应跳过）。 */
    fun claim(sessionId: String, serverId: String): Boolean {
        val existing = owners.putIfAbsent(sessionId, serverId)
        return existing == null || existing == serverId
    }

    fun release(sessionId: String) {
        owners.remove(sessionId)
    }

    fun releaseAllForServer(serverId: String) {
        owners.entries.removeAll { it.value == serverId }
    }

    fun clearAll() {
        owners.clear()
    }
}
