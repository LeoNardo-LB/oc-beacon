# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#206**。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

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

**Journal**：每个工作批次一个 `docs/journal/YYYY-MM-DD-<英文kebab名>.md`，**开工时创建**，过程中取证/验证证据直接写入 journal（卡片全程保持 ≤3 行）；完结条目当场迁入，原文保留不压缩不删改。可复用的蒸馏结论提炼进 `docs/research/`，journal 只记执行与证据。**新 journal 术语三原则**：①叙述段用 CONTEXT.md 规范名；②证据引用豁免（logcat 行、SSE 事件名、i18n key、标识符原样保留）；③规范名首现带英文原词，编号遵循 [numbering-charter](docs/numbering-charter.md)。

---

## P0 — 主流程阻塞

> （空）架构评审批次 2026-08-21–08-23 完结：六候选 + 顺手清理全部通过用户验收（2026-08-22 批次末 17 项 15 过，两问题已闭环：①MIUI 渠道默认关闭非 app bug、②升级为 #189 换件并于 08-23 验收通过）→ `docs/journal/2026-08-21-arch-review-deepening.md` · `docs/journal/2026-08-23-acceptance-closeout.md`

## P1 — 核心功能需求

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - 2026-08-23 评估（#151 两轮 E2E 全绿触发）：用户定规**两半均继续缓**——崩溃提示基建已齐（recordCrash→FATAL 持久化）只差启动提示 UI；gist 需 App 加 Gists 权限+重新授权，正文 20+3 上下文实证够分诊
  - 复评时机：beta 线上跑出真实报告后再看（崩溃提示优先级高于 gist）
  - → `docs/journal/2026-08-21-error-report-github.md` · `docs/journal/2026-08-23-beta-readiness-review.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`



## P2 — 优化与锦上添花

> （空）#191 已完结验收（实现 5693ddb6 + 单测 24/24 独立复跑 + 真机降幅 ≈93% + 用户关闭 2026-08-23）→ `docs/journal/2026-08-23-beta-readiness-review.md` §三

- [~] **#201 Tier C-1：wire 层 @SerialName——裁决零改名 + 交付 wire 兼容矩阵** `refactor`
  - 149 属性名全部已符规范形态（API 原词 camelCase）；改名集为空；WireCompatMatrixTest 9 测试锁全 wire 名（大写 ID 族+snake_case 族+多态回环）
  - → docs/journal/2026-08-24-tier-c-contract-renames.md

- [ ] **#202 Tier C-2：DataStore PreferencesKey 重命名（50 键）** `refactor`
  - 需迁移代码（unread v2 值域迁移为先例）；错失即用户设置全量丢失
  - → 同上 Tier C

- [~] **#203 Tier C-3：Room 实体/列重命名（5 实体）——裁决零改名** `refactor`
  - 术语表已裁 archive_buckets/pending message 为规范名本体（C07 裁决保留现名）；列名审计 21/21 无冲突；待用户验收零改名裁决
  - → 同上 Tier C · docs/journal/2026-08-24-tier-c-contract-renames.md

- [ ] **#204 Tier C-4：i18n key 改名（category 族→tag 等）** `refactor`
  - R.string 903 引用点 + maestro 34 flows 锁文案联动；CI i18n 检查可兜底
  - → 同上 Tier C

- [ ] **#205 Tier C-5：intent extra/导航参数改名（22+27 处）** `refactor`
  - debug intent #132 外部已配置依赖 extra 名；零自动化覆盖，需真机验证（houji）
  - → 同上 Tier C

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
