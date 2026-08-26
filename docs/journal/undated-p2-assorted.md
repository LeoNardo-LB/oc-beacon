# P2 散装条目（批次化登记之前，2026-06~08）
> 状态：全部完结
> 迁移：2026-08-20 自 backlog.md 原文迁入（spec/journal 分离批次；原文逐字保留，未压缩）

- [x] **提问卡片 M3 原生化改造（消除"外来物"拼盘感）——2026-08-19 模拟器代验收 ✅** `ui`
  - 背景：2026-08-17 用户反馈主对话中提问卡不美观，希望用 M3 原生组件。grilling 共识（Q5=A/Q6=B/Q7=A/Q8=A/Q9=不纳入）：内嵌保留 + 控件原生化 + 活动/历史统一 + 分页保留
  - **2026-08-17 代码完成（待人工验证）**：
    1. Q5：QuestionCard 容器 Surface → **OutlinedCard** + 表单头部（AutoMirrored HelpOutline + "待你回答"新键 question_awaiting_reply，15 语言）；AMOLED contentColor 语义保留
    2. Q6：选项行自绘 Surface+BorderStroke+Check → **M3 ListItem + 原生 RadioButton/Checkbox**（leading 控件 + 整行 clickable；material3 1.4.0 ListItem 无 onClick 重载，用 Modifier.clickable）；自定义答案三态同步 ListItem 化
    3. Q7：Q1/Q2/Q3 tab 压高 SegmentedButton（hack）→ **原生 FilterChip**（32dp 自身设计高度）
    4. Q8：CollapsibleQuestionPart 历史折叠卡容器 → OutlinedCard（与活动卡统一）；展开态经 QuestionPagerView(readOnly) 自动继承
    5. 死导入清理：QuestionCard.kt 30+ / QuestionPartContent.kt 8
  - 验证：compileDevDebugKotlin ✅ 全量单测 ✅ i18n 15 文件 ×1 ✅
  - **2026-08-17 E2E 三轮实测（模拟器独占，真实 V2 服务器）**：
    - 布局终态（用户迭代三轮）：标题栏（? + 待你回答 + **Q1|Q2 SegmentedButton 原生高度** + SINGLE/MULTI 标签同行右侧）→ 分割线 → 纯问题文本 → ListItem 选项行 → 输入框 → 按钮；历史视图元信息行在 pager 上方（无标题栏）——E2E-3 截图确认全部渲染正确
    - 行为：单选互斥 ✅ / 多选勾选 ✅ / SegmentedButton 双向翻页 ✅ / 多自定义共存+定点删除+Edit 替换 ✅（定点重测 dump 铁证）/ 提交通道送达 ✅（agent 复述完整答案）/ 历史折叠展开 ✅ / FATAL=0
    - **样式终态（第四轮用户决策 f191a876）**：选择指示 = 右侧 ✔（选中才显示，未选中无控件）；ListItem 行结构保留。E2E 像素级验证 6/6
    - **2026-08-18 美化重构（5be0e8b5/dc0562de/1c19b59c）**：双审计驱动——tonal 实底化（描边 3→0 层、明度锯齿 Δ7→0.2、圆角 12dp、左缘收敛）、Next→Tonal、Q-tab→FilterChip、问题 14sp Medium。像素复审计四指标全达标
    - **2026-08-18 自定义输入语义纠正（e6aae7a0）**：用户澄清自定义=提交自己的回答非添加选项——回滚多自定义支持，恢复三态（空态输入框/已输入行/编辑替换）；E2E 三断言全过（dump 结构性证据：保存后卡内 0 EditText、编辑预填当前值、删除后输入框恢复）
    - **2026-08-18 三个真 bug 根治 + 真机终验 ✅**：① 输入框字体缩放裁切（4de66692：定高 44dp 溢出切字形 → 自绘 CustomAnswerInput heightIn 内容驱动 + 6 项美化：14sp 对齐/焦点 tonal/Enter 提交/40dp 触达/✕ 取消）② REST 恢复卡 key=null → answer={} "未作答"（41811a2d，用户真机复现翻案 E2E-B）③ 点选项挤掉自定义答案（d4f436b5：toggleQuestionAnswer 纯函数两槽位互不挤占，单测直调生产函数 15 处）——**真机终验（OPPO PLK110/中文 UI/OEM 输入法）：两槽位/无裁切/IME 提交全过，FATAL=0**（/tmp/e2e15/）
    - **2026-08-18 基础容器统一（a90dbead）✅**：用户指出提问卡应有与其他卡片一样的基础容器——tonal 实底（surfaceContainerHighest）与气泡仅差一档无边框，"融进"气泡不像独立卡片。抽共享组件 EmbeddedCardContainer（样式 = FileCard 既有基准：surfaceContainerLow 实底 + 1dp outlineVariant 边框 + 12dp 圆角 + 无投影），应用 3 处（活动卡/历史卡/FileCard 自身改调共享容器）。真机像素验证：容器 244 vs 气泡 227（17 档差）+ 边框线清晰可辨，vision 走查 "distinct card container, no flaws"（/tmp/e2e16/）。此后所有内嵌卡片走同一容器不再漂移
    - 迭代中发现并修复：多选自定义只渲染第一个的结构缺陷（b6bf568f）**⚠️ 2026-08-18 勘误：该"修复"是语义误解**——用户澄清自定义输入 = 提交自己的回答（至多一个，三态输入框），不是"添加选项"；"多自定义支持"与"输入框常驻"已回滚（见下方语义澄清条目）；衍生登记 E2E-C（导航丢状态 P1）/E2E-D（v2 reply 恒 404 P2）
    - 注：视觉模型对全卡截图 3 次幻觉"左侧有控件"，最终以逐行裁剪放大 + 像素扫描定性（E2E 截图判读的方法学经验）
  - **第五轮视觉微调（6bad8c39 + a3e181d6）**：① 元信息序调换——SINGLE/MULTI 标签左、Q1|Q2 分段按钮右（E2E px 证据）② 分段按钮 40→32dp+labelSmall ③ 问题域间距 SM→MD（实测 12.6dp）④ 行紧凑化——M3 ListItem（固定 48dp+ 无 padding 参数压不矮）→ 紧凑 Row（单行实测 27.8dp；带 description 双行内容驱动）⑤ 自定义行图标序 Edit/✕/✔（✔ 最右对齐普通行；✕=删除该自定义）⑥ 输入框高度对齐 44dp——三轮演进：heightIn(44) 无效（min 非上限）→ Provider 压 LocalMinimumInteractiveComponentSize 无效（M3 源码实证该 Local 只管 icon 边距）→ **显式 height(44.dp)** 终验达标（可视边框精确 44.0dp、无裁剪、三态恒定、FATAL=0）
  - ⚠️ 人工验收待用户：整体观感（维度 5 视觉目测，截图在 /tmp/e2e3/ /tmp/e2e5/ /tmp/e2e6/ /tmp/e2e8/）
  - **2026-08-19 模拟器代验收（用户授权"用模拟机搞定"）✅**：多状态画廊 + 视觉审查（/tmp/verify-acceptance/p4_01/p6_01~06）——双问题活动卡（待你回答 + SINGLE 标签左 / Q1|Q2 chips 右、问题文本、紧凑选项行含 description 副文本、自定义输入框、忽略/下一个/提交三按钮）、选中态（accent wash (221,222,237) + 右侧 ✔，单/多选像素一致）、自定义三态（Emacs 保存行 Edit/✕/✔ + 删除复原）、折叠历史 "Asked" 态。vision 审查：tonal 实底圆角容器、无拼盘感、行语言统一。结合 2026-08-18 补充复验与真机终验（e2e15/16），观感维度以模拟器画廊 + 真机功能终验合并结案
  - **2026-08-18 补充复验（模拟器，beta-17595）**：双题卡全交互链正常——SINGLE/MULTI 标签、Q1|Q2 FilterChip 分页、选项行（含 description 副文本）、自定义三态（输入→保存勾选 accent wash 像素 221,222,237）、三按钮（Dismiss/Next/Submit）、提交后折叠 "Asked" 态。截图 /tmp/verify-0818/20-29；交互细节归档见「2026-08-18 模拟器验证批次」
  - 行为保持：单选互斥/多选/单选可取消/三按钮流程/#125/#126 全部未动

