package dev.leonardo.ocbeacon.domain.repository

import dev.leonardo.ocbeacon.domain.model.ServerConnection

/**
 * 服务器管理的聚合 Repository 接口。
 * 遵循 ISP 拆分为 2 个子接口：
 * - [ServerConfigRepository]：服务器 CRUD
 * - [ProviderRepository]：Provider/model 管理
 */
interface ServerRepository :
    ServerConfigRepository,
    ProviderRepository {

    /**
     * 将服务器配置解析为用于 API 调用的 [ServerConnection]。
     * 其他 Repository（如 FileRepository）复用此方法，避免重复
     * serverId→connection 的查找逻辑。
     */
    suspend fun resolveConnection(serverId: String): ServerConnection
}
