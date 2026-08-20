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
