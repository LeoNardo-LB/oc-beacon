package dev.leonardo.ocbeacon.data.adapter

import dev.leonardo.ocbeacon.domain.model.CoreFlags
import dev.leonardo.ocbeacon.domain.model.ServerConnection
import dev.leonardo.ocbeacon.domain.model.ServerFeature
import dev.leonardo.ocbeacon.domain.model.ServerType
import dev.leonardo.ocbeacon.domain.model.ServerUiSlot

/**
 * 服务器类型适配器契约（#391 ServerAdapter 架构；spec 适配器契约）。
 *
 * 每种服务器类型 = 一个适配器单元：它实现哪些域端口、有哪些独有能力、用哪种连接
 * 策略、在哪些界面插槽有内容。新增一种服务器类型 = 新增一个本接口的实现 + 同目录
 * 注册（@IntoSet 多绑定），不改既有共享代码。
 *
 * 归属说明：[ports] 返回的 [ServerPorts] 持有数据层端口接口（载荷含 DTO），因此本
 * 契约与 [ServerPorts] 落在数据层；领域层只持有
 * [dev.leonardo.ocbeacon.domain.adapter.ServerAdapterResolver] 这一领域安全的投影
 * （能力位 / 世代 id / 界面插槽声明）。依赖方向仍是 UI → Domain ← Data。
 *
 * 契约冻结：切片 1-2 冻结本形状，之后只实现不改契约。connectionStrategy 在切片 6
 * 抽取连接策略时加入（探针需要完整凭据上下文，切片 1 无法给出真实实现）。
 */
interface ServerAdapter {

    /** 本适配器负责的服务器类型。 */
    val type: ServerType

    /** 该连接的线面世代纯 id（如 v1 / v2 / v011 / v012），不含协议细节。 */
    fun wireGeneration(conn: ServerConnection): String

    /** 该连接可用的域端口集合：可选端口为 null 即该能力不存在（端口存在性 = 唯一真相）。 */
    fun ports(conn: ServerConnection): ServerPorts

    /** 不可由端口可选性推导的核心行为标志（目标个位数）。 */
    fun coreFlags(conn: ServerConnection): CoreFlags

    /** 类型私有能力（自带命名空间，如 dsh.agentPreset）；默认空。 */
    fun privateFeatures(conn: ServerConnection): Set<ServerFeature> = emptySet()

    /** 声明在哪些界面插槽有内容（纯声明，不含界面代码）。 */
    val uiSlots: Set<ServerUiSlot> get() = emptySet()
}
