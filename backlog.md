# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。

**编号**：全局递增，不回收。下一编号：**#169**。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |

**状态流转**：代码写好但未验证不等于完成！要求完成需求、自行验证、用户验收通过之后才算完结；完结即迁移（见首段）。

| 状态 | checkbox | 含义与流转规则 |
|------|----------|------|
| **进行中** | `[ ]` | 需求已登记或正在开发。开发完成后跑通自动化验证（编译/单测/i18n/assemble）并自行完成可覆盖的验证后 → 转「待验证」 |
| **待验证** | `[~]` | 代码完成、自动化验证通过，但**用户人工/真机验收未完成**。后续 Agent 看到 `[~]`：向用户给出验证清单并请其执行；通过 → 转「已完成」并**当场迁移**；发现问题 → 改回 `[ ]` 进入修复 |
| **已完成** | `[x]` | 仅迁移瞬间存在的过渡态——迁移完成后本文件不含任何 `[x]` 顶层条目（check 脚本强制） |

**Tag 标签体系**：标记相关领域便于批量排查；现有 Tag 不足以描述则新增。

| Tag | 说明 |
|-----|------|
| `crash` | 崩溃 / 闪退 |
| `ui` | 界面显示、组件缺失、布局问题 |
| `data` | 数据展示不准确、数据源疑问 |
| `sse` | SSE 连接、事件推送相关 |
| `session` | 会话管理相关 |
| `permission` | 权限请求、审批相关 |
| `security` | 安全与隐私（明文凭据、泄漏、合规） |
| `refactor` | 重构、死代码清理、分层修复 |

**Spec**：满足「有非显然取舍需留档」或「跨会话实现需完整上下文」其一 → 在 `docs/specs/` 写 `YYYY-MM-DD-<名称>-design.md`（spec 是权威，卡片只留摘要+链接）；实现并用户验收后移入 `docs/archive/specs/`，同步更新 spec 头部状态行与卡片引用路径。**归档 spec 定期清理零外部引用者**（git history 永久可找回）。简单需求不写 spec。

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。

---

## P0 — 主流程阻塞

（当前无未决项）

## P1 — 核心功能需求

- [ ] **#155 会话内提示音：被抑制的系统通知转为提示音+震动，严格镜像系统通知策略** `ui` `sse`
  - 前台会话 turn 结束/权限/问题/错误事件现状零反馈 → 补提示音+震动，策略完全镜像系统通知四层静音矩阵；错误 streak 只响第一声；零新增设置项（含 VIBRATE 权限与通知侧 streak 去重）
  - spec 已定案（grilling Q1–Q12 + F1–F5），实现前必读；模拟器无音频输出，维度 5 必须真机实测
  - → `docs/specs/2026-08-21-in-session-audio-feedback-design.md` · `docs/journal/2026-08-21-in-session-audio-feedback.md`

- [ ] **#151 错误日志 GitHub 上报（手动触发 + 指纹查重 + 重复评论）** `ui` `data`
  - Diagnostics 屏把 ERROR/FATAL 报到 GitHub issue；已报过的追加环境差异评论；强制预览可编辑；GitHub App device flow 一次授权永久有效
  - 前置依赖：#152、#153、维护者注册 GitHub App（spec §Further Notes 操作清单）
  - → `docs/specs/2026-08-21-error-report-github-design.md` · `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#152 前置：日志分级修复（SSE 灌水/双日志/丢堆栈，审计 15+ 处）** `sse` `refactor`
  - 重连风暴灌水（挤出真实错误）、同一失败双日志、per-event INFO 遗漏网、7 处 `e` 缺 throwable 等——不修则 #151 的"最近 20 条错误"全是重连噪音
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#153 前置：release CI 留存 R8 mapping.txt artifact** `refactor`
  - release.yml 只传 APK，mapping.txt 随 runner 销毁——用户混淆堆栈永久无法还原；加 artifact 上传（90 天保留）
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - spec §Out of Scope 明确后置项；触发条件：#151 落地并稳定后评估
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`

- [~] **#150 V1 连接速度慢于 beta.4 误判 V2——探测复用 + 预加载/SSE 并行化** `perf` `v1`
  - 已实现并合回 master（25927de5）：V1 冷首连 ~3×（81-138ms→25-43ms），模拟器 E2E 5 项全过（含升级场景真机复现）
  - 剩余：真机复验（2026-08-20 真机优先方针）+ 回复 upstream issue #1
  - → `docs/journal/2026-08-21-issue1-v1-speed.md`

## P2 — 优化与锦上添花

