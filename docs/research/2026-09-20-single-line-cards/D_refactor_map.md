# 思考卡/工具卡 → 单行标记形态 改造地图（D_refactor_map）

> 调研范围：oc-beacon @ master（只读）。目标形态 = 无卡片背景，一行「图标 + 类型 · 摘要」，点击展开。
> 结论先行：**ToolCardScaffold 是全部工具卡的单一收口点**（TodoListCard 是唯一未走 scaffold 的自绘例外），在其加一个行模式参数即可覆盖 12 个工具卡；ReasoningBlock 独立同构改造；#420 CardExpandReveal 是 drop-in 包装、行模式下契约自然保持，但有 3 条硬注意（见 §3）。

---

## 1. 现状组件树

### 1.1 容器与样式对照

| 组件 | 容器 | 圆角 | 描边 | 背景 | 内边距 | occupyBottomGap |
|---|---|---|---|---|---|---|
| **ReasoningBlock** | Surface | ShapeTokens.smallMedium (6dp) | CardStandardBorder (1dp) | surfaceContainer@AlphaTokens.MEDIUM(0.70) | start MD(12) / end 10 / 垂直 2 | 有 |
| **ToolCardScaffold** | AmoledSurface | smallMedium (6dp) | CardStandardBorder (1dp) | containerColor(默认 surface) + tonalElevation 1dp | 水平 XS(4) / 垂直 2 | 有 |
| **TodoListCard**（自绘，未走 scaffold） | AmoledSurface | smallMedium (6dp) | CardStandardBorder (1dp) | surface + tonal 1dp | SM(8) 全向 | **无（卡族遗漏）** |
| EventCard（通知家族，参考系） | MessageBubble | medium (12dp) | 1dp outline@MEDIUM / 失败 AgentError | Transparent | MessageBubble 栅格 | 无（通知层明确保留卡容器） |
| InjectionCard（#416「淡形态」，最近先例） | Surface | smallMedium (6dp) | 无 | surfaceContainer@MEDIUM | start MD(12) / end 10 / 垂直 XS(4) | 无 |

### 1.2 标题行构成

- **ReasoningBlock**（components/ReasoningBlock.kt:174-218）
  Row(SpaceBetween, CenterVertically, clickable=haptic+toggle)
  左 Row(weight 1f)：**脉冲圆点** Box(5dp drawBehind，流式=infiniteTransition 0.3→1f，完结/停表=静态 0.4f) → Spacer(5dp) → Text(headerText, labelMedium, onSurface@MUTED, maxLines=1) →〔流式且 text 空白：Spacer(6dp)+CircularProgressIndicator(14dp)〕
  右侧：无（#215 批3 chevron 已删，本体点击=唯一展开入口）
  headerText 三态：chat_thinking_in_progress / chat_thinking_complete / chat_thinking_complete_unknown / chat_status_thinking（含时长合成 resolveReasoningDisplayDuration：服务器可信 → 本地冻结 → null 不伪造）
- **ToolCardScaffold**（tools/cards/ToolCardScaffold.kt:142-238）
  左：titleContent!=null ? Row(weight 1f, clickable){titleContent} : Row(spacedBy 3dp, weight 1f, clickable enabled=hasContent||onCardClick!=null){Icon(16dp, iconTint) + Text(title, labelMedium, maxLines=1, Ellipsis, weight 1f)}
  右：isRunning → rightSideExtras + PulsingDotsIndicator(5dp)；hasContent → rightSideExtras + trailingExtras + 复制 IconButton(22dp/ContentCopy 14dp)；否则无
  槽位：onCardClick（#349 点击覆盖，subagent 直达）、rightSideExtras（DiffChangesInline）、trailingExtras（OpenFileIconButton）、titleContent、containerColor（任务类状态底色：蓝/绿/红）
- **TodoListCard**（cards/TodoListCard.kt:113-155）
  左：Icon(Checklist 16dp)+spacedBy(6dp)+Text(chat_tasks_label, labelMedium)；右：labelSmall "x/y" + **ExpandLess/More chevron 16dp（遗留，全家已删）**；Column padding SM(8) 未跟进 2026-09-20 垂直 2dp 裁决
