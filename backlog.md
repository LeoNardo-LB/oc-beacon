# OC Beacon — 需求与问题总览

本文档是唯一的**未决工作项清单**：只保留尚未完结的需求与问题卡片。条目完结（用户验收 `[x]`）后**当场迁出**——记录连同证据移入 `docs/journal/` 对应批次文件，本文件不保留完结记录；历史查询走 journal 与 git。

**卡片格式**：标题（含全局编号）+ Tag + 状态 checkbox + **≤3 行**摘要 + 链接。需求全文、实现要点、验证证据一律写在链接目标（spec / journal）中，不内联。登记新批次用 `./scripts/backlog-new-batch.sh "<批次名>"`（自动建 journal 文件）；改动后跑 `./scripts/backlog-check.sh` 校验机械不变量。**放置规则（check 脚本强制）**：卡片一律写在下方对应 **Pn 节内**（按优先级定义归位；一节内新卡置顶）；头部编号行与优先级定义表之间**不放任何卡片**（仅允许编号勘误等注释）。**P4 格式增补**：P4 卡必含「**前提**：…」行——说清实现前提是什么、当前为何不可实现（外部硬阻碍所在）。**术语句**：卡片标题与摘要用词遵循 [CONTEXT.md](CONTEXT.md) 术语表（堆积消息/子智能体/轮次/撤销/中断…）；「待处理」保留给权限/问题（状态词待验证/待办/待裁决不受影响）；Tag 英文与 #N 编号不受中文术语约束；API 英文原词（cursor/fork）合法，_Avoid_ 仅限中文对应词。

**编号**：全局递增，不回收。下一编号：**#401**（2026-09-11 #400 Testing seam 4：真机/模拟器 +）。

**操作纪律（2026-09-09 用户定规，账本事故后）**：卡片区**禁止手工直编**——登记/明细追加/状态流转/完结迁移一律经 `./scripts/backlog.sh`（add/note/status/migrate；真实 backlog 变更后自动跑 check）；journal 新节追加用 `backlog.sh journal append`（append-only）或编辑工具定位插入，**禁止全量覆写重写 journal**（2026-09-09 演示批覆写丢章事故定规）。**裁决优先级（2026-09-09 用户定规）**：同一问题域存在多项历史裁决时**以最新裁决为准**；新裁决落地时须回写旧裁决域卡片的注记（#350 为先例）。

> 编号勘误（2026-08-23 合并时）：terminology 分支先行占用的 #194–#199 与主工作区 #194（FAB）撞号，合并时 terminology 侧六卡顺移 +5 → #200–#205；文档内旧引用已同步改。

**优先级定义**：

| 等级 | 含义 | 示例 |
|------|------|------|
| **P0** | 影响主要流程体验或核心业务场景的 bug | 聊天页面崩溃、SSE 断连无法恢复 |
| **P1** | 主要业务流程的需求功能点 | 会话搜索、消息转发 |
| **P2** | 优化专项、锦上添花功能、不影响体验的小 bug | 动画微调、文案优化 |
| **P3** | 观察项 / 依赖外部条件的低价值改进 | 偶发自愈的异常观察、环境因素类缓解 |
| **P4** | **外部前提阻塞**：功能/工作方向明确，但实现前提在 app 之外（服务器能力缺失 / 上游未合 / 用户流程门槛），前提满足前不可动工——**卡内必含「前提」行**（前提是什么、现在为何做不了）；前提变化时重验归位 | 服务器未暴露的事件聚合、上游 PR 候选清单 |

**修复方针**（2026-09-03 用户定规）：bug 类条目**根因修复优先**——交付的修复必须消除触发链的根因层（架构 / 生命周期 / 状态机 / 协议缺陷），不以表象层兜底单独交差（「缓存兜底显示」「重试遮罩」「吞异常」等手段不得作为修复本体）。兜底类缓解仅在同时满足以下条件时接受：①对应根因修复卡已登记并被引用；②兜底卡摘要显式标注「过渡措施」并关联根因卡编号。分层不明确或拿不准时，先向用户呈现根因分析与分层方案，裁决后再落卡/动工。

