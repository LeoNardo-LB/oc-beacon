# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#247**。

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

## P1 — 核心功能需求

- [ ] **#154 上报增强：崩溃后自动提示 + secret gist 全量日志附件** `ui` `data`
  - 2026-08-23 评估（#151 两轮 E2E 全绿触发）：用户定规**两半均继续缓**——崩溃提示基建已齐（recordCrash→FATAL 持久化）只差启动提示 UI；gist 需 App 加 Gists 权限+重新授权，正文 20+3 上下文实证够分诊
  - 复评时机：beta 线上跑出真实报告后再看（崩溃提示优先级高于 gist）
  - → `docs/journal/2026-08-21-error-report-github.md` · `docs/journal/2026-08-23-beta-readiness-review.md`

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - 提 PR 前提（用户定规）：本地定位官方源码 → 修复 → 完整测试（含 E2E+交叉验证）→ 人工测试 → 才可提交
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`

## P2 — 优化与锦上添花

- [ ] **#235 Compose 稳定矩阵维持——beta 全家稳定后解除** `deps`
  - 2026-08-27 完整定因（0775582d）：material3 1.5.0-alpha26 经原子组约束拉 **整组** Compose（runtime/ui/ui-text/animation）到 1.12.0-beta01——08-20 丝滑基线自此未运行过；alpha26 的 Surface/FAB 还调 ui 1.12 独有 graphicsLayer 签名（LayerOutsets），与稳定 ui 二进制冲突（滚动 FAB 重组即 NoSuchMethodError）
  - 现状：material3 回 BOM 1.4.0 + eachDependency 四组（ui/runtime/foundation/animation）对齐 1.11.2；FAB 菜单已稳定 API 复刻（ChatFabMenu.kt，morph 动画简化）
  - 解除条件：material3 稳定版收录 FloatingActionButtonMenu/ToggleFloatingActionButton **且** compose 1.12 全家稳定发布 → 先解除 eachDependency 试跑真机（重点：流式滚动手感 + FAB 全功能），通过后删收敛块

- [~] **#238 C1 ServerDialect 剩余 5 域收编（File/Provider/System/Terminal/Shell）——五域全部完成** `refactor` `api`
  - C1-4～C1-8（2026-08-27）：五域 43 处逐方法 if 全部清零，替换为每域单点 pick；V1/V2ApiClient 实现全部 7 个域接口；Shell V1 常量降级下沉 V1ApiClient；契约测试新增 17 用例
  - 验证：每域编译+契约测试绿；全量单测 + assembleDevDebug 绿；5 域目录 isV2 残留=每文件恰 1 处（pick 本体）。待 V6：V1/V2 服务器各做一轮日常操作冒烟（连接/消息/文件/终端/shell）
  - 决策与先例见 2026-08-26 架构走查（候选 1）+ Session/Message 试点 · 逐域清单见 docs/research/2026-08-27-backlog-recheck-158-238-243-245.md
  - 2026-08-27 复核：43 处精确成立（File12/Provider13/System8/Terminal6/Shell4，逐 file:line 清单见 docs/research/2026-08-27-backlog-recheck-158-238-243-245.md）；Shell 域收编需先定 V1 常量降级位（同 Session 域 backgroundSession 模式）；建议顺序 System/Terminal→File/Provider→Shell

## P3 — 观察与低价值改进

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - 八轮复核：向前导航箭头=会话切换路由（EventCard.kt:139/SyntheticNotificationCard.kt:128）**不经过 JumpMaskOverlay**（蒙版仅服务快速定位/定位卡跳转）；箭头路径 15/15 即时 dump 满内容未复现——历史 8% 样本若来自蒙版路径，后续探测应改走「快速定位」抽屉跳转；采样功效不足断言已修
  - → `docs/journal/2026-08-20-queue-todo.md` · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`

- [~] **#243 同色巨型日志气泡连续堆叠易读作「消息重叠」——合成卡去重已落地（×N）** `ui` `data`
  - #234 二轮取证证伪渲染层重叠（像素级零越界）；八轮复核维持证伪，现象转化为「同色紧凑卡连排+表头重复」
  - 去重已落地（2026-08-27 用户裁决「完全相同内容显示 ×N 即可」）：syntheticDedupKey（仅 shell 卡，易变 id 不参与）+ 连续同键首张保留 ×N、其余抑制；SyntheticDedupTest 6/6；真机 E2E 全自动通过（服务器注入 3 连同命令 → 1 卡「后台命令完成 ×3」+ 保留卡可展开，历史非连续卡不误折叠）
  - 待 V6：×N 标签观感。回合内 tool 卡连排为另一表面未去重（如需另立卡）；溢出防御扫漏 DiffHelpers/QuestionPartContent 已补 clipToBounds
  - → `docs/journal/2026-08-27-event-card-unification.md` §取证 · §八轮补九 · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`


- [ ] **#245 巨型消息区下滑翻旧偶发「拖不动」——方向不对称滚动死帧** `ui` `sse`
  - 手势阶梯实验（e234g-REPORT）+ 六轮两次现场同帧复现：数屏长单项区域下滑帧字节级静止（方向不对称、moveCount 完整送达）；四轮 T2 一度判全档失效后更正为测量假象嫌疑——维持「嫌疑+未确证」；#246 自愈装机后仍观察一次，疑独立机制
  - 2026-08-27 八轮巨帧取证（PtrDiag 探针链）：冷启动进场窗口拖动全灭；平台把 2.5s 拖动合并成 2-3 巨帧（travel 完整）送达、列表认领却零消耗（consumed=0）；锚点战争/闩锁/输入缺失三族排除；v1 连接器形态机制性空转（勘误入档）、v2 Initial 隧道分块无效——下一步=守卫内打点看 dispatchRawDelta 返回值定界 app/框架
  - 八轮复核（research，6 冷启动全「冻」）：**判词修正**——自动化样本全部是「贴底 + 朝更新方向拖」= 范围尽头语义（本不该滚，无回弹反馈加剧死感），离底同手势即恢复（1399-1421px 全通）——即边缘语义而非 #245 本体；自动化未能复现「历史区中段死帧」；下一步=真人现场复现时记录列表位置（是否贴底）+ 录屏，再决定是否需要守卫内打点
  - → `docs/journal/2026-08-27-event-card-unification.md` §手势阶梯 · §八轮/#245 · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`