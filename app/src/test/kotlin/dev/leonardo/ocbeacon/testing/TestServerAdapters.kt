package dev.leonardo.ocbeacon.testing

import dev.leonardo.ocbeacon.data.adapter.ServerAdapter
import dev.leonardo.ocbeacon.data.adapter.ServerAdapterRegistry
import dev.leonardo.ocbeacon.data.adapter.ConnectionStrategy
import dev.leonardo.ocbeacon.data.adapter.OpenCodeConnectionStrategy
import dev.leonardo.ocbeacon.data.adapter.ServerPorts
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.shell.ShellApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerType
import io.mockk.mockk

/**
 * #391 测试用适配器：端口与标志可注入，不做任何类型分支。
 *
 * 供"只关心域端口行为"的仓储/服务测试使用——把被测类原本注入的 mock 端口塞进
 * [ServerPorts]，再包成注册表，即可在不再依赖被删除的手写路由门面的前提下复用既有桩。
 */
class FakeServerAdapter(
    override val type: ServerType,
    private val ports: ServerPorts,
    private val flags: CoreFlags = CoreFlags(false, false, false, false),
    private val features: Set<ServerFeature> = emptySet(),
    private val wire: String = "fake",
    strategy: ConnectionStrategy = OpenCodeConnectionStrategy(),
) : ServerAdapter {

    override val connectionStrategy: ConnectionStrategy = strategy

    override fun wireGeneration(conn: ServerConnection): String = wire

    override fun ports(conn: ServerConnection): ServerPorts = ports

    override fun coreFlags(conn: ServerConnection): CoreFlags = flags

    override fun privateFeatures(conn: ServerConnection): Set<ServerFeature> = features
}

/**
 * 两类型共用同一组端口的最小注册表（OpenCode 与 DSH 都返回传入端口）。
 * 需要区分两面时改用 [FakeServerAdapter] 自行组装。
 */
fun testAdapterRegistry(
    session: SessionApi = mockk(relaxed = true),
    message: MessageApi = mockk(relaxed = true),
    system: SystemApi = mockk(relaxed = true),
    file: FileApi = mockk(relaxed = true),
    provider: ProviderApi = mockk(relaxed = true),
    terminal: TerminalApi = mockk(relaxed = true),
    shell: ShellApi = mockk(relaxed = true),
): ServerAdapterRegistry {
    val ports = ServerPorts(
        session = session,
        message = message,
        system = system,
        file = file,
        provider = provider,
        terminal = terminal,
        shell = shell,
    )
    return ServerAdapterRegistry(
        setOf(
            FakeServerAdapter(ServerType.OpenCode, ports),
            FakeServerAdapter(ServerType.Dsh, ports),
        )
    )
}