- [x] **提问卡容器对齐工具卡主流语言（7f278a93 + a76cd513）** `ui`
  - 2026-08-18 完成（含方向纠正）：用户澄清诉求是"提问卡改成跟其他卡片一致"（此前两轮 a90dbead/d9cbb252 做反成"其他卡片改跟提问卡"——d9cbb252 已 revert 8db1e786，其他 6 种卡片恢复原样）
  - 提问卡（活动+历史）换 ToolCardScaffold 主流语言：AmoledSurface + **surface 同色** + smallMedium(6dp) + tonal 1dp 无边框（AMOLED 纯黑+边框内建）
  - E2E 同屏像素证据：提问卡与 Shell 工具卡均无描边、同 6dp 圆角、tonal 家族、底色一致（/tmp/e2e19_both.png）；toggle 两槽位/自定义三态逻辑零改动（diff 铁证）；EmbeddedCardContainer 组件保留（FileCard 用）

- [x] **权限卡视觉复审（提问卡原生化后的配套）——已修 8d452e08** `ui`
  - 来源：2026-08-17 grilling Q9 决策不纳入当时批次
  - 2026-08-18 更新：EmbeddedCardContainer 已支持 containerColor 语义色覆盖——权限卡可低成本迁入（骨架统一 + error 红语义底透传，参照 ToolCardScaffold 状态色模式）
  - **2026-08-19 triage 勘误 + 实现（8d452e08）**：EmbeddedCardContainer 实际**无** containerColor 参数（2026-08-18 记录超前于实现，且该容器全家迁移已在 d9cbb252 回滚）；提问卡终态（三次修正后）= ToolCardScaffold 语言（AmoledSurface + smallMedium 6dp + tonal 1dp）。权限卡照此迁移：AmoledSurface(normalColor=errorContainer) + 标准边框（AMOLED 纯黑），内容/按钮/触感零改动。E2E：卡片渲染（需要权限+图标+位置标签+三按钮）、像素铁证（卡片 249,222,220 红调 vs 背景 250,248,255 中性）、三按钮全链路（拒绝/仅一次无规则/始终允许+确认框）、FATAL=0（证据 /tmp/verify-permcard/）。AMOLED 形态未单独复验（同组件此前批次已验证）

- [x] **#71 后台系统 + V2 消息链路 D4 人工验收（时间性现象，自动化无法覆盖）** `ui` `sse`
  - **2026-08-13 验收 ✅（用户口头确认"后台没啥问题" + Agent 数据正确性确认）**：shell 生命周期（created/exited/deleted）与内联展示数据与服务器 SSE 事件一致；流式期间 Back 无 ANR。⚠️ 附注：`session.tool.progress` 事件被 SessionNextEventHandler 标记 Unhandled（工具实时进度缺口）→ 登记 #92
  - 问题：2026-08-11 后台系统（入口/工具栏/面板/Shell 卡片）与 V2 消息链路（V2SseMapper 流式）开发完成，自动化验证（编译/单测/E2E 功能走查）全部通过；但以下**时间性现象**自动化无法覆盖，需用户真机验收后才可声称完成（verification-requirements.md 维度 5）：
  - 验收清单：
    1. **转后台工具栏**滑出/消失动画（fade + expandVertically）——出现时机正确、动画顺滑无跳动
    2. **后台入口按钮**角标数字出现/消失过渡（BadgedBox）——有后台活动时数字正确、无闪烁
    3. **后台面板**（ModalBottomSheet）——上拉/拖拽关闭手感、Subagents/Shells tab 切换流畅、子会话跳转返回正常
    4. **SSE 流式节奏**——AI 回复逐字出现无闪烁/卡顿/跳底（SSE 铁律）；停止生成后状态立即恢复
    5. **消息即时显示**——发送后用户消息 3s 内出现（V2SseMapper input.admitted 播种），多轮连续发送顺序正确
  - 验证环境：模拟器 + 真实 V2 服务器（10.0.2.2:4199），**测试专用会话**（用户指定）
  - 证据：docs/research/RG-2026-08-11-v2-contract-background.md（D4 待验收项）
  - 状态：`[~]` 待验证——**用户逐项验收通过后勾选 `[x]`**；发现任何问题改回 `[ ]` 进入修复

- [x] **#67 V2 后台完成通知：synthetic 消息被过滤（PartContent Text 分支）** `sse` `ui`
  - 问题：2026-08-11 V2 契约对齐调研确认——opencode v2 后台任务/subagent 完成时向主会话注入 `POST /api/session/{id}/synthetic` 合成消息；但 oc-beacon 的 PartContent.kt Text 分支 `part.synthetic != true` 直接过滤 → 用户看不到后台完成通知
  - 方案：识别 synthetic 消息并以特殊样式（卡片/淡色+标签）渲染，或独立事件通道驱动通知
  - 来源：docs/archive/specs/2026-08-11-v2-contract-alignment-design.md §3（synthetic 端点）+ 后台系统调研
  - **2026-08-11 完成（实测澄清）**：Part.Text.synthetic 全项目无赋值点（过滤是死代码，消息本就能显示）；服务器 POST synthetic 后不广播 SSE 事件（仅 REST 可见）。新增 MessageCardRole.SYNTHETIC + SyntheticNotificationCard（居中淡色卡片+图标+时间），模拟器实测显示 "后台测试完成通知：subagent X 已完成" ✅

