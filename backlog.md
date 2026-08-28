# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#258**。

- [ ] **#257 历史 shell 卡输出体空白——跨进程输出续读链缺失（V6 二轮顺带发现）** `ui` `data`
  - 现象（2026-08-29 真机）：重启后的历史卡（fabrepro/fabfix/convfix 等 5 张）命令行在、输出体空白——ShellJobsStore 内存态清空后 provider 三级链（store→parts 回填→REST /api/shell/:id/output）未兜住
  - 嫌疑：Room 落库时 output 未持久化（早期映射）或分页窗口外 parts 缺 output 字段；早于换道手术存在，非本轮回归
  - 修复方向：落库时持久化 output 全文 / 卡片组合时按 shellID REST 续读；→ `docs/journal/2026-08-27-event-card-unification.md` §二十六轮

- [ ] **#255 shell 模式触发健壮化——前导空白 + 全角「！」容许（开发过程发现，已修复，待验收）** `ui`
  - #252/#253 E2E 过程实证：「空格 + !cmd」（E2E 驱动误触空格键）整体回落普通消息（uiautomator 直读字段前导 0x20）；中文 IME 环境「!」偶发落全角「！」（条带 exit 127 实证）——真人同样可触
  - 修复：ChatScreenBottomBar 检测与发送兜底双路径 trimStart + 半角/全角「!」「！」双形态接受（drop(1) 对两者均剥单字符）；全量单测 2142/0
  - E2E：前导空白流（原失败流）修复后走 shell ✓；全角路径 adb input 不支持非 ASCII 注入不可驱动（仅真人 IME 可触发），代码级正确性评审覆盖
  - → `docs/journal/2026-08-27-event-card-unification.md` §十六轮——**用户验收后迁 journal**

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

- [ ] **#247 回合内 tool 卡连续同内容去重（#243 另一表面）——首张 + ×N 折叠落地（已修复，待验收）** `ui`
  - #243 去重已覆盖合成事件卡（×N）；回合**内部**的 tool 卡为另一渲染面（RenderableTurn 层）——2026-08-28 用户裁决「首张 + ×N」（同 #243 交互）后实施
  - 修复：`RenderItem.RepeatingTool` 渲染项 + `toolDedupKey`（工具名 + 命令/标题，易变字段与状态不入键；context/过滤工具排除）+ `collapseConsecutiveToolCards` 纯函数（跨消息折叠、卡间分隔线随折叠消失），主路径与分片路径双分支渲染 ×N 徽标；`RenderableTurnCollapseTest` 7 用例
  - 真机 E2E（V1 big-pickle）：诱导三次独立 bash 调用 → 单张 `$ echo dedup-check · 完成` 卡 + `×3` 徽标 ✓
  - → `docs/journal/2026-08-27-event-card-unification.md` §八轮补九 · §十五轮——**用户验收后迁 journal**

## P3 — 观察与低价值改进

