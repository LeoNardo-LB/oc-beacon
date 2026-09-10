package dev.leonardo.ocbeacon.domain.adapter

import dev.leonardo.ocbeacon.domain.model.ServerCapabilities
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot

/**
 * 服务器适配器解析 seam（#391 ServerAdapter 架构）——**领域层唯一路由入口**。
 *
 * 架构决策：这是领域层与"具体服务器类型"之间唯一的接触面。上层（服务层 / 界面层）
 * 只经本接口取三样东西：能力位、线面世代 id、界面插槽声明；不接触适配器实现、
 * 端口接口或任何服务器类型判断。
 *
 * 端口查询（`ports`）不在本接口——端口接口位于数据层（含 DTO 载荷），领域层必须保持
 * 纯净（不依赖数据层）。数据层内部另有注册表提供端口解析。
 *
 * 实现由数据层提供并在注册表构造期完成校验；Application 启动期强制解析一次，
 * 注册缺失即失败暴露（用户故事 19）。
 */
interface ServerAdapterResolver {

    /** 已注册（= 产品支持）的服务器类型集合。服务器选择器由此枚举驱动。 */
    fun supportedTypes(): Set<ServerType>

    /** 该连接的线面世代纯 id（如 `v1` / `v2` / `v011` / `v012`），不含协议细节。 */
    fun wireGeneration(conn: ServerConnection): String

    /**
     * 该连接的能力位（唯一查询入口）。
     *
     * 目标形态：能力 = 核心行为标志 + 端口可选性派生能力 + 适配器私有能力，由框架计算，
     * 适配器不手写布尔矩阵。
     */
    fun capabilities(conn: ServerConnection): ServerCapabilities

    /** 该连接在界面层声明有内容的插槽（纯声明，不含界面代码）。 */
    fun uiSlots(conn: ServerConnection): Set<ServerUiSlot>
}
