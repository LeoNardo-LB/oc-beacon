# 2026-08-31 goal+token-ring+slash 批次（backlog #285 · 工作区 oc-beacon-286 分支 286-goal-token-slash）

> 本批次三任务（用户裁决 D1/D2）：Goal 特性 / token 环对齐 Web / 斜杠命令面调研实现。
> 注：工作区并发事故（另一代理在共享 checkout 反复 git reset，本批次全部重做后迁入隔离 worktree 完成）——
> 完整记录见本文件 §0。

## 0. 并发事故与隔离（为何在 worktree 完成）

- 主 checkout（oc-beacon）会话期间出现另一代理的并发编辑：初始仅 DshEventMapperTest.kt 修改，随后 13+ 文件被改写
  （ContentHitNavigation 特征 + agent-error message 键修复 + sessionErrorEvents），HEAD 编译失败（ChatScreen.kt:900 initialJumpTarget）。
- 我的第一轮实现（主 checkout）在 21:47 被整体抹除（git status 从 40 文件坍缩到 2；GoalSheet.kt/ContentHitNavigation.kt 消失；无新 commit）。
- 处置：`git worktree add -b 286-goal-token-slash oc-beacon-286 HEAD`，全部工作在隔离 worktree 重做；
  主 checkout 不再写入。交付物 = 分支 286-goal-token-slash（4 commits，见 §6）。
- 工具教训：dsh read 对 >~46KB 文件静默截断至 ~943 行（totalLines 正确）——read→write 回写会把大文件截断；
  本批改用 tools.edit 手术式替换 + `git show HEAD:file` 重建 + wc -l/tail 事后校验。

## 1. Task 1 · Goal 特性（用户裁决 D1）

### 1.1 事实源（官方包 dsh-goal/dsh-host-apiproxy）
- goal 域突变型六方法（goals.d.ts）：create/edit/pause/resume/complete/clear；payload {sessionId, objective?, maxGoalRounds?, ref?}。
- 读侧面 = 'goal' session 投影（无 goal.get）；mutation 回执只带新 CAS ref，状态走 goal/change 事件 / session/projection 帧整值。
- GoalProjection = {goal: {id, revision, objective, phase: active|paused|blocked|complete, blockedReason?: {code,message}, maxGoalRounds}, roundsStarted, createdAt, updatedAt}；clear 后投影=null。
- 移动端映射：DshApiClient 六 RPC + DshEventMapper goal/change + key=goal 投影 → SessionGoalChanged → SessionEventHandler 折叠 Session.goal（last-wins 全量快照）；CAS ref 取当前投影。

### 1.2 UI（FAB 目标 sheet）
- ChatToolbarEntry.GOAL 第五入口 + FAB run-dot 角标（blocked 用 error 色）+ 菜单项 phase 色角标。
- GoalSheet（新文件）：active/paused/blocked 详情（phase 标签 + objective + rounds N/M + blockedReason 内联 message）+
  pause(active)/resume(paused)/edit(表单)/clear 按钮；无 goal / complete → 创建表单（objective + maxGoalRounds）——完成态不渲染条目（Web 语义，面板内一致）。
- 能力位 goalSupported（DSH=true，OpenCode V1/V2=false → GOAL 入口零渲染）。

### 1.3 验证（自动化）
- DshEventMapperTest：goal/change create 整值 / blocked 内联 reason / clear→null / 投影 key=goal null 与对象 / 四投影键（contextPressure/contextBreakdown/sessionStats）映射——新增 5 测试 + 2 旧断言修订。
- DshApiClientTest：goal.create/edit/pause/resume/complete/clear 的 URL/payload/CAS ref/cleared 回执 + commands/list 数组通道 + 会话缺席空列表——新增 6 测试。
- 待验证：真机 Goal sheet 交互（创建 → 详情 → 暂停/恢复/编辑/清除；blocked reason 内联；角标显示）——归委派方。

## 2. Task 2 · token 环对齐 Web（用户裁决 D2）

### 2.1 事实源
- dsh-token-meter projections：contextPressure {pressureTokens?, projectedTokens?, contextWindow?}（独立 last-wins，非原子观测）；
- contextBreakdown {systemTokens, toolsTokens, messageTokens}（启发式构成）；dsh-session-stats：sessionStats {turns,steps,llmMs,toolMs,ttftMs,ttftSteps,decodeMs,decodeTokens}。
- Web 环（dsh-client-ui-conversation ContextMeter）：contextOccupancy = projectedTokens ?? pressureTokens / contextWindow，任一缺席→环不渲染；
  面板显示 ~used/window + system/tools/messages 分段条（宽度=percent×key/total）。
