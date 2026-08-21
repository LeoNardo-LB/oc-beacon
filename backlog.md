# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。

**编号**：全局递增，不回收。下一编号：**#190**。

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

> 架构评审批次（2026-08-21，用户定 P0）：六候选 + 顺手清理，证据与设计定案全在 journal。#169 已完结（用户验收 2026-08-21，归档 journal），当前推进 #170。

- [~] **#170 架构评审候选 2：连接生命周期协调器 ConnectionLifecycleCoordinator——已实现，待用户真机验收** `refactor`
  - 三段式落地（d3baf95c/b297e47e/d21a45f5）：connect 七步/disconnect 四路单点化，双份 teardown 合一；registry 真相源 + FGS 回调派生；10 条 JVM 测试 + 全量单测过（1 例无关 flaky 已记录）
  - 真机 E2E 四场景过：连接（幂等真实触发）/断开/重连/飞行模式恢复（SSE 自愈）；待用户验收 UI 状态观感（维度 5）
  - → `docs/journal/2026-08-21-arch-review-deepening.md` · `CONTEXT.md`

- [~] **#171 架构评审候选 3：未读红点时钟域收进 interface——已实现，真机 E2E 全绿，待用户验收** `refactor` `data`
  - 三段式（a048b1ea/2231d301/941f17f8/a33d0d27）：UnreadEvent 事件化封死客户端时钟域泄漏（漏斗载荷提取 + DB 回环 seedCachedMessages 隔离）；已读侧全吸收（Signal 删除/判定入模块）；1808 单测 + 真机红点四态+双持久化全绿
  - ⏳ 维度 5（红点观感）待验收；错误红点真机无触发手段（JVM 覆盖）
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [~] **#172 架构评审候选 4：V1/V2 seam 泄漏收编——已实现，真机 V2 E2E 全绿，待用户验收** `refactor`
  - 取证修正后落地（2a0bb5a6/f8521376/2de6889e）：PaginationCursorPolicy 收编 6 泄漏点（isV2 从 domain/UI 绝迹）+ ServerCapabilities 门控（god-client 显式不拆 #185）；真机实证服务器原生 cursor 续页 + V2 门控位
  - V1 无真机服务器（JVM 契约覆盖，与 #150 复验一并）；⏳ 维度 5 分页观感待验收

- [~] **#173 架构评审候选 5：ChatViewModel 按状态簇重组——已实现，真机对话全生命周期 E2E 全绿，待用户验收** `refactor` `ui`
  - 四段串行（b511eef5/7c5f9cd9/55b803ba+22a4cff9+007bb527/d4601004）：Terminal 迁出 + 4 簇门面 + UI 三子组件按簇迁移（28 处）+ uiState 退役（生产零消费，ChatUiState 删除）；跨簇编排留薄 VM
  - 深化（2026-08-21，e9731b12）：25 个零调用死转发删除 + sessionOps 第 5 簇；公共成员 111→93；全量绿 + 真机冒烟
  - 真机实证 FSM 全链（Idle→Busy→Streaming→Idle force-complete）+ composer/conversation 簇路径；⏳ 维度 5 观感待验收

- [~] **#174 架构评审候选 6：SessionStateService 8 回调旋钮 → 1 必需协作者——已实现，真机烟雾全绿，待用户验收** `refactor`
  - f179ad70+ab2c36c3：SessionStateCollaborator 构造注入（漏接=编译错误），EventDispatcher 接线块迁入 Impl，1808 单测全绿；真机 FSM 完整生命周期经新接线实证（含 force-complete×2）
  - ⏳ 维度 5（FSM 状态 UI 观感）待验收；僵尸场景（3min busy）JVM 覆盖
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [~] **#175 架构评审顺手清理四件 + bonus——已实现，真机烟雾全绿，待用户验收** `refactor`
  - 四件全落地（65a51723/67d496f3/276f2850/d757d499）：双调用点合一（子会话 else 分支真机实证）· 删三壳（Boolean 签名保留+契约测试）· 双胞胎合并 · ScrollPositionDelegate 死代码删除；bonus：repo deprecated trio 三层删除
  - 全量 1805/1805 绿（-3 死代码测试 +2 契约测试）；→ `docs/journal/2026-08-21-arch-review-deepening.md`

## P1 — 核心功能需求

- [~] **#155 会话内提示音：被抑制的系统通知转为提示音+震动，严格镜像系统通知策略** `ui` `sse`
  - 前台会话 turn 结束/权限/问题/错误事件现状零反馈 → 补提示音+震动，策略完全镜像系统通知四层静音矩阵；错误 streak 只响第一声；零新增设置项（含 VIBRATE 权限与通知侧 streak 去重）
  - spec 已定案（grilling Q1–Q12 + F1–F5），实现前必读；模拟器无音频输出，维度 5 必须真机实测
  - 落地（2026-08-21，`23e38a00`）：策略管线纯函数 + streak 通知/提示音双侧 + 独立去重 + VIBRATE；测试 18 例全绿；真机双分支实证（聚焦=提示音零通知 / 非聚焦=通知照常）；待用户维度5听感验收
  - → `docs/specs/2026-08-21-in-session-audio-feedback-design.md` · `docs/journal/2026-08-21-in-session-audio-feedback.md`

- [~] **#151 GitHub 上报——代码全量完成（a68263b5..6b623f51），1826 测试绿，真机禁用态验证过；待维护者注册 GitHub App 填凭据后激活 E2E** `ui` `data`
  - 四模块：device flow 认证（SecretCipher 加密存储）/ API 客户端（指纹查重/建/评）/ 上报服务（双轨指纹+24h 防刷）/ Diagnostics UI（六分支状态机+15 语言）；双缝测试 13 条
  - 激活清单：注册 GitHub App → BuildConfig 填 client_id/secret → 真机走授权/建 issue/命中评论 E2E
  - → `docs/specs/2026-08-21-error-report-github-design.md` · `docs/journal/2026-08-21-error-report-github.md`

