package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.api.v1.V1ApiClient
import dev.leonardo.ocbeacon.data.api.v2.V2ApiClient
import dev.leonardo.ocbeacon.domain.model.ApiVersion
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * OpenCode Server 适配器（V1/V2 由 [ServerConnection.apiVersion] 在类型内二分）。
 *
 * 端口在场性：V1/V2 两代端点均存在，7 个域端口全部在场；版本差异只体现在
 * [coreFlags] 与各端口实现内部（V1/V2 两个具体客户端各自完成降级），不上浮。
 */
@Singleton
class OpenCodeServerAdapter @Inject constructor(
    private val v1: V1ApiClient,
    private val v2: V2ApiClient,
) : ServerAdapter {

    override val type: ServerType = ServerType.OpenCode

    override fun wireGeneration(conn: ServerConnection): String =
        if (conn.apiVersion.isV2) WIRE_V2 else WIRE_V1

    override fun ports(conn: ServerConnection): ServerPorts =
        if (conn.apiVersion.isV2) {
            ServerPorts(
                session = v2, message = v2, system = v2,
                file = v2, provider = v2, terminal = v2, shell = v2,
                // V2 有 inbox 域（移除 / 插话；无编辑动词）
                queue = v2,
            )
        } else {
            // V1 / UNKNOWN / null：UNKNOWN 与 null 维持 V1 行为（#132 语义）
            ServerPorts(
                session = v1, message = v1, system = v1,
                file = v1, provider = v1, terminal = v1, shell = v1,
            )
        }

    override fun coreFlags(conn: ServerConnection): CoreFlags {
        val isV2 = conn.apiVersion.isV2
        return CoreFlags(
            compactionAsync = isV2,
            compactionModelIndependent = false,
            exportIsArchive = false,
            // V2 /api/config 只读（PATCH 404，backlog #85）；V1 / UNKNOWN / null 可写
            configEditable = !isV2,
        )
    }

    /**
     * 非端口派生的能力声明（共享语义用 core.* 常量）：
     * - 两代共有的端口内子能力：命令、文件内容读、vcs、文件搜索、会话删除、撤销；
     * - V2：前台会话后台化 + inbox 排队；V1/UNKNOWN：会话分享（V2 无 share 端点）；
     * - 逐位与迁移前的集中矩阵一致（切片 2 等价网见 ServerCapabilitiesDerivationTest）。
     */
    override fun privateFeatures(conn: ServerConnection): Set<ServerFeature> = buildSet {
        add(ServerFeatures.COMMANDS)
        add(ServerFeatures.FILE_READ)
        add(ServerFeatures.VCS)
        add(ServerFeatures.FILE_SEARCH)
        add(ServerFeatures.SESSION_DELETE)
        add(ServerFeatures.SESSION_REVERT)
        if (conn.apiVersion.isV2) {
            add(ServerFeatures.SESSION_BACKGROUND)
        } else {
            add(ServerFeatures.SESSION_SHARE)
        }
    }

    companion object {
        const val WIRE_V1 = "v1"
        const val WIRE_V2 = "v2"
    }
}
