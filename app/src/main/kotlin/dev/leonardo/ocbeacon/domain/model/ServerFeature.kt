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
}
