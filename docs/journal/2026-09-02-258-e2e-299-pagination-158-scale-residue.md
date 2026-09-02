# 258-e2e-299-pagination-158-scale-residue（2026-09-02）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## §一 #258 Stage B 真机 E2E 验收（用户授权：端到端测试完成即验收）

> 前批实现证据见 `docs/journal/2026-09-02-258-stage-b-history-chunking.md`（进场即分段 /
> 深页 24 turn 提交 / fast fling 分段组合 ≤19.4ms / gfxinfo 双门 / V6 视觉）。
> 本批补全 E2E 四维（houji devDebug dea6e480 谱系）：

| 维度 | 用例 | 结果 |
|---|---|---|
| 流式互斥（C-R3） | Host-4199 一次性会话真实 prompt（glm 流式回复完整渲染：思考完毕+正文+统计栏） | **PASS**——流式期 `seg compose` ×0、FATAL ×0，回复 turn 正常单 item 完结（a11y 树含用户 turn+reasoning+回复+统计栏） |
| 跳转落点 | 快速定位抽屉（本批加载成功）→ 跳最深消息（!pwd，08-28 会话起点） | **PASS**——`jumpToMessage inDisplay=1 renderable=true`，落点可见 [0:t_…,1:u_…]，turn 带标签栏完整渲染；loadAround 深跳无异常。分段 turn 落点由单测钉扎保证（turnChunksEmitTailFirstAndPinHead：displayEntryStart 钉首片） |
| 分段完整性 | 上批：分段区截图（顶/底圆角、中段直角、正文/代码/表格连续）+ 17 个 seg compose ≤19.4ms + total=176 分段条目 | **PASS**（引上批 §四.3/§四.6） |
| 自动加载互操作 | 上批：auto-load 前置页到达 → 单轮 24 turn SEG commit | **PASS**（引上批 §四.2） |

E2E 过程备注：①深跳后窗口为小旧窗、向新滚需重进（预存在深跳窗口行为，非本批回归）；
②本批一次重进途中 adb 拖动被 autoScroll 回底意图拉扯（offset 0↔1998 弹跳）——
adb 注入拖动不清「在底意图」所致，真人手势无此问题（上批同会话正常遍历）。

**结论：#258 验收通过，卡片迁移本 journal 归档。**

## §五 #301 底部上滑高频拉回（用户报告，当批定罪+修复）

**现象**：贴底向上滑阅读历史，视口被高频拽回最底部（用户 2026-09-02 报告；
本批早前真机取证已捕到同签名——LEAP off 0↔~2000px 弹跳，当时误判为 adb 注入
伪影，勘误）。

**机制（代码定位 ChatScrollController）**：两环自持循环——

1. **点火器**：下跳守卫（msgCount effect 武装；条件 非滚动中 && autoOn && 离底
   → requestScrollToItem(0)）在**拖动→fling 交接瞬间 isScrollInProgress 闪断帧
   同步开火**（原 collect 无挂起，闪断即触发）把上滑用户拉回底部；
2. **再武装**：拉底使 offset 归 0 → isAtBottom（idx==0 && off<100）复真 →
   autoScroll 瞬时再武装（非滚动+贴底即 true）→ 守卫重装弹药 → 下一闪断帧再拉
   ——循环成立。进场首屏 messageCount 变化（0→N）即武装守卫，故每次开会话
   都能复现。

**修复（[AutoScrollArbiter]，单一真相源）**：再武装与守卫触发均改「**稳定非滚动
≥250ms + 条件复查**」，effect 改 collectLatest（新快照取消未决去抖）——闪断帧
（1-2 帧 ≈ 16-33ms）不再触火；守卫本职（异步内容长高推离贴底，600ms-数秒）与
fling 落底/推送回底（稳定停留）均超窗不受影响。滚动恢复→撤武装的即时分支不变
（铁律：用户阅读位置优先权；isAtBottom 仍在键位——B-F5 语义保留）。

**验证**：

- 单测 `AutoScrollArbiterTest` ×6（闪断取消/稳定武装/复查兜底/守卫本职/用户撤让/
  跳转锁让位）；全量 2560/0/0。
- **真机对照（目标会话贴底上滑 ×12，同款手势）**：修复前 off 0↔~2000 弹跳 +
  高频归零；修复后 **offset 单调累计 4936→跨 item1→2→3→17109，归零 ×0、
  GUARD reanchor ×0**。
- **2026-09-03 用户真机体感复验通过，迁卡完结**（dev debug 包；stable/beta
  待下轮发版携带）。

## §四 #300① 中间带 turn 段化收编（开工即落地）

- 改动：`computeTurnSegments` 权重门槛加**巨型豁免**——turn 存在 ≥3000 巨型 part
  即跳过 `TURN_SEGMENT_MIN_WEIGHT` 判定（巨型自身 ≥3200 当量足以切 ≥2 段）；
  此前中间带 turn（有巨型但总权重 <12000）落入旧 MdChunkPlan 粗片路径
  （真机见过 10K+px 单 chunk 253ms）。
- 配套：到达扫描对带巨型 turn 入 pendingSegmentSkeletons → 旧路径 parse 回调的
  装配抑制（pendingSegmentSkeletons 检查）天然接管——旧粗片计划不再提交。
- 测试：`middleBandTurnWithGiantBypassesWeightGate`（巨型 3.6K + 小 part，总权重
  ~5K → 产出 GiantHole 骨架）；全量 2554/0/0。
