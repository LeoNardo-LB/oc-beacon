# 258-stage-b-history-chunking（2026-09-02）

> 状态：实现+单测+真机验证完结（待用户验收）
> 关联：backlog #258 · spec `docs/specs/2026-09-02-258-stage-b-history-turn-chunking-design.md` · Stage A journal `docs/journal/2026-09-02-258-perfetto-stage-a.md`
> 来源：#258 卡既定方向（Stage A 靶点 1：历史长 turn 分片）

## §一 Stage B 补充取证（修正 Stage A 归因细节）

Stage A 判词「chunkPlans 是流式期产物」需修正——视口循环**理论上**覆盖历史 turn，
真机现场复跑（同会话 12 记 60ms 甩 + logcat）实锤失败链：

- `CHUNK plan` ×14（计划确实算出，全部 `_reasoning_ord_` 后缀的 Part.Text——
  `key.take(14)` 截断显示；Part.Text 携 reasoning id 的类型来源之谜未解，不阻塞
  本批，见 §五）而 **`CHUNK commit` ×0 / `ChunkDiag compose` ×0**——计划全部滞留
  pending，无一提交。
- 机制：视口内/裂变带内计划只能 skip 计数（3 轮才强制提交），fling 停止后
  `onViewportChanged` 不再触发 → 永不提交；±20 窗口全量预解析洪峰（数百个后台
  解析排队）进一步推迟关键 part 解析完成。
- 会话进场只装 1 页（实测 total=6~10）；巨帧本体 = 进场首屏 turn（可见，无法后补
  分片）+ fling 途中前置页到达的 turn（未可见——**可在到达时刻抢先分片**）。
- 服务器事件日志全量走页（624,203 events）拿 part 尺寸分布：2,013 个 text/reasoning
  part 中 **175 个 ≥3000 字符**；turn 176/173/25 = 22.4K/16.8K/12.9 万字符、
  476/297/135 parts——**单巨型 part 门槛本可满足，失败在管线时机而非数据形态**；
  同时 part 数量主导的重 turn（334ms 样例 ≈ 55 小 text + 25 tool + 30 reason +
  6 长 text）需要 renderItem 边界分段（旧模型切不动的部分）。

## §二 实现（spec §2 全量落地）

| 组件 | 内容 |
|---|---|
| `TurnSegmenting.kt`（新） | `TurnSegmentPlan`/`TurnSegmentSkeleton`/`computeTurnSegments`（权重切割纯函数）/`buildPlan`（骨架→计划装配，giant 缺席降级单 item 段）/`turnPlanFingerprint`（陈旧检测）。权重标定：text=200+len、reason=700、tool/context=550、synthetic=900、divider=50；门槛 `TURN_SEGMENT_MIN_WEIGHT=12000`、目标 `SEGMENT_TARGET_WEIGHT=3000`、段内上限 `SEGMENT_MAX_ITEMS=10` |
| `RenderSupplyCoordinator` | `segmentPlans` StateFlow + `onWorldArrived`（到达扫描：距视口升序、带外才碰、每轮预算 24 turn、旧 MdChunkPlan 优先跳过）+ `pendingSegmentSkeletons`（巨型解析未齐的骨架）+ `materializePendingSegments`（全终态才装配；带内保留；turn 消失丢弃；旧计划让位复查）。视口巡检同窗装配（F3 跳转门控同适用）。解析回调**不做装配**（协调器单线程约定） |
| `MarkdownChunking.kt` | `ChatEntry.TurnChunk`（key `t_…#s<i>`，与旧 `#c` 互斥不撞）+ `buildChatEntries` segmentPlans 参数与发射分支（逆文档序 + displayEntryStart 钉首片，#246 语义）+ 指纹失配弃置 |
| `MessageCardAssistant.kt` | `SegmentedAssistantMessage`（Items 段走 `ChunkAssistantItems` 子序列；Giant 段走 `MarkdownContent(preParsedState, blockRange, blockAnchor)`；首段标签栏/末段统计栏+error；顶/底圆角分段 shape） |
| `ChatMessageList.kt` | 到达桥 `LaunchedEffect(displayItems, turnGroups, renderableTurns, bannerCount)` → `onWorldArrived`；itemsIndexed TurnChunk 分支（contentType `assistant_segment` + `flng:it:seg` atrace + `perf-flng` 组合计时） |
| 旧路径互斥 | 视口循环 parse 回调对已分段/分段中 turn 不再入 MdChunkPlan 队列；装配处复查让位 |

实现期修复：单 GiantHole turn 被 `cuts.size<=1` 错误弃置（「纯 Items 单段才 null」）；
测试基建：await helper 5s→15s（forkEvery=50 邻居漂移下 Default 池争饿偶发）、
T12 探针化（`pendingSkipCountFor`——种子调用尾段与解析完成的竞态使「N 次调用 =
N 次 skip」假设不成立，产品行为本正确）。

## §三 单元测试

- `TurnSegmentTest`（新，12 例）：门槛/多段切割+上限/giant 独立成段/单 giant turn/
  尾段合并/装配降级/AST 展开/键序钉扎/旧路径优先/指纹陈旧/流式与 recentStreamed 排除
