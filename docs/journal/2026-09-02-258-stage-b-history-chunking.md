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

## §九 顺带项：#154a 崩溃后启动提示 UI（用户解冻 2026-09-02 指令）

#154 两半中的崩溃提示半（gist 半维持缓）：上次运行存在**未确认 FATAL** 时
Home 顶部横幅提示，「查看」→ 诊断页、「忽略」→ 确认水位推进（后续新崩溃仍提示）。

- 数据层：`LogDao.latestFatal()`（FATAL 最新 1 条）+ `DiagnosticLogRepository
  .latestUnacknowledgedCrash()`（晚于 DataStore 确认水位 `crash_notice_ack_at`
  才返回）+ `acknowledgeCrashNotice(atMillis)`（水位只升不降）。
- UI：`CrashNoticeBanner`（home/components，errorContainer 卡片 + BugReport 图标，
  双布局接入 grid/list，优先于电池横幅）+ `HomeViewModel.crashNotice`/
  `dismissCrashNotice`（崩溃必经进程死亡 → init 一次加载足够）+ HomeRoute/
  NavGraph `onNavigateToDiagnostics` 接线。
- i18n：5 键 ×15 语言（crash_notice_title/body/body_no_time/view/dismiss），
  i18n-check 766 keys × 14 语言一致通过；fr/it 撇号 `'` 转义（`&#x27;` 数字实体
  flatten 后仍判非法——同 #298 坑的变体）。
- 测试：HomeViewModelCancelConnectionTest 构造参数补 mock；全量 2553/0/0。
- **真机五步验证全过**（houji devDebug）：①无崩溃冷启无横幅 ②`am crash` →
  重启 Home 横幅出现（「上次运行发生崩溃」+查看/忽略）③查看 → 诊断页（不确认）
  ④忽略 → 重启不再现（水位生效）⑤再崩溃 → 重启横幅回归（新 FATAL > 水位）。
  尾态已忽略复位。

## §十 顺带项：#146 六项上游问题逐项复现取证（用户指令开工；不提交 PR——提交需用户前提流程）

上游源码浅克隆 `~/Documents/code/opencode-upstream`（anomalyco/opencode @69c172e）；
live 复验对 Host-4199（v0.0.0-beta-18743，只读交互 + 一次性会话即删）。

| # | 问题 | HEAD(69c172e) 静态 | 运行版(beta-18743) live | 判词 |
|---|---|---|---|---|
| ① | V2 不发 compaction.started | `session-compaction-event.ts` 仅定义 `Compacted`——`Started` 变体**schema 层不存在**；compaction.ts 只 publish Compacted(:554) | — | **仍成立**（连事件类型都没定义，比「引擎没接线」更彻底） |
| ② | SSE 重连无事件回溯 | `Last-Event-ID` 全库仅出现于无关 LLM 插件（snowflake/openai ws）——服务器 SSE 端点零回溯处理 | 带 Last-Event-ID 连 6s：仅 `server.connected` + heartbeat，无回溯无报错 | **仍成立**（重连=事件空洞） |
| ③ | cursor V1 格式 400 | `message-v2.ts` `decodeCursor = decodeUnknownSync`（硬抛） | `/api/session?cursor=<V1 时间串>` → 400 `{"_tag":"InvalidCursorError"}`；非法 base64 同 | **仍成立**（错误已类型化 `_tag`，但 V1 格式仍硬拒无降级） |
| ④ | fork handleRaw 任何 body 400 | forkRaw **已有空 body 分支**（:223 trim 空直通）+ ForkPayload 重设计为 `{messageID?}`（boundary 键已删） | 空 body → 400 `Expected object`；`{}` → 400 `Missing key ["boundary"]` | **上游已修/改版，运行版未跟上**——app 现发 `{messageID?}` 与 HEAD 匹配，服务器升级后即通 |
| ⑤ | 工具输出保尾截头语义 | truncate.ts 语义不变（全文落盘+预览+提示）；`tool_output.max_lines/max_bytes` 配置仍在 | — | 不变（设计使然；progress 提前携带 truncated/outputPath 的 FR 仍开放） |
| ⑥ | 后台 shell 状态恒 completed | prompt.ts(:540-) 流结束**无条件** `status:"completed"`；`handle.exitCode` 结果从不映射进 part；非零退出走 failCause 只落错误文本 | — | **仍成立**（失败信号仅在正文文本——与 2026-08-27 八轮实证一致） |

**PR 候选排序（按可行性）**：③ cursor 宽容降级（无效 cursor 视为无 cursor 返回首页——
一刀切改 decode + 单测，影响面最小）> ⑥ shell exitCode 映射 status（error 态 + exitCode
metadata，需对齐 schema ToolState）> ① 补 `session.compaction.started` 事件（schema +
compaction.ts 两处，但涉及 2.0 事件桥）> ② 回溯需事件日志存储（大工程，可先 FR）。
④无需 PR（上游已改）；⑤维持 FR。所有 PR 走用户前提流程（本地源码已就位
`opencode-upstream`），提交与否由用户裁决。

## §七 提交

- 5020e977 feat: 骨架实现（TurnSegmentPlan + 到达扫描 + 分段渲染 + 桥接）
- 53b3b58e test: 单测 16 例 + 单 GiantHole 弃置修复 + 超时去 flake
- （本批收尾）T12 探针化 + 诊断清理