- 真机：进场扫描新提交 `1a86d10b0a segs=4 chunks=5`（此前该形态 turn 无段），
  FATAL 0；深滚组合证据承前批（§一/上批 §四）。
- 残余（#300 卡续项）：②`_rea` 之谜取证、③SelectionContainer 二阶、
  ④pendingSegmentSkeletons 上限——留待后续批。

## §三 #158 大规模 a11y 计数（用户点名）

同会话「快速定位抽屉开→点项跳转→settle→dump」×30（判满 = dump>5K + 输入栏在场）：
**30/30 全满，0 退化，0 采集错误**。累计 **65 连不复现**（箭头导航 15 + 抽屉 10+10 +
E2E 顺带 10）——「2026-08-27 后期间改动已间接消除」假说增强。

**2026-09-03 用户裁决关闭，迁卡完结**：65 连不复现 + 零用户可感知影响，关闭不再观察。

## §二 #299 DSH 进场分页提速（第一刀：往返与双走者）

**取证（真机 NetTrace 时间线，未缓存会话冷进场）**：

- 进场瞬间**三条链并发拉同一会话**：UI 初始页（limit=30）+ 全量游走器 ×2
  （`MessageDataDelegate.fetchAllMessages` limit=50——进场预取 prefetchJumpTargets
  与轮终对账 reconcileFromRest 在 ~800ms 内各起一条，同页重复拉取 NetTrace 双证，
  其一跑在主线程）+ HistorySyncManager drain（limit=50）。
- RPC 非瓶颈：UI 页 REQUEST→RESPONSE 253-500ms；**loadOlder 总时长 9.9s 中
  RPC 仅 0.25s**——余量是游走器互踩 + 后处理。
- 服务器侧（#299 立卡取证）：50/200/500 msg 页 = 79/220/672ms，线性。

**第一刀修复（三件）**：

1. `fetchAllMessages` 页 50→200 + 上限 400→100 页（200×100=2 万条语义不变）；
2. 全程 `Dispatchers.IO`（原随调用方跑主线程——进场即主线程，翻页+合并直接吃帧预算）；
3. 同会话在途去重（prefetchJumpTargets/reconcileFromRest 共享一次游走）；
4. `HistorySyncManager.PAGE_SIZE` 50→200 + `MAX_PAGES` 400→100。

**AFTER（未缓存会话「诊断程序连接失败原因」118 msgs 冷进场）**：

- drain `synced 118 msgs in 1 pages`（旧 3-6 页）；prefetch 单走完成；
  总请求 12-19 → **8**；双走者消失；2553 单测绿。

**暴露的下一层瓶颈（卡片续项）**：页 RPC 1.9s 后**落库+FTS 后处理 ~20s**
（drain 页 RESPONSE 21:27:54 → synced 日志 21:28:15；loadOlder RPC 1s →
完成 13.5s）——118 msgs ≈ 170ms/条，疑 FTS 分词大 part 文本 + 逐条事务；
方向：批事务化 / FTS 挂后台队列 / 大 part 截断索引。

## §二B #299 第二刀：FTS 全表扫描定罪与幂等收窄（探针驱动）

**分段探针**（[299-probe] tx/fts/archive 三段，常驻 DEBUG）定罪链：

1. 批事务化后仍慢：`upsert n=50 tx=60ms fts=13605ms`——Room 事务仅 60ms，
   **FTS 13.6s**；小批恒 ~616ms/次 = 全表扫描特征值。
2. 根因：`DELETE FROM message_fts WHERE partId=?` 在 FTS5 虚表上**无索引可走
   （非 MATCH 谓词）→ 全表扫描 ~600ms/次**；每 text part 一次 → 页级爆炸。
   （第一刀的"批事务"只消了 fsync，没消扫描。）
3. 同场发现：`replaceSessionMessages`（prefetch 对账路径）**未做 #79 工具载荷
   截断**——全量 JSON 落库把 #79 省的 97% DB 体积每进场写回一次。

**第二刀修复**：

1. **FTS 幂等收窄**：写前 `existingPartTexts` 快照（PK 批查，chunked 500）——
   文本未变的 part **整体跳过索引**（重进场零 FTS）；新 part 免 DELETE
   （`IndexedTextPart.existing` 标志——FTS 行只可能跟随 cached_parts 行存在，
   无行可删）；仅「文本变化重索引」走 DELETE。upsert 与 replace 两路径同收窄。
2. **replace 路径对齐 #79 截断**（tool 载荷 500 字符预览，内存渲染不受影响）。

**验证（真机）**：

| 场景 | 前 | 后 |
|---|---|---|
| 未缓存页 FTS（n=95） | 13.6s（n=50 批） | **4ms** |
| 未缓存页落库总时长（n=95） | ~14s | **68ms** |
| 缓存重进场首渲染（V1 loaded） | ~17s | **0.6s** |
| 缓存重进场后台整定（prefetch complete） | —（未及） | **4s** |

全量单测 2554/0/0（MessageStoreTest 假 DAO 补 existingPartTexts）；FTS 批事务
含防御降级（SQLException 吞并降级，索引是增强可幂等补齐）。 战役残项（中间带 turn / _rea 之谜 /
SelectionContainer 二阶 / pendingSegmentSkeletons 上限）另立卡承接（见 §四）。
