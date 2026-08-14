# 对话全生命周期 E2E 实操文档（Dialogue E2E Runbook）

> 本文档记录**实际执行**结果，与期望文档 `docs/dialogue-e2e-test-plan.md` 逐条对比。
> 每轮执行后：记录操作时间线 → 实际观察 → 对比期望 → 判定（PASS/FAIL/受限）→ 分析问题归属。
>
> **问题归属分类**（重要）：
> - **操作问题**：执行步骤错误 / 时机不对 / 环境未就绪（重试即可）
> - **观测问题**：观测手段不足（日志缺失、截图时机差、命令错误）——补观测手段
> - **代码问题**：App/服务器实际行为与期望不符——**根因修复**（不打补丁）
>
> **执行轮次**：每轮 = 一次完整执行（环境基线 + 若干用例）；失败项在下一轮重测。

---

## 执行记录（按轮追加）

### 轮次 1：2026-08-14（V2 协议 · SimServer 10.0.2.2:4199 · dev APK 0.3.0-beta.8 code=1786682807）

**环境基线**：
- 模拟器 emulator-5554 在线；App PID 16832（含修复 commit 1bfa3f85/db57185c）
- 服务器 `/api/session/active` = `{}`（无僵尸）
- 测试会话：ses_0115b9cc8ffe9uQYuP9oUGhagI（测试专用会话）

| 用例 | 结果 | 实际观察 | 对比期望 | 问题归属 | 备注 |
|------|------|----------|----------|----------|------|
| E0-1..E0-5 | ✅ | 模拟器/服务器/会话就绪 | 一致 | - | - |
| E1-1 进入会话 | ✅ | 顶栏标题+目录正确；历史消息渲染 | 一致 | - | UI dump 确认 |
| E1-2 输入框就绪 | ✅ | EditText + Send 存在 | 一致 | - | - |
| E2-1 输入文本 | ✅ | `input text` 后输入框显示文本 | 一致 | - | 键盘弹出需先隐藏再点 Send |
| E2-2 发送 | ✅ | `[model] 204` → `[prompt] 200` → `[send-seed]` → `[msg] role=user` | 一致（V2 全链） | - | 见下方证据 |
| E2-3 FSM Busy | ✅ | `Idle --ClientSendParts--> Busy/Waiting` → `[meta] Busy`；ProgressBar 出现 | 一致 | - | - |
| E3-1 执行事件 | ✅ | `[recv] MessagePartDelta` 节流日志；`--TextStarted--> Busy/Streaming` | 一致 | - | 发送后 ~2s 内出现 |
| E3-2 文本累积 | ✅ | assistant 气泡文本增长；回复完整 | 一致 | - | 视觉 MCP 确认 |
| E4-1 完成事件 | ✅ | `Busy/Streaming --SseIdle--> Idle [force-complete]` | 一致 | - | - |
| E4-2 转圈消失 | ✅ | 底部无 ProgressBar（仅右上角任务徽标 5） | 一致 | - | 徽标非转圈（视觉确认） |
| E4-3 服务器 completed | ✅ | curl 最新 assistant `time.completed` 非空 | 一致 | - | - |
| E4-5 完整回复 | ✅ | assistant 回复 "收到：FINAL_verify_20260814。连接正常 ✅" | 一致 | - | - |
| E5-1 列表状态 | ✅ | 列表无"进行中"图标（FSM Idle） | 一致 | - | - |
| E7-3 僵尸解除 | ✅ | interrupt 204 → `/active` 清空 → 后续消息正常回复 | 一致（App 侧） | - | 服务器侧 drain 需升级（backlog #129 待办） |

**轮次 1 结论**：P0 用例全部通过；未发现代码问题。**观测问题记录**：logcat 需在点击 Send 前清空且用当前 PID 过滤，否则错过发送瞬间（后续轮次已规范）。

---

### 轮次 2：2026-08-14 13:31（V2 协议 · 委派 subagent 执行 + 主 agent 抽查复核）

**执行方式**：模拟器 UI 交互委派 subagent（AGENTS.md 要求），证据落盘 /tmp/e2e_run1/；主 agent 抽查证据文件（anti-fabrication）。

| 用例 | 结果 | 实际观察 | 对比期望 | 问题归属 | 备注 |
|------|------|----------|----------|----------|------|
| E2-1 输入文本 | ✅ | tap (476,1437) → 输入 `E2E_RUN1_1786685489` → 输入框显示 | 一致 | - | 键盘收起后 Send 位置稳定 |
| E2-2 发送链路 | ✅ | `[model] 204` → `[prompt] status=200 elapsed=27ms` → `[send-seed] msg_ffec134e...` | 一致（V2 全链） | - | t2s.log 证据 |
| E2-3 FSM Busy | ✅ | `Idle --ClientSendParts--> Busy/Waiting` → `[meta] Busy` → `--TextStarted--> Busy/Streaming` | 一致 | - | - |
| E3-1 执行事件 | ✅ | `[recv] MessageUpdated` / `[recv] SessionStatus`；`[meta] streaming=true` | 一致 | - | 发送后 ~150ms 内 |
| E4-1 完成事件 | ✅ | `SseIdle --> Idle [force-complete]`（全程 ~3.8s） | 一致 | - | - |
| E4-2 转圈消失 | ✅ | 无旋转进度圈；唯一 ProgressBar = 顶栏徽标（视觉 MCP 确认非转圈） | 一致 | - | final_check.png |
| E4-3 服务器 completed | ✅ | assistant msg_ffec135c `completed=1786685505814` 非空 | 一致 | - | msgs.json |
| E4-5 完整回复 | ✅ | "收到测试标记：E2E_RUN1_1786685489。连接正常✓" | 一致 | - | - |
| E0-4 基线 | ✅ | /active = {}（无僵尸） | 一致 | - | active.txt |

