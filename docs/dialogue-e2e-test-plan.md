# 对话全生命周期 E2E 测试期望文档（Dialogue E2E Test Plan）

> 本文档定义**主对话界面（ChatScreen）全生命周期**的端到端测试期望：
> 从"进入会话"到"发送消息"到"流式输出"到"完成落库"到"退出重进"，覆盖正向、逆向、极端流程。
>
> **配套文档**：实际执行记录在 `docs/dialogue-e2e-test-runbook.md`（实操文档）。
> **方法论**：`docs/qa-methodology.md`（交叉验证 ≥2 独立维度、证据链完整、操作可复现）。
>
> **V1/V2 双协议标注**：每条用例标注适用协议。当前环境（2026-08-14）：
> - **V2 服务器**：`SimServer` @ `10.0.2.2:4199`（API v2 · 0.0.0-next-17403）——主测试对象
> - **V1 服务器**：`10.0.2.2:4096`（旧 opencode serve）——需确认可用后执行
> - 协议差异速查：`docs/v1-v2-differences.md`（prompt：V1 `prompt_async` 204 无播种 vs V2 `prompt` 200 返回 Inbox+本地播种；abort：V1 `abort` vs V2 `interrupt`）

> **执行状态（2026-08-14 轮次 2/3/4，详见 runbook）**：
> - ✅ 已执行并 PASS：E0 全部、E1-1..E1-2、E2-1..E2-4、E3-1..E3-3、E4-1..E4-5、E5-1、E7-3（#129 僵尸自动解除完整闭环）、V1 question 全生命周期（触发→渲染→回答→提交→agent 继续）、#131 修复验证（V1 4 题卡片渲染）
> - ⚠️ 受限：E7-5 V2 question（#130 服务器缺陷，V1 已通过）、#126 远页草稿 UI 手势（模拟器无法 pager fling，需真机）
> - ⏳ 未执行：E6-2 冷启动恢复、E7-1/E7-2/E7-4、E8 系列（后续轮次）、V1 全套剩余

---

## 0. 编排评估：动态 / 静态 / 纯脚本

### 0.1 三维度分类

| 类别 | 定义 | 示例 | 执行方式 |
|------|------|------|----------|
| **DYN（动态）** | 需要真实 UI 交互 + 时间推进 | 点击发送、等待流式、观察转圈 | 模拟器 adb tap/input + 截图 + UI dump |
| **STA（静态）** | 一次采集即可验证、不随运行变化 | 代码检查（D1）、单测（D2） | 命令行直接执行 |
| **SCR（脚本）** | 可纯脚本轮询/断言，无需 UI | 服务器 curl、Room 直查、logcat 过滤 | bash/python + adb shell |

### 0.2 编排建议（推荐顺序）

```
阶段 0 环境准备（SCR）→ 阶段 1 进入会话（DYN+SCR）→ 阶段 2 发送（DYN+SCR）
→ 阶段 3 流式（DYN+SCR 轮询）→ 阶段 4 完成（DYN+SCR）
→ 阶段 5 状态联动（SCR+DYN）→ 阶段 6 退出重进（DYN）
→ 阶段 7 逆向流程（DYN+SCR）→ 阶段 8 极端流程（DYN）
```

- **每阶段的核心断言**优先用 SCR（logcat 事件 / DB 直查 / curl）——可重复、可机器核对；
- **UI 视觉断言**（气泡出现、转圈消失）用 DYN 截图 + 视觉 MCP 分析；
- **交叉验证**：同一结论至少 1 个 SCR + 1 个 DYN（如"消息已发送" = logcat `[send-seed]` + 截图可见用户气泡）。

### 0.3 通用断言速查（跨阶段复用）

| 证据通道 | 命令 | 断言示例 |
|----------|------|----------|
| logcat 应用日志 | `adb logcat -d | grep <PID>` | `[send-seed]`、`[msg] MessageUpdated role=user`、`Busy/Streaming --SseIdle--> Idle` |
| 服务器 SSE 状态 | `curl /api/session/active` | 会话从 `running` 消失（僵尸解除） |
| 服务器消息列表 | `curl /api/session/<sid>/message?limit=5` | 新消息 id 存在、assistant 消息 `completed` 非空 |
| Room 数据库 | `adb shell run-as dev.leonardo.ocbeacon.dev sqlite3 .../ocbeacon.db "SELECT ..."` | `cached_messages` 新增行、`cached_parts` 文本累积 |
| UI 转圈指示器 | `uiautomator dump` grep ProgressBar | 流式时存在、完成后消失 |
| UI 截图 | `adb exec-out screencap -p` + 视觉 MCP | 气泡可见、文案正确 |

---

## 1. 阶段 0：环境准备（SCR）

- [ ] **E0-1 模拟器就绪**：`adb devices` 有 `emulator-5554`；App 已安装（`dumpsys package dev.leonardo.ocbeacon.dev | grep versionName` = 目标版本）
- [ ] **E0-2 服务器连接**：App 首页显示 SimServer `Connected`；`curl /api/session/active` 可达
- [ ] **E0-3 测试会话存在**：`curl /api/session?limit=100` 包含 `ses_0115b9cc8ffe9uQYuP9oUGhagI`（测试专用会话）
- [ ] **E0-4 基线清理**：`adb logcat -c` 清空；记录基线 `/api/session/active`（应为 `{}` 或已知列表）
- [ ] **E0-5 协议标注**：确认当前测试协议 = V2（SimServer `API v2`）；若测 V1 需切换服务器

