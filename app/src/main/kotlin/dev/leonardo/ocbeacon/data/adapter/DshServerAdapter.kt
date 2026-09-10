package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.data.api.dsh.DshApiClient
import dev.leonardo.ocbeacon.data.api.dsh.DshProtocolSource
import dev.leonardo.ocbeacon.data.api.dsh.DshWireProtocol
import dev.leonardo.ocbeacon.data.api.feedback.FeedbackApi
import dev.leonardo.ocbeacon.data.api.goal.GoalApi
import dev.leonardo.ocbeacon.data.api.queue.MessageQueueApi
import dev.leonardo.ocbeacon.data.api.subagent.SubagentApi
import dev.leonardo.ocbeacon.data.adapter.dsh.DshFeedbackPort
import dev.leonardo.ocbeacon.data.adapter.dsh.DshGoalPort
import dev.leonardo.ocbeacon.data.adapter.dsh.DshQueuePort
import dev.leonardo.ocbeacon.data.adapter.dsh.DshSubagentPort
import dev.leonardo.ocbeacon.data.repository.ServerSettingsRepositoryImpl
import dev.leonardo.ocbeacon.domain.repository.ServerSettingsRepository
import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerFeatures
import dev.leonardo.ocbeacon.domain.model.ServerType
import javax.inject.Inject
import javax.inject.Singleton

/**
 * DeepSeek Harness 适配器（线面世代 v011 / v012 由双形态探测判定）。
 *
 * 端口在场性（端口存在性 = 客户端能否提供该能力）：
 * - 在场：会话 / 消息 / 系统 / 文件（目录树等子能力）/ 提供商 + 子智能体 / 目标 /
 *   反馈 / 消息队列（四类私有能力端口，见 data/adapter/dsh/）；
 * - 缺席：终端 PTY、shell 命令（DSH 方法面无对应域；原实现抛
 *   UnsupportedServerCapability，现由端口缺席统一表达）。
 */
@Singleton
class DshServerAdapter @Inject constructor(
    private val dsh: DshApiClient,
    private val protocolSource: DshProtocolSource,
) : ServerAdapter {

    // 私有能力端口（薄委托实现，与协议客户端解耦；端口即本适配器的能力声明）
    private val subagents: SubagentApi = DshSubagentPort(dsh)
    private val goals: GoalApi = DshGoalPort(dsh)
    private val feedback: FeedbackApi = DshFeedbackPort(dsh)
    private val queue: MessageQueueApi = DshQueuePort(dsh)
    // 服务器设置端口：实现与 Hilt 单例同源同构（无状态薄委托），由适配器持有其端口身份
    private val serverSettings: ServerSettingsRepository = ServerSettingsRepositoryImpl(dsh)

    override val type: ServerType = ServerType.Dsh

    override fun wireGeneration(conn: ServerConnection): String =
        when (protocolSource.protocolOf(conn.baseUrl)) {
            DshWireProtocol.V012 -> WIRE_V012
            DshWireProtocol.V011 -> WIRE_V011
            // 未探测：保守 V011（0.1.1 全功能路径，零回归）
            null -> WIRE_V011
        }

    override fun ports(conn: ServerConnection): ServerPorts = ServerPorts(
        session = dsh,
        message = dsh,
        system = dsh,
        file = dsh,
        provider = dsh,
        terminal = null,
        shell = null,
        // 四类私有能力经端口挂载（端口在场即能力可用）
        subagents = subagents,
        goals = goals,
        feedback = feedback,
        queue = queue,
        serverSettings = serverSettings,
    )

    override fun coreFlags(conn: ServerConnection): CoreFlags = CoreFlags(
        // #276 接口补全修订：compact 走 /compact 命令通道，HTTP 受理即回，终态由
        // compaction/end 事件通告 → 异步语义 true（旧 false 会产生 59ms 分割线闪现）
        compactionAsync = true,
        // 压缩与模型无关（走斜杠命令通道，不进模型）
        compactionModelIndependent = true,
        // 会话导出响应体即 ZIP 流（落盘名 .zip）
        exportIsArchive = true,
        // settings 特权面 UI 不开放
        configEditable = false,
    )

    /**
     * 非端口派生的能力声明（DSH 域动词）：命令、目标、反馈、权限档、Agent 预设、
     * 归档、排队与排队编辑。终端 / shell 不在此声明——那两者由端口缺席表达。
     */
    override fun privateFeatures(conn: ServerConnection): Set<ServerFeature> = buildSet {
        add(ServerFeatures.COMMANDS)
        add(ServerFeatures.PERMISSION_SWITCH)
        add(ServerFeatures.AGENT_PRESET)
        add(ServerFeatures.SESSION_ARCHIVE)
        // 排队可编辑是端口内子能力（端口在场之外的部分支持）
        add(ServerFeatures.QUEUE_EDIT)
    }

    companion object {
        const val WIRE_V011 = "v011"
        const val WIRE_V012 = "v012"
    }
}
