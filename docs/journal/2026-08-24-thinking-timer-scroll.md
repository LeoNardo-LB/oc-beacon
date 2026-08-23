# thinking-timer-scroll（2026-08-24）

> 状态：待验证（自动化全绿+真机活体回归绿；等待用户验收历史会话场景）
> 关联：#207（P2）· 顺带发现 #208（Room seed 缺口）· 前序：#206 验收时用户报告
> 来源：用户真机验收 #206 反馈「思考中卡片的计时在上下滑动的时候会反复从 0 开始（最后一条消息）」；用户补充说明：场景为**已完成的历史会话**，非临时发消息

## 根因取证

### 机制（代码层）
- `ReasoningBlock.kt:66`：`startTimeMs == null` 时 fallback `remember { System.currentTimeMillis() }`——**组合期时钟锚点**
- `PartContent.kt` reasoning 分支（修复前）：`isStreaming = !partEnded || (sessionStreaming && !partEnded)`——对 `time == null` 的 part **恒真** → 永续 tick
- LazyColumn 滑出视口 = item 销毁（remember 丢弃）；滑回 = 重建重取时钟 → **计时归零**

### 野生实例（设备 Room 实证，2026-08-24）
- `msg_02da49cff0017Wq1Z53aW3Ujl7_reasoning_ord_0`（事故恢复消息，ses_fda79dde）：payload 仅 `[id, sessionID, messageID, text]` 四键——**无 time**，text 535 字非空 → 渲染「思考中」卡
- 全库扫描 400 条 reasoning part 仅此 1 条缺陷：time=null 恰是**服务器侧无 time** 的消息（事故重建时服务器 schema 剥离 time——REST 每次拉回都无 time，merge 无法回填）
- 用户验收 #206 时正开着该会话（02:03 用户发「好」+中断验证），上滑看到此卡 tick+滑动归零 → 报告完全吻合

### 路径排除（均有实证）
- 活体 SSE 路径（app 内发消息+滑动）：稳定递增 8.3→13.8→19.1s（SSE reasoning.started 写 start=now 锚点）——无此 bug
- 纯 REST 中断历史（app 死时 API 造中断→冷启动开）：服务器返回 content time={created,completed} 完整 → 「思考完毕 · 4.5s」静态——无此 bug
- Kotlin 会话 02:03 中断消息（REST time 完整）：「思考完毕 · 2.0s」静态

## 修复（TDD）

**判定提纯**（`ChatParts.kt` 新增 `isReasoningStreaming(partEnded, sessionStreaming, hasValidAnchor)`）：
`!partEnded && (hasValidAnchor || sessionStreaming)`——time=null 残留 part 在 idle 会话下**静态不计时**；活体重进错过 started（会话流式）保留续计语义（2026-08-16 三态合成）。

**锚点加固**（`ReasoningBlock.kt`）：fallback `remember` → `rememberSaveable`——滑出销毁后滑回锚点不重置（LazyColumn item key 经 SaveableStateHolder 保留）。

**脉冲动画 gate**：`isComplete || !isStreaming` 均用静态 alpha——停表卡不再播「正在思考」脉冲。

**测试**：`PartRenderLogicTest` +4（活体锚定 / 完结不计时 / **残缺 idle 不计时** / 活体无锚续计）；全套件 1917 绿。
中途修正：heredoc 追加测试与 sed 删闭合行顺序颠倒 → 类闭合错位 → initializationError（已修，教训：先删后追加）。

## E2E（houji e69a99d8，修复版 versionCode 1787510914）

- **活体回归绿**：两轮「滑出销毁→滑回重建」循环，思考中计时 1m16s → 1m21s 单调递增无归零（随后轮次自然完成，思考完毕 · 1m24s 定格）；排队中（第二条 prompt 已提交）时第一条思考卡正常走动——新判定未破坏活体语义
- time=null 静态化端到端观察：**受阻于 #208**（冷启动 seed 不装历史 part——事故卡/假 id 探针两度不渲染，与修复无关的数据通路缺口）；判定逻辑由单测矩阵锁定
- 受控 RED 尝试记录：①REST merge 回填 time（服务器权威）②假 id 消息不渲染（#208）——两条注入路径均被环境特性挡住，未能留下设备端 RED 截图；RED 证据以「修复前代码对 time=null 恒 streaming + 组合锚点」的代码级论证 + 野生实例存在性替代

## 完结迁移

（用户验收后从 backlog.md 迁入）