- [x] **#68 V2 新会话创建后 get/pending 404（服务器怪癖）** `session` `data`
  - 问题：2026-08-11 实测——新建会话出现在 `GET /api/session` 列表（自动生成标题），但 `GET /api/session/{id}` / `pending` 返回 `SessionNotFoundError`（带/不带 x-opencode-directory 均 404）；服务端重启/升级（next-17132→17135）后可能自愈
  - 方案：待复现确认；若稳定复现需调研 V2 location/workspace 路由语义（列表可能跨 location 返回而 get 限定 location）
  - 来源：模拟器 E2E 实测（2026-08-11）

- [x] **#69 session.instructions.updated 事件未处理（低频 parse error）** `sse`
  - 问题：2026-08-11 回归走查发现 1 次 `session.instructions.updated` parse error（无 parser 匹配且触发异常路径）；频率极低（指令更新时）
  - 方案：V2EventParser handledPrefixes 加 `session.instructions.` 占位解析（返回 SessionNext Unknown 即可）
  - 来源：回归走查 logcat（2026-08-11）
  - **2026-08-11 完成**：SseClientV2.parseV2Event data/properties 判型防御（instructions data 是数组时 jsonObject 扩展抛异常 → 回退顶层字段）+ V2EventParser handledPrefixes 加 session.instructions.；V2EventParserTest 新增用例；实测 parse error 归零

- [x] **#70 V2 事件体系未确认项——已完结（①崩溃路径实证排除 + durable 恢复发现）** `sse` `refactor`
  - 问题：docs/archive/specs/2026-08-11-v2-contract-alignment-design.md §7 列出 7 项未确认：
    1. `session.retry.scheduled` payload 结构（未触发重试未抓到）——影响 Retry 状态映射
    2. `/api/config` info 已实测无 mcp 字段——McpRepositoryImpl 的 mcp 配置来源需确认（当前 type 回退 "local"）
    3. `/api/session/active` type 完整枚举（目前仅见 "running"）
    4. listPtyShells 正确端点已实测 = `/api/pty`（✅ 已修复 #Task7）
    5. completeProviderOauth 已补 `/api` 前缀（✅ 已修复 #Task7）
    6. `session.tool.failed` 事件已实测存在（✅ mapper 已支持）
    7. 多 step 工具循环已实测同 assistantMessageID（✅ 幂等 upsert 天然处理）
  - 方案：剩余 1/2/3 项在下次触碰相关功能时补测；4-7 已闭环
  - 来源：docs/archive/specs/2026-08-11-v2-contract-alignment-design.md
  - **2026-08-19 盘点补测（beta-17595 curl）**：② **已答**——/api/config 的 document 条目 info **含 mcp 字段**（用户配置有 mcp 块；当时"无 mcp 字段"应为旧版本或配置为空），McpRepositoryImpl 可从 config info 读取；③ **已答**——/api/session/active 返回 **map 格式** `{data:{sessionId:{type:"running"}}}`，类型仅见 "running"（无 idle/error 等其他值可观测）。① retry.scheduled 仍无法主动触发（需真实失败重试场景），保持 touch-when-needed。**条目收敛：仅剩①（条件触发时抓 payload）**
  - **2026-08-19 ①崩溃路径实证（kill -9 实验，两个 SSE 监视窗口全程捕获）**：生成中（65 reasoning.delta 已流）kill -9 服务器 → 重启 → **durable 事件日志自动恢复被中断的 turn**（74 reasoning.delta + 36 text.delta + text.ended，最终 assistant 1179 字符完整交付，会话转 idle 无僵尸）——**全程无 session.retry.scheduled**。结论：① 崩溃-重启路径不发出该事件（durable 恢复静默续跑取代之，App 重连后自然看到续流，无需 Retry UI）；剩余唯一触发面 = provider 级瞬时失败（429/5xx）需真实故障 provider—— inherent 外部条件，与「下次发生时抓」同义。**App 侧有价值的副产品认知：服务器崩溃不丢 turn，SSE 重连即续**

- [x] **#29 androidTest 编译修复（#25 已读标记遗留）** `refactor` `data`
  - 问题：commit 5793957f（#25 已读标记服务器域重构）为 SessionRepository 增加 `getLastCompletedReplyTimeFlow()`、SettingsRepository 增加 5 个已读状态方法，但 FakeSessionRepository/FakeSettingsRepository 未同步实现 → `compileDevDebugAndroidTestKotlin` 从该 commit 起持续失败（2026-08-08 悲观重构验证时发现，与重构无关的预存在问题）
  - 方案：Fake 补缺失接口方法（按接口签名 + 现有 fake 语义实现），恢复 androidTest 编译
  - 工时：~30min | 难度：低 | 涉及：androidTest/fakes/FakeSessionRepository.kt、FakeSettingsRepository.kt
  - **2026-08-09 完成（待人工验证）**：3 个 Fake 补齐 7 个接口新成员（FakeChatRepository.upsertMessages 按 MergeStrategy 分支；FakeSessionRepository.listMessages 改 before+MessagePage 签名 + getLastCompletedReplyTimeFlow；FakeSettingsRepository 5 个已读方法），commit 1ae44d57；compileDevDebugAndroidTestKotlin BUILD SUCCESSFUL ✅（解锁 LogDaoTest 等全部插桩测试编译）；⚠️ 真机验证待用户：插桩测试套件实际运行（connectedDevDebugAndroidTest）