**验证方针**（2026-09-03 用户定规）：**绝大多数情况（含 UIUX/交互类改动）都应通过真机端到端测试验证并关闭**，`[~]` 不按「是否 UIUX」划分，按「是否存在可注入/可观测的自动化仪器」划分。可用仪器（持续积累）：uiautomator dump/tap（导航+断言，注意 LazyColumn 视口冻结——dump 前先滚顶）、logcat/Room 日志直查、`/proc/net` socket 观测、debug 注入工具（#305 `--ez debug_simulate_timeout` 先例）、curl 模拟服务器侧事件（POST 建会话/订阅 SSE 抓帧）、网络扰动（nc 黑洞 + `adb reverse` 重定向 + `adb kill-server` 断既有连接）、`pm install` 静默装包 + `debug-entry.sh` 确定起点。**仅以下情形才需要人工介入**：①真手指连续手势/体感类——注入手势是平台批处理伪影，无法模拟真手指位移流（#245 两轮证伪）；②需用户凭据/跨设备操作（GitHub 密码/2FA、扫码等）；③数日级真实使用观察（不可加速，如挂机断链观察）；④主观体验拍板类（「好不好用」的最终确认）。判定次序：先穷举仪器→构造红回路→E2E 验证关闭；真找不到仪器才登记人工项并写明卡内为何仪器不可行。

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

- [ ] **#391 ServerAdapter 服务器类型插件化架构（含 DSH 0.1.5/V3 适配）** `refactor` `dsh` `sse` `data`
  - 目标形态：ServerAdapter 聚合根 + @IntoSet 注册表 + 端口可选性派生能力 + 5 UI 插槽扩展 + ConnectionStrategy；新增服务器类型=新目录一个 bundle，零既有改动。
  - 现状根因：7 份手写路由、60 处 ServerType 特判、闭集能力矩阵+中心 switch、事件词汇无版本维度（DSH 0.1.5/V3 击穿历史与流式）。
  - 架构一次到位、开发按 8 切片分批（契约前两片冻结）；首个真实落地=DSH 0.1.5/V3 适配。
  - → docs/specs/2026-09-10-server-adapter-architecture-design.md
  - 通用 UIUX 统一纳入承重与交付：差异分级 L0/L1/L2 + 统一贡献注册表 + 隐藏/禁用判据；静态强制走 Android Lint 自定义规则（既有 lint 门禁，不新增工具链），审计矩阵 BAD 归零（切片 9）。
  - 开发切片数更新：9（原 8 + 通用 UIUX 统一落地，见 spec Further Notes）。
  - 进度（2026-09-10）：切片1-7 已实现并提交（3f59c5d9..8ce0107a）；切片8 上已提交 44095a9c（lint 门禁恢复绿 #396 清零 / 选择器注册表驱动 / 适配器契约测试 / 探测器去类型化 / 架构文档登记）；切片8 下的自定义 lint 规则见 #397；切片9（通用 UIUX 统一）未开工。

## P1 — 核心功能需求

- [ ] **#400 Testing seam 4：真机/模拟器 + 真实服务器端到端** `test` `dsh`
  - 现状：#391 批次全部验证为 JVM 单测/编译/lint；spec Testing seam 4（真实服务器 + 真机端到端）未执行。
  - 目标：模拟器或可达网络下跑 V1/V2/DSH 三面 E2E，重点覆盖历史拒绝重建（surfaceOp 越界）、assistant-stream 实时流、界面插槽渲染（横幅/设置区块）。
  - 阻塞：机场公共 WiFi 客户端隔离致无线调试不可达（10.3.2.3 ARP FAILED）；改用模拟器。
  - 进度（2026-09-11）：模拟器 OpenCode V2 面 E2E 通过——连接/会话列表/聊天转录/条目动作注册表（GOAL 按能力隐藏、QUEUE 在场）/SERVER_SETTINGS 槽位不泄漏 DSH 区块；无崩溃。
  - 未覆盖：DSH 线面（无靶机）——token 横幅、DSH 设置区块、surfaceOp 越界拒绝重建、assistant-stream 实时流。
  - DSH V012 面 E2E 通过（本机 DSH 3080 + debug_token）：token 交换 ok、wire=v012 authed、转录渲染、assistant-stream 实时流（t_dsh-t55s10 增量 RESIZE）、DSH FAB 能力过滤（Goal 有/Shells 无）、404 优雅降级、无崩溃。
  - OpenCode V2 面此前已过；V3 五类新事件渲染未覆盖（见 #398）。

