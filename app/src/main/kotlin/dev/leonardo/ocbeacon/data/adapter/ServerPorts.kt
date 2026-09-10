package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.api.attachment.AttachmentApi
import dev.leonardo.ocbeacon.data.api.feedback.FeedbackApi
import dev.leonardo.ocbeacon.data.api.file.FileApi
import dev.leonardo.ocbeacon.data.api.goal.GoalApi
import dev.leonardo.ocbeacon.data.api.message.MessageApi
import dev.leonardo.ocbeacon.data.api.provider.ProviderApi
import dev.leonardo.ocbeacon.data.api.queue.MessageQueueApi
import dev.leonardo.ocbeacon.data.api.reference.ReferenceApi
import dev.leonardo.ocbeacon.data.api.subagent.SubagentApi
import dev.leonardo.ocbeacon.data.api.session.SessionApi
import dev.leonardo.ocbeacon.data.api.shell.ShellApi
import dev.leonardo.ocbeacon.data.api.system.SystemApi
import dev.leonardo.ocbeacon.data.api.UnsupportedServerCapability
import dev.leonardo.ocbeacon.data.api.terminal.TerminalApi
import dev.leonardo.ocbeacon.data.api.workspace.WorkspaceApi
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.repository.ServerSettingsRepository
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures

/**
 * 一个连接可用的域端口集合（#391 ServerAdapter 架构）。
 *
 * 唯一真相：可选端口缺席（null）即该能力不存在。能力集合由本类的
 * [derivedFeatures] 从端口可选性派生，适配器不手写布尔矩阵。
 *
 * 端口命名不得暗示实现来源（如"消息队列"而非"服务器队列"——它可能由客户端实现）。
 * 端口在场只表达"客户端能否提供该能力"，不表达"服务器是否原生支持"。
 *
 * 契约冻结：切片 1-2 冻结本形状，之后只实现不改契约；后续切片以"新增可空字段 +
 * 默认 null"的方式扩展（纯增量，不改既有字段语义）。
 */
data class ServerPorts(
    val session: SessionApi,
    val message: MessageApi,
    val system: SystemApi,
    val file: FileApi? = null,
    val provider: ProviderApi? = null,
    val terminal: TerminalApi? = null,
    val shell: ShellApi? = null,
    val subagents: SubagentApi? = null,
    val goals: GoalApi? = null,
    val feedback: FeedbackApi? = null,
    val queue: MessageQueueApi? = null,
    val serverSettings: ServerSettingsRepository? = null,
    /** 工作区语义（归档 + 含 blank 全量列表）；缺席 = 无工作区连接语义。 */
    val workspace: WorkspaceApi? = null,
    /** @ 引用候选解析；缺席 = 无引用候选能力。 */
    val references: ReferenceApi? = null,
    /** 附件字节拉取；缺席 = 无附件能力（调用方按 null 降级）。 */
    val attachments: AttachmentApi? = null,
) {

    /**
     * 端口在场 => 对应通用能力。
     *
     * 只映射**有用户可见开关的能力**；纯数据层操作端口（references / attachments）
     * 不派发能力位——其权威仍是端口在场性本身（调用方判 null / requireX），
     * 避免为无人消费的端口制造悬空能力位。
     */
    fun derivedFeatures(): Set<ServerFeature> = buildSet {
        add(ServerFeatures.SESSION)
        add(ServerFeatures.MESSAGES)
        add(ServerFeatures.SYSTEM)
        file?.let { add(ServerFeatures.FILES) }
        provider?.let { add(ServerFeatures.PROVIDERS) }
        terminal?.let { add(ServerFeatures.TERMINAL) }
        shell?.let { add(ServerFeatures.SHELL) }
        subagents?.let { add(ServerFeatures.SUBAGENTS) }
        goals?.let { add(ServerFeatures.GOALS) }
        feedback?.let { add(ServerFeatures.FEEDBACK) }
        queue?.let { add(ServerFeatures.QUEUE) }
        serverSettings?.let { add(ServerFeatures.SERVER_SETTINGS) }
        // 工作区域端口在场即派生出两条语义能力：工作区投影与归档写操作
        workspace?.let {
            add(ServerFeatures.WORKSPACE)
            add(ServerFeatures.SESSION_ARCHIVE)
        }
    }

    // ---- 可选端口取值：缺席即显式失败（读空 / 写抛的统一入口） ----------------

    /** 可选端口强制取值：缺席即显式失败；两个名字仅用于诊断（端口名 + 连接类型名）。 */
    private fun <T> requirePort(port: T?, name: String, conn: ServerConnection): T =
        port ?: throw UnsupportedServerCapability(name, conn.serverType.name)

    fun requireFile(conn: ServerConnection): FileApi = requirePort(file, "file", conn)

    fun requireProvider(conn: ServerConnection): ProviderApi = requirePort(provider, "provider", conn)

    fun requireTerminal(conn: ServerConnection): TerminalApi = requirePort(terminal, "terminal", conn)

    fun requireShell(conn: ServerConnection): ShellApi = requirePort(shell, "shell", conn)

    fun requireWorkspace(conn: ServerConnection): WorkspaceApi = requirePort(workspace, "workspace", conn)

    fun requireReferences(conn: ServerConnection): ReferenceApi = requirePort(references, "references", conn)
}
