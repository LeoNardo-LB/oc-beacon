package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
// 注：JsonElement 已无类型引用（metadata 均为 Map<String, String>）——import 未使用，待清理（#194）

/**
 * SSE 事件 —— 来自 Server-Sent Events 流的事件。
 * 客户端从 GET /global/event 或 GET /event 收到的所有事件。
 */
@Serializable
sealed class SseEvent {
    // 服务器事件
    @Serializable
    data object ServerConnected : SseEvent()

    @Serializable
    data object ServerHeartbeat : SseEvent()

    @Serializable
    data class ServerInstanceDisposed(val directory: String) : SseEvent()

    // 会话生命周期
    @Serializable
    data class SessionCreated(val info: Session) : SseEvent()

    @Serializable
    data class SessionUpdated(val info: Session) : SseEvent()

    @Serializable
    data class SessionDeleted(val info: Session) : SseEvent()

    @Serializable
    data class SessionDiff(
        val sessionId: String,
        val diff: List<FileDiff>
    ) : SseEvent()

    @Serializable
    data class SessionStatus(
        val sessionId: String,
        val status: dev.leonardo.ocbeacon.domain.model.SessionStatus
    ) : SseEvent()

    @Serializable
    data class SessionIdle(
        val sessionId: String,
        /** 事件原始时刻（epoch ms；DSH 帧/历史行携带）。null=来源无时刻（V1/V2）。
         *  #294：重放的历史 turn/end 携带原始时刻，供通知层做陈旧过滤。 */
        val time: Long? = null,
    ) : SseEvent()

    @Serializable
    data class SessionError(
        val sessionId: String?,
        val error: String
    ) : SseEvent()

    /**
     * #309 批1⑤：DSH turn/end reason.kind="max-tokens"（输出达上限，本轮被截断）。
     * Web 对位 turn-max-tokens 通知节点（chat 内插、非悬浮）；续写=用户再发一条
     * prompt（无专用 continue 端点）——UI 卡带「继续」钮发 "continue"。
     * 新一轮 turn/start（SessionStatus Busy）即清除（跨 handler，dispatcher 装配）。
     */
    @Serializable
    data class TurnMaxTokens(
        val sessionId: String,
        val turn: Long,
    ) : SseEvent()

    /**
     * #323：DSH 斜杠命令执行开始（转录 log-only 事件 command/run——先于 handler
     * 的直追加，无轮包裹）。由 MiscEventHandler 折叠进 commandFeedback
     * （commandId 配对，见 [CommandFeedbackFolder]）；历史重放同路径（durable）。
     */
    @Serializable
    data class CommandRunStarted(
        val sessionId: String,
        /** 配对键（command/done 携带同一 id）。 */
        val commandId: String,
        val name: String,
        /** 命令原始入参（recordInput=false 的命令缺席为 null）。 */
        val args: String? = null,
        /** 发起来源（wire source.kind，如 "user"；保真透传）。 */
        val source: String? = null,
        /** 信封 seq（消息列表插入序键）。 */
        val seq: Long = 0L,
        /** 信封 time（卡时间戳）。 */
        val time: Long = 0L,
    ) : SseEvent()

    /**
     * #323：DSH 斜杠命令执行结算（command/done——handler 结算后的直追加）。
     * 与 [CommandRunStarted] 经 commandId 配对，同卡原位刷新为终态（非两行）。
     */
    @Serializable
    data class CommandDone(
        val sessionId: String,
        val commandId: String,
        /** 结算种类（success|error|…——dsh-commands 契约开放词汇）。 */
        val kind: String,
        /** 结算文本（缺席为 null）。 */
        val text: String? = null,
        /** success 结算引用的源事件 seq（缺席为 null）。 */
        val sourceEventSeq: Long? = null,
        val seq: Long = 0L,
        val time: Long = 0L,
    ) : SseEvent()

    // 消息事件
    @Serializable
    data class MessageUpdated(val info: Message) : SseEvent()

    @Serializable
    data class MessageRemoved(
        val sessionId: String,
        val messageId: String
    ) : SseEvent()

