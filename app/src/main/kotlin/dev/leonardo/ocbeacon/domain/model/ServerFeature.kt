package dev.leonardo.ocbeacon.domain.model

/**
 * 服务器能力标识（#391 ServerAdapter 架构）——开放命名空间 id，取代"固定布尔字段集合"。
 *
 * 能力集合 = 端口可选性派生能力 + 适配器私有能力（见 `ServerPorts.derivedFeatures`）。
 * - 通用能力：跨类型同语义，常量集中在 [ServerFeatures]；
 * - 私有能力：由适配器自带命名空间（如 `oc.share` / `dsh.agentPreset`），新增私有能力
 *   不改共享代码。
 *
 * 值类保证零装箱：能力集合是 Set<ServerFeature>，比较按 id 字符串。
 */
@JvmInline
value class ServerFeature(val id: String) {
    override fun toString(): String = id
}

/**
 * 通用能力常量（跨服务器类型同语义）。
 *
 * 命名约定：`core.` 前缀 = 架构层定义的通用能力；类型私有能力用类型前缀
 * （`oc.` / `dsh.`）由各适配器自带，不登记在此。
 */
object ServerFeatures {
    /** 会话域端口在场（所有类型必有）。 */
    val SESSION = ServerFeature("core.session")

    /** 消息域端口在场（所有类型必有）。 */
    val MESSAGES = ServerFeature("core.messages")

    /** 系统域端口在场（健康 / 命令 / 技能 / MCP）。 */
    val SYSTEM = ServerFeature("core.system")

    /** 文件域端口在场（目录树 / 内容读 / vcs 等子能力按端口内子能力表达）。 */
    val FILES = ServerFeature("core.files")

    /** 提供商与模型目录端口在场。 */
    val PROVIDERS = ServerFeature("core.providers")

    /** 终端 PTY 端口在场。 */
    val TERMINAL = ServerFeature("core.terminal")

    /** shell 命令端口在场。 */
    val SHELL = ServerFeature("core.shell")

    // ---- 端口内子能力（端口在场但子能力因类型/世代而异） --------------------

    /** 文件内容读取。 */
    val FILE_READ = ServerFeature("core.files.read")

    /** Git / vcs 面板。 */
    val VCS = ServerFeature("core.files.vcs")

    /** 文件内容搜索（find）。 */
    val FILE_SEARCH = ServerFeature("core.files.search")

    /** 会话删除。 */
    val SESSION_DELETE = ServerFeature("core.session.delete")

    /** 会话分享。 */
    val SESSION_SHARE = ServerFeature("core.session.share")

    /** 前台会话后台化（堆积队列）。 */
    val SESSION_BACKGROUND = ServerFeature("core.session.background")

    /** 撤销 / 重做（revert/unrevert）。 */
    val SESSION_REVERT = ServerFeature("core.session.revert")

    /** 会话归档。 */
    val SESSION_ARCHIVE = ServerFeature("core.session.archive")

    /** 工作区连接语义（workspace 投影 / 归档 / 连接复用候选）。 */
    val WORKSPACE = ServerFeature("core.session.workspace")

    /** 斜杠命令面板与命令执行。 */
    val COMMANDS = ServerFeature("core.commands")

    /** 排队可见性（消息队列域）。 */
    val QUEUE = ServerFeature("core.queue")

    /** 排队项编辑动词（部分实现只有移除/插话，无编辑）。 */
    val QUEUE_EDIT = ServerFeature("core.queue.edit")

    /** 队列由服务器**推送**（帧快照）而非客户端拉取——数据源差异对上层只暴露为能力位。 */
    val QUEUE_PUSH = ServerFeature("core.queue.push")

    /** 后台任务由服务器帧推送（session/jobs）。 */
    val JOBS_PUSH = ServerFeature("core.jobs.push")

    /** Plan 模式投影域（计划 chip 的显隐依据）。 */
    val PLAN = ServerFeature("core.plan")

    /** 目标（goal）动作面板。 */
    val GOALS = ServerFeature("core.goals")

    /** 消息 👍/👎 反馈。 */
    val FEEDBACK = ServerFeature("core.feedback")

    /** 消息级成本（cost 桶在场；DSH 全链无 → 不渲染成本项）。 */
    val COST = ServerFeature("core.cost")

    /** 消息级删除动词（单条消息可删；DSH 客户端恒返回 false → 不出现入口）。 */
    val MESSAGE_DELETE = ServerFeature("core.message.delete")

    /** turn 级计时（TTFT / tokens·s；仅 DSH 投影面提供）。 */
    val TURN_TIMING = ServerFeature("core.turnTiming")

    /** 权限预设切换器（沙箱 × 审批组合档位）。 */
    val PERMISSION_SWITCH = ServerFeature("core.permissionSwitch")

    /** Agent 预设选择器。 */
    val AGENT_PRESET = ServerFeature("core.agentPreset")

    /** 子智能体域（目录 / 续聊 / 父址中断）。 */
    val SUBAGENTS = ServerFeature("core.subagents")

    /** 服务器设置特权面（设置描述 / 变更 / 插件清单 / 预设管理 / 提供商目录）。 */
    val SERVER_SETTINGS = ServerFeature("core.serverSettings")

    /** 凭据（token / cookie）输入式鉴权——断连时需引导用户录入凭据。 */
    val AUTH_TOKEN = ServerFeature("core.auth.token")
}