- [~] **#152 前置：日志分级修复——已实现（f535e15d/f398b7f3/2a07ad74），真机风暴验证 PASS，待用户验收** `sse` `refactor`
  - 三组修复：风暴环 i→d 全降 + 双 e 记录消除 + 4 处补 throwable + per-event 门控 + WebView 主帧/子资源分流 + 遗留标签删除；真机实证断连窗口 6D+5I+0E（残留 I 均一次性里程碑）；附带 #186 测试脆弱当场根因修复（两次连续全量绿）
  - → `docs/journal/2026-08-21-error-report-github.md`

- [~] **#153 前置：release CI 留存 R8 mapping.txt artifact——已实现（cfeae270），待下次发版 CI 实跑验证** `refactor`
  - workflow 增 Upload R8 mapping（90 天 + if-no-files-found=error 防 minify 回归）；路径模式经本地 outputs/mapping/<variant>/mapping.txt 实证（devRelease 产物在）
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


- [ ] **#179 消息气泡间距变大（主观）——静态取证完成，等用户截图/会话定位** `ui`
  - 已排除：messageSpacing=8dp 未变（07-31 至今）；分片中段零装饰、首末段装饰与普通气泡同值；唯一视觉变化= 92e2855c ≥3K 消息段内空行化（气泡**内部**变高，治滚动卡顿所需）
  - #183 分割线减半已落地（8a965166）或已缓解；待用户实测新包观感或给截图精确定位



- [ ] **#187 ModelPicker 二级面板：variant 行内 accordion + 默认模型开关重设计（调研+UIUX 已定案，未实现）** `ui` `model-config`
  - 调研结论（2026-08-21）：variant 随模型列表一并返回（V1 `/provider` variants map · V2 `/api/model` variants 数组 `V2ApiClient.kt:765-799`），domain 层 `ModelCatalog.variantNames` 已就绪，**无需动 API/数据层**；默认模型功能已存在（658abb11：SettingsDataStore 按 serverId 本地存=机器绑定 + 解析链第 3 级），本条仅重做入口可发现性（现状 16dp 星标不可见）
  - UIUX 定案（用户三选）：①模型行右侧 chevron 行内 accordion 展开；②面板内容 = variant pills（含「默认」档）+「设为默认模型」开关，模型行尾星标降为纯指示（不承担点击）；③移除输入行 variant pill（`AgentModelVariantSelector.kt:147-162`），当前 variant 仅二级面板可见、输入行只显模型名
  - 改动面：`onSelect` 扩 variant 参数 · `ModelConfigDelegate.selectModel` 适配（:189-232）· i18n；实现时决策点：无 variants 模型 chevron 是否显示（面板仅剩默认开关）

- [ ] **#188 默认模型星标点击"无效"——快照消费链断裂，写入成功但 UI 永不回显 + toggle 自我抵消** `ui` `model-config`
  - 根因（2026-08-21 定案）：`ModelConfigDelegate.kt:323` `localDefaultModel` 为普通 getter 快照 → `ChatScreen.kt:869` 传参一次性快照 → DataStore 写入后无 Compose 状态观察 → 星标不变实心；用户补点在 toggle 语义（`ChatViewModel.kt:347` 同模型再点=取消）下自我抵消；偶发"成功"=其他状态恰触发重组
  - 修复：Dialog 的 `defaultModel` 参数改自 `_localDefaultModel` StateFlow `collectAsState`（一处接线级改动）；与 #187 强关联——重做默认模型入口（开关进二级面板）时一并修，星标本就降为纯指示

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


- [ ] **#184 未读水位线 globalMax 跨服务器混合——多服务器时钟偏差场景** `data`
  - markAllSessionsRead 对不分服务器的水位线 map 取全局 max（SessionListViewModel:423-430）——多服务器时钟不同域时一键已读可能错杀/漏杀红点；#171 grilling Q6 定案：不动存储 schema，登记不动
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [ ] **#185 V1/V2 god-client 拆解（终局债务，显式不做）** `refactor`
  - V1ApiClient(72 方法)/V2ApiClient(84) 全域 god-client + 7 门面 78 处 if 分发——#172 grilling Q1 定案：seam 已在门面 interface 正确收敛，拆轴属内部代码组织（22 测试文件重写 + 缓存式适配器版本竞态），显式登记不拆
  - → `docs/journal/2026-08-21-arch-review-deepening.md`

- [~] **#189 终端组件换件：termlib → Termux terminal-view/emulator（vendored）** `terminal` `ui` `arch`
  - 用户验收②+明确指令「bug 挺多，最好引入主流的终端组件」。真机取证：vim 插入模式打字被 IME 组合输入拦截（「全部」候选态）、ESC 无响应——termlib 0.1.0 早期版本键盘/IME 处理不成熟且依赖闭源不可修
  - 选型：Termux terminal-view + terminal-emulator（10+ 年亿级验证；仓库 GPLv3 但两模块为 Apache 2.0 明确例外，MIT 兼容）；vendored 源码引入（无 maven artifact）
  - 换件范围：VT 内核 + 渲染/键盘 View 层；PTY WebSocket 传输（ServerTerminalWorkspace）保留，adapter 换实现；KeyboardOverlay 保留
  - 落地（2026-08-21 真机闭环）：6bb577e0 spec → b76919c7 vendor → 53837c7b 桥接+UI+依赖切换 → 82559a26 六根因修复；vim 试金石过、完整回环实证（journal §验收问题②）；待用户维度5手体验收
  - → `docs/specs/2026-08-21-terminal-component-swap-design.md`
