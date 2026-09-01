# dsh-refactor-closeout（2026-09-01）

> 状态：#282 / #284 已完结（goal-3a35a221 重构清尾批）
> 关联：backlog #282 / #284 · docs/journal/2026-08-30-dsh-integration-and-disconnect-design.md §9
> 来源：上一批量清欠轮 round 7 裁决「独立重构批次承接」——本批即该承接批

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 纪律口径

纯重构零行为变更（#282 四项）+ 根因式小修（#284 两项）；同形收口以
「同名测试全绿」为冻结验收；独立 commit；单 Gradle owner；真机 smoke
按目标条款可选（#282 无行为变更，#284-a 单测全覆盖降级矩阵）。

## #282 DSH 特性批重构群（Standards 轴）

### a. DshApiClient settings 域四方法同形（325e5ff0）

getPermissionDefault / getDefaultAgentPreset 的 ns 提取收口 `settingsNamespace`；
setPermissionDefault / setDefaultAgentPreset 的单键 set payload 收口
`settingsMutateSet`（乐观并发先行读）。冻结验收：DshApiClientTest 56 用例全绿。

### b. SessionEventHandler 投影帧同形折叠（2542824f）

卡片口径「四新 handler」实为 **7 处同形**（agentPreset/tokenUsage/
subagentTiming/goal/contextPressure/contextBreakdown/sessionStats 皆
last-wins copy）——按根因口径全量收口到 `updateSession(sessionId, transform)`。
permission 三 knob 事件为字段级合并形（保留 options 基线）不走此口。
冻结验收：SessionEventHandlerTest 28 用例全绿。

### c. SubagentTreeDelegate 双 DFS 提取（244203ee）

buildLocalTreeRows / buildCatalogTreeRows 共享骨架（rows 累积 + visited
防环 + 展开态下钻）提取 `flattenTreeRows`；调用方仅提供子代查表/行映射/
下钻谓词三差异点。冻结验收：SubagentTreeDelegateTest 6 用例全绿。

### d. TaskAggregator 两处排序重复（3c6a4562）

树化分组（childrenByParent）与根层摘要（rootSummaries）共用比较器
（创建时间倒序 + id tie-break）提取 `subagentOrder`。
冻结验收：TaskAggregatorJobsBranchingTest 全绿。

**未做**：卡片第 5 点 DshPermissionDefault/DshAgentPresetDefault 同形双值
类型合并——两类型字段集与消费面（options 三参 vs 两参、UI 组件各自渲染）
实际不同形，强行合并不是提取是耦合；判定为审查误判，注记于此。

## #284 特性批小项集（剩余两子项）

### a. SubagentTreeHolder 全局 degraded → 逐层降级（18c9e1f8，fix）

根因：单一 degraded 布尔把**任一层**拉取失败放大成**整树**退回本地镜像
（已缓存层被无谓丢弃）——降级粒度错误，缺陷产生机制在状态建模而非调用点。
修复：

- 渲染规则改为 catalog 命中层权威、缺席层回退本地镜像
  （`buildMergedTreeRows`，取代 buildLocalTreeRows/buildCatalogTreeRows
  二步行器——二者即其空/满 catalog 退化形态，保留即死代码）；
- `degradedIds` 集仅服务 toggle 防重探（失败层不再逐次展开打请求），
  refreshRoot 成功复位根层；
- 本地层行装饰 hasChildren（镜像或 catalog 缓存孙层推导）防展开箭头丢失。

验收：同名 6 用例冻结全绿 + 新增单层失败语义用例
（`dsh single layer failure degrades only that layer keeping cached layers
authoritative`：根层权威行 + 失败层本地子行 + 防重探 fetchCalls 断言），
7 用例全绿。

### b. JobStatus 枚举接线 + 死代码处置（71bc02d2）

PendingSheets（状态点色/文案）与 ChatMessageList（时间线卡 label/failed
判定）的裸串 STATUS_* 比对改走 `statusKind` 枚举分支（UNKNOWN 渲染兜底
与原 else 分支一致，渲染零变化）；删除无调用方死代码 JobView.isRunning、
DshJobsStore.jobsFor()/clear()（测试改读 jobsBySession.value）。
验收：DshJobsStoreTest / JobStatusTest 全绿。

## 全量回归

- 单测：`testDevDebugUnitTest` **255 套件 / 2511 用例 / 0 失败 0 错误**
- androidTest：`compileDevDebugAndroidTestKotlin` BUILD SUCCESSFUL
- 真机 smoke：未执行（本批六项均无 UI 行为变更面；#284-a 降级矩阵由单测
  覆盖，如需现场目验可在 AgentSheet 树中断网展开失败层观察局部回退）。

## 顺带观察（未处理，非本批范围）

- backlog #281（androidTest 编译基线破损）所述症状已不复现——本批
  compileDevDebugAndroidTestKotlin 直接绿，疑似已在清欠批修复但卡片未迁。

## V6（用户复验邀请）

#284-a 逐层降级的现场观感（可选）：DSH 会话 AgentSheet 树内任选一层展开、
断网后再展开另一层——失败层显示本地镜像行、已缓存层保持权威渲染。