- [~] **#162 真机滚动"还是卡"→ 帧级取证三层根因全修——待用户验收（GKD 重开场景）** `ui` `perf`
  - 三根因全修：重组风暴（慢拖 janky 41.7%→0.88%）、巨型消息分片（p95 400ms→9ms 级）、GKD 无障碍税（环境因素，App 内无低风险修复）；GKD 关闭场景用户已验收"十分丝滑"
  - 遗留条件：GKD 重开且卡顿回归时按根因③结论处置
  - → `docs/journal/2026-08-20-scroll-jank-investigation.md`

- [~] **#163 真机滚动两问题（滑过气泡卡顿 + fling 下跳）——已修 f03a89d5，待验收手感** `ui` `perf`
  - 三件套：视口预解析驱动 + SafeFlingBehavior 限速 + 解析移出主线程；RESIZE 11→0、fling 自然跑满、逐帧异常 6→0
  - 待用户验收：滚动手感（限速档位/预解析距离可调）
  - → `docs/journal/2026-08-20-scroll-stability.md`

- [~] **#164 主对话抽屉高度统一（min = max = 75% 屏高）——待验收观感** `ui`
  - 四抽屉 + SystemPromptDialog 固定 75% 屏高；真机 E2E 像素级全 PASS（顶边逐像素一致、空内容撑满）
  - 待用户验收：空内容抽屉底部留白观感
  - → `docs/journal/2026-08-20-drawer-height-75.md`

## P3 — 观察与低价值改进

- [~] **#156 Room 缓存行 tokens 持久化缺口——已修，待用户验收** `data` `storage`
  - c71ac4ec：SSE_PRIORITY 合并 CAS 检测 tokens 变更→增量落库；真机 E2E 复验 PASS（44/45 落库，19.1 万行 logcat FATAL=0）
  - → `docs/journal/2026-08-19-final-regression.md`

- [~] **#157 离线态终端 sessionDirectory=null + 输入框层级缺失——观察①已修待验收** `terminal` `edge-case`
  - 观察① reloadDirectory 兜底已修（de96758c）；观察②定性为不可达路径关闭（离线冷启停在连接页无法进会话）
  - → `docs/journal/2026-08-20-scan-round2.md`

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [~] **#159 jumpLockActive 镜像标志应从 JumpNavigationController.phase 派生——核心已修，剩纯清理** `arch` `jump`
  - fire-time 门控已直读 isJumpInProgress 真源（88774278）；剩启动 key 与 B-F2 提交门控（带 2s 时窗语义需一并设计），删除全部手工写点后收口（~1h）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [~] **#160 LeakCanary 报 OpenCodeConnectionService$LocalBinder 泄漏——已修，待用户验收** `leak` `service`
  - d8331596：孤儿 job 取消 + SSE takeWhile 守卫 + connect 入口守卫 + HomeViewModel 卫生项（红绿验证，全量 1758 绿）；结构性根治（Router 抽取）按需另立项
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#161 离线时顶栏 context 圆环隐藏** `data` `ui`
  - contextWindow 仅存内存、依赖会话级 REST；现状代码注释已声明可接受，仅当期望离线可见才做（落库方向，~2h）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#165 长文本 Part 级 semantics merge（GKD 税缓解，条件性价值）** `perf` `a11y`
  - GKD 已长期关闭主收益消失；仅 GKD 用户重开才有价值。A/B 中止线已定：GKD 关 p50 回退 >2ms 或 p95 改善 <15% 即 abort（~3h）
  - → `docs/journal/2026-08-20-scroll-jank-investigation.md`（提升自该批子条目）

- [ ] **#166 RaceProbe 复现取证待用户执行** `race`
  - 若跳转叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可重放定位）
  - → `docs/journal/2026-08-21-race-audit-round6.md`（提升自该批子条目）

- [ ] **#167 overlay HUD 真机授权走查** `dev-infra`
  - 悬浮窗权限授予 + overlay 显示/dropCount 读数验证（代码已交付 dc57cba0，未真机走查）
  - → `docs/journal/2026-08-20-quick-jump-round4.md`（提升自该批子条目）

- [ ] **#168 慢拖残余 ~18ms 偶发尖刺——最低优先级** `perf`
  - F5 后残余（draw 4-8ms + input 3-5ms，12 轮仅 10 条）；「预取 idle_frame」候选已否证；release 口径 p95 7.9ms 已低于感知阈值，再深挖方向为 draw/input 相位本身（~2h）
  - → `docs/journal/2026-08-20-perf-monitoring-round3.md`（提升自该批子条目）
