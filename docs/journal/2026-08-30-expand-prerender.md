# expand-prerender（2026-08-30）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## 零轮（2026-08-30）：grill 共识与 spec 落册

- grill 14 问全定案（零位移验收/tap 锚点/对称收起/纯 clip/tap 时测量/EV 试点/channel 保留/spec 先行/分级承诺/折叠不组合+缓存/测试替换/tween200/边界分级）——全文见 `docs/specs/2026-08-30-expand-prerender-design.md` §3。
- 架构事实图（ExpandReveal 六槽位 / PreRenderShiftChannel / 证据链）由 subagent 取证，沉淀于 spec §2（file:line 索引）。
- 待评审：spec §5 实现层选型 A-E（测量挂点/预移通道/hit-test 门控/缓存失效键/试点窗口）；§4.3 收起侧「布局同步缓动」与卡片「布局恒定」表述的显式偏差。
- 顺带产出：backlog.md 空行纪律修复（commit 6a21b46d，对齐 69e133e1 版结构）。