- `RenderSupplyCoordinatorTest` S 系列（4 例）：S1 带外提交 / S2 带内不分段 /
  S3 巨型解析后装配 / S4 旧 MdChunkPlan 优先
- 全量 `testDevDebugUnitTest --rerun`：**2552 通过 0 失败**（T8/T11/T12 三轮去 flake
  后稳定绿）

## §四 真机验证（houji WiFi ADB，devDebug）

目标会话同 Stage A（DSH `StreamingMarkdownState优化与自动验证`，搜索直达）：

1. **进场即分段**：进场页到达扫描 `SEG commit turn=seq-590160 segs=11 chunks=11`
   （复跑多次稳定复现，含诊断移除后的最终构建）。
2. **深历史批量提交**：深页到达（n=560 世界）单轮提交 24 个重 turn——
   `seq-579611` 93 段 / `1a86d10b0a` 46 段 / `seq-516999` 45 段 / `seq-397238` 41 段 /
   `d4b2ec1c5e` 37 段…（每轮预算 24 生效）。
3. **分段组合成本（fast fling，perf-flng 日志）**：17 个 seg compose 样本
   **全部 ≤19.4ms**（8.8/10.2/10.3/10.6/10.7/10.9/14.7/15.1/19.4…）——对照 Stage A
   同会话单体 `flng:it:turn-a` avg 165ms / max 334ms，旧 chunk 路径实测 208-253ms。
4. **gfxinfo 分段课程门**（深历史 3 趟，含 93 段巨 turn 课程）：janky 10.00/4.42/11.90%，
   p99 34/61/89ms，FATAL 0——单体时代同位置为 165-334ms 冻帧课程。
5. **gfxinfo 标准回归门**（Host-4199 首会话，与 2026-09-02 基线同课程）：
   p99 40/34/25ms（基线 32/40/53）、满内容趟 janky 0.00% p95 9ms、FATAL 0——**无回归**。
6. **V6 视觉**（截图存档，多模态核验）：分段气泡顶/底圆角、中段直角、视觉连续，
   正文/代码块/表格正常，无空白块/重叠/断裂/重复。

观察备注（不阻塞）：`input swipe` 60ms 甩在本机仅推进 ~450px/记（无 fling 动量，
疑 MIUI 手势监控拦截）；深滚时 auto-load 仅在近列表顶触发。均与本批无关。

## §五 已知局限（潜在 Stage C，未立卡）

1. **中间带 turn**：有 ≥3000 巨型 part 但总权重 <12000 的 turn 仍走旧 MdChunkPlan
   路径（2-4 粗片，单片可数十 ms 且携带大量邻位 parts）——真机观察到 10K+px 的
   旧路径 chunk item。方向：门槛调优或旧路径 turn 的段化收编。
2. **`_rea` 类型之谜**：`seq-*_reasoning_ord_*` id 的 **Part.Text**（Room 行类型
   正确、mapper 流式/整装路径类型正确、inferDeltaKind 正确——来源未定位）。
   它们按正文渲染且被两套分片正常处理，行为无异常；建议单独取证。
3. 到达扫描的 `pendingSegmentSkeletons` 无上限（深滚会话最坏几十个骨架）——
   v1 接受，观察期后再定淘汰。

## §六 验证矩阵小结（V1-V6）

- V1 编译/单测：compileDevDebugKotlin ✅ · 2552/0/0 ✅
- V2 真机功能：进场分段/深页批量提交/分段渲染 ✅（logcat 证据 §四.1-3）
- V3 观测（logcat/gfxinfo）：ScrollDiag SEG commit · perf-flng seg compose ·
  gfxinfo 双课程 ✅
- V4 回归：标准门无回归（§四.5）· 既有 chunk 单测族全绿
- V5 工具链：flng:it:seg atrace 入库（DEBUG 门控）
- V6 人工视觉：分段连续性截图核验 ✅（用户复验待验收时补）

## §八 顺带项：×2 双投递观察确认（完结，未立卡项清账）

#296 验证期观察（preset 切换时 CommandsChanged/SessionAgentPresetChanged/Loaded 各 ×2）
经主机侧双流 WS tap + `agentPreset.select` ×3（空会话 9075daf0，code→standard→code）
对照计数**定音为双路设计使然，非服务器双投递**：

- MUX tap（`/api/events.mux` 单订阅者）：内层 `session/event` 帧 `agent-preset/selected`
  恰好 ×3（每次切换 1 条），**零同载荷重复帧**；
- HOST tap（`/api/events.host`）：`host/remote-event` 转发 `agent-preset/selected` ×3 +
  `commands/change` ×2——与 mux 各一路，互不重叠；
- 机制 = DshEventMapper 两处同映射（内层 :489 + 转发 :395，注释明示「host 流双保险」）
  ——app 双流各收一次 → 两条 SSE；消费端幂等（CommandsChanged 重取注册表、
  preset 卡高亮为状态置位），无副作用。
- 结论：**良性，维持现状**；若未来出现同流同 seq 重复帧才是真双投递（本 tap 未见）。

## §七 提交

- 5020e977 feat: 骨架实现（TurnSegmentPlan + 到达扫描 + 分段渲染 + 桥接）
- 53b3b58e test: 单测 16 例 + 单 GiantHole 弃置修复 + 超时去 flake
- （本批收尾）T12 探针化 + 诊断清理