    // Part 事件 —— 流式内容
    @Serializable
    data class MessagePartUpdated(val part: Part) : SseEvent()

    @Serializable
    data class MessagePartDelta(
        val sessionId: String,
        val messageId: String,
        val partId: String,
        val field: String,  // 通常为 "text"
        val delta: String   // 要追加的新内容块
    ) : SseEvent()

    @Serializable
    data class MessagePartRemoved(
        val sessionId: String,
        val messageId: String,
        val partId: String
    ) : SseEvent()

    // 权限事件
    @Serializable
    data class PermissionAsked(
        val id: String,
        val sessionId: String,
        val permission: String,
        val patterns: List<String> = emptyList(),
        val metadata: Map<String, String>? = null,
        val always: Boolean = false,
        val tool: ToolRef? = null,
        /** 瞬态：子智能体来源标签（例如 "scout subagent" 服务器原串），不参与序列化。 */
        @kotlinx.serialization.Transient
        val sourceSessionTitle: String? = null
    ) : SseEvent()

    @Serializable
    data class PermissionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    /**
     * DSH 会话权限预设状态变更（三 knob 事件之一，部分事实——单帧只带一个旋钮）。
     *
     * - permission/preset {preset} → [preset]
     * - sandbox/mode {mode} → [sandboxMode]
     * - approval/policy {policy} → [approvalPolicy]
     *
     * 由 SessionEventHandler 折叠进对应 [Session] 的 [Session.permissions]，驱动 UI 回显。
     */
    @Serializable
    data class SessionPermissionChanged(
        val sessionId: String,
        val preset: String? = null,
        val sandboxMode: String? = null,
        val approvalPolicy: String? = null,
    ) : SseEvent()

    /**
     * DSH permissions 投影整值帧（session/projection key="permissions"，
     * value={options,currentValue}——与 session.list 基线同形，DshSessionMapper
     * 单源解析）。整值替换 [Session.permissions]（区别于三 knob 事件的字段级
     * 合并：投影帧携带完整值，含部署预设表 options）；value=null 为 clear tombstone。
     */
    @Serializable
    data class SessionPermissionsChanged(
        val sessionId: String,
        val permissions: SessionPermissions?,
    ) : SseEvent()

    /**
     * DSH 会话 Agent 预设变更（agent-preset/selected {agentPreset}）。
     * 由 SessionEventHandler 折叠进对应 [Session] 的 [Session.agentPreset]，驱动卡片高亮回显。
     */
    @Serializable
    data class SessionAgentPresetChanged(
        val sessionId: String,
        val agentPreset: String,
    ) : SseEvent()

    /**
     * DSH 命令注册表变更（commands/change 帧；#285）。
     *
     * **全局未过滤通知**（cordis 契约："unfiltered registry notification"——非会话
     * 域，无 sessionId）：任一命令注册/注销即广播。消费端（ChatViewModel 经
     * EventDispatcher.commandsChanged）重载当前会话命令列表（commands/list
     * 是 agent-scoped 的，需带 sessionId 重取）。
     */
    @Serializable
    data object CommandsChanged : SseEvent()

    /**
     * DSH 后台任务整快照（session/jobs 帧）。
     * last-wins 整替换：空 [jobs] = 清空该会话任务（subscribed 重连清空同样发空集）。
     * 由 DshJobsHandler 写入 DshJobsStore；OpenCode 无此帧。
     */
    @Serializable
    data class JobsSnapshot(
        val sessionId: String,
        val jobs: List<JobView>,
    ) : SseEvent()

    /**
     * DSH 会话排队收件箱整快照（session/queue 帧；2026-09-01 QueueDock）。
     *
     * 瞬态语义（官方 QueueDock）：不入历史/不重放——仅帧面投递；last-wins 整替换
     *（空 [items] = 清空该会话队列；subscribed 重连清空同样发空集，服务器随后重推）。
     * 由 DshQueueHandler 写入 DshQueueStore；OpenCode 无此帧。
     */
    @Serializable
    data class QueueSnapshot(
        val sessionId: String,
        val items: List<QueuedInboxItem>,
    ) : SseEvent()

