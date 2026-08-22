# OC Beacon

非官方 OpenCode Android 客户端——本文件是项目领域术语表（glossary），只定义概念，不记录实现。规范名裁决原则：以 OpenCode API（V2 现行协议）术语为权威源；每个术语必有中文对应名；代码标识符引用 API 字段用 API 原拼写（sessionID），域内用 camelCase（sessionId）。

## 会话与消息

**会话（session）**:
OpenCode 服务器上的对话单元；15 语言文案已全一致用「会话」。
_Avoid_: 对话（文案域旧称）、conversation、chat（仅屏名 Chat 保留）

**消息（message）**:
会话内一条用户/助手/系统记录；V2 type 含 synthetic/shell/compaction 等扩展 role。
_Avoid_: prompt（那是请求侧概念）

**内容块（part）**:
消息内 16 种类型的内容组成块（text/reasoning/tool/shell/step-start/…），API 原词 part；V2 以 ordinal 派生 id 定位，工具块以 call_id 定位。
_Avoid_: 零件、part 裸用（中文语境）

**提示块（PromptPart）**:
发给服务器的请求体组成块（文本/文件/图片），与消息内容块是两个概念；V2 现代端点 POST /prompt 统一承载。
_Avoid_: 与「内容块」混称

**轮次（turn）**:
一次用户输入到助手回复完成的交互单元；「流式 turn」词条定义展示口径。任务完成通知文案用「turn 完成」。
_Avoid_: 任务（通知文案旧称）、回合（CHANGELOG 旧称）

**流式 turn（Streaming Turn）**:
completed 时间戳为空的 assistant 回复轮次；其内容随时增长，禁止被预解析或分片固化。结束信号以 V2 session.execution.succeeded 为权威，session.status(idle)/step.ended 为兼容信号。
_Avoid_: 流式消息（AGENTS.md:106,110 违逆待修）

**合成消息（synthetic）**:
API role/type=synthetic 的系统合成记录；通知文案「合成通知」。
_Avoid_: 系统通知、后台消息（与 background session 混淆）、后台通知、转后台提示

**子智能体（subagent）**:
工具派生的下级 agent 会话；V2 服务器 metadata 以 jobId 承载其会话 ID（服务器别名），客户端另有 childID；拼写统一无连字符。
_Avoid_: sub-agent、子代理、子会话（child session 口语）

**智能体（agent）**:
OpenCode agent 概念（plan/build 等模式）；UI 显示词统一 agent/智能体。
_Avoid_: Assistant（旧显示词）、代理

**会话细粒度事件（session.next.*）**:
V1 引入的细粒度实时事件族（Text*/Tool*/Step*/Usage* 等 27 变体）。
_Avoid_: 「下一代事件」（误读）、Session Next 事件（半英半中）

## 队列与待处理

**堆积消息（pending message）**:
turn 进行中暂存本地、turn 结束后自动发出的消息队列成员；UI 徽章 QUEUED 保留（用户视角）。
_Avoid_: 待发消息、未发消息、暂存消息、排队消息、STACKED

**待处理权限/问题（pending permission/question）**:
服务器下发、等待用户答复的权限请求或问题（question 已吸收 V2 中间契约 form）。
_Avoid_: 待答、堆积（严禁挪用）

## 服务器交互动词

**中断（interrupt）**:
停止运行中的会话（V2 POST /api/session/{id}/interrupt）。
_Avoid_: abort（V1 端点名，仅历史对照）、中止

**重命名（rename）**:
修改会话标题（V2 POST /rename）。
_Avoid_: update（V1 语义泛称）

**压缩（compact）**:
压缩会话上下文（V2 POST /compact；事件族 compaction.*；横幅/摘要统一前缀「压缩」）。
_Avoid_: summarize（V1 端点名）、压缩摘要、上下文压缩、摘要（压缩）

**凭据（credential）**:
provider 的 API key 存取（V2 PATCH/DELETE /api/credential/{id}）。
_Avoid_: auth（V1 端点名）

**撤销（revert）/ 取消撤销（unrevert）**:
回退到某消息之前（V2 revert/stage 两段式）；「重做/redo」保留为口语别名。
_Avoid_: 回退（歧义词）、undo（斜杠命令 /undo /redo 语境保留）

**答复（reply）**:
对权限/问题的回应；权限侧 once/always/reject（一次/总是/拒绝）。
_Avoid_: effect allow/deny（legacy 字段）

## 游标与分页

**分页游标（cursor）**:
消息分页的 opaque token（V2 cursor 对象 {previous=更新, next=更旧}——方向语义反直觉，属 API 契约）。
_Avoid_: 裸称「游标」

**Shell 输出游标**:
后台 shell 输出分页的字节偏移（Long），与分页游标无关。