- **GlobToolCard 抽样**（cards/GlobToolCard.kt:63-67）：title 已是「**类型 · 摘要**」拼接—— "Find files · $pattern"，单行形态的摘要拼装**已存在，零新逻辑**；match count（chat_glob_match_count）目前在展开区。
- 调用链：PartContent.kt:204 → ReasoningBlock；PartContent.kt:220 → TodoListCard；其余工具卡 → ToolCardRenderer.kt ToolCallCard（含 resolveToolDisplay 的 title/subtitle 双行 task 变体）→ ToolCardScaffold。

---

## 2. 改单行形态的改造点清单

### 2.1 组件参数（「行模式」开关）

1. **ToolCardScaffold 加 rowMode: Boolean = false**（或 CardChrome { Card, Row } 枚举，利于后续第三形态）——单一收口点，12+ 个卡文件（Bash/Edit/Read/Search/Shell/Task/WebFetch/WebSearch/Write/Glob/ApplyPatch/Patch/Skill + ToolCallCard 兜底）只需透传。rowMode 下：
   - AmoledSurface → 透明容器（去 border、去 tonalElevation；可直接降为 Column，省一层 Surface 语义）
   - Column padding：水平保持 XS(4)、垂直保持 2（Spacing.kt 注释明确允许组件特例内联，无需新 token）
   - 标题行结构**不动**：Icon(16dp)+「title · 摘要」已是现状拼接；右侧复制/运行指示槽保留
   - **clickable 保留 ripple**，但无背景时矩形 ripple 会突兀 → 建议 .clip(ShapeTokens.extraSmall)（4dp）包 clickable
2. **ReasoningBlock 加同名 rowMode**：Surface 降透明（去 border/surfaceContainer 底）；左色条 2.5dp 保留或换成图标（建议保留色条——它是该卡唯一的家族识别标记，且 InjectionCard 同语言）；脉冲圆点/计时/冻结逻辑（#207/#263）**与形态无关，不动**；headerText 三态沿用。
3. **TodoListCard 收编进 scaffold(rowMode)**：顺带修复其 occupyBottomGap 缺失（当前与卡族折叠高度不对齐）与遗留 chevron；todos 空 fallback ToolCallCard 路径自动跟随。
4. **层级协调（评审必看）**：EventCard/合成通知/SyntheticNotificationCard/CommandFeedbackCard/DshJobTimeline 走 MessageBubble 容器（MessageBubble.kt:45 注释明确通知层保留卡容器）。若只把工具卡+思考卡改单行，会出现「通知有卡、操作无卡」的层级反转——需裁决：a) 接受（通知=事件值得卡）；b) EventCard 也提供 rowMode。本地图按 a 记录风险。
5. **InjectionCard 参照**：#416 淡形态已是「无描边+弱底+单行标题」，是最接近目标的现存实现（13dp 图标、11sp 文本、MUTED/FAINT），行模式 token 档位直接抄它即可。

### 2.2 样式 token 建议（对齐现有 SpacingTokens/AlphaTokens 体系，不新增刻度）

- **前景**：类型图标 tint = 各卡现有语义色（primary/tertiary/error…，状态色路由已在 ToolCallCard stateColor）；标题文本 onSurface@**MUTED**(0.50)（思考卡现状），弱于正文 onSurface；摘要后缀段 labelSmall@**FAINT**(0.35) 或 CodeTypography 11sp（task 双行变体降为同行副段）。
- **尺寸**：图标 16dp 沿用（InjectionCard/EventCard 的 13dp 是通知层档位，不建议混用）；行垂直 padding 2dp（2026-09-20 间距统一裁决已定）；图标-文本 gap 统一 3dp（scaffold spacedBy 现状；ReasoningBlock 的 5dp Spacer 顺带对齐）。
- **不新增 token**：AlphaTokens 七档（SELECTED/DIFF_BG/FAINT/MUTED/MEDIUM/HIGH/AMOLED）与 SpacingTokens 4dp 网格已覆盖全部所需值；2dp/3dp 按 Spacing.kt 既有注释作组件特例内联。若团队坚持语义化，再考虑 SpacingTokens.XXS=2，属可选。

---

## 3. 与 #420 CardExpandReveal 的兼容注意

CardExpandReveal 是 drop-in 包装（components/CardExpandReveal.kt）：主路径 = fraction 时钟驱动几何 + dispatchRawDelta 同帧配对；降级路径（LocalCardExpandListState 缺席 / LocalInStreamingTurn）= 裸 AnimatedVisibility。改造约束：

