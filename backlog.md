# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#216**。

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

- [~] **#215 聊天流卡片体系统一：容器语言 + 交互契约** `ui` `refactor`
  - 三批完成（bcc435f1/998e32dc/6dedc566）+ 验收遗留修复完成（a4eedab6）：展开/收起两方向视口稳定（矩阵 6 格全 dy=0，动画保留；定因=倒序 LazyColumn 对 item 内高度变化零锚定修正·存量机制·非批次引入；修法=toggle 修正窗 + scrollToBeConsumed 逐帧注入，request-position 通道被测量回写丢弃的定因存档 journal）→ 待用户 V6 手感验收
  - → `docs/specs/2026-08-24-card-unification-design.md` · `docs/journal/2026-08-24-card-unification.md` §验收反馈·一

> （空）#191 已完结验收（实现 5693ddb6 + 单测 24/24 独立复跑 + 真机降幅 ≈93% + 用户关闭 2026-08-23）→ `docs/journal/2026-08-23-beta-readiness-review.md` §三
>
> （空）Tier C 五卡 #201–#205 已完结（2026-08-24 用户授权代验收官：实改 #204/#202 + 零改名裁决 #201/#203/#205，自动化全绿+真机证据链）→ `docs/journal/2026-08-24-tier-c-contract-renames.md`
>
> （空）#207 已完结验收（2026-08-24 用户验收通过：三态判定 + rememberSaveable 锚点；单测 1917 绿 + 真机活体单调不归零 + 野生实例静态化）→ `docs/journal/2026-08-24-thinking-timer-scroll.md` §完结迁移
>
> （空）#208 已证伪闭卡（2026-08-24 同日勘误：登记时滑动方向搞反致三项主张全部误判——正确方向复测 130 条历史全程可翻、loadOlder 补载正常、假 id 不渲染是服务器权威 upsert 正确行为）→ `docs/journal/2026-08-24-thinking-timer-scroll.md` §#208 证伪闭卡
>
> （空）#161 已闭卡（2026-08-24 用户裁决离线隐藏为可接受行为，不修复；调研确认机制主张全部成立，方案草图留存 journal 备查）→ `docs/journal/2026-08-24-p3-quad-research.md` §#161
>
> （空）#210 已修复转待验证（2026-08-24 jdb 取栈定音三根因：①MIUI「后台弹出界面」权限随卸载重置→DeviceGuard 拦 HiltEntryActivity 启动致 startActivitySync 永久等待=挂死本体，非代码回归；②测试 Activity 无语言覆盖，系统 locale 回 zh-CN 后英文断言漂移；③#209 test3 seed id 错。修=授权脚本化+attachBaseContext en-US+seed 一行。ChatInteractionTest 全类 6 过 + 单测 1923 绿；#209 插桩补跑同步完成）→ `docs/journal/2026-08-24-p3-quad-research.md` §#210 修复执行

> （空）#211 已完结验收（2026-08-24 用户裁决清理：HiltComponentActivity.attachBaseContext 强制 en-US 单点覆盖 19 类，零生产代码；主会话全量复验 135 测 3 败、locale 族 27 败全灭无挂死，与同事 14 类复验双证据闭环；残留三例分立 #212/#213/#214）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移·二批
>
> （空）#212 #213 已完结验收（2026-08-24 主会话修复+真机 OK 9 tests：MigrationTest 补挂 MIGRATION_3_4 对齐 DB v4 / 空态断言对齐 KT10a session 术语——均为「代码先行测试未跟」机械勘误）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移·二批
>
> （空）#214 已完结验收（2026-08-24 定因=测试时间炸弹：硬编码崩溃报告 ts 越 LogStore 21 天 ERROR retention 边界被内联 prune 插入即清除（hist db=0 vs fresh db=2 实验定音），非生产回归；sharedTimestamp 改取系统时钟零生产改动；真机本类 OK(1) + **全量 OK(135) 08-18 以来首次全绿**）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移·三批

## P3 — 观察与低价值改进

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - → `docs/journal/2026-08-20-queue-todo.md`

- [ ] **#166 RaceProbe 复现取证待用户执行** `race`
  - 若跳转叠放仍出现：`am start --ez debug_race true` 后复现，`adb logcat -d -s RaceProbe` 导出（时序可重放定位）
  - → `docs/journal/2026-08-21-race-audit-round6.md`（提升自该批子条目）

> （空）#168 已证伪闭卡（2026-08-24 devRelease 真机实测：720 帧 ≥17ms 率 0.00%、p99 8.5ms 低于 8.33 预算——devDebug 尖刺系 47% debug 构建税放大，release 无感知；原始数据归档 perf-evidence/r168-release-20260824）→ `docs/journal/2026-08-24-p3-quad-research.md` §#168

> （空）#184 已完结验收（2026-08-24 用户验收通过：四步回归确认——造未读→一键已读全灭→强杀重启不复活→新消息红点重现；修复 7bd04c11 作用域化 markAllSessionsRead，单服务器无行为变化）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移
>
> （空）#209 已完结验收（2026-08-24 用户验收通过：日常使用 context 圆环正常显示；修复 caea2b30 删死字段+恒假分支，单测 1923 绿 + test3 真机插桩 OK）→ `docs/journal/2026-08-24-p3-quad-research.md` §完结迁移
