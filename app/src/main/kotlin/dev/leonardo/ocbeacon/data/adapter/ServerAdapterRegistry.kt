package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.domain.adapter.ServerAdapterResolver
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot
import dev.leonardo.ocbeacon.logging.AppLogger
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerAdapterRegistry"

/**
 * 适配器注册表 + 解析器（#391 ServerAdapter 架构）——唯一路由 seam 的数据层实现。
 *
 * 注册：每个适配器在自己包内以集合多绑定（@IntoSet）贡献实例；本类在构造期校验
 * 重复键与全类型覆盖，缺失或不一致即抛错（用户故事 19：Application 启动期强制解析
 * 一次，失败暴露而不是运行到一半才发现）。
 *
 * 解析：
 * - [ports] / [coreFlags] / [privateFeatures] 供数据层内部取端口与标志；
 * - [capabilities] / [wireGeneration] / [uiSlots] / [supportedTypes] 实现领域接口
 *   [ServerAdapterResolver]，是上层唯一的能力查询入口。
 *
 * 能力派生（切片 2）：[capabilities] = 核心行为标志 + 端口可选性派生能力 +
 * 适配器声明的非端口派生能力；集中的服务器类型能力矩阵已删除，适配器不手写布尔矩阵。
 */
@Singleton
class ServerAdapterRegistry @Inject constructor(
    adapters: Set<@JvmSuppressWildcards ServerAdapter>,
) : ServerAdapterResolver {

    private val byType: Map<ServerType, ServerAdapter>

    init {
        val duplicates = adapters.groupBy { it.type }.filterValues { it.size > 1 }.keys
        require(duplicates.isEmpty()) {
            "duplicate ServerAdapter registration for: " + duplicates
        }
        val registered = adapters.map { it.type }.toSet()
        val missing = ServerType.entries.toSet() - registered
        require(missing.isEmpty()) {
            "missing ServerAdapter registration for: " + missing
        }
        byType = adapters.associateBy { it.type }
    }

    /** 该连接的适配器。 */
    fun adapterFor(conn: ServerConnection): ServerAdapter =
        byType[conn.serverType] ?: error("no ServerAdapter registered for " + conn.serverType)

    /** 该连接可用的域端口（端口存在性 = 能力唯一真相）。 */
    fun ports(conn: ServerConnection): ServerPorts = adapterFor(conn).ports(conn)

    /** 该连接的核心行为标志（不可由端口推导项）。 */
    fun coreFlags(conn: ServerConnection): CoreFlags = adapterFor(conn).coreFlags(conn)

    /** 该连接的类型私有能力。 */
    fun privateFeatures(conn: ServerConnection): Set<ServerFeature> =
        adapterFor(conn).privateFeatures(conn)

    override fun supportedTypes(): Set<ServerType> = byType.keys

    override fun wireGeneration(conn: ServerConnection): String =
        adapterFor(conn).wireGeneration(conn)

    override fun capabilities(conn: ServerConnection): ServerCapabilities {
        val adapter = adapterFor(conn)
        return ServerCapabilities(
            coreFlags = adapter.coreFlags(conn),
            features = adapter.ports(conn).derivedFeatures() + adapter.privateFeatures(conn),
        )
    }

    /**
     * 未就绪连接的默认能力（无服务器上下文时 UI 的初始态）：按缺省类型 OpenCode 的
     * V1 语义解析（与原 permissive 缺省一致；唯一的差异是 background 位，旧实现以
     * "版本为 null" 表达、现不可表示，未探测一律按 V1）。
     */
    override fun defaultCapabilities(): ServerCapabilities =
        capabilities(
            ServerConnection(
                baseUrl = "",
                authHeader = null,
                apiVersion = ApiVersion.V1,
                serverType = ServerType.OpenCode,
            )
        )

    override fun uiSlots(conn: ServerConnection): Set<ServerUiSlot> =
        adapterFor(conn).uiSlots

    /**
     * 启动期强制解析一次（Application.onCreate）——注册表构造期校验已保证完整性，
     * 这里再对每条已注册类型做一次解析并留痕，确保"注册缺失即失败暴露"发生在启动期
     * 而不是首次使用。
     */
    fun verifyComplete() {
        val registered = byType.keys
        AppLogger.i(TAG, "ServerAdapter registry verified: types=" + registered)
        for (type in ServerType.entries) {
            check(type in registered) { "ServerAdapter missing for " + type + " at startup" }
        }
    }
}
