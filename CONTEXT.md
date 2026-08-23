# OC Beacon

非官方 OpenCode Android 客户端——本文件是项目领域术语表（glossary），只定义概念，不记录实现。

**总则**：规范名以 OpenCode API（V2 现行协议）术语为权威源；每个术语必有中文对应名；注释引用 API 字段用 API 原拼写（sessionID），域内标识符用 camelCase（sessionId）。**_Avoid_ 仅指名称性使用**——标识符/键名/枚举/包名一律豁免（「仅改注释+文档不改标识符」），canonical 词条定义句内的描述性用词亦豁免；证据引用（logcat/SSE 事件名/i18n key/代码直引）原样保留。中文文案中 turn/Agent/Shell 等英文原词按各词条口径保留。

## 会话与消息

**会话（session）**:
OpenCode 服务器上的对话单元；15 语言文案统一「会话」。dialogue-e2e 文档族已整册改用「会话」。
_Avoid_: 对话、conversation、chat（仅屏名 Chat 保留）

**消息（message）**:
会话内一条用户/助手/系统记录；V2 type 含 synthetic/shell/compaction 等扩展 role。ID 前缀 msg_/ses_/pty_/frm_/call_/evt_（官方证实为服务器短 ID 体系，非 ULID，可客户端生成）。
_Avoid_: prompt（那是请求侧概念）

**内容块（part）**:
消息内容组成块（官方证实 10+ 类型：text/reasoning/tool/shell/step-start/step-finish/file/snapshot/patch/subtask/compaction/retry/abort/agent…；parser 支持数为客户端事实，两口径并存）；V2 以 ordinal 派生 id 定位，工具块以 call_id 定位。
_Avoid_: 零件、中文句中裸用 part

**提示块（PromptPart）**:
发给服务器的请求体组成块（文本/文件/图片），与内容块两个概念；V2 现代端点 POST /prompt 统一承载。
_Avoid_: 与「内容块」混称

**轮次（turn）**:
一次用户输入到助手回复完成的交互单元。中文一律「轮次」，首现标注「轮次（turn）」；UI/CHANGELOG/RN 统一（轮次完成通知、轮次分割线）。「第 N 轮」在设计文档=迭代轮，另一概念，豁免。
_Avoid_: 任务（通知文案旧称）、回合、中文句中裸用 turn（EN 显示词 Turn completed 合法）

**流式 turn（Streaming Turn）**:
completed 时间戳为空的 assistant 回复轮次；其内容随时增长，禁止被预解析或分片固化。结束信号以 V2 session.execution.succeeded 为权威，session.status(idle)/step.ended 为兼容信号。
_Avoid_: 流式消息（AGENTS.md SSE 铁律节违逆待修，行号漂移以 grep 定位）

**合成消息（synthetic）**:
API role/type=synthetic 的系统合成记录；通知文案「合成通知（子智能体已完成）」。
_Avoid_: 系统通知（Android OS 设置义另豁免）、后台消息、后台通知、转后台提示

**子智能体（subagent）**:
工具派生的下级 agent 会话；V2 服务器 metadata 以 jobId 承载其会话 ID（服务器别名），客户端另有 childID；拼写统一无连字符。其派生会话称「子智能体会话」。
_Avoid_: sub-agent、子代理、子会话

**智能体（agent）**:
OpenCode agent 概念（plan/build 等模式）；UI 显示词统一 Agent/智能体（EN 源 Assistant→Agent 已裁）。
_Avoid_: Assistant（role 值 assistant 是 API 消息角色，豁免）、代理

**推理（reasoning）**:
API part 类型（代码注释规范名）；UI 英文显示词保留 thinking（13 语言现状）。
_Avoid_: 代码注释用 thinking 指该 part 类型

**会话细粒度事件（session.next.*）**:
V1 引入的细粒度实时事件族（官方体系；27 变体）；V2 以 execution.*/step.* 等平铺事件并行存在（v2_* 命名轴与 /api 线径轴是两个概念）。
_Avoid_: 「下一代事件」（误读）、Session Next 事件

**收件箱（inbox）**:
V2 session.inbox.enqueued/delivered 事件的输入排队概念。
_Avoid_: 与本地堆积消息混称

## 队列与待处理

**堆积消息（pending message）**:
轮次进行中暂存本地、轮次结束后自动发出的消息队列成员；EN 源 Queued；UI 徽章 QUEUED 保留英文；drain 状态=「发送中」。
_Avoid_: 待发消息、未发消息、暂存消息、排队消息（zh 4 键已裁改堆积）、STACKED

**待处理权限/问题（pending permission/question）**:
服务器下发、等待用户答复的权限请求或问题（question.v2 为官方事件名；form 为端点级官方中间契约）。
_Avoid_: 待答、堆积（严禁挪用）

## 服务器交互动词

**中断（interrupt）**:
停止运行中的会话（V2 POST /api/session/{id}/interrupt）；本地编排动作同用「中断」（单轨；标识符 abortSession→interruptSession 已裁改名）。
_Avoid_: abort（V1 端点名仅历史对照）、中止（全面退役）

**重命名（rename）**:
修改会话标题（V2 POST /rename）。
_Avoid_: update（V1 泛称）

**压缩（compact）**:
压缩会话上下文（V2 POST /compact；compaction 事件族；统一前缀「压缩」）。
_Avoid_: summarize（V1 端点名）、压缩摘要、上下文压缩（语序变体）、摘要（压缩）

**凭据（credential）**:
provider 的 API key 存取（V2 PATCH/DELETE /api/credential/{id}）。
_Avoid_: auth（V1 端点名；「认证方式」authMethods 语义独立豁免）

