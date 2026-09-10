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
        dev.leonardo.ocbeacon.domain.adapter.TransportKind.SSE

    override fun capabilities(conn: ServerConnection): ServerCapabilities =
        ServerCapabilities(coreFlags, features)

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
    }
}
