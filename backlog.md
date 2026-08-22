# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。

**编号**：全局递增，不回收。下一编号：**#193**。

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

> （空）架构评审批次 2026-08-21–08-23 完结：六候选 + 顺手清理全部通过用户验收（2026-08-22 批次末 17 项 15 过，两问题已闭环：①MIUI 渠道默认关闭非 app bug、②升级为 #189 换件并于 08-23 验收通过）→ `docs/journal/2026-08-21-arch-review-deepening.md` · `docs/journal/2026-08-23-acceptance-closeout.md`

## P1 — 核心功能需求

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - spec §Out of Scope 明确后置项；触发条件：#151 落地并稳定后评估
  - → `docs/journal/2026-08-21-error-report-github.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`




## P2 — 优化与锦上添花

- [ ] **#191 L2 stale 等待态无限循环——pending-input 会话 5s 轮询风暴自适应降频** `session` `perf`
  - 根因：等待提问/子会话期服务器恒报 busy + zombie guard 跳过 + RestValidation 不刷 lastEventAt → 5s 循环无终止（V1/V2 同构：V1 二进制 + V2 真机双实证；状态本身正确，错在观测节奏，24 WARN/min + 12 REST/min）
  - 方案 B 定案：REST 确认等待态打标 waitingConfirmedAt → checkStaleness 60s 窗口内跳过 → SSE 真实事件清标；V1/V2 通吃（打标条件无版本分支），日志/请求降 ~92%
  - → `docs/journal/2026-08-23-issue-cleanup-triage.md`

- [ ] **#192 双 FAB 会话级滑动隐藏/展示：左（跳到底部）左滑收起→左缘半透明拉杆；右（菜单）右滑收起，展开态先收拢成按钮** `ui`（实现完成但真机渲染受阻，二分排查中）
  - 定案（2026-08-23 grilling 七问全结，spec §2 D1–D8）：仅会话内生效（不落盘）/ 主子会话独立记忆 / 手动隐藏优先于自动显隐 / 拉杆点按+拖拽双通道 / 菜单两段式 / 右拉杆保留角标
  - → `docs/specs/2026-08-23-fab-swipe-hide-design.md` · `docs/journal/2026-08-23-acceptance-closeout.md`

## P3 — 观察与低价值改进

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#161 离线时顶栏 context 圆环隐藏** `data` `ui`
  - contextWindow 仅存内存、依赖会话级 REST；现状代码注释已声明可接受，仅当期望离线可见才做（落库方向，~2h）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#166 RaceProbe 复现取证待用户执行** `race`
  - 若跳转叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可重放定位）
  - → `docs/journal/2026-08-21-race-audit-round6.md`（提升自该批子条目）

- [ ] **#168 慢拖残余 ~18ms 偶发尖刺——最低优先级** `perf`
  - F5 后残余（draw 4-8ms + input 3-5ms，12 轮仅 10 条）；「预取 idle_frame」候选已否证；release 口径 p95 7.9ms 已低于感知阈值，再深挖方向为 draw/input 相位本身（~2h）
  - → `docs/journal/2026-08-20-perf-monitoring-round3.md`（提升自该批子条目）

- [ ] **#184 未读水位线 globalMax 跨服务器混合——多服务器时钟偏差场景** `data`
  - markAllSessionsRead 对不分服务器的水位线 map 取全局 max（SessionListViewModel:423-430）——多服务器时钟不同域时一键已读可能错杀/漏杀红点；#171 grilling Q6 定案：不动存储 schema，登记不动
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [ ] **#185 V1/V2 god-client 拆解（终局债务，显式不做）** `refactor`
  - V1ApiClient(72 方法)/V2ApiClient(84) 全域 god-client + 7 门面 78 处 if 分发——#172 grilling Q1 定案：seam 已在门面 interface 正确收敛，拆轴属内部代码组织（22 测试文件重写 + 缓存式适配器版本竞态），显式登记不拆
  - → `docs/journal/2026-08-21-arch-review-deepening.md`
