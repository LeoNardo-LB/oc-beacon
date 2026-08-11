package dev.leonardo.ocbeacon.domain.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

/**
 * V2 后台 shell 命令（非交互）。
 *
 * 对应 V2 `Shell.Info`：
 * ```
 * {id, status, command, cwd, shell, file, pid, exit, metadata, time}
 * ```
 * - 与 Pty 交互式终端（PtyInfo）是不同概念：这是 `POST /api/shell` 启动的
 *   一次性非交互命令，stdout/stderr 合并捕获到文件，可通过 output 分页读取。
 * - `metadata.sessionID` 标识归属会话（父会话收到 session.shell.* 事件）。
 * - 生命周期：running → exited（exit code）或被杀掉。
 */
@Serializable
data class ShellJob(
    val id: String = "",
    /** "running" / "exited" / 其他服务器状态值 */
    val status: String = "",
    val command: String = "",
    val cwd: String = "",
    /** 解释器（bash/zsh 等） */
    val shell: String = "",
    /** 捕获输出文件路径 */
    val file: String = "",
    val pid: Long? = null,
    /** 退出码（ended 后有） */
    val exit: Int? = null,
    /** 归属会话 ID（从 metadata.sessionID 提取） */
    val sessionId: String? = null,
    val startedAt: Long? = null,
    val completedAt: Long? = null,
    /** 事件携带的输出（session.shell.ended 的 output 字段） */
    val output: String? = null,
    /** 输出分页游标（来自 output 端点响应） */
    val cursor: Long? = null,
    /** 原始 metadata（双写 sessionID/sessionId） */
    val metadata: Map<String, JsonElement>? = null
) {
    val isRunning: Boolean get() = status == "running"
}

/**
 * V2 后台 shell 事件（session.shell.started/ended 解析产物）。
 */
@Serializable
data class ShellJobEvent(
    val info: ShellJob,
    /** ended 事件附带输出（可为 null） */
    val output: String? = null
)

/**
 * 活跃会话信息（GET /api/session/active 的 value）。
 * V2 返回 `{data: {sessionID: {type: "running"}}}`——absent = 非前台（后台/无活动）。
 */
@Serializable
data class ActiveSessionInfo(
    val type: String = ""
)

/**
 * 活跃会话查询结果：sessionID → 活动类型。
 */
@Serializable
data class ActiveSessionsResult(
    @SerialName("data")
    val sessions: Map<String, ActiveSessionInfo> = emptyMap()
)

/**
 * Shell 输出分页响应（GET /api/shell/:id/output）。
 * 对应 V2 `Shell.Output`：`{output, cursor, size, truncated}`。
 */
@Serializable
data class ShellOutput(
    val output: String = "",
    val cursor: Long = 0,
    val size: Long = 0,
    val truncated: Boolean = false
)