**撤销（revert）/ 取消撤销（unrevert）**:
回退到某消息之前（V2 revert/stage 两段式）；「重做/redo」保留口语别名。
_Avoid_: 回退（五域分词：API=撤销/样式=改回/跳转=向后跳/性能=退化/fallback=降级——「回退」全局退役）、undo（/undo /redo 命令语境保留）

**答复（reply）**:
对权限/问题的回应；once/always/reject=一次/总是/拒绝。
_Avoid_: effect allow/deny（legacy）

## 游标与分页

**分页游标（cursor）**:
消息分页 opaque token（V2 {previous=更新, next=更旧}——反直觉方向是 API 契约）。首现限定，同文件简称放行。
_Avoid_: 裸称「游标」过 300+ 处机械改

**Shell 输出游标**:
后台 shell 输出分页的字节偏移（Long）。

**会话列表游标**:
会话列表分页 token，第三种独立 cursor。

**序数（ordinal）**:
V2 内容块定位键（派生 id msg_type_ord_N）。
_Avoid_: 序号

## 展示与渲染

**渲染供给（Render Supply）**:
聊天列表视口前方的渲染资源预备决策——预解析哪些长文本、何时安全地分片、何时必须冻结（跳转稳定窗口）。
_Avoid_: 预解析驱动器、分片协调器

**跳转稳定窗口（Jump Settling Window）**:
跳转终点后的短冻结期（现值 2s）；期间禁止分片提交。三窗口机制：本窗口（2s 分片冻结）/ jumpLock 解锁缓冲（300ms 滚动恢复）/ 跳转后滚动稳定（900ms 现值；KDoc 1.5s 为失实）。
_Avoid_: 跳转锁、滚动锚定锁

**状态簇（State Cluster）**:
按状态归属划分的消费单元（会话上下文/会话数据/输入/模型配置）；会话列表侧同族。
_Avoid_: 「全都能从 VM 拿」、内容册/外壳册、集群

**自动展开工具结果（autoExpand 工具卡片）**:
设置键 collapseTools（历史名，语义反转：false=默认折叠）消费端取反为 autoExpand；Phase 2 改名对齐语义方向。
_Avoid_: 「默认折叠」表述与 UI「自动展开」并存的歧义

## 时间与未读

**红点时钟域（Unread Clock Domain）**:
未读红点只消费服务器完成时刻；唯一例外是会话错误显式携带客户端时刻。水位线（watermark）为机制名；maxCompleted/lastCompletedReplyTime 为载体。
_Avoid_: 「消息时间戳都能用」

## 会话状态机

**必需协作者（Required Collaborator）**:
FSM 运行所需全部外部事实由单一接口构造期整体提供。注释引用：SessionStateService（实现 SessionStateRepository 接口）。
_Avoid_: 「回调旋钮」

**僵尸检测**:
事件超时后状态不可信的判定与自愈（L2 检测/L3 恢复，首现注明）。
_Avoid_: 陈旧检测（stale 可作机制名并存）

## 连接与版本

**版本 seam（Version Seam）**:
V1/V2 协议差异收口：分页游标策略 + 服务器能力位；isV2 判定收口于 api/ 门面（ServerCard 版本徽章为展示豁免）。
_Avoid_: 「到处判 isV2」

**连接生命周期协调（Connection Lifecycle）**:
一台服务器从纳入连接到断开的完整编排单一决策点。
_Avoid_: 「Service 管连接」

## 文件与工作区

**工作树（worktree）**:
服务器侧项目检出视图（GET /project）。
_Avoid_: 工作区（留给 workspace）

**工作区（workspace）**:
OpenCode workspace 概念（项目分支视图）；本地文件浏览属 directory。
_Avoid_: 工作空间、工作区浏览器

**目录（directory）**:
文件系统目录（API 词；x-opencode-directory；V2 /api/fs/*）。EN 源 folder→directory 已裁。
_Avoid_: 文件夹、folder

**目录视图（catalog）**:
provider/model 目录列表概念（ProviderCatalog/ModelCatalog）。
_Avoid_: 裸称「目录」

## 提供商与模型

**提供商（provider）**:
模型提供方（API 原词 provider；中文注释事实标准「提供商」）。
_Avoid_: Provider 裸用不译（中文语境）、供应方

**令牌（tokens）**:
模型用量计数（input/output/reasoning/cache 读写）。
_Avoid_: token 裸用（中文语境；zh 半分裂已裁统一「令牌」）

## 标注与备注

**标注（annotation）**:
用户对代码选区的标记（offset/行列定位）。
_Avoid_: 备注（留给 note）

**备注（note）**:
随标注/修改提交的说明文本。
_Avoid_: 批注说明、修改说明、整体说明

## 归档两义

**会话归档（archive）**:
用户对会话的归档操作（API time.archived）。

**冷存桶（archive bucket）**:
本地消息分层存储冷数据表（archive_buckets，zstd+TLRU）。
_Avoid_: 归档桶、裸称「归档」

## 通知

**轮次完成通知**:
轮次结束时的本地通知（FeedbackType.TURN_COMPLETE；EN "Turn completed"；频道名/描述/EN 已裁全 turn 化，旧「任务通知」频道作废重建）。
_Avoid_: 任务完成通知、TaskComplete 通知

## 编号与写作

**编号行话（RS-0xx/T1-T10/C1-C10）**:
代码/测试内局部标签保留可追溯；不得与全局前缀 V/A/P/S/F/# 冲突（见 docs/numbering-charter.md，D3-5）。
_Avoid_: 无展开的裸编号（首现处补展开注释）
