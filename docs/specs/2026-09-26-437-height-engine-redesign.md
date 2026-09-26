# 高度引擎重写设计（#437/#440 · 底原点预留契约）

## 证据基线（2026-09-26，journal 十六轮 + VDRAW 取证）
- 状态层原子（VPT 54/54 同帧）但**绘制层泄漏**：VDRAW 实锤 83 帧「新高度+旧偏移」上屏 + 148 帧偏移阶梯追赶 = 用户主诉的上推→回弹。reject-draw（pre-draw return false）挡不住、配对待定位经下一遍 measure 才生效——增长帧与配对帧跨绘制。
- #440：回合结束 t_dsh-* 临时键 remove + 终态键 add 非原子 → 锚点重锚 −392px。
- streamingMsgId=null（DSH 路径）——配对判定依赖的会话状态在该路径缺失。

## 契约（用户裁决，不可让步）
- **I1′ 零可见位移**：离底阅读内容零位移；高度 Δ 与 offset +Δ **同一次 measure/layout 原子消费**；任何中间帧不得进入绘制；手势进行中无豁免。
- **I2′ 底原点预留**：坐标自底向上（底=0）；t₀（手势介入）快照 + Σ已预测量增量 ≡ 任意时刻高度；测量永不 surprise 列表。
- 实现只允许**预测量**（i）；帧前反射写入；禁止一切事后补偿。

## 架构
1. **节奏收编**：SSE 原始 delta 直入引擎；48ms 时间片退役为引擎内部策略（默认 48ms，可调 100ms，性能/体验权衡）。
2. **预测量管线**：增量 chunk 在施加前离屏测出精确高度（markdown 增量测量）；引擎持有「预留高度表」。
3. **原子施加**：增长帧绘制前，同一 measure pass 内完成「高度生效 + offset +Δ」——具体机制：**不在增长帧依靠 LazyList 滚动状态自然重排，而是增长内容先进预留区（固定高度占位，底原点对齐），offset 写入与占位扩展同 pass 生效**；中间帧构造性不存在，不依赖 reject-draw。
4. **换装原子（#440）**：终态切换为键稳定原位内容替换（同 key 换 payload）或帧前预留终态高度，禁止 remove+add。
5. **旁路收口**：GUARD/MSGEFFECT/BANNER/ForceScroll 等 offset 写入者全部改为引擎客户端（经引擎单点派发），离底态一律零派发。

## 验收
- 协议：scripts/stream-flicker-test.sh（位置 A）；判据：VDRAW 泄漏帧（H_ONLY/O_ONLY）= 0、#440 跳变 = 0、贴底跟随回归不破。

## 设计拷问轮裁决（2026-09-26）
- Q1 预测量开销：接受（每增量一次离屏 measure）。
- Q2 误差兜底撤销：同约束测量确定性、零误差；异步内容（图片/高亮）就绪时补预测量、作为新预留增量前加；已上屏高度永不回改。
- Q3 换装=在位键：流式尾巴自出生用轮次槽位键（非 temp/final id）；正式 id 落库仅做 temp 到 final 映射，键不动；LazyList 视角零结构事件。
- Q4 旁路无特例：GUARD/MSGEFFECT/BANNER/ForceScroll 全收编引擎单点，离底零派发。
- 下一阶段：引擎实现（预留区占位 + 同 pass 原子施加 + 在位键换装），验收判据 VDRAW 泄漏=0、#440 跳变=0（stream-flicker-test.sh）。


## 实现裁决变更记录（2026-09-26 二十四世轮双轴审查后补，用户要求根修时固化）

1. **架构 2「预测量管线（离屏测高+预留高度表）」未按字面落地**——实现为「一帧缓冲帽」
   （streamingHeightReserve：增长当帧帽裁+pre-draw 单事务释放+配对）。效果等价（I1′ 原子性
   有 VPT/VDRAW 帧级证据链），但字面偏离「只允许预测量」。**已知协议性代价：帽每批需
   全子树重测真高（增量测量与「当帧真高」结构性冲突）= 贴底跟随帧 18ms 的主源。**
   后续 R2 重构（预留高度表：稳定块高度缓存+增量 chunk 离屏预测量+多槽 per-item）
   将回到本 spec 架构 2 原案，帽退役为表的消费者。
2. **裁决 6「单一引擎点无例外」现状**：列名族经 ViewportDispatchGateway 收口；跳转族/
   snapToBottom 强推/CardExpandReveal episode 为网关白名单例外（KDoc 自认）；网关本质是
   logging 门面无强制力。R5（视口写入全经引擎 API）为目标态。
3. **裁决 4 cadence**：间隔已 100ms（STREAM_FLUSH_INTERVAL_MS），但住 MessageEventHandler
   （数据层）非引擎内部策略——结构收编留待 R1/R4。
4. **配对规则双轨**（ledger itemIndex==anchorIndex vs 帽 firstVisibleIndex<=growthIndex）与
   同帧双滚动 set 覆盖风险——R1 合并为单一纯函数。