**会话列表游标**:
会话列表分页 token，第三种独立 cursor。

## 展示与渲染

**渲染供给（Render Supply）**:
聊天列表视口前方的渲染资源预备决策——预解析哪些长文本、何时安全地分片、何时必须冻结（跳转稳定窗口）。
_Avoid_: 预解析驱动器（preparse driver）、分片协调器（chunk coordinator）——它们是同一供给决策的两个机制，不是独立概念

**跳转稳定窗口（Jump Settling Window）**:
跳转终点后的短冻结期（现值 2s）；期间禁止分片提交，防止视口边缘 key 裂变。三窗口机制之一——另两个：jumpLock 解锁缓冲（300ms，滚动恢复）与跳转后滚动稳定（900ms，现值；KDoc 残留 1.5s 为失实）。
_Avoid_: 跳转锁（jump lock——那是 autoLoad 抑制，另一个概念）、滚动锚定锁

**状态簇（State Cluster）**:
聊天界面按状态归属划分的消费单元：会话上下文/会话数据（含分页与 SSE 生命周期）/输入/模型配置——UI 读簇对象而非 ViewModel 的百个散成员；跨簇编排（发送、revert、abort）留在薄 ViewModel。会话列表侧同族概念（内容册/外壳册为其自造旧称）。
_Avoid_: "全都能从 VM 拿"、内容册/外壳册、集群

## 时间与未读

**红点时钟域（Unread Clock Domain）**:
未读红点只消费服务器完成时刻（SSE 载荷 / REST 载荷）——本地终结戳（UI 流式终止）对展示域正当，但对红点域不可见；唯一例外是会话错误，显式携带客户端时刻。水位线（watermark）为其机制名；字段族 maxCompleted/lastCompletedReplyTime 为其载体。
_Avoid_: "消息时间戳都能用"

## 会话状态机

**必需协作者（Required Collaborator）**:
FSM（会话状态机）运行所需的全部外部事实（消息缓存的流式状态、目录路由、僵尸防护、turn 结束副作用）由单一接口在构造期整体提供——不可缺省、不可事后补挂。注释引用规范：SessionStateService（实现 SessionStateRepository 接口）。
_Avoid_: "回调旋钮"

**僵尸检测**:
事件超时后状态不可信的判定与自愈（L2 检测/L3 恢复分层编号首现处须注明）。
_Avoid_: 陈旧检测（stale 可作机制名并存）

## 连接与版本

**版本 seam（Version Seam）**:
V1/V2 协议差异的两个收口：分页游标策略（行为差异——V2 是服务器窗口语义）与服务器能力位（门控差异——UI 只读能力不读版本）；版本号本身只在连接对象与数据层门面存在（isV2 判定收口于 api/ 门面；ServerCard 版本徽章为展示豁免）。
_Avoid_: "到处判 isV2"

**连接生命周期协调（Connection Lifecycle）**:
一台服务器从纳入连接到断开的完整编排——SSE 连接驱动、轮询启停、终端与通知资源清理的单一决策点。
_Avoid_: "Service 管连接"

## 文件与工作区

**工作树（worktree）**:
服务器侧项目检出视图（GET /project 返回 worktree 信息）。
_Avoid_: 工作区（留给 workspace）

**工作区（workspace）**:
OpenCode workspace 概念（项目分支视图）；本地文件浏览功能属 directory 概念，勿称工作区。
_Avoid_: 工作空间、工作区浏览器（README 旧称，实指 directory 浏览）

**目录（directory）**:
文件系统目录（API 词；x-opencode-directory 作用域头；V2 /api/fs/* 域）。「打开项目」对话框实为目录浏览器。
_Avoid_: 文件夹（视图文案旧称）、folder

**目录视图（catalog）**:
provider/model 的目录列表概念（ProviderCatalog/ModelCatalog）。
_Avoid_: 裸称「目录」（与 directory 撞名）

## 标注与备注

**标注（annotation）**:
用户对代码选区的标记（offset/行列定位，Annotation 模型）。
_Avoid_: 备注（留给 note）

**备注（note）**:
随标注/修改提交的说明文本（文件备注/总体备注/具体备注）。
_Avoid_: 批注说明、修改说明、整体说明

## 归档两义（须带限定词使用）

**会话归档（archive）**:
用户对会话的归档操作（API time.archived 字段）。

**冷存桶（archive bucket）**:
本地消息分层存储的冷数据表（archive_buckets，zstd+TLRU），与服务器无关。
_Avoid_: 归档桶、裸称「归档」

## 通知

**turn 完成通知**:
turn 结束时的本地通知（FeedbackType.TURN_COMPLETE；对应 SessionIdle 事件域）。
_Avoid_: 任务完成通知、TaskComplete 通知（口语转写）