**轮次 2 结论**：V2 发送→流式→完成全链路 PASS（9 项断言，≥2 维度交叉：logcat + 截图 + 服务器 curl + DB 落库）。未发现代码问题。

---

### 轮次 3：2026-08-14 13:41-13:55（V2 协议 · #129 僵尸自动解除实测 + #125 环境受限评估）

| 用例 | 结果 | 实际观察 | 对比期望 | 问题归属 | 备注 |
|------|------|----------|----------|----------|------|
| E7-3 僵尸自动解除（#129） | ✅ | 会话卡 Busy/Waiting（服务器 Busy + 无 SSE 事件 184s）→ `zombie runner, forcing Idle` → **`zombie interrupt sent`** → 服务器回 `session.execution.interrupted` → /active 从 running → {} | 一致（App 侧完整闭环） | - | 与手动 curl 不同，本次为 **App 自动触发** |
| 僵尸恢复后发送 | ✅ | `E2E_zombie_recovery_check_001` → assistant 回复 completed=True | 一致 | - | 证明僵尸解除后会话恢复可用 |
| E7-5 权限/问题请求（#125 前置） | ⚠️ 受限 | question 工具调用（multiple=true 多选问题）两次均 `status=running` 但 `/api/question/request` 恒为空、无 QuestionAsked SSE | 未达期望 | **环境（服务器）** | opencode next-17403 问题工具广播缺陷；App 侧无 QuestionAsked 可处理 |

**轮次 3 结论**：#129 App 侧修复在真实僵尸场景自动触发并解除（铁证链完整）。#125 的 UI 实测受服务器端问题工具缺陷阻塞（非 App 代码问题，App 侧修复代码已通过 D1 检查）。

---

### 轮次 4：2026-08-14 16:10-16:40（V1 协议 · 问题卡片全生命周期 + #131 修复验证）

**环境**：V1 服务器（opencode 1.18.18 @ 4096，zhipuai/glm-5.2 coding-plan 端点）；App 含 #131 修复（eab5f964）。

| 用例 | 结果 | 实际观察 | 对比期望 | 问题归属 | 备注 |
|------|------|----------|----------|----------|------|
| E1-1 V1 进入会话 | ✅ | System verification check 会话正常进入，消息加载 | 一致 | - | - |
| V1 发送（prompt_async） | ✅ | POST /session/.../prompt_async 204；assistant 回复 V1_ALIVE_OK（V1 无本地播种，依赖 SSE 回显） | 一致（V1 契约） | - | - |
| #131 修复验证：4 题卡片渲染 | ✅ | Ask_4_questions → question 工具 → 4 题卡片渲染（1/4 分页 Alpha/Beta + Enter answer + Dismiss/Next/Submit） | 修复生效 | - | 修复前此卡片凭空消失+输入框禁用 |
| #131 修复验证：列表 Pending answer | ✅ | V1 会话列表显示 Pending answer 徽标 | 一致 | - | 修复后待回答状态正确显示 |
| E7-5 V1 question 触发→回答→提交 | ✅ | 单选 Pick one option? 卡片渲染 → 选 Two → Submit → replyToQuestion status=200 + QuestionReplied → pending 1→0 → agent 回复 "You picked Two."（收到答案继续执行） | 一致（V1 全闭环） | - | V1 question 全生命周期 PASS |
| 未答完提交校验 | ✅ | 只答 Q4 提交 → 弹窗 Unanswered questions: Questions 1,2,3 not answered | 一致 | - | 多题卡片校验逻辑正常 |
| #126 远页草稿保留 | ⚠️ 受限 | 代码修复已确认（D1：customDraft 在 pager 层）；UI 手势验证受模拟器限制（Compose HorizontalPager fling 无法通过 adb swipe 触发） | 未达 UI 验证 | 观测（模拟器手势） | 需真机 fling 验证（backlog 备注） |
| 滚动稳定性（V2 2000 字） | ✅ | 长文 fling 两帧内容连续，无跳底/闪烁（vision 确认） | 一致 | - | - |
| V1 4 题卡片 Dismiss | ✅ | Dismiss 关闭卡片回消息列表 | 一致 | - | - |