- [ ] **#399 切片9 剩余：条目动作贡献注册表 + 令牌门禁 + 审计矩阵 BAD 归零** `ui` `arch`
  - 现状：区域插槽（ServerUiSlot）已落并有三处贡献；但条目级动作仍是组件内联能力判断，非声明式贡献；令牌门禁（硬编码色值/间距/时长）未做。
  - 目标：#391 spec 切片9 统一落地——条目动作贡献注册表 + 令牌 Lint 规则接 :lint-checks + docs/research/2026-09-07-server-face-unification-audit.md 的 BAD 项（FAB 门控/队列空态泄漏）处置。
  - BAD 项核对（2026-09-11）：审计两条 BAD（FAB 五入口无能力位门控 / QUEUE 空态泄漏）已在统一审计批1（36734c23，2026-09-07）+ 本批切片2 能力位化后归零（ChatScreen.kt:1040-1046 entries 按 GOALS/TERMINAL/QUEUE 构建），审计文档未回标。
  - 因此本条剩余 = 声明式条目动作贡献注册表 + 令牌门禁（硬编码色值/间距/时长，接 :lint-checks，存量入 baseline）。
  - 进度（2026-09-11）：3 个 DSH 私有 UI 文件已迁入类型私有包（ui/components/dsh、ui/screens/server/providers/dsh、ui/screens/sessions/dsh），ServerTypeUiBoundary 豁免 6→3。
  - 剩余：ChatMessageList 的私有 DshJobTimelineCard、SessionListScreen 的 DshTokenDialog、SessionListViewModel 的 DshTokenExchangeState（需 token 对话框+交换状态下沉到 DSH 扩展）；86 文件标准间距存量迁移；声明式条目动作注册表。
  - 进度：DSH token 录入状态已下沉 DshTokenEntryViewModel（ServerTypeUiBoundary 豁免 6→1，仅余 ChatMessageList 私有 DshJobTimelineCard——需新增消息列表插槽，见本卡）。
  - 剩余：86 文件标准间距存量迁移（SpacingTokenBypass baseline）；声明式条目动作注册表。
  - 进度：86 文件标准间距迁移完成，SpacingTokenBypass baseline 清零。
  - 剩余：ServerTypeUiBoundary 最后 1 条（ChatMessageList 的 DshJobTimelineCard，需新增消息列表插槽）；声明式条目动作注册表。
  - 统一贡献注册表条目级部分已落：ServerActionContribution/ServerActionRegistry/LocalServerActions + ChatFabActionsModule 声明 5 条 FAB 入口贡献；ChatScreen 内联能力门控改注册表消费。区域插槽 + 条目动作两块齐。
  - 剩余：条目→内容映射仍由通用壳持有（设计如此）；V3 P1 渲染见 #398；E2E 见 #400。