    /**
     * DSH workspace 基线整快照（workspace/follow baseline → 合成帧 workspace/baseline；#311 Task1）。
     *
     * wire = WorkspaceBaseline {items:[WorkspaceView], archivedSessionIds}——每代
     * 重连恰一帧（集合替换式）。瞬态语义（不入历史/不重放）；由 DshWorkspaceHandler
     * 写入 DshWorkspaceStore；OpenCode 无此帧。增量消费面：upsert 见
     * [WorkspaceUpserted]、remove/order 见 [WorkspaceRemoved]/[WorkspaceOrderChanged]（#330）。
     */
    @Serializable
    data class WorkspaceSnapshotChanged(
        val workspaces: List<Workspace>,
        val archivedSessionIds: List<String>,
    ) : SseEvent()

    /**
     * DSH workspace 注册表行变更（workspace/follow 增量 {type:'upsert',
     * workspace:WorkspaceView} → 合成帧 workspace/upsert；#311 Task3）。
     *
     * [workspace] 携带整行（title + sessionIds 显式数组 + path）——title 重命名、
     * 新会话入组均走此帧；由 DshWorkspaceHandler 按 workspaceId 原位替换写入
     * DshWorkspaceStore（archived 集合保持）。OpenCode 无此帧。
     */
    @Serializable
    data class WorkspaceUpserted(
        val workspace: Workspace,
    ) : SseEvent()

    /**
     * DSH workspace 归档集合变更（workspace/follow 增量 {type:'archived'} →
     * 合成帧 workspace/archived；#311 Task1）。
     *
     * [archivedSessionIds] 是**完整新集合**（集合替换式，非增量合并——契约 ①-a：
     * workspace/archiveSession 回执同语义）；由 DshWorkspaceHandler 写入
     * DshWorkspaceStore（workspaces 保持不变）。OpenCode 无此帧。
     */
    @Serializable
    data class WorkspaceArchivedChanged(
        val archivedSessionIds: List<String>,
    ) : SseEvent()

    /**
     * DSH workspace 注册表行删除（workspace/follow 增量 {type:'remove', workspaceId}
     * → 合成帧 workspace/remove；#330）。
     *
     * 由 DshWorkspaceHandler 按 workspaceId 删行写入 DshWorkspaceStore（其余行与
     * archived 集合保持）；重连 baseline 前即收敛——#331 对话框陈旧条目随 remove
     * 消费而减。OpenCode 无此帧。
     */
    @Serializable
    data class WorkspaceRemoved(
        val workspaceId: String,
    ) : SseEvent()

    /**
     * DSH workspace 注册表序变更（workspace/follow 增量 {type:'order', workspaceIds}
     * → 合成帧 workspace/order；#330）。
     *
     * [workspaceIds] 是**完整新序**（服务器 changed() 在序变时 publish 全量
     * workspaceIds 数组）；由 DshWorkspaceHandler 写入 DshWorkspaceStore 按帧序
     * 重排（帧内未知 id 忽略、未提及行防丢行，见 applyOrder）。OpenCode 无此帧。
     */
    @Serializable
    data class WorkspaceOrderChanged(
        val workspaceIds: List<String>,
    ) : SseEvent()

    /**
     * DSH tokenUsage 投影变更（session/projection 帧 key=tokenUsage）。
     * 由 SessionEventHandler 折叠进 Session.tokenUsage（last-wins）。
     */
    @Serializable
    data class SessionTokenUsageChanged(
        val sessionId: String,
        val tokenUsage: DshTokenUsage,
    ) : SseEvent()

    /**
     * DSH subagentTiming 投影变更（session/projection 帧 key=subagentTiming）。
     * 由 SessionEventHandler 折叠进 Session.subagentTiming（last-wins）。
     */
    @Serializable
    data class SessionSubagentTimingChanged(
        val sessionId: String,
        val timing: DshSubagentTiming,
    ) : SseEvent()