---

## 2. 阶段 1：进入主对话界面（DYN + SCR）

- [ ] **E1-1 会话列表 → 会话**：从会话列表点击"测试专用会话"
  - 0s 截图：期望进入会话详情（顶栏标题 = 会话标题、副标题 = 目录）
  - 3s 后截图：期望消息列表渲染（历史消息可见）；若未见，等 3s 重试截图，最多 3 遍
  - logcat 期望：`[seed] session=... cached messages -> memory hot view`；`ChatStateAggregator [meta] status=Idle`
  - DB 期望：`cached_messages` 该会话行数与服务器消息数一致（±分页窗口）
- [ ] **E1-2 输入框就绪**：底部输入框可聚焦；占位符显示；Send 按钮存在
  - UI dump 期望：`EditText` + `desc='Send'`
- [ ] **E1-3 状态恢复**：若会话上次为 Busy（僵尸未解除），期望 3 分钟内 FSM 自动转 Idle
  - logcat 期望：`zombie runner, forcing Idle` → `[meta] status=Idle`（若触发）
  - 服务器期望：`/api/session/active` 中该会话消失（App 主动 interrupt 解除）

---

## 3. 阶段 2：发送消息（DYN + SCR）

- [ ] **E2-1 输入文本**：tap 输入框 → `input text 'E2E_plan_verify_<时间戳>'` → 输入框显示该文本
- [ ] **E2-2 点击发送**：tap Send 按钮
  - 0.5s 内截图：期望输入框清空（发送成功回调）
  - logcat 期望（V2）：`[model] POST /session/.../model status=204`（若有模型切换）→ `[prompt] POST /prompt status=200` → `[prompt] admission id=msg_...` → `[send-seed] user message msg_...` → `[msg] MessageUpdated role=user`
  - logcat 期望（V1）：`POST /session/<sid>/prompt_async` 200 → 依赖 SSE 回显（无本地播种日志）
  - DB 期望：`cached_messages` 新增该 user 消息行（V2 播种后立即、V1 回显后）
  - UI 期望：用户气泡出现（蓝色/右侧）
- [ ] **E2-3 FSM 转 Busy**：发送后 FSM 从 Idle → Busy
  - logcat 期望：`Idle --ClientSendParts--> Busy/Waiting` → `[meta] status=Busy streaming=false`
  - UI 期望：底部模型选择器旁出现转圈指示器（ProgressBar）
- [ ] **E2-4 防重复发送**：发送期间再次 tap Send 被忽略
  - logcat 期望：`sendParts: already sending, ignoring duplicate`（若 100ms 内连点）

---

## 4. 阶段 3：流式输出（DYN + SCR）

- [ ] **E3-1 执行事件到达**：发送后 ≤60s 内出现服务器执行事件（V2：`session.step.started` / `session.text.*`；V1：`session.next` / message.updated）
  - logcat 期望：`[recv] MessagePartDelta`（节流日志）或 `[recv] SessionNext`；`Busy/Waiting --TextStarted--> Busy/Streaming`；`[meta] streaming=true`
  - **若 60s 无任何事件**：判定服务器僵尸 → 期望 App 3 分钟兜底自动 interrupt + 转 Idle（见 E7-3）
- [ ] **E3-2 文本累积**：assistant 气泡逐步出现文本
  - 每 3s 截图，期望文本长度单调增长；若 15s 无增长且未完成，重试截图最多 5 遍
  - DB 期望：`cached_parts` 对应 part 行文本累积（48ms 批处理落库）
- [ ] **E3-3 token 统计**：token 统计更新（UI 底部或详情）
  - 期望：`[meta] streaming=true` 期间统计行存在
- [ ] **E3-4 流式滚动稳定**：新 token 到达时视口不跳动（D5 人工确认项，模拟器可粗略观察）

---

## 5. 阶段 4：输出完成（DYN + SCR）

- [ ] **E4-1 完成事件**：服务器发完成事件（V2：`session.step.ended` / `session.execution.succeeded` + `session.status` idle；V1：`session.idle` / message.updated completed）
  - logcat 期望：`Busy/Streaming --SseIdle--> Idle [force-complete]`；`[meta] status=Idle streaming=false`
- [ ] **E4-2 转圈消失**：底部 ProgressBar 消失
  - UI dump 期望：无 `class="android.widget.ProgressBar"`（或仅顶部任务徽标，非转圈）
- [ ] **E4-3 服务器 completed 时间戳**：`curl /api/session/<sid>/message?limit=5` 最新 assistant 消息 `time.completed` 非空
- [ ] **E4-4 DB 持久化**：`cached_messages` 该 assistant 行存在且 payload 含完整文本；`cached_parts` 文本完整
- [ ] **E4-5 完整回复断言**：assistant 文本包含预期关键词（如"连接正常 ✅"或 echo 内容）