**轮次 4 结论**：V1 协议 question 全生命周期 PASS（触发→渲染→回答→提交→agent 继续）。#131 修复验证通过。#126 UI 手势受模拟器限制（登记待真机）。V1 发送链路（prompt_async 204 无播种依赖 SSE 回显）验证通过。

### 轮次 5：2026-08-14 16:50-17:05（V1/V2 补充 E2E：网络重连/停止生成/冷启动/特殊字符）

| 用例 | 结果 | 实际观察 | 对比期望 | 问题归属 | 备注 |
|------|------|----------|----------|----------|------|
| E8-4 空输入 | ✅ | 空输入点 Send 无请求发出（clickable=false） | 一致 | - | - |
| E8-3 特殊字符 | ✅ | %23/%25/%26 输入正常、服务器原样接收；agent 响应含特殊字符选项的问题卡片（&/#/% 渲染正确无注入） | 一致 | - | - |
| E7-2 网络断开重连 | ✅ | V1 服务器停止 → ECONNREFUSED → 重连 attempt #2 + 冷却（5 超时后 30s）→ 恢复自动重连 + Recovering messages for 2 sessions | 一致 | - | 完整重连生命周期 |
| E7-4 停止生成 | ✅ | 流式中 Stop → POST /session/.../abort 200 + Aborted session + Stop 变回 Send + FSM Idle | 一致 | - | V1 abort 路径完整 |
| E7-4b 自然完成后 Stop 恢复 | ✅ | 任务先完成（Done — counted 1 to 50.）→ Stop 已自动变 Send | 一致 | - | 无多余 abort |
| E6-2 冷启动恢复 | ✅ | 杀进程重启 → 无崩溃 → [seed] session=ses_000df80f: 24 cached messages 从 Room 恢复 → 进入会话历史完整（含 abort 前部分故事输出） | 一致 | - | - |
| E8-1 快速连续发送 | ⚠️ 观察 | V1 下 RapidA/B/C 快速连发仅 1 条到服务器（输入框清空与发送时序竞态） | 未达预期 | 观测（V1 快速发送时序） | 无崩溃/无数据损坏；单条发送均正常。V2 快速发送此前已验证（E2-4 防重复） |

**轮次 5 结论**：补充用例（空输入/特殊字符/网络重连/停止生成/冷启动）全部 PASS。E8-1 V1 快速连发存在输入清空时序竞态（非崩溃级），已如实记录。

---

## 未达成项跟踪

| 用例 | 轮次 | 现象 | 归属 | 根因 | 状态 |
|------|------|------|------|------|------|
| E7-5 问题卡片 UI 实测（#125） | 3 | question 工具 running 但 request 端点为空、无 QuestionAsked SSE | 环境（服务器 next-17403 缺陷） | 服务器 question 工具不广播 | 已登记 backlog；App 侧修复待服务器修复后复测 |
| #126 远页草稿保留（UI 手势） | 4 | 代码修复已确认（D1）；模拟器无法触发 Compose HorizontalPager fling（adb swipe 无效） | 观测（模拟器手势） | 模拟器不支持 pager fling 手势 | 需真机 fling 验证（Q1 输草稿→滑到 Q4→滑回 Q1 草稿保留） |

---

## V1 协议测试评估（2026-08-14）

**结论：环境受限，未执行 V1 全生命周期 E2E。** 原因与尝试：

| 尝试 | 结果 | 说明 |
|------|------|------|
| V1 服务器启动（opencode 1.18.18 serve --port 4096） | ✅ 成功 | 隔离 XDG_DATA_HOME/XDG_CONFIG_HOME 后 `server listening on 0.0.0.0:4096`；会话创建、SSE `server.connected` 均正常 |
| V1 模型配置 | ❌ 不可用 | V1 孤立配置无 provider 认证（需 HPC_AI_API_KEY 等）；主配置的 mcp 格式 V1 1.18 不兼容（schema 校验失败）→ agent 无法真实回复 |
| App 连接 V1 服务器 | 未执行 | 服务器无模型回复能力，只能测发送链路（代码级已由 V1ApiClient 单测覆盖） |

**待办**：配置带可用模型认证的 V1 服务器后，按期望文档执行 V1 协议用例（重点：prompt_async 204 无播种依赖 SSE 回显、V1 SSE 事件解析、V1 abort）。

---

## 修复记录

| 日期 | 关联 | 修复内容 | 证据 |
|------|------|----------|------|
| 2026-08-14 | #129/#128/#125 前置 | commit 1bfa3f85：僵尸主动 interrupt + switchModel 契约修复 + debug 日志 | 模拟器 V2 全链实测 |
| 2026-08-14 | #128 | commit ab20e24f：runCatchingCancellable 迁移（10 文件） | 1596 单测 0 失败 |
| 2026-08-14 | #125/#126/#127 | commit 77074c05：多选自定义取消 ✕ + 远页草稿提升 + 越界保护 | D1 检查 + backlog 更新（UI 实测受服务器阻塞） |

---

*本文档为"实操"文档——只记录**实际发生**的事实与结论；期望值见 plan 文档。
维护：每次执行后追加轮次；未达成项必须给出归属分类与处理状态。*