    /**
     * DSH goal 投影变更（session/projection 帧 key=goal / goal/change 事件全量值）。
     * 由 SessionEventHandler 折叠进 Session.goal（last-wins；[goal] null = 首建前/clear tombstone）。
     */
    @Serializable
    data class SessionGoalChanged(
        val sessionId: String,
        val goal: DshGoalProjection?,
    ) : SseEvent()

    /**
     * DSH plan 投影变更（session/projection 帧 key=plan，#310③）。
     * 由 SessionEventHandler 折叠进 Session.plan（last-wins；[plan] null = tombstone）。
     */
    @Serializable
    data class SessionPlanChanged(
        val sessionId: String,
        val plan: DshPlanProjection?,
    ) : SseEvent()

    /**
     * DSH contextPressure 投影变更（session/projection 帧 key=contextPressure）。
     * 由 SessionEventHandler 折叠进 Session.contextPressure（last-wins）。
     */
    @Serializable
    data class SessionContextPressureChanged(
        val sessionId: String,
        val pressure: DshContextPressure,
    ) : SseEvent()

    /**
     * DSH contextBreakdown 投影变更（session/projection 帧 key=contextBreakdown）。
     * 由 SessionEventHandler 折叠进 Session.contextBreakdown（last-wins）。
     */
    @Serializable
    data class SessionContextBreakdownChanged(
        val sessionId: String,
        val breakdown: DshContextBreakdown,
    ) : SseEvent()

    /**
     * DSH sessionStats 投影变更（session/projection 帧 key=sessionStats）。
     * 由 SessionEventHandler 折叠进 Session.sessionStats（last-wins）。
     */
    @Serializable
    data class SessionStatsChanged(
        val sessionId: String,
        val stats: DshSessionStats,
    ) : SseEvent()

    // 问题事件
    @Serializable
    data class QuestionAsked(
        val id: String,
        val sessionId: String,
        val questions: List<Question>,
        val tool: ToolRef? = null,
        /** 瞬态：子智能体来源标签，不参与序列化。 */
        @kotlinx.serialization.Transient
        val sourceSessionTitle: String? = null
    ) : SseEvent() {
        @Serializable
        data class Question(
            val header: String,
            val question: String,
            val multiple: Boolean = false,
            val custom: Boolean = true,
            val options: List<Option>,
            /** V2 form field key（q0/q1...）；V1 为 null。用于 form reply 构造 answer map。 */
            val key: String? = null,
            /** #310③：DSH 问题描述正文（user-questions detail）——plan-review 时为计划全文。 */
            val detail: String? = null,
            /** #310③：呈现意图（只改呈现不改协议；不认识 kind 的 UI 按通用选项表渲染）。 */
            val intent: Intent? = null,
        )

        /**
         * #310③：调用方声明的呈现意图（dsh-user-questions AskUserQuestionIntent）。
         * kind="plan-review"：detail 是待审计划全文，[approve] 命名批准选项的
         * label（非位置、非布尔——服务端 intent.approve 即 option label 字符串），
         * 其余选项均为否决。
         */
        @Serializable
        data class Intent(
            val kind: String? = null,
            val approve: String? = null,
        )

        @Serializable
        data class Option(
            val label: String,
            val description: String,
            /** V2 form option value（提交用）；V1 为 null（label 即提交值）。 */
            val value: String? = null
        )
    }

