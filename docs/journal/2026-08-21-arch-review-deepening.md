# arch-review-deepening（2026-08-21）

> 状态：进行中
> 关联：CONTEXT.md（渲染供给术语已登记）· HTML 报告 /tmp/architecture-review-2026-08-20T20-42-30.html（临时件，内容已收录于下）
> 来源：用户发起 /improve-codebase-architecture · 三路并行子代理走查 + git 热点定向

## 调研方法

- 热点定向：近 60 天 git churn（ChatMessageList 76 次/30 天居首、EventDispatcher 38、ChatViewModel 47）→ 三路子代理并行走查（A 聊天 UI 管线 / B 数据事件管线 / C 连接生命周期+UseCase）
- 词汇表：/codebase-design（module/interface/seam/adapter/depth/locality/leverage）；对每项疑似 shallow 做 deletion test
- 产出 6 候选 + 顺手清理清单；候选 1 已 grilling 定案（Q1-Q11 全按推荐）并实现

## 六候选总表（证据摘要）

| # | 候选 | 强度 | 核心证据 |
|---|------|------|---------|
| 1 | RenderSupplyCoordinator 抽出 | Strong | ~190 行 LaunchedEffect 内嵌（ChatMessageList 536-727），interface 宽度 0；五条隐含约束（流式禁预解析/双门控/partId 反查/LRU 联动/display 粒度窗口）全靠注释；buildChatEntries/computeChunkPlan 在 test/ 0 引用；四轮竞态修复（bf3d1cf7→94f7a968→8347acd0→88774278）全靠真机 RaceProbe |
| 2 | 每服务器连接生命周期 module | Strong | 状态 ≥6 module 分持；teardown 双份（OpenCodeConnectionService 315-337 vs 381-402）；1309 行 20+ 带日期竞态注释（RS-001~005、#110~#133） |
| 3 | 未读红点时钟域收进 interface | Strong | 三铁律散 3 层 6 文件；静默泄漏路径：markSessionIdle（MessageEventHandler 1087-1148）写客户端 now → recomputeMaxCompleted 扫描混入服务器域水位线 |
| 4 | V1/V2 seam 按域翻转 | Worth exploring | 79 个 if(conn.apiVersion.isV2) 每方法站点（SessionApi 23 处）；isV2 泄漏进 SessionStateService:301/642、MessagePaginationUseCase:101/201/232 |
| 5 | ChatViewModel delegate 重组 | Worth exploring | 假 seam：UI 消费 98 成员/147 调用点，40+ 行 1:1 转发，delegate 间 sink 回写+lambda 互接；MessageDataDelegate 12 构造参数 |
| 6 | SessionStateService 8 旋钮→1 协作者 | Worth exploring | 8 个可缺省 var 回调（SessionStateService 79-111）只在 EventDispatcher.init 接线；漏接静默降级（directoryResolver 默认 null→REST 打错路由） |

顺手清理（deletion test 全正）：ChatMessageList 双调用点合一（ChatScreen 812-866）；三个纯转发壳 handler（MessagePart/Updated/Removed 24-28 行）+ SseEventHandler.handle 残留 Boolean；SessionFocusHolder.shouldSuppress/shouldSuppressEvent 同体双胞胎。

正例（deep 不是不大）：RenderReadinessRegistry（4 方法藏整个就绪状态机，主动删死 interface）、ApiVersionDetector.detect()、MessageEventHandler（1150 行/~12 成员，三套合并策略+批处理+persist actor）。

UseCase 层核查结论：维持 Option B 但冻结——22/25 纯转发（~486 行），有逻辑的 3 个（MessagePagination 319 行/CreateDirectory/SubmitAnnotations）证明 seam 在有逻辑处产出杠杆；新增规则：仅在自带逻辑时新建 UseCase。

## 候选 1 设计定案（grilling Q1-Q11）

- Q1 边界=C：窗口计算+预解析+LRU+分片 pending/提交门控+recentStreamedTurnKeys 清理整段外移（窗口计算是共同前提，切开会暴露中间结果）
- Q2 输入=A：单方法推送 onViewportChanged(firstEntryIdx, lastEntryIdx, world)，world=不可变快照（displayItems/turnGroups/chatEntries/bannerCount/streamingMsgId）
- Q3 所有权=A：chunkPlans/recentStreamedTurnKeys 为模块私有+StateFlow 只读暴露；pendingChunkPlans 彻底私有；流式结束写入改经 noteStreamTurnEnded(turnKey)
- Q4 生命周期=A：remember{} 于 ChatMessageList（与 jumpController/renderReadiness 同款）
- Q5 门控=A：构造注入 phase: StateFlow<JumpPhase> + clock（默认 elapsedRealtime），模块自记跳转终点时刻——跨 effect 时间戳耦合（lastJumpEndAtMillis）消灭，B-F3 桥接滞后竞态类别整体消失
- Q6 测试=B：9 条 JVM 用例（五约束各 1 + B-F2 视口边缘裂变 + F1 陈旧索引 + C-R4c 陈旧丢弃 + 流式预解析截断/流式 turn 记录）
- Q7 迁移=B：三段式（外移壳与状态→收编门控→测试），每步编译+commit（ChatScreen 编辑协议同款循环）；真机最小走查=长消息滚动+跳转×2+130K 消息
- Q8 命名=A：RenderSupplyCoordinator（渲染供给协调器；概念名抗机制老化）；Q9 位置=A：components/（类型伙伴所在）；Q10=A：建 CONTEXT.md（已建，3 术语）；Q11=A：常量随迁 companion、RaceProbe 探针原样随迁（重构当天 logcat 前后 diff 即等价性证据）

## 候选 1 实现记录

（进行中——阶段 1/2/3 证据随做随记）