---

## 6. 阶段 5：会话状态联动（SCR + DYN）

- [ ] **E5-1 列表状态**：返回会话列表，该会话显示正确状态（Idle 无"进行中"图标）
  - logcat 期望：`[meta] status=Idle`（列表行读取同一 FSM）
- [ ] **E5-2 会话排序**：会话列表按更新时间排序，测试会话排到顶部
- [ ] **E5-3 未读红点**：新回复产生未读标记（若在非当前会话场景测试）
- [ ] **E5-4 标题更新**：发送后 8s 内服务器生成标题（若默认标题），REST 回填日志 `[Title] REST fallback` 或不出现（SSE 已投递）

---

## 7. 阶段 6：退出与重进（DYN）

- [ ] **E6-1 退出会话**：返回键 → 会话列表；再点击进入同一会话
  - 期望：消息完整（含刚才的 user/assistant 消息）；状态正确；无重复气泡
  - DB 期望：无重复行（主键 id 去重）
- [ ] **E6-2 冷启动恢复**：杀进程重启 App → 重新连接 → 进入会话
  - 期望：消息从 Room 恢复（`[seed]` 日志），再与服务器同步；无崩溃

---

## 8. 阶段 7：逆向 / 异常流程（DYN + SCR）

- [ ] **E7-1 发送失败（服务器 4xx）**：向不存在/错误会话发送（或临时断网）
  - 期望：AlertDialog 提示失败；输入框保留原文（不清空）
  - logcat 期望：`Failed to send message`；`[prompt] status=4xx`
- [ ] **E7-2 网络断开重连**：断网 → SSE 断连 → 恢复
  - logcat 期望：`SSE stream completed` → `Reconnecting in ...ms` → `Connected to server`；`Recovering messages for N sessions`
- [ ] **E7-3 僵尸会话自动解除（#129 核心）**：服务器僵尸 running + 无事件
  - 前置：制造/发现僵尸（`/active` 有会话但 curl 消息无新增 + 无 SSE 事件）
  - 期望（App 侧）：3 分钟兜底 → `zombie runner, forcing Idle` + `zombie interrupt sent`（新日志）→ `[meta] status=Idle`
  - 期望（服务器侧）：`/api/session/active` 该会话消失（interrupt 生效）
  - 后续发送期望：消息正常执行并回复（僵尸已解除）
- [ ] **E7-4 停止生成**：流式中点 Stop
  - logcat 期望：`Aborted session`；FSM → Idle；服务器 `interrupt` 调用
- [ ] **E7-5 权限/问题请求**：agent 发问题卡片 → 输入框禁用 → 回复后恢复
  - logcat 期望：`inputEnabled=false` 相关日志；回复请求 POST

---

## 9. 阶段 8：极端 / 边界流程（DYN）

- [ ] **E8-1 快速连续发送**：连发 3 条消息（间隔 <1s）
  - 期望：3 条 user 消息均出现；无重复；agent 依次处理（或排队）
- [ ] **E8-2 超长文本**：发送 >1000 字符文本
  - 期望：输入/发送正常；气泡完整渲染（无截断/溢出）
- [ ] **E8-3 特殊字符**：发送含 `%`、`#`、`<tag>`、emoji、换行的文本
  - 期望：正常显示（无 HTML 注入、无崩溃）
- [ ] **E8-4 空输入**：仅空白/空 → Send 按钮 disabled；无请求发出
- [ ] **E8-5 revert 回滚**：对某条 user 消息点 Revert
  - 期望：该消息及之后消息被移除（`Pruned N reverted messages`）；可恢复
- [ ] **E8-6 大会话分页**：会话消息 >50 条时上滑加载更早消息（`hasOlder` 分页）

---

## 10. 优先级与执行顺序

| 优先级 | 用例 | 理由 |
|--------|------|------|
| P0（必测） | E1-1..E1-3、E2-1..E2-4、E3-1..E3-2、E4-1..E4-5、E5-1、E6-1、E7-3 | 核心链路 + 当前已知 bug（#129 僵尸） |
| P1（重要） | E5-2..E5-4、E6-2、E7-1、E7-2、E7-4 | 状态联动与异常恢复 |
| P2（补充） | E7-5、E8-1..E8-6 | 边界与极端场景 |

---

## 11. 通过标准（汇总）

一份 E2E 测试"通过"= 满足：
1. P0 用例全部通过（每用例 ≥2 独立维度交叉验证）；
2. P1 用例 ≥80% 通过（未通过项登记到实操文档"未达成"区并分析原因）；
3. 无 FATAL/AndroidRuntime 崩溃日志；
4. 实操文档记录完整：每用例 = 操作时间线 + 实际观察 + 对比期望 + 结论（PASS/FAIL/受限）。

---

*本文档为"期望"文档——规定**要测什么、期望看到什么**；实际执行结果、差异分析与修复记录见
`docs/dialogue-e2e-test-runbook.md`。维护：协议差异（V1/V2）、服务器版本变化时更新 §0.3 与各用例标注。*
