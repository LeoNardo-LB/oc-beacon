# OC Beacon

非官方 OpenCode Android 客户端——本文件是项目领域术语表（glossary），只定义概念，不记录实现。

## Language

**渲染供给（Render Supply）**:
聊天列表视口前方的渲染资源预备决策——预解析哪些长文本、何时安全地分片、何时必须冻结（跳转稳定窗口）。
_Avoid_: 预解析驱动器（preparse driver）、分片协调器（chunk coordinator）——它们是同一供给决策的两个机制，不是独立概念

**流式 turn（Streaming Turn）**:
completed 时间戳为空的 assistant 回复轮次；其内容随时增长，禁止被预解析或分片固化。
_Avoid_: 流式消息（易与"正在显示的消息"混淆）

**跳转稳定窗口（Jump Settling Window）**:
跳转终点后的短冻结期（现值 2s）；期间禁止分片提交，防止视口边缘 key 裂变。
_Avoid_: 跳转锁（jump lock——那是 autoLoad 抑制，另一个概念）

**红点时钟域（Unread Clock Domain）**:
未读红点只消费服务器完成时刻（SSE 载荷 / REST 载荷）——本地终结戳（UI 流式终止）对展示域正当，但对红点域不可见；唯一例外是会话错误，显式携带客户端时刻。
_Avoid_: "消息时间戳都能用"（合并缓存/DB 回读混入客户端时刻，时钟偏差时红点粘滞或消失）

**必需协作者（Required Collaborator）**:
FSM（会话状态机）运行所需的全部外部事实（消息缓存的流式状态、目录路由、僵尸防护、turn 结束副作用）由单一接口在构造期整体提供——不可缺省、不可事后补挂。
_Avoid_: "回调旋钮"（可缺省 var 逐个接线，漏接即静默降级——REST 打错路由这类事故的根源）

**版本 seam（Version Seam）**:
V1/V2 协议差异的两个收口：分页游标策略（行为差异——V2 是服务器窗口语义）与服务器能力位（门控差异——UI 只读能力不读版本）；版本号本身只在连接对象与数据层门面存在。
_Avoid_: "到处判 isV2"（79 个决策点中 78 个已在门面内收敛，门面外的散布即泄漏）

**连接生命周期协调（Connection Lifecycle）**:
一台服务器从纳入连接到断开的完整编排——SSE 连接驱动、轮询启停、终端与通知资源清理的单一决策点。
_Avoid_: "Service 管连接"（Service 是 Android 前台服务 adapter，不持有生命周期状态）
