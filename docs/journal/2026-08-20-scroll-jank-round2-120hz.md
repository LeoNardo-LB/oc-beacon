# 2026-08-20 第二轮滚动卡顿深度调查（120Hz 帧预算口径）
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）


- **背景**：用户反馈首轮修复后仍有三项残余症状（新消息临近顿挫 / 整体迟滞 / 长消息内卡顿）。首轮 gfxinfo 判定口径（16.7ms）在 120Hz 设备上漏报——本轮以 8.33ms 重建基准，四路子代理并行（真机测量 / UI 状态审计 / Markdown 渲染审计 / 文献调研）+ 主线交叉验证。
- **测量基础（真机 e69a99d8，会话 验收测试会话AB）**：滚动期间确认真 120Hz（doFrame p50=8.32ms，96.9% 落 8.33ms 节拍桶，118.8fps 均值）；本机 SF 三层宽松预算（WorkloadTarget 13.7ms + legacy 16.7ms）把 60% 超 8.33ms 的帧全判不 jank——系统计数器全绿是假象。
- **基线（8.33ms 口径）**：S1 慢拖普通区 >8.33ms 63.92% p99=32.3ms；S2 fling 12.53% p99=35.6ms；S3 巨型消息内 59.35% p99=25.1ms；最差帧归因 Compose:recompose 63.5%。
- **根因与修复（6 项全部落地）**：
  1. `47edb53c` 预取窗口速度自适应（慢拖1/快拖3/fling6）——PREFETCH_AHEAD=6 是 13 万字符单 item 时代设定，分片后宽窗纯剩主线程预取预算冲突
  2. `92e2855c` 超长段落空行化 + chunk 参数调优（8000/5000→3000/2500）——真实 GFM parser 实测巨型消息顶层仅 7 块（主体 129K 单 PARAGRAPH），分片对最坏消息完全失效；空行化后 blocks=8998 chunks=53
  3. `8548c3f7` 快速导航 derived 读取下沉 + 条件订阅（B-F3 重组风暴）
  4. `4cb549d5` Markdown 配置对象 remember（C-F4——53 片后每片重建 15+ TextStyle.copy 的回收）
  5. `a80b3e68` 视口内 key 裂变门控（B-F2 pending 队列）+ LazyColumn 容器每帧死回调删除（listTopY 零读取者，trace 实证 544ms/1127 次）
  6. `9bb4a537` 两个 TalkBack 可达性 P3 修复（见上方条目）
- **最终验收（vs 基线）**：S1 p99 32.29→22.38ms（-31%）；S3 >8.33ms 59.35%→53.06%、p50 9.13→8.60ms；S2 >8.33ms 12.53%→9.28%、p50 6.66→5.24ms。
- **诚实边界**：S1/S3 中位帧 ~9ms 未消除——R8-on-debug 实验（e8fe5219）证伪 debug 构建税（p50 无改善），trace 构成 = 每帧真实工作量（measure 1.3ms + recordDraw 1.1ms + RT Drawing 2.8ms + touch 0.5ms）。进一步压缩需 item RenderNode 层化（调研明确反对于全列表铺开）或 Baseline Profile（已登记下方）。
- **工具沉淀（/tmp/perf-round2/）**：scenario_runner.py（环形缓冲 ~120 帧逐轮快照 + VsyncId 去重）、merge2.py（多 PROFILEDATA 块合并修复）、frameparse.py 8.33ms 口径、scope 分析脚本、四路子代理报告（device-report / code-audit-uistate / code-audit-markdown / research）。
- **已登记未做**：Baseline Profile（app/baseline-prof.txt 手工通配 ui/screens/chat/**，官方 ~30% 代码路径加速口径）——下一批次候选。