- Web formatTokens（client.js:2879）：<1000 原数；缩放值 >=100 整数、<100 一位小数——517 / 12.2K / 517K / 1.2M。

### 2.2 实现
- 解析 contextPressure/contextBreakdown/sessionStats 入 Session 投影态（DshSessionMapper 基线 + DshEventMapper 帧 + SessionEventHandler 折叠 + defendSessionReplacement 防抹除）。
- ChatTopBar 环：分子 projectedTokens??pressureTokens、分母投影 contextWindow（替换 llm.models 目录源；打开即用投影——
  投影缺席 → 整环不渲染，删除 no-window 裸计数 chip 分支（Web 语义）；OpenCode 走既有 llm.models+token 统计路径）。
- ContextDetailDialog 超集追加：~used/window 行 + system/tools/messages 分段条与图例（webFormatTokens）。
- webFormatTokens + ChatFormattersWebTokensTest（12.2K/517K/1.2M/半点向上）。

### 2.3 验证
- ChatFormattersWebTokensTest 通过；ChatTopBar/对话框编译通过。
- 待验证（归委派方）：真机/模拟器看 DSH 环（pressureTokens 125148/contextWindow 1000000 实例）与弹窗分段条。

## 3. Task 3 · 斜杠命令面调研 + 按事实实现

### 3.1 调研实录（2026-08-31 活体只读探测 127.0.0.1:3080）
- **早前 404 证据勘误**：本部署 `/api/commands/list` **可用**——POST {type:client-request, rpcId, method:commands/list, payload:{args:{agentId:<sessionId>}}}
  返回 200 + value=[CommandDescriptor[]]：compact/export/feedback[input.hint]/goal[input.hint+images]/permission[input.hint]/plan[input.hint+images]。
- 子代理会话 → agent-busy（“owned by subagent routing”）——枚举切面只对主会话有效（与 Web CommandDirectory 一致：subagentAddress → 空列表）。
- 官方 Web 补全数据源 = 同一 `remote.commands.list(sessionId)`（dsh-client-ui-commands CommandUiRuntime → CommandDirectory，监听 commands/change 失效重取 + agent-preset/selected 刷新）。
- 官方包的 commands/list 是 typert 描述符（dsh-commands TYPERT_REMOTE），value 为**数组**→ 需 callJson（现有 call() 要求对象值）。

### 3.2 实现
- DshApiClient.listCommands(sessionId)（commands/list 数组值走 callJson）+ 泛型 executeCommand 组装 "/name args" 走 commands/execute 既有通道（/permission 先例）。
- 能力位 commandsSupported 转真（DSH）→ 输入 / 触发建议面板；input.hint 非空命令选择即填入输入框（对齐 Web 选即填）。
- 命令加载链 threading sessionId（ModelConfigDelegate/ManageAgentUseCase/AgentRepository/AgentRepositoryImpl/SystemApi 2-arg）。

### 3.3 结论 + 缺口
- 有可用 RPC → 已按事实实现（无需静态本地列表兜底）。
- 残留缺口登记 **backlog #285**：懒建会话/首连期命令列表空（loadCommands 单次执行）、commands/change 事件未订阅、DSH 无技能域。

## 4. i18n
- 新增 24 key（goal 18 + context 6）→ 英文源 + 14 翻译；`bash scripts/i18n-check.sh` PASSED（759 keys × 14 languages）。

## 5. 回归
- 全量单测：见 §6（末次全量数字在提交前记录）。

## 6. 交付（分支 286-goal-token-slash，基于 e5581a36）

| commit | 内容 |
|--------|------|
| 163b029d | feat: DSH goal 域 + 上下文投影——模型/Session/SSE/RPC/commands.list 通道 |
| 2e4a4d58 | feat: 会话内 Goal 面板（FAB + GoalSheet + 完成态空态 + 角标） |
| 8b47a40d | fix: 上下文环对齐 Web（投影分子/分母 + 弹窗超集 + webFormatTokens） |
| 96d2930f | feat: 斜杠命令补全（DSH commands/list 转真 + 选择填入/执行） |

- 全量回归命令（worktree）：`./gradlew :app:testDevDebugUnitTest --rerun` → **2455 tests / 0 failures / 0 errors**（248 类；基线 2439+ 之上 +16 新测试）
- assembleDevDebug 门：通过（APK 产出）
- i18n：`bash scripts/i18n-check.sh`（PASSED）；backlog：`bash scripts/backlog-check.sh`（通过）
- 真机验证项（归委派方）：GoalSheet 交互、环显示、斜杠补全——见 §1.3/§2.3/§3.3 待验证清单。