1. **折叠态高度恒定假设**：δ 全导数公式 δ = f_new·H_last − lastReported 假设 toggle 间完整内容高 H 不变（除内容迟到增长）。rowMode 只改折叠态外观、不碰 Reveal content → 账本契约**天然不破坏**。
2. **occupyBottomGap 是常量收缩（CARDS_BOTTOM_SHRINK=8dp）**：收缩量与 expanded 无关 → toggle 间占位 delta == 视觉 delta，#420 安全性注释（ReasoningBlock.kt:267）明确依赖此性质。rowMode 下二选一均可：保留（推荐，卡族一致）或移除（无背景后「卡↔正文空白」问题消失，8dp 收缩会让行间距偏紧）；但**不得改成动态量**——随展开态变化的收缩会直接污染占位 delta==视觉 delta 等式。
3. **整型账本**：advance 与 onMeasure 共用 (fraction×H).toInt() 量化器（防每帧 ~0.5px 子像素泄漏，实测净漂 −26px 的根修）。rowMode 不得在 CardExpandReveal **外层**新增固定 padding/Spacer——EventCard L201-205 的「硬地板」教训：AV/时钟收缩路径上的固定尺寸兄弟级会让高度缩不到 0，收起末帧单帧砸掉（实测 33px）。需要的间距一律加在 expandedContent **内部**。
4. **账本 per-instance**：CardExpandClock 由 remember 持有、冷组合首测对齐（ledgerInitialized）——rowMode 改变折叠高度只影响 H 起点，机制已兜住；无需改 CardExpandReveal 本体。
5. **守卫/离开跟随不动**：展开累计位移 >100px 触发 LocalCardExpandDeparture（关 autoScroll）；用户滚动 snap 取消；流式 turn 内降级裸 AV——local 由 ChatMessageList 提供，与卡形态正交。
6. **同族混排**：同一消息内 rowMode 与卡模式混排时折叠高度基线不同，不破坏 #420 但破坏 2026-08-16「折叠行高一致」裁决 → rowMode 应一次性全量切换（工具卡+思考卡同批），不留开关期混排。

---

## 4. i18n 影响面

- **可完全沿用（推荐目标：零新词）**：
  - 思考卡三态文案已是「动作+时长摘要」形态：chat_thinking_in_progress / chat_thinking_complete / chat_thinking_complete_unknown / chat_status_thinking；
  - 工具类型名全部现成：tool_read/tool_glob/tool_grep/tool_terminal/tool_find_files/tool_search_code/tool_list_directory/tool_fetch_url/tool_sub_agent/tool_apply_patch/tool_shell/tool_web_fetch/tool_web_search/tool_read_file/tool_write_file/tool_edit_file；
  - 摘要拼装已存在（GlobToolCard "Find files · $pattern"；ToolCallCard "title · subtitle"；tool_search_pattern/tool_search_path 参数化摘要）；
  - a11y 沿用 a11y_icon_expand/collapse、chat_copy、a11y_icon_open_file。
- **可能新增（仅当状态摘要上移进标题行）**：如 Glob 的 match count 从展开区上移到单行摘要——chat_glob_match_count 已存在，直接复用；若做「N 文件已改」之类新聚合摘要需新增 string/plurals。
- **翻面成本**：strings 改动 = 15 语言全量（values/ + ar/de/es/fr/id/it/ja/ko/pl/pt-rBR/ru/tr/uk/zh-rCN），按 docs/i18n-guide.md 工作流（改英文源 → 14 语言 → 检查脚本，CI 发版自动校验）。**形态改造若不加新文案则 i18n 零影响**——建议按此定位。

---

### 附：关键文件清单
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/ReasoningBlock.kt（291 行）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/cards/ToolCardScaffold.kt（279 行）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/CardExpandReveal.kt（339 行）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/cards/TodoListCard.kt / GlobToolCard.kt
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/EventCard.kt / InjectionCard.kt
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/tools/ToolCardRenderer.kt（ToolCallCard 兜底卡）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/screens/chat/components/PartContent.kt（分流调用点）
- app/src/main/kotlin/dev/leonardo/ocbeacon/ui/theme/{Alpha,Spacing,Shape}.kt（token 体系）
- app/src/main/res/values*/strings.xml（15 语言）
