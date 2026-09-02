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

## §三 #158 大规模 a11y 计数（用户点名）

同会话「快速定位抽屉开→点项跳转→settle→dump」×30（判满 = dump>5K + 输入栏在场）：
**30/30 全满，0 退化，0 采集错误**。累计 **65 连不复现**（箭头导航 15 + 抽屉 10+10 +
E2E 顺带 10）——「2026-08-27 后期间改动已间接消除」假说增强；卡维持观察态，关闭待用户裁决。

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
方向：批事务化 / FTS 挂后台队列 / 大 part 截断索引。 战役残项（中间带 turn / _rea 之谜 /
SelectionContainer 二阶 / pendingSegmentSkeletons 上限）另立卡承接（见 §四）。
