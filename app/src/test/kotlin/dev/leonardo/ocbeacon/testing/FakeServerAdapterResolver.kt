package dev.leonardo.ocbeacon.testing

import dev.leonardo.ocbeacon.domain.adapter.ServerAdapterResolver
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot

/**
 * #391 测试替身：能力位解析器。
 *
 * ViewModel 测试不需要真实适配器图，用本替身给出稳定的能力集合。默认集合对齐
 * OpenCode V1（迁移前 permissive 缺省：终端 / shell / 命令 / 文件读 / vcs / 文件搜索 /
 * 会话删除 / 撤销 / 分享在位），可按需覆盖。
 */
class FakeServerAdapterResolver(
    private val features: Set<ServerFeature> = OPEN_CODE_V1,
    private val coreFlags: CoreFlags = CoreFlags(
        compactionAsync = false,
        compactionModelIndependent = false,
        exportIsArchive = false,
        configEditable = true,
    ),
) : ServerAdapterResolver {

    override fun supportedTypes(): Set<ServerType> = ServerType.entries.toSet()

    override fun wireGeneration(conn: ServerConnection): String = "v1"

    override fun transportKind(conn: ServerConnection): dev.leonardo.ocbeacon.domain.adapter.TransportKind =
        if (conn.serverType == ServerType.Dsh) {
            dev.leonardo.ocbeacon.domain.adapter.TransportKind.MUX
        } else {
            dev.leonardo.ocbeacon.domain.adapter.TransportKind.SSE
        }

    /** 按连接的服务器类型给出能力（镜像真实注册表的按类型解析，便于 DSH 路径测试）。 */
    override fun capabilities(conn: ServerConnection): ServerCapabilities =
        if (conn.serverType == ServerType.Dsh) {
            ServerCapabilities(
                CoreFlags(
                    compactionAsync = true,
                    compactionModelIndependent = true,
                    exportIsArchive = true,
                    configEditable = false,
                ),
                DSH,
            )
        } else {
            ServerCapabilities(coreFlags, features)
        }

    override fun uiSlots(conn: ServerConnection): Set<ServerUiSlot> = emptySet()

    override fun defaultCapabilities(): ServerCapabilities = ServerCapabilities(coreFlags, features)

    companion object {
        /** OpenCode V1 语义的能力集合。 */
        val OPEN_CODE_V1: Set<ServerFeature> = setOf(
            ServerFeatures.SESSION,
            ServerFeatures.MESSAGES,
            ServerFeatures.SYSTEM,
            ServerFeatures.FILES,
            ServerFeatures.PROVIDERS,
            ServerFeatures.TERMINAL,
            ServerFeatures.SHELL,
            ServerFeatures.COMMANDS,
            ServerFeatures.FILE_READ,
            ServerFeatures.VCS,
            ServerFeatures.FILE_SEARCH,
            ServerFeatures.SESSION_DELETE,
            ServerFeatures.SESSION_REVERT,
            ServerFeatures.SESSION_SHARE,
        )

        /** DSH 语义的能力集合（镜像 DshServerAdapter 的派生 + 声明位）。 */
        val DSH: Set<ServerFeature> = setOf(
            ServerFeatures.SESSION,
            ServerFeatures.MESSAGES,
            ServerFeatures.SYSTEM,
            ServerFeatures.FILES,
            ServerFeatures.PROVIDERS,
            ServerFeatures.COMMANDS,
            ServerFeatures.PERMISSION_SWITCH,
            ServerFeatures.AGENT_PRESET,
            ServerFeatures.SESSION_ARCHIVE,
            ServerFeatures.WORKSPACE,
            ServerFeatures.QUEUE,
            ServerFeatures.QUEUE_EDIT,
            ServerFeatures.QUEUE_PUSH,
            ServerFeatures.JOBS_PUSH,
            ServerFeatures.PLAN,
            ServerFeatures.GOALS,
            ServerFeatures.FEEDBACK,
            ServerFeatures.SUBAGENTS,
            ServerFeatures.SERVER_SETTINGS,
            ServerFeatures.AUTH_TOKEN,
        )
    }
}