- [ ] **#398 V3 新事件族渲染（切片7 P1）+ 按代事件词汇表** `dsh` `arch`
  - 现状：#391 两轴评审确认 V3 五类新事件（system/message、assistant/attempt、feedback/message-put|delete、subagent/catalog、deliverables/presented）仅 Ignored(SESSION_FORMAT_V3) 降级不渲染；事件映射仍是单体 when + protocolOf==V012 硬判。
  - 目标：按 spec 切片7 补渲染（系统节点/失败尝试/反馈/子智能体目录/产物卡）+ 落地按代 EventVocabulary 表。
  - 前提：需 DSH 0.1.5 真实 wire 样本（当前仅 0.1.1/0.1.2 实录），无样本不臆造字段。
  - 步骤1（2026-09-11，实况取证+修复）：V3 user/message 的 reasoning/tool-call 块此前被丢弃，现 reasoning→Part.Reasoning（对齐 assistant）、tool-call/result→静默（冗余镜像）；模拟器连本机 DSH 0.1.5-rc.1 实测 warning 16→0。
  - 本机 DSH 0.1.5 服务器（3080）已成为权威 wire 取证源；其余 V3 类型渲染 + 按代词汇表待续。
  - 步骤2（2026-09-11）：29 个 V3 归档取证固化到 docs/research/2026-09-11-dsh-v3-event-payloads.md——assistant/attempt(692)/system/message(41)/subagent/catalog(16)/deliverables/presented(15) 原始载荷+字段+映射计划；feedback/* 归档无样本。

- [ ] **#396 Android Lint devDebug 门禁 4 项存量错误** `lint` `ci`
  - 现象：./gradlew :app:lintDevDebug 红（abortOnError），4 error——HiltEntryActivity MissingClass ×1（src/debug/AndroidManifest.xml:18，类仅存在于 androidTest 源集）+ LocalContextGetResourceValueCall ×3（ChatScreen.kt:786/1067、SettingsScreen.kt:97 的 context.getString 应走 stringResource）。
  - 归因：blame 分别为 6c41d0a2(2026-08-16)/f2df106c/2e4a4d58/54cbc555(2026-08-31~09-01)，#106 批次曾清至 0 后回归；与 #391 切片1/2 无关（ChatScreen numstat 9/9 行数不变，命中行未改）。
  - 影响：#391 切片8 的自定义 Lint 规则要接入同一门禁，需先清此 4 项或确认 lintRelease 路径。

- [ ] **#394 跳转终点 5s 高亮未生效 + 疑似破坏会话渲染（优化2 复验未过）** `chat`
  - 用户复验（2026-09-10）：搜索命中行点击进会话后无 5s 高亮，且报告「似乎破坏会话渲染」。疑点：①Displayed 相位 hook 的 itemKey 键式推导（assistant 目标 t_ 键）与渲染 itemKey 实际格式不匹配→不设键不高亮；②async 跳转路径 entry 查空→静默不设键；③渲染破坏待复现取证（当前帧 /tmp/n5_regression.png VLM 复核健康——你好 会话轮次 19-21 气泡/台账正常，疑为 search-jump 进入路径暂时性）。实现：commit a0b24103。

- [ ] **#393 搜索内容命中：全部过滤下 AI 行仍不可见（⑤复验未过——逐命中行改造后依旧）** `ui`
  - 用户复验（2026-09-10）：逐命中行+角色标签改造后，「全部」过滤下仍只见人类行。装机冒烟实证：story 查询首屏全用户行（BM25 短文档偏置 user 恒靠前），AI 行在折叠下方——待确认用户是否滚动；若 UX 需要 AI 无滚动可见，需按角色交错排序或每会话最优双角色先行。取证：拉库已证 FTS 层 AI 命中健全（whale: assistant 153 + user 14）。

## P2 — 优化与锦上添花

- [ ] **#397 自定义 Android Lint 规则（服务器类型分支白名单 / 界面分层 / 令牌绕过）** `lint` `arch`
  - 背景：#391 切片8 的静态强制部分未落——spec 要求走自定义 Android Lint 规则，但 Lint 检查必须是独立 Gradle 模块，与 spec Out of Scope「不把单模块拆成多 Gradle 模块」存在取舍，需用户裁决。
  - 现状：本机 Gradle 缓存已具备 lint-api/lint-checks 32.3.2（与 AGP 9.3.2 匹配），可离线新增 :lint-checks 模块（com.android.lint 插件 + Detector + IssueRegistry + META-INF services）+ app 端 lintChecks(project(:lint-checks))。建议先只落「ServerType 分支白名单」一条（文本级 Detector，白名单：类型定义/ServerConfig/ServerConnection/data-adapter/ServerDialog/调试入口），跑通后再扩 UI 分层与令牌两条（存量需入 baseline）。
  - 替代方案：脚本门禁（grep）复用现有 release 门禁，零新模块但表达力弱。当前兜底：架构文档承重规则 + code review。
  - 进度（2026-09-11）：:lint-checks 模块已落并接入 app 的 lintChecks；首条规则 ServerTypeWhitelist（剥离注释后判 ServerType 词元 + 路径白名单）经探针实证可拦截、当前树 lintDevDebug 绿。
  - 剩余「通用界面 import 具体类型组件」「令牌绕过（硬编码色值/间距/时长）」两条未落。
  - 白名单已收窄到文件级精确清单（ServerTypeWhitelist 含 MainActivity/DebugProfile/domain-model 三件/server-adapter/存储/重复后端比较/用户选择四件）；architecture.md 同步点名。
  - 剩余两条规则未落：通用界面 import 具体服务器类型组件 / 令牌绕过（硬编码色值·间距·时长）。
  - 第二条规则 ServerTypeUiBoundary 已落（通用界面禁 import/声明 Dsh/OpenCode 前缀 UI 符号，排除 /dsh/、/opencode/、ui/theme/）；6 处存量经 baseline 豁免，lintDevDebug 0 new issues、lintDevRelease 绿。
  - 仅剩令牌门禁：分析见 journal——建议窄化为「非 theme 的 Color(0x…) + 动画时长 tween/durationMillis」，间距（令牌值 .dp 372 处/padding 193 处）因 ui-conventions 允许 dp 常量，需独立迁移批次。
  - 第三条规则 TokenBypass 已落（色值字面量 + 动画时长 tween/durationMillis，排除 theme 与分类调色板）；唯一存量 CopyButton 已真实改用 AppMotion 令牌（新增 FAST=100），未入 baseline。spec 四条静态强制仅剩「dp 间距」因 ui-conventions 允许多为约定内写法，另立迁移批次。
  - 第四条规则 SpacingTokenBypass 已落（padding/spacedBy/PaddingValues 的标准间距值 4/8/12/16/24/32.dp），86 处存量入 baseline。至此 spec 四条静态强制全部落地。
  - 剩余：86 文件间距存量迁移 + 6 处类型私有 UI 迁移（见 #399）。
  - SpacingTokenBypass 存量已清零：86 文件标准间距机械迁移到 SpacingTokens（逐值等价），baseline 减 602 行。四条静态规则全部零存量（boundary 仅余 1 条消息列表私有卡）。
  - 四条静态规则零存量 error：ServerTypeUiBoundary 收窄到非 private 声明后最后 1 条豁免清除（lintDevDebug 0 error）。

- [ ] **#395 立即发送（steer）上屏消息缺标识徽标——chat_queued 徽章链整撤连带丢失** `queue`
  - 用户裁决（2026-09-10）：排队不上屏 ✓ + 立刻发送（steer）上屏 ✓，但 steer 消息上屏后无任何徽标标识是有问题的——需恢复徽标（建议 steer 专属文案如「插话/注入中」而非「排队中」，文案待用户裁决；i18n ×15 + MessageCardUser 两变体渲染点）。注意 steer 无 wire 侧标记——识别依赖发送路径（steer=true 时 seedTranscript 播种），徽章状态需随消息携带或按 rpcId 关联。

- [ ] **#392 Sheet fling 手势隔离在 V1 未生效——快速定位抽屉快速下滑仍收起（#379 回归面）** `sheet`
  - 用户自助验收 ⑥ 顺带发现（2026-09-10）：#379 SheetGestures 内容手势隔离（fling 不收起+手柄收起）在 V2 验过，但 V1 服务器上 QuickNavigate 快速下滑 fling 仍会让抽屉收起——服务器类型交互统一铁律（ui-conventions §1）违背。疑点：sheet 组件按 server type 分叉 or V1 会话内容高度/嵌套滚动差异绕过隔离。

- [ ] **#387 V2注入刷新消息渲染为用户气泡文字墙** `chat` `ui` `v2`
  - skill-catalog/上下文刷新类注入（<system-reminder>包裹、无source.kind标记）按普通用户气泡整文渲染，[Ack] 3 会话顶部现存活例（VLM 09-41 复核：calculator 全文蓝色气泡墙，而同位插件配置已是收起小卡）。初判服务端对此类刷新不带 kind，mapper 按普通 user 落库。根因方向：对齐 dsh web 对 system-reminder 注入的识别与收起呈现（内容嗅探或等价机制），修在映射/渲染层单点。证据：/tmp/n2_acklink_top.png n2_ackthree_top.png；演示批 journal 待补

## P3 — 观察与低价值改进

- [ ] **#390 服务器断连时会话页空白/弹回服务器管理无重连提示** `resilience`
  - 今日链路闪断窗口多帧实证（VLM 确认仅剩状态栏）：reverse 隧道拆→app 断连→会话数据释放（EventDispatcher releaseSessionData）→转录空白或弹回服务器管理界面，期间无重连横幅/按钮，用户无路可走。需断连 UX 兜底（提示+重连入口），证据链 journal 2026-09-09-378-380-wire.md §十五

- [ ] **#388 V2服务端注入推送条件不明：今日新会话零注入** `chat` `v2` `server`
  - 同一服务进程（4199，9-7 22:17 起未重启）下：05-40 前后的 ack 会话有插件配置/工作区指令注入，09-14 后新建会话（leo-tkp 与 oc-beacon 工作区各一，含首轮 hi/1+1 提问）零注入事件（InjCard 全程 kind=null，转录顶无卡）。注入到底何时推送（每工作区一次性？目录变更才推？）未定；需以服务端历史 API 与 dsh web 同会话对照定责（服务端没推 vs 客户端漏收）。定责前不动客户端。证据：/tmp/n1_*.png n2_top_injections.png InjCard logcat

- [ ] **#345 adb 注入 tap 间歇丢弃观察——MIUI 平台行为定性(非 app 缺陷),真手指未复现即不处理** `env` `device`
  - 定性修正(2026-09-07 二查):原「两案全灭」重析后——**第二案翻案**:Doubang 输入法为浅色主题,screencap 下半屏与 app surface 同色族 (247,250,253),误判「无 IME」后 tap 实际全打在键盘上;7 节点 dump=输入法安全窗致盲(平台正常)。第一案(t4401 克隆任务后 composer 聚焦 tap 无响应)仍疑似 MIUI 注入丢弃家族(同 E4② shade 组卡先例);两案中键事件/焦点全程有效(`dumpsys input_method` mServedView 在场实证),app 侧无缺陷证据
  - 本批定向复现未再现(IME 抬起+乱序 tap 串轰击后交互正常);缓解纪律已沉淀 device-testing.md(IME 判定用 dumpsys input_method 勿用像素分析;tap 失活二分定位;冷启恢复配方)。保持观察:真手指复现才升级为 app 卡
  - → 证据:journal §二十五 #345 节 + /tmp/e2e-instr/r1-r3.xml(复现尝试全程交互正常)

## P4 — 外部前提阻塞

- [ ] **#381 192.168.110.248:248 重配——凭据宿主零痕迹不可探查（2026-09-09 用户裁决入 P4）** `infra`
  - **前提**：上游提供远程凭据探知能力——用户原话「后续看看 dsh 官方是否会支持远程探知 token 或其他认证手段」（DSH 官方远程 token 发现，或 OAuth/设备码流等替代认证）；前提满足后 `cred-probe.sh opencode 248 <名称>` 即可接管（/proc 探针现成）；若上游确认不会支持，再议弃用或人工一次性重配

- [ ] **#352 长按菜单「取消归档」——wire 层无恢复动词（2026-09-07 用户裁决要求，服务器阻塞）** `dsh` `archive` `ui`
  - 裁决原文:「归档单向契约同删除一样在长按弹出框中增加即可」——用户要求已归档行长按菜单加「取消归档」
  - **前提**：上游 dsh 服务器提供恢复动词——实测证据（2026-09-07 深夜，当前部署源码 dsh-api-workspace-controller typert）：WorkspaceArchiveSessionRequest={sessionId} **add-only**，全 API 面仅 archiveSession 一个归档动词，官方 web 客户端同无恢复入口（SessionRowMenu 2026-09-05 四重取证注释仍有效）；动词就位后：菜单项+RPC+已归档折叠区行刷新一步到位（#351 能力位先例同款）

- [ ] **#350 V1/V2 归档 API 接线——统一归档面收尾（统一审计批 4）** `v2` `archive`
  - 方向(若端点就位):V2ApiClient.updateSessionFields 补归档真线面(现仅 title 走 rename,归档字段 no-op 回 getSession);ServerCapabilities V1/V2 archiveSupported 翻 true→长按菜单归档项+已归档折叠区自动统一(能力位门控现成)
  - **2026-09-07 深夜探针定音(否定)**:服务器修复后实测——opencode2 beta-19086 自家 OpenAPI(/openapi.json,119 路由)**零归档端点**(无 /archive、无 home 域、PATCH /api/session/{id}=404、POST/PATCH /archive=404);审计前提「官方 web 有 archiveHomeSession」对本服务器版本不成立。V1 PATCH /session/{id} 仅 V1 面文档、本环境无 V1 服务器可证
  - **前提**：上游 opencode 服务器发布归档端点(OpenAPI 出现 archive/home 域)——届时报错即改+真机验收;环境已修复留档:坏因=postinstall 未跑完(stub 占位),本地平台包完好,`node postinstall.mjs` 离线修复,服务已恢复监听 4199
  - **裁决优先级（2026-09-09 定规）**：以节点 2 最新裁决为准——删除优先，归档仅在删除无 API 的面使用；V1/V2 已有删除 API，上游归档端点就位≠自动接线，届时须先回用户重裁
  - 前提加固（2026-09-10 端点考古）：V1-4198 无任何会话更新端点（PATCH/PUT 落 SPA 兜底 200 HTML）；V2-4199 无 PATCH /session（rename=POST /session/{id}/rename 单动词）；两服均无归档写入通道——归档接线前提（服务器暴露 time.archived 写）在两现行版本均不成立，维持 P4 待服

- [ ] **#332 spill 提示行——服务器无结构化信号（工具结果溢出 notice 内嵌纯文本）** `dsh` `sse`
  - **前提**：dsh-spill-policy 全链查实——溢出替换为有界 head/tail 预览+locator 提示全部内嵌工具结果 output 文本,transcript 无 spill 事件（session 事件枚举/types/实现三路 grep 0）;文案模式匹配脆弱（#136 先例:服务器改文案即静默失效）。待服务器暴露结构化字段再实现。→ `docs/research/2026-09-05-audit-309-313.md` #312③ + 实现 agent 取证（暂不可实现）

- [ ] **#288 workflow 阶段卡（tool-workflow agent-start/end 聚合渲染）** `dsh` `ui`
  - **前提**：dsh 服务器在任何客户端面暴露 tool-workflow 运行事件——events.mux 实况 / session.history journal / session.projection / session/jobs 四面实测皆无（Web 端 workflow 树为 client-ui 本地组件，同一事件源）；服务器升级暴露后重验再启聚合器
  - Task 3b 落地 workflow run-start/run-end 降级单卡（同 runId 原位更新 running→终态）；agent-start/end 维持 Ignored（防逐成员刷卡）
  - 方向：run 级聚合器（成员 label/outcome/phase 折叠进阶段卡，参照官方 tool-workflow 装配）；验证=真机 workflow 运行会话卡片分阶段展示
  - **2026-09-01 活体四面包夹（走查 #9 定性）**：events.mux 实况帧（两次 WS tap + 现跑 workflow 对照，仅 tool/code-dispatch* 渲染伴生）、session.history journal（39 页全翻 0 行，fresh run 亦不入）、session/projection（仅 permissions）、session/jobs（仅 bash 后台任务）四面皆无 → app 侧映射链（DshEventMapper:469 + DshMessageAssembler）为休眠代码路径，非缺陷；走查期「18 事件在 a6c4」不复现（疑当时另有来源/版本窗口）。重开丢卡=结构性（无服务器数据源），DSH synthetic 消息零持久化同因
  - **2026-09-02 复验（差距调研独立交叉确认）**：`docs/research/dsh-gap-2026-09-01/` 四路证据（fe 源码/Android 清点/Web 实测/服务端 api-gap）再次确认服务器事件面无 tool-workflow 运行事件——门维持关闭；聚合器设计参照 fe-inventory §2.17 client-ui-workflow-run

- [ ] **#146 OpenCode 官方问题清单（issue/PR 候选）** `upstream`
  - **前提**：上游 anomalyco/opencode 合入变更——需先过用户流程门槛（本地定位官方源码→修复→完整测试含 E2E+交叉验证→人工测试→用户放行才可提交 PR；源码已就位）；2026-09-03 用户裁决长期挂起，上游不提不影响本 app（客户端防御均已落地）
  - ①V2 不发 compaction.started（引擎没接线）②SSE 重连无事件回溯 ③cursor V1 格式返回 400 ④fork handleRaw bug ⑤工具输出截断语义——上游核查完成（repo 已迁 anomalyco/opencode），逐项行动方案已定
  - ⑥候选（2026-08-27 八轮实证）：V2 后台 shell 状态恒 completed（exit 7 亦然），失败信号仅正文文本——上游语义退化，客户端已防御性派生
  - **2026-09-02 逐项复现取证完结（journal 258-stage-b §十）**：源码浅克隆 `~/Documents/code/opencode-upstream`@69c172e + Host-4199 live 复验——①②③⑥ HEAD 仍成立（①连 schema 都无 Started；②端点零回溯处理；③400 已类型化 `_tag` 但无降级；⑥exitCode 从不映射 status）；**④上游已修/改版**（空 body 分支 + payload 改 `{messageID?}`，运行版未跟上，app 现发形状已匹配 HEAD）；⑤不变（FR 开放）。PR 候选排序 ③>⑥>①>②
  - → `docs/journal/2026-08-15-chat-flow-bugs.md`