- [x] **#28 提问组件样式与高度统一优化** `ui`
  - **2026-08-13 修复完成 + V1 实测通过 ✅**：Q tab 替换 M3 大 Tab（48dp）→ 自绘 28dp 胶囊 tab（选中高亮）；问题文本/选项描述 bodySmall→bodyMedium。V1 实测：tab 高度 ~27dp 吻合、字体可读性良好
  - 原问题：用户反馈提问卡片样式不好看、各组件高度不统一、提问区域缺少外边距，"缩在一起很难看"
  - 问题：用户反馈提问卡片样式不好看、各组件高度不统一、提问区域缺少外边距，"缩在一起很难看"
  - 现状：QuestionCard 用 AmoledCard + padding SpacingTokens.MD，内部 spacedBy SM；选项行 Surface padding 12/8dp、图标 16dp、文本 bodyMedium/bodySmall；QuestionPagerView 多问题用 TabRow + HorizontalPager
  - 调研方向：M3 官方无 Stepper/提问组件，但可参考官方组件规范——RadioButton/Checkbox 触摸目标 48dp、SegmentedButton（SingleChoice/MultiChoiceSegmentedButtonRow）选项场景、AlertDialog 内容间距规范、LinearProgressIndicator 作步骤指示；对比各组件理论高度与当前实现的差距；检查外边距/间距 tokens（SpacingTokens）是否按 M3 规范使用；评估是否适合改用官方组件
  - 工时：~2-3h | 难度：中 | 涉及：QuestionCard.kt / QuestionPartContent.kt / theme tokens
  - **2026-08-08 代码完成（待人工验证）**：选项行 48dp 触摸目标（defaultMinSize）、图标 16→24dp、间距统一（SpacingTokens.XS/SM/MD）+ QuestionPagerView 多问题分支 spacedBy SM（消除"缩在一起"，commit 83f4ea31）；编译 ✅；⚠️ 真机验证待用户：行高统一/图标大小/间距舒展（维度 5 视觉目测）

- [x] **#18 ChatMessageList 指纹函数外移** `refactor`
  - 问题：765 行因铁律 6-8 缓存逻辑膨胀
  - 方案：只外移纯函数（messageFingerprint/partsFingerprint/tailHash/messagesSignature）到 util/MessageFingerprints.kt；缓存函数高度耦合不动
  - 工时：~30min | 难度：低 | 涉及：ChatMessageList.kt

