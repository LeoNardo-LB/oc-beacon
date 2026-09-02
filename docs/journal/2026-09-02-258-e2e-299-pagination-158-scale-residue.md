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

**结论：#258 验收通过，卡片迁移本 journal 归档。** 战役残项（中间带 turn / _rea 之谜 /
SelectionContainer 二阶 / pendingSegmentSkeletons 上限）另立卡承接（见 §四）。