- [ ] **#158 面板开关/跳转期间 a11y 树偶发只剩遮罩或空文本节点——维持观察** `queue` `ui` `a11y`
  - 真机 12 次跳转 1 次退化（~8%，均 ~15s 内自愈、零用户可感知影响）；与「跳转+蒙版周期」相关性高，机制未定位（候选：全屏遮罩后 semantics 刷新延迟）
  - 八轮复核：向前导航箭头=会话切换路由（EventCard.kt:139/SyntheticNotificationCard.kt:128）**不经过 JumpMaskOverlay**（蒙版仅服务快速定位/定位卡跳转）；箭头路径 15/15 即时 dump 满内容未复现——历史 8% 样本若来自蒙版路径，后续探测应改走「快速定位」抽屉跳转；采样功效不足断言已修
  - → `docs/journal/2026-08-20-queue-todo.md` · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`

- [ ] **#252 V2 `!cmd` 对话流内可见反馈——shell 卡内嵌消息流（TUI 语义，已修复，待验收）** `ui` `api`
  - #250 验收时判定为非缺陷的开放设计点：V2 会话级 shell = 后台 shell 体系（shell.created/exited → ShellJobsStore），**不产聊天消息** → 用户 `!cmd` 后聊天区无任何反馈（V1 渲染轮次卡，两方言 UX 不对称）
  - 修复（2026-08-28 用户裁决终版「类似通知那种」）：每个 job 渲染一张 **`EventCard` 通知卡本体**（Shell 完成/失败既有形态：label/图标/红描边/i18n 全现成，description=`$ 命令`，body=输出 Markdown 三级 provider），内嵌消息列表贴最新消息下方（bannerCount/reveal 接入 #222 体系 + 内容变化贴底重锚）；迭代史浮层→ShellCard→气泡包卡→EventCard；全量单测 2142/0
  - 真机 E2E：卡片长在对话流 ✓ + `✗ exit 127` 失败态 ✓ + REST 输出渲染卡内 ✓（成功态同构已演示）
  - **勘误二（2026-08-28 用户报「间隔仍有大」→ UI dump + Room 实证定音）**：V2 为每次 `!cmd` 创建 role='shell' 零 parts 信封消息，MessageSerializer 按 role 分发时 'shell' 落入 else 回退为 Message.User——原 `(as? Assistant)` 判定永不命中，空气泡（48dp/条）照常渲染，15 条占位累积 = 半屏鸿沟（dump 实证 gap 区 12 个空气泡、8dp 步进）。修复：按 `Message.role` 字符串过滤 `SYNTHETIC_ENVELOPE_ROLES`（shell/agent-switched/model-switched 一并过滤）。真机复测语义树 bounds：气泡容器底 y2081 → 通知卡容器顶 y2105，**gap = 24px = 8dp = messageSpacing 精确达标**（acc_final_8dp.png）；`SyntheticEnvelopeFilterTest` 3 用例锁回退行为 + 过滤零发射
  - 顺带发现：GET `/api/session/{id}/message` 返回 shell 条目带完整 command/status/exit/output——**V2 存在已结束 shell 的历史 API**（早前「无历史 API」判断有误）；如需跨进程恢复通知卡可评估另立卡
  - **时间线化（2026-08-28 用户两问「卡片为啥没被顶上去 / opencode 中指令执行是否对话数据的一部分」→ beta-18414 二进制源码证据定音）**：官方语义 = shell 执行 appendMessage 进会话消息历史（type:'shell' 一等公民，TUI 消息流渲染，输出注入 agent 上下文）。客户端对齐：Part.Shell 载荷入库 + 消息时间线渲染 EventCard + 钉底横幅退役 + store 观察去抖刷新。真机全链 E2E：顶上去 ✓ / 实时出现 ✓ / 跨进程持久化 ✓（「进程死卡消失」限制解除）；→ §十九轮
  - → `docs/journal/2026-08-27-event-card-unification.md` §十五轮/§十七轮/§十八轮——**用户验收后迁 journal**

- [ ] **#254 RenderSupplyCoordinatorTest.T12 负载敏感偶发——skip 早期提交竞态（测试基建）** `refactor`
  - 现象（2026-08-28 两轮全量复现）：T12「前两次 skip 不应提交」满载挂、隔离运行恒绿（12/12）；与本轮改动代码零交集（协调器未 import 被改文件）
  - 锐化诊断：Env 假时钟已注入（冻结 now）→ 稳定窗口门控非机制；早提交路径 = skip1 时 `inViewportNow || hotNearBand` 均假——锁定 `everVisiblePartIds` 标记/异步 pending 管线的负载竞态假设，需深挖 pending 入队与视口标记的先后序
  - 候选：视口标记同步性审计 / pending 入队-消费序单测化；实施前先确认偶发率（连续观察全量轮次）
  - → `docs/journal/2026-08-27-event-card-unification.md` §十五轮


- [ ] **#245 巨型消息区下滑翻旧偶发「拖不动」——方向不对称滚动死帧** `ui` `sse`
  - 手势阶梯实验（e234g-REPORT）+ 六轮两次现场同帧复现：数屏长单项区域下滑帧字节级静止（方向不对称、moveCount 完整送达）；四轮 T2 一度判全档失效后更正为测量假象嫌疑——维持「嫌疑+未确证」；#246 自愈装机后仍观察一次，疑独立机制
  - 2026-08-27 八轮巨帧取证（PtrDiag 探针链）：冷启动进场窗口拖动全灭；平台把 2.5s 拖动合并成 2-3 巨帧（travel 完整）送达、列表认领却零消耗（consumed=0）；锚点战争/闩锁/输入缺失三族排除；v1 连接器形态机制性空转（勘误入档）、v2 Initial 隧道分块无效——下一步=守卫内打点看 dispatchRawDelta 返回值定界 app/框架
  - 八轮复核（research，6 冷启动全「冻」）：**判词修正**——自动化样本全部是「贴底 + 朝更新方向拖」= 范围尽头语义（本不该滚，无回弹反馈加剧死感），离底同手势即恢复（1399-1421px 全通）——即边缘语义而非 #245 本体；自动化未能复现「历史区中段死帧」；下一步=真人现场复现时记录列表位置（是否贴底）+ 录屏，再决定是否需要守卫内打点
  - → `docs/journal/2026-08-27-event-card-unification.md` §手势阶梯 · §八轮/#245 · `docs/research/2026-08-27-backlog-recheck-158-238-243-245.md`

- [ ] **#253 #251 边界收尾——切换后首帧过渡暴露 + legacy 未再激活条目（已修复，待验收）** `session`
  - #251 验收记录的两条非缺陷边界：①图标冷启 FGS sweep 先于 debug 协程 promote → 切换后首次启动仍连一次陈旧后端（单会话自限）；②legacy 无标记条目只有再激活才打标，永不激活者需手动 toggle 或一次性迁移
  - 修复（2026-08-28）：`computeDemotedAutoConnectIds` 纯函数 + `promoteDebugBackend` 返回被降级 id + MainActivity 对被降级后端补发 ACTION_DISCONNECT（服务已有通道）——过渡暴露同启动周期关闭；单测 +3，全量单测通过
  - 真机 E2E：双向切换——降级后端同秒 `Disconnect requested` + 轮询停摆（4199 断连后 20s 零新增）/ 4200 流量 24→24 零增长 + 4199 存活 ✓
  - → `docs/journal/2026-08-27-event-card-unification.md` §十四轮——**用户验收后迁 journal**（legacy 永不激活条目仍需手动 toggle，见 §十二轮）