- [x] **#19 Phase 历史注释清理** `refactor`
  - 问题：约 30 处"在 Phase N Task X 中提取"注释，工作已完成，纯历史噪音
  - 方案：批量删除历史标记，保留功能说明
  - 工时：~30min | 难度：低 | 涉及：ui/screens/chat/* + FileViewer* + Workspace*

- [x] **#20 SearchMatchDto 字段对齐** `data`
  - 问题：DTO 字段与 API 不匹配（API 是 path:{text}/line_number，DTO 是 lines/lineNumber）；当前 /find 未启用未触发，启用即静默失败
  - 方案：按 API 对齐字段（@SerialName 处理 snake_case）
  - 工时：~1h | 难度：低 | 涉及：FileResponses.kt（启用 /find 前必须完成）

- [x] **#21 androidTest 4 个 flaky 用例** `ui` `data`
  - 问题：2026-08-07 #16 androidTest 回归发现 4 个 flaky（与重构无关）：`ChatInteractionIsolatedTest.scrollToBottomFab_appearsWhenScrolledAway`（swipeDown 手势未越阈值）、`ChatInteractionTest.abortSession_callsAbortApi`（等 Stop 按钮超时）、`ChatInteractionTest.permissionDialog_appears_whenPermissionRequested` 与 `questionDialog_appears_whenQuestionAsked`（interactionState 7 路 combine 时序）
  - 方案：失败时重试/等待策略；scrollToBottomFab 用更可靠手势（多段 swipe）；其余 3 个检查 isBusy/interactionState 传播时序
  - 工时：~2h | 难度：中 | 涉及：app/src/androidTest/chat/*
  - **2026-08-07 完成（系统性调试）**：4 用例实为稳定失败非 flaky，根因 3 类——(1) abortSession：测试注入真实 SessionStateService 但 VM 经接口注入 FakeSessionStateRepository（fake 状态机缺失）→ Fake 增强 FSM 模拟 + 测试改操作 Fake；(2) permission/question：测试未 seed 消息走 ChatEmptyState 分支致 ChatMessageList 未渲染 → 补 seedConversation；(3) scrollToBottomFab：全屏 swipeDown(0.05-0.95) 无效改默认；questionDialog 断言歧义（问题文本 2 处）改 onAllNodes().onFirst()；两轮验证 4/4 通过

- [x] **#22 单测 2 个 flaky 用例** `data`
  - 问题：2026-08-07 #17 全量单测（1222）发现 2 个预存在 flaky：`PermissionAutoApproverTest`（序列化相关，此前 round-trip 修复后仍有残留）、`FileViewerViewModelTest`（协程异常时序）；均已三重验证（HEAD 通过/单独跑通过/不引用改动类）与重构无关
  - 方案：PermissionAutoApproverTest 检查 createdAt 等时间字段的序列化竞态；FileViewerViewModelTest 检查协程异常处理时序
  - 工时：~1-2h | 难度：中 | 涉及：app/src/test/...
  - **2026-08-07 完成**：PermissionAutoApproverTest 固定 createdAt 消除毫秒默认值竞态；真根因在 SessionListViewModel finally 块空流 first() 抛 NoSuchElementException 泄漏全局线程池污染无关测试（改 firstOrNull）；FileViewerViewModelTest 防御性加固；全量单测连续 3 次全过

- [x] **#23 SessionList combine 魔法索引 tuple 化重构** `refactor`
  - 问题：SessionListViewModel.combine 22 个源 + SessionListStateBuilder 的 values[18..22] 魔法索引——加源/删源时索引错位（2026-08-07 已踩坑多次：多选/未读/基线/一键已读各加一次源，每次索引偏移导致编译错误多轮修复）
  - 方案：combine lambda 内构造具名数据类（如 SessionListInputs），StateBuilder 接收它而非裸 Array<Any?>；或 combine 嵌套分组
  - 风险：中（改动 combine 签名 + StateBuilder + 相关测试）；收益：消除索引错位类 bug
  - 备注：索引语义注释已加（StateBuilder 顶部），缓解了短期风险
  - 备注：2026-08-07 已落地——状态切片方案（spec: docs/archive/specs/2026-08-07-session-list-state-slicing-design.md）

- [x] **#24 长 turn（多步工具调用）期间未读红点延迟** ui session
  - 问题：agent 长回复（多 step 循环）期间，红点要等整个 turn 结束（服务器发 SessionStatus=idle）才出现——用户在列表等待时迟迟看不到红点（2026-08-07 subagent 实测：后台 13 分钟长 turn 无红点）
  - 现状：列表有 Working/busy 状态指示可缓解
  - 待决策：是否需要"进行中即红点"（每条 assistant 消息 completed 即算）？权衡：与"turn 结束绑定"的用户需求冲突
  - **2026-08-07 关闭（用户决策）**：红点绑定 turn 完全结束是**设计意图**（用户确认），不实施"进行中即红点"。idle 丢失兜底已确认无需做——L3/L4 REST 校验 → FSM forceComplete 链路覆盖 SSE 丢失。衍生项：#25 时钟一致性（当前实施中）；FOLDER 折叠未读计数 / busy 图标强化 / 未读置顶排序 暂不实施

- [x] **#25 红点时间戳时钟一致性** `data` `session`
  - 问题：红点判定混用服务器时刻与客户端时刻——`MessageUpdated` 的 `time.completed`（服务器时钟）与 `markSessionIdle` 覆盖的 `System.currentTimeMillis()`（客户端时钟，MessageEventHandler.kt:520）都流入 `_turnEndTime`；已读时间（readTimes/allReadAt）是客户端 now。本机部署（模拟器连本机 serve）时钟一致无实害；**连远端服务器时时钟偏差 → 红点误报/漏报**
  - 前因：2026-08-07 用服务器时刻（StepEnded.timestamp / completed）是为了避免"退出后事件才到达 → 客户端接收时刻偏晚 → 红点误报"；markSessionIdle 的客户端 now 是 SSE 丢失时 REST 回退补标记的权宜
  - 状态：**调研中**（2026-08-07 起）——需先完成时间戳来源全图谱 + 历史演进梳理，再定优化重构 or 根治方案
  - 工时：调研后再估 | 难度：中-高 | 涉及：EventDispatcher.kt / MessageEventHandler.kt / SessionStateService.kt / SettingsDataStore
  - **2026-08-07 完成**：红点改派生状态模型——maxCompleted（服务器 completed 时刻）+ isUnread 加 status==Idle 门控（turn 结束才红点）+ 已读标记/一键已读改服务器域（markRead 传 completedTs、全局 max）+ v2 迁移（EventDispatcher init 触发，清旧客户端域值）。全量单测（39s）+ 构建安装 + 真机回归 6 场景通过 5/6。**含重启未读恢复**：maxCompleted 持久化（82fc2493）——杀进程重启后未读回复红点恢复，spec §5.7 原"遗留 concern"已解决
- [x] **#31 本地库损坏自愈（Room 版）** `data` `room`
  - 问题：Plan 1 迁移后删除了旧 withDatabaseRecovery（catch SQLiteException → deleteDatabase 重建），Room 版无等价兜底；ocbeacon.db 损坏时 recordBatch 异常会传播至 AppLogger。Room+WAL 较旧实现健壮，诊断日志非用户资产，属低风险
  - **2026-08-09 完成（待人工验证）**：新建 DatabaseRecovery（@Singleton，`withCorruptionRecovery` 捕获 SQLiteException → deleteDatabase → Room 自动重建空库，commit 6fdff190）；LogStore/MessageStore 读写路径接入；DatabaseRecoveryTest 3 用例（成功/SQLiteException 触发删除/非 SQLite 传播）+ 全量单测 PASS；⚠️ 真机验证待用户：模拟 db 损坏后 App 自愈（可选，低优先级）

- [x] **#32 归档压缩（二期：热/冷分层 + 整桶 zstd + TLRU 淘汰）** `data` `room` `cache`
  - 背景：消息本地化批次（#30）Plan 1/2/3 已代码完成（待人工验证）；归档为本需求二期，用户决策：一期（归档之外）全部开发完并人工验证后再开发二期
  - 方案（spec §9 批次 2 + 调研结论）：热表（近期可查）→ 归档表按 (session, 时间桶) 整桶序列化 zstd 压缩（单桶 ≤512KB，CursorWindow 2MB 限制）；压缩触发 = TLRU（now − last_accessed > TTL 如 14 天 或 会话超阈值）；解压触发 = 用户向上滚动到归档边界异步解压整桶入热表（UI loading）；重压缩 = 后台 sweep 超 TTL 未访问桶
  - 技术选型：zstd-jni（Maven Central AAR，~1MB ABI）| 单条消息压缩在 Android 是负优化（<10KB 收益 < 开销，Discord 实测）→ 必须整桶压缩
  - 工时：~1-2d | 难度：中-高 | 涉及：MessageDao/MessageStore 扩展 + ArchiveBucket 表 + sweep 协程 + zstd-jni 依赖 + 翻页管线解压分支
  - **2026-08-09 二期启动**：一期（#30）模拟器验证已全覆盖（14 项 + 补充 3 项，仅剩真机最终确认，用户暂不在附近）；用户决策"先开发二期，注意提交隔离"→ **开发分支 `feature/archive-compression`**（基于 master 954e3c89，未合回）；zstd-jni 最新稳定版 **1.5.7-13**（2026-08-08，Maven Central，Android AAR 支持 API 21+）；二期完成并经验证后合回 master
  - **2026-08-09 二期代码完成**：SDD 6 任务 + 最终 whole-branch review + fix wave（I1 原子事务/I2 Migration 测试/I3 512KB 字节切分 + M2-M9），全量单测 **1342 通过/0 失败**（最终修复含归档游标推进后新增 2 测试）；MigrationTest 编译验证（运行时待模拟器）
  - **2026-08-09 模拟器验证全部通过（日志+db 实证）**：①DB v1→v2 Migration ✅（user_version=2）；②归档写入 ✅（[archive] 161 msgs→1 bucket，热表精确回 1000，压缩率 21x）；③归档读取 ✅（[dearchive] 逐桶 + [paging] from archive + source=ARCHIVE）；④断网离线浏览 ✅（飞行模式仍可读归档）；⑤数据完整性 ✅（解压大小精确匹配、161 条消息完整可解析、热表+归档**零重复**——I1 原子事务有效）；⑥无崩溃 ✅
  - **2026-08-09 完整场景矩阵验证（22 项全过）**：512KB 字节切分 ✅（13条/桶 ≤512KB，0 超限）；跨天分桶 ✅（10/11 天窗口）；TLRU 淘汰 ✅（251 桶→evicted 61 保持 200，leastAccessed 优先）；归档失败降级 ✅（坏 payload → prune-only fallback 实测触发）；**坏桶 skip-continue ✅（bucket=267 decode failed, skipping，跳过继续读后续桶）**；多会话归档隔离 ✅（db 实证仅目标会话有归档）；冷启动种子化 ✅；UI 消息渲染 ✅（uiautomator 抓到消息文本）；迁移旧数据保留 ✅；touch lastAccessedAt ✅
  - **2026-08-09 归档逐条容错（69df372b）**：模拟器实测发现——单条 payload 解码失败（测试注入的 path 字段格式错误）导致**整批归档失败**（500 条全丢，降级为 prune-only）。修复：逐条 runCatching 跳过坏消息（`skip undecodable msg` 日志），好的仍归档。补单测 `upsertMessages_archiveSkipsUndecodableMessage_keepsBatch`
  - **2026-08-09 #35 ANR 复现**：验证过程中 Back 触发 ANR（Input dispatching timed out，wait queue 2）——**根因：SSE 高频流量（每分钟数百事件）占满主线程**，Back 键输入事件排队超时。与二期归档无直接关系（归档在 IO 线程）。backlog #35 已登记，待专项排查（SSE 事件处理主线程负载优化）
  - **2026-08-09 修复（d30a0d57）**：模拟器实证发现**归档翻页死循环**——loadOlderMessages 的 before 始终取热表最老（归档只进内存不落热表 → 热表最老不变 → 每次翻页读同一批归档桶）。修复：Delegate 维护**归档时间游标**（ARCHIVE 来源用返回最老消息 created 推进；NETWORK 来源重置），use case 增加 beforeCreated 参数优先用它查归档。修复后验证：before 正确前进 → 归档读尽 → 网络回退 ✅

- [x] **#33 草稿在进程被杀时丢失（saveDraft 仅 onCleared 触发）** `data` `session`
  - **2026-08-13 验证 ✅（Agent 代测）**：输入草稿 → force-stop → 重启重进会话 → 草稿完整恢复（截图 v33_draft.png）
  - 问题：2026-08-09 模拟器走查（V7）发现——ChatViewModel.kt:430 的 draftDelegate.saveDraft() 仅在 onCleared() 调用，am force-stop / 系统低内存杀进程不触发 onCleared → 草稿丢失（输入框重置为 placeholder）。预存问题（触发时机一直如此，非 #30 的 DataStore 迁移引入；迁移只改存储机制）
  - 影响：用户按 Home 后台 + 系统杀进程 → 草稿丢失；正常返回（ViewModel onCleared 触发）不受影响
  - 方案：updateDraftText 加防抖定期 saveDraft()（如 1-2s 无输入即存），或 Activity onStop/onSaveInstanceState 触发；需评估写频率与 DataStore 成本
  - 工时：~1h | 难度：低 | 涉及：DraftInputDelegate / ChatViewModel
  - 来源：2026-08-09 模拟器走查 V7
  - **2026-08-09 完成（待人工验证）**：DraftInputDelegate.updateDraftText 加 500ms 防抖自动持久化（DRAFT_SAVE_DEBOUNCE_MS，scope.launch + delay，每次输入取消重启）；clearDraft 取消挂起 job（防清空后又被存回）；onCleared 兜底保留；新增 DraftInputDelegateTest 4 用例（防抖窗口内不存/快速输入只存最后一次/clear 取消/即时状态更新，commit e3ffeae7）；编译 ✅ 全量单测 ✅；⚠️ 真机验证待用户：输入草稿 → force-stop → 重启 → 草稿仍在

- [x] **#34 同 URL 第二服务器连接 UX（永久 Connecting 无提示）** `ui` `sse`
  - **2026-08-13 验证 ✅（Agent 代测）**：允许添加同 URL 服务器；连接时提示 "Already connected to this server"，未卡 Connecting（日志：HomeViewModel shares backend with already-connected）（截图 v34_dup_server.png）
  - 问题：2026-08-09 双服务器验证发现——同 URL 第二个配置点 Connect 后永久卡 'Connecting...'（>60s 无握手/无错误/无日志），手动 Cancel 才能退出。架构上 app 限制同 URL 单一活跃 SSE 连接（防双投递），但 UX 无反馈
  - 方案：检测到同 URL 已有活跃连接时直接拒绝并提示'该后端已连接'，或复用现有连接；或加超时/错误提示
  - 工时：~1h | 难度：低 | 涉及：连接管理 UI + SseConnectionManager
  - 来源：2026-08-09 双服务器去重验证走查
  - **2026-08-10 完成（待真机验证）**：根因 = OpenCodeConnectionService.connect 已有 url+username 去重但静默 return，HomeViewModel 乐观 connecting 状态无回传 → 永久 Connecting。修复：HomeViewModel connectToServer 经 serviceBinder.findDuplicateBackend 预检（ServerConfig.sameBackend 归一化：协议/host 小写、默认端口、尾斜杠）→ 命中写 connectionErrors 红字提示"该服务器已连接"（home_error_already_connected，15 语言）；Service 内去重保留为纵深防御。编译 ✅ i18n-check ✅（583 keys × 14 语言一致）

- [x] **#35 会话内 Back 触发一次 ANR（待复现）** `crash` `ui`
  - **2026-08-13 验证 ✅（Agent 代测）**：流式输出期间按返回键——无 ANR、无 crash、无 "not responding"（crash buffer 空）；SSE 高频负载下 Back 正常（截图 v35_back.png）
  - 问题：2026-08-09 走查——首次启动后会话内按 Back 触发 ANR（'OC Beacon Dev isn't responding'），force-stop 重启后恢复。可能与 SSE 长连接 + 主线程阻塞有关。仅一次未复现
  - 方案：待复现——logcat 抓 ANR trace；检查 Back 导航路径是否有主线程阻塞（会话关闭时的同步操作）
  - **2026-08-10 模拟器高强度复现未复现**（D35）：55+ 轮（标准循环 20 / 加载中 Back 10 / 双击 Back 10 / 后台切换 5 / 300ms 高强度 20）零 ANR 零崩溃；最大 GC pause 91.69ms、最长帧 ~4.9s 均未触发阈值；52 条 ERROR 全为 JobCancellationException（Back 取消分页加载的预期行为）。结论：模拟器无法复现，疑似偶发/低端真机内存压力场景；保持 P2 低优先，真机复现后再查。证据：docs/research/audit-2026-08-10/D35-investigation.md + metrics/D35-*（45 份 logcat）
  - **副发现（新条目 #65）**：Back 退出时 JobCancellationException 被记为 ERROR 级（日志噪声），建议降级 INFO/DEBUG
  - 工时：待复现后再估 | 难度：中 | 涉及：会话导航/生命周期
  - 来源：2026-08-09 双服务器验证走查
  - **2026-08-09 根因确认并修复**：真机 ANR trace 抓取——退出会话 → ChatViewModel.onCleared → draftDelegate.saveDraft → DraftDataStore.persist → **runBlocking 阻塞主线程**（草稿 DataStore IO 在主线程同步执行）。修复（0eaac6dc）：onCleared 改 `viewModelScope.launch { withContext(NonCancellable) { saveDraft() } }`（异步不阻塞主线程）+ DraftInputDelegate.saveDraft/clearDraft 移 Dispatchers.IO；编译 ✅ 全量单测 ✅（1343 PASS）；⚠️ 真机验证：用户确认闪退/卡死已修复（2026-08-10 用户实测 ✅）

- [x] **新增 C：会话列表点击进入会话的过渡动画丢失** `ui`
  - 问题：2026-08-10 真机排查滑动卡顿期间发现——点击会话进入会话界面时，如果会话内容未加载完毕，原应有过渡动画（loading 过渡）；现在过渡动画也没有了，进入会话直接无过渡/直接显示
  - 影响：进入会话体验突兀（无加载过渡反馈）；可能与导航/加载状态显示逻辑近期改动有关（#23 状态切片或翻页管线改动后）
  - 方案：对比 ChatScreen 进入时的 loading 状态显示逻辑（SessionLifecycleDelegate.loadSession 加载编排 + ChatScreen 加载态）；确认过渡动画缺失点（加载指示器/淡入过渡）
  - 工时：~1h | 难度：中 | 涉及：ChatScreen 加载态 / 导航过渡 / SessionLifecycleDelegate
  - 来源：2026-08-10 真机排查滑动卡顿（用户口头反馈，明确"记一下，后面修复"）
  - **2026-08-10 根因并修复**：加载动画逻辑存在（`isLoading && messages.isEmpty()` 显示 PulsingDots），但**消息本地化（一期）后缓存秒开** → 加载太快 → PulsingDots 一闪而过不可见 → 用户感知"过渡没了"。修复（ec875ff7）：ChatScreen 加 `showLoadingTransition` 状态 + `MIN_LOADING_VISIBLE_MS=400`——即使消息立即到达，PulsingDots 也至少显示 400ms 再消失（仅首次进入且内容未加载完时显示；返回已有会话不显示；不遮挡内容/不拦截触摸，区别于已移除的"加载蒙版"）。验证：模拟器实证——进入"系统优化"/"生成对话标题"会话，150-250ms 加载指示器清晰可见 ✅；全量单测 1343 PASS ✅；i18n PASS ✅；⚠️ 真机复测待用户

- [x] **新增 D：会话列表滑动卡顿/掉帧——结案（3 轮模拟器验证 + 环境校准排除假回归；2026-08-19 用户指示模拟器优先）** `ui` `performance`
  - 问题：2026-08-10 真机排查——会话列表滑动"卡手"（拉伸动画中无法反向滑动）+ 掉帧（SSE 活跃时 90th 17ms / 99th 30ms+ / slowUI 40-50 次）
  - **根因 1（卡手）**：Android 12+ 默认 Stretch overscroll 拉伸动画拦截输入 → 全局禁用（`LocalOverscrollFactory provides null`，MainActivity）
  - **根因 2（掉帧）**：日志风暴——MessageDataDelegate combine 每 48ms 打 4 条 MsgDiag（每秒 ~80 条 logcat 写入）→ 彻底删除
  - **根因 3**：MessageEventHandler.handleMessageUpdated 每次 O(n) 全量 filter（1896 条消息仅用于诊断日志）→ 删除
  - **根因 4**：combine 每 48ms 冗余 O(n log n) 排序（数据源已有序）→ 移除
  - **根因 5**：SQLite IN 999 变量上限——大会话（1896 条）partsForMessages 抛 SQLiteException → 分块查询（≤900/块，新增回归测试）
  - **根因 6**：L3 REST 校验 limit=0 全量拉取（1989 条）→ 最新 50 条补漏
  - **根因 7**：上滑分页失效——reverseLayout 下 lastVisibleItemIndex 语义错误（恒等于底部 → 无限翻页/不触发）→ firstVisibleItemIndex + isScrollInProgress
  - **根因 8**：ANR——onCleared 主线程 runBlocking（已在 #35 修复）
  - 验证：模拟器实证——上滑翻页归档加载 ✅（`Loaded older: 20 msgs source=ARCHIVE`）；SQLite 错误 0 ✅；L3 refresh 50 msgs ✅；slowUI 26→0 ✅；全量单测 1343 PASS ✅；i18n PASS ✅
  - **2026-08-18 模拟器帧数据基线（软渲染参考，非真机结论）**：8 轮快速 fling（上下交替）gfxinfo——106 帧渲染 / jank 24.5%（legacy 82%）/ p50=30ms p90=40ms p99=69ms / 慢 UI 线程 19 / **无 >300ms 卡死帧、无 ANR**。模拟器 swiftshader 软渲染天花板明显（p50 30ms 即超 16.7ms 预算），8 项根因修复无劣化证据；真机基线仍待用户复测（数据 /tmp/verify-0818/49_gfxinfo.txt）
  - ⚠️ **待真机复测**：用户拿回手机后验证——滑动跟手度（无拉伸）/上滑翻页/掉帧（SSE 活跃时）
  - **2026-08-19 第三轮模拟器复测 + 结案（用户指示模拟器校验优先减少人工）**：① 功能全过——双向 fling ×8 到达两端、零 ANR/零 FATAL、深滚无卡死，overscroll 禁用代码在位（根因1）；② 帧数据三测（86%/74%/75% jank）高于 08-18 基线 → **环境校准实验**：同窗口原生系统设置应用滚动 jank 78.8%/p50=65——与 App（75%/61）同水位，App 略优 → 判定今日软渲染环境整体偏慢（宿主负载），**非 App 回归**；③ 滚动窗口 StrictMode 违规 0 条（排除 c3078b41 penaltyLog 干扰）。真机主观跟手度仍可选复测，不阻塞

- [x] **新增 E：上滑分页后底部最新消息消失——结案（3 轮模拟器实证，2026-08-19 用户指示模拟器优先）** `data` `session`
  - 问题：2026-08-10 用户实测——进入主对话界面后，上滑（加载更早消息）再下滑，**无法回到最底部**；"最底部的消息像是直接从整个主对话流中没有了一样"
  - **根因**：`MessageEventHandler.upsertAppendOnly`（APPEND_ONLY 合并策略）的 `_messages.update` 用 `incomingMsgs.map { existingById[newMsg.id] ?: newMsg }`——**把整个 _messages 替换为分页加载的"更早消息"**（incoming 只含更早，不含现有最新）→ **现有最新消息（底部）全部丢失**。二期 caf8019b（upsert 合并策略统一）引入；注释语义"仅补充缺失"与实现不符
  - **修复（ff192fd5）**：改为 `(existing + incomingMsgs).distinctBy { it.id }.sortedBy { it.time.created }`——existing 保留 + incoming 补充缺失 + 按 created 排序（combine 依赖写入路径有序）。同时修正 EventDispatcherTest 旧断言（固化 bug 的 size=1 → size=2），新增 2 回归测试（APPEND_ONLY 保留最新 + 分页场景）
  - 验证：模拟器实证——上滑分页 18 次（540 条更早消息）后下滑，底部最新消息仍保留 ✅；全量单测 1345 PASS ✅
  - **2026-08-18 模拟器复验受阻→修复后完整验证 ✅**：首轮受阻于新 P1（V2 长会话历史 0 条，已修 53cfea68）；修复后 501 条会话深翻（total 226+ 项、游标深入 8月12日历史）→ 回滚穿越全程消息连续渲染，Q174 跳转定位正常、新消息（01:57→10:20 区间）全部保留在流中——**底部最新消息不消失** ✅；Room 热表 501 条全量
  - ⚠️ **待真机复测**：上滑加载更早后下滑能回到最底部，最新消息不消失
  - **2026-08-19 第三轮模拟器复测 + 结案**：127 条会话深翻多轮（最新消息 → 劳务派遣 → 劳动合同 → 追缴 → 027 电话多段更早内容连续渲染，无断裂）→ 滑回绝对底部 → **最新消息（「模板已创建：劳动保障监察投诉书」）完整渲染在底**，服务器侧最新消息与 App 渲染一致（REST 双侧对照）。分页内容完整性 + 底部保留双验收达成

- [x] **新增 F：上滑自动加载更多失效——结案（3 轮模拟器实证，2026-08-19 用户指示模拟器优先）** `ui` `session`
  - 问题：2026-08-10 用户实测——主对话界面上滑"看似滑到顶"但不再加载更多（有更多内容却加载不出来）
  - **根因 1（不触发）**：`shouldPaginate` 依赖 `listState.isScrollInProgress`——用户滑到顶**停住**时 =false → 不触发。修复：改 `LaunchedEffect(hasOlderMessages, isLoadingOlder, autoLoadPaused)` + `snapshotFlow { listState.layoutInfo }` 持续监听——距顶 ≤8 即触发（无论是否滚动中）；`isLoadingOlder` 作 key → 加载完成重启监听 → 停在阈值内自动续载
  - **根因 2（死循环）**：NETWORK 分页游标不前进——热表最老不变（窗口外消息不落热表）+ use case 的 before 编码依赖 `messageCreatedAt(beforeId)`（游标消息不在热表 → null → before 不编码 → 服务器返回最新 → 游标 A→B 交替循环，模拟器实证每 ~100ms 拉同一批）。修复：Delegate 新增 `networkCursorId/Created` 独立游标 + use case 新增 `networkBeforeCreated` 参数（跳过归档直接 `CursorCodec.encode(id, created)`）
  - **根因 3（防风暴）**：自动续载无保护——连续失败会无限重试。修复：失败指数退避（500ms→8s）+ 3 次失败暂停（autoLoadPaused，UI 停止自动续载）+ 成功恢复清零
  - 日志：ChatPaging（auto-load triggered/backoff wait）+ loadOlder START/END/NETWORK/ARCHIVE/退避/暂停/恢复全链路
  - 验证：模拟器实证——停在顶部 8s 自动续载、游标 fe0c5862→fe0b9e6e→fe0b4438 前进、读尽 hasOlder=false 自动停止 ✅；全量单测 1350 PASS ✅（新增 5 回归测试：游标前进/网络游标跳过归档/退避/暂停/恢复）；i18n PASS ✅
  - **2026-08-18 模拟器复验：首轮断裂→修复后完整闭环 ✅**：首轮 NETWORK 首翻 0 条误判读尽（P1，根因 08-12 补丁旁路 08-16 根治，已修 53cfea68）；修复后 auto-load 全链工作——probe 触发（`topVisible=75 total=137`）→ loadOlder NETWORK 30 msgs → 游标链逐页前进（serverCursor 透传）→ 自动续载不停（hasOlder 恒 true 至读尽）、无重复无风暴（failures=0 paused=false 恒定）
  - ⚠️ **待真机复测**：上滑到顶停住 → 自动加载更早直到读尽，不重复加载、不风暴
  - **2026-08-19 第三轮模拟器复测 + 结案**：深翻全程内容连续加载渲染（无卡死/无重复/无风暴——零 ChatPaging 报错）；今日 127 条会话 < 初始加载窗口（hasOlder=false 不触发自动续载 = 正确行为，热表全量命中）；触发路径（probe/游标前进/退避/读尽停止）已有 08-10 首验 + 08-18 完整闭环（501 条会话游标深入历史）两轮实证。真机如遇长会话可选抽查，不阻塞

## 验收清欠（2026-08-27 深夜，自动化复测）

- ✅ **草稿防抖持久化**（500ms debounce）：自动化实证——输入新草稿（叠在旧
  草稿之上）→ force-stop → 冷启动 → 完整恢复 ✅；测试后已清场
- ✅ **插桩套件首次实际运行**（08-09 遗留）：androidTest 编译损坏真因 =
  FakeMessageCacheRepository 缺 sweepEmptyStreamParts（接口 08-26 扩展），
  补齐后 HiltTestRunner 全套真机运行中；MIUI 三坑实录：①adb uninstall
  DELETE_FAILED_INTERNAL_ERROR（用 pm uninstall --user 0）②test 包安装弹窗
  是 securitycenter「USB安装提示/继续安装」单引号文本 ③gradle uninstall
  编排被拦 → 改设备侧 nohup am instrument + 轮询
- ⚠️ QuestionPagerView 选项行 48dp/图标/间距：目标对象勘误——是 agent 提问
  的选项卡片不是设置页；需服务端 agent 主动提问才可触发，留日常使用观察
- ✅ 真机 gfxinfo/atrace 基线：已被 08-27 流式卡顿批次真机 atrace 覆盖
  （doFrame p50=5.71ms p99=8.41ms，120Hz 预算内）——模拟器软渲染基线作废

## 插桩套件运行结果（2026-08-27 02:50 补录）

- FakeMessageCacheRepository 补 sweepEmptyStreamParts 后编译通过；
  29/29 测试类真机全绿（HiltTestRunner，am instrument 直跑），约 135 用例零失败
- MIUI 拦截三层实录（未来重跑照此办理）：①adb uninstall 报
  DELETE_FAILED_INTERNAL_ERROR → 用 adb uninstall（非 --user 0）重试成功
  ②test 包首装弹 securitycenter「USB安装提示/继续安装」（单引号 text）
  ③gradle uninstallTest 编排被拦 → 改 push APK + 弹窗自动点 +
  am instrument 直跑；另有残留 nohup instrument shell 互杀干扰（perclass
  前先 kill 残留 sh）
- 版本插曲：测试期间 devDebug 时间戳 versionCode 抬高 → release 重装被拒
  （older），需完全卸载再装
- ⚠️ QuestionPagerView 选项行 48dp 触达项勘误：对象是 agent 提问选项卡片
  （服务端触发），非设置页；留日常观察