    @Serializable
    data class QuestionReplied(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    @Serializable
    data class QuestionRejected(
        val sessionId: String,
        val requestId: String
    ) : SseEvent()

    // Todo 事件
    @Serializable
    data class TodoUpdated(
        val sessionId: String,
        val todos: List<Todo>
    ) : SseEvent() {
        @Serializable
        data class Todo(
            val content: String,
            val status: String,
            val priority: String
        )
    }

    // VCS 事件
    @Serializable
    data class VcsBranchUpdated(val branch: String) : SseEvent()

    // LSP 事件
    @Serializable
    data object LspUpdated : SseEvent()

    // 项目事件
    @Serializable
    data class ProjectUpdated(val info: Project) : SseEvent()

    // ============ V2 新增事件 ============

    // 会话压缩
    @Serializable
    data class SessionCompacted(val sessionId: String) : SseEvent()

    // PTY 事件（使用简单字段以避免跨包依赖）
    @Serializable
    data class PtyCreated(
        val id: String,
        val title: String = "",
        val command: String = "",
        val cwd: String = ""
    ) : SseEvent()

    @Serializable
    data class PtyUpdated(
        val id: String,
        val title: String = "",
        val command: String = "",
        val status: String = ""
    ) : SseEvent()

    @Serializable
    data class PtyDeleted(val id: String) : SseEvent()

    // V2 后台 shell 命令事件（session.shell.started/ended）
    // 与 Pty（交互式终端）不同：非交互命令，输出捕获到文件。
    @Serializable
    data class ShellJobStarted(val info: ShellJob) : SseEvent()

    @Serializable
    data class ShellJobEnded(val info: ShellJob, val output: String? = null) : SseEvent()

    // 工作区事件
    @Serializable
    data class WorkspaceReady(val workspaceId: String) : SseEvent()

    @Serializable
    data class WorkspaceFailed(val workspaceId: String, val error: String? = null) : SseEvent()

    // 文件编辑事件
    @Serializable
    data class FileEdited(val path: String) : SseEvent()

    // MCP 工具变更
    @Serializable
    data class McpToolsChanged(val server: String) : SseEvent()

    // 命令执行完成
    @Serializable
    data class CommandExecuted(
        val name: String,
        val sessionId: String,
        val arguments: String = "",
        val messageId: String = ""
    ) : SseEvent()

    // 文件监听
    @Serializable
    data class FileWatcherUpdated(val path: String) : SseEvent()

    // 安装更新
    @Serializable
    data class InstallationUpdated(val version: String) : SseEvent()

    @Serializable
    data class InstallationUpdateAvailable(val version: String) : SseEvent()

    // Worktree 事件
    @Serializable
    data class WorktreeReady(val path: String) : SseEvent()

    @Serializable
    data class WorktreeFailed(val path: String, val error: String? = null) : SseEvent()

    // Session Next 事件 —— 细粒度实时状态
    @Serializable
    data class SessionNext(val event: SessionNextEvent) : SseEvent()
}

/**
 * 工具调用引用（用于 permission/question 事件）。
 */
@Serializable
data class ToolRef(
    @SerialName("messageID") val messageId: String,
    @SerialName("callID") val callId: String
)

/**
 * 文件差异 —— 表示文件变更。
 * 与服务器的 Snapshot.FileDiff 对应。
 */
@Serializable
data class FileDiff(
    val file: String,
    val before: String = "",
    val after: String = "",
    val additions: Int = 0,
    val deletions: Int = 0,
    val status: String? = null // "added"、"deleted"、"modified"
)

/**
 * 项目 —— 表示一个 OpenCode 项目。
 * 服务器返回字段：id、worktree、vcs、name、icon、commands、time、sandboxes
 */
@Serializable
data class Project(
    val id: String = "",
    val worktree: String = "",
    val name: String? = null,
    val path: String = "", // 旧字段，可能缺失
    val vcs: String? = null,
    val directory: String? = null,
    val canonical: String? = null // V2 /api/project 实测字段（2026-08-11）
) {
    /** 显示名称：显式 name，或 worktree 的最后一段路径，或 id */
    val displayName: String
        get() = name?.takeIf { it.isNotEmpty() }
            ?: worktree.takeIf { it.isNotEmpty() }?.let { dev.leonardo.ocbeacon.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: canonical?.takeIf { it.isNotEmpty() }?.let { dev.leonardo.ocbeacon.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: path.takeIf { it.isNotEmpty() }?.let { dev.leonardo.ocbeacon.util.PathUtils.fileName(it.trimEnd('/', '\\')) }?.takeIf { it.isNotEmpty() }
            ?: id.take(8)
}

