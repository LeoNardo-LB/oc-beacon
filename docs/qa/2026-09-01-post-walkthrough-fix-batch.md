# 走查后修复批（goal-ec551928）——2026-09-01 收口报告

走查（docs/qa/2026-08-31-dsh-full-walkthrough.md）后由 glm-5.3-flash 解锁写路径的修复批。全部根因修复模式（禁打补丁），TDD + 全量单测 + 真机四维证据。

## 已裁决项（前轮完成）

| # | 项 | 结论 |
|---|----|------|
| #6 | 排队发送悲观行不消失 | 测试伪象（实按 STOP）；真缺口=#8 → 双键方案（18ae1a3d） |
| #7 | QueueDock 不渲染 | 无真实队列存在（服务器 inbox 无 splice）；渲染链正常 |
| #8 | 忙碌无排队能力 | 双键发送区（停止+发送并排）已落地 |
| ③ | goal 轮注入活体验证 | round 消息渲染 + wrapup + goal driver 消费队列实证 ✅ |
| ⑤ | 卡片联动 RB-EXP 取证 | ✅（前轮） |

## 本轮新增修复（3 commits）

### 1. SSE 双写落盘事务化——FK 787 根治（aa9bae68）

- **取证**：真机 logs 表两次 `appendPartTexts failed: SQLiteConstraintException FOREIGN KEY (787)` + 堆栈（appendPartText）；时间线上紧邻重开分页/预取日志与 `orphan part host missing` 骨架自愈告警。
- **根因**：`MessageStore.appendPartTexts` 的骨架插入与 delta 追加是**两条独立 DAO 提交**；会话重开时 `prefetchJumpTargets → replaceSessionMessages`（事务内 clearSession+重写服务器权威集，权威集不含流式中消息）插进两步之间 → 骨架被清 → 部件插入 FK 787 → 本批 delta 落盘全丢（内存视图不受影响声明掩盖了落盘丢失）。
- **修复**：两处多步写库原子化——appendPartTexts（骨架+追加）与 upsertMessages（消息 REPLACE+parts 重写）各包同一 `withTransaction`。事务化后并发替换要么整体先于（被权威重写收敛）要么整体后于（俱在），FK 孤儿结构性不可能。
- **TDD**：MessageStoreTest 新增 2 条原子性契约测试（事件序列断言事务内性；RED→GREEN），全量单测过。
- **真机验证**：修复包装机 → 重开忙会话 a6c4（对账 10 动作 + 分页全刷 + 流式落盘并发）→ logcat FK 0 错误；一致性 DB 快照（WAL 三件套 + integrity ok）logs 表 FK 0、`seq-509766_text_ord_1` 含本轮实时流式文本（落盘链工作）；UI 渲染正常（截图 + a11y）。

### 2. QueueDock 队列变更 404 根治——updateQueue → session.updateQueue（11bcbc17）

- **发现路径**：steer 交互抽验时 logcat 见 `REQUEST /api/updateQueue` 25ms 回包，服务器探针 8 个候选方法名仅 `session.updateQueue` 通（bad-request = 路由存在）→ 原 wire 方法名 404 → **edit/remove/steer 全部静默失效**。走查期「remove 可用」实为步边界消费误判（行消失=被服务器消费，非 RPC 生效）。
- **修复**：`DshApiClient.updateQueue` 方法名改 `session.updateQueue`（服务器方法面 session.<域>.<动作>）。TDD：DshApiClientTest 钉子测试（端点+载荷形状，RED→GREEN），全量单测过。
- **真机 E2E**：重装机 → a6c4 排队 WT-STEER2 → 点删除 → UI 行消失 + logcat `/api/session.updateQueue` 正确端点（45ms）+ 服务器队列空（WS 快照无帧=空）；服务器直连 RPC remove accepted。

### 3. ⑥ 预设锁定竞态抽验（ea24dda0）

- `defendSessionReplacement` 对 agentPreset 的保持无专测 → 补最小 SessionUpdated 保留缓存 agentPreset 断言（首跑即绿=防御坐实，非修 bug）。

## #9 workflow 卡不渲染——定性收口（非 app 缺陷）

活体四面包夹：events.mux 实况 WS（两次 tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）——**当前服务器对客户端不暴露 tool-workflow 进度事件**。app 侧映射链（DshEventMapper:469 + Assembler + UI）为休眠代码路径；DSH synthetic 消息零持久化同因（无数据源 + 权威替换不含）。走查期「18 事件在 a6c4」不复现。→ backlog #290（含升级前置：待服务器暴露后重验）。#288 卡片补升级注记。

## 证据目录

- /tmp/dsh-wt/verify_fk_*.png、verify_steer_*.png、verify_queuefix_01.png（截图）
- /tmp/dsh-wt/ws_frames.json、ws_tap*.py、probe_methods.py、queue_live.py（WS/服务器探针）
- 设备 DB 快照：v3.db（integrity ok）、a6c4.db（历史）；logs 表 FK 错误行（修复前 2 条/修复后 0）

## 遗留

- master 未推送（累计 ~120 commits，待用户裁决推送时机）
- backlog #282-290（走查登记项 + 本批 #290）
- steer 语义级效果（生成实际被引导）= 服务器侧行为，app 侧交互链已闭环
