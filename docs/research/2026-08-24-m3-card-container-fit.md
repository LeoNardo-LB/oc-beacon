# Material 3 卡片容器适配性调研（2026-08-24）

> **目标**：评估「Material 3 自带的 Card 及其他容器/内容组件」能否容纳聊天流 20+ 种卡片的内容，从紧凑度、视觉层级、气泡内嵌套观感、AMOLED/动态取色兼容、交互语义、迁移成本维度判定；据此校验「卡片体系统一 v2」方案容器层的判断。
> **方法**：一手源码取证——本仓库全部容器/卡片实现 + 本地 Gradle 缓存中的 `material3-android-1.5.0-alpha26-sources.jar` 解包（/tmp/m3src，Card/ListItem/Badge/IconButton/Surface/tokens 逐个核对默认值）。未联网、未跑构建、未触碰设备。
> **约束**：纯调研，不改代码；结论留给「卡片体系统一」立项使用。
> **关联**：docs/ui-conventions.md（M3 First 约定）、主会话「卡片体系统一 v2」方案、docs/ui-conventions.md §Shape tokens。

---

## 执行摘要（结论先行）

**核心结论：M3 Card 家族「能用但无增益」——容器层维持 v2 方案（自定义 AmoledSurface 参数规格），不推翻。** M3 Card 的 shape/containerColor/border 参数透传完全可行（本仓库 MessageBubble 已实证此用法），AMOLED 也能满足；但 Card 相对裸 Surface 有一个**结构性缺口**：**不透传 tonalElevation**（源码验证见 §2.1），v2 的「surface 底 + tonal 1dp」层级语言无法经 Card 表达。把 Card 全部参数覆写后的产物恰好等价于现有的 AmoledSurface 封装——即 AmoledSurface 不是对 M3 的偏离，而是与 Card 同构的「M3 Surface 薄参数化」，符合 ui-conventions「优先 M3 原生组件」精神。

四条分层判断（详见 §6）：

| 层 | 推荐 | 判据 |
|---|---|---|
| 容器 | **保留 AmoledSurface**（= v2 规格：smallMedium 6dp + surface + tonal 1dp，AMOLED 纯黑 + 边框） | Card 无 tonalElevation 参数；无嵌套卡片语义；覆写后等价于现有封装 |
| 标题行 | **保留自绘 Row**（16dp Icon + labelMedium） | M3 无标题行组件；ListItem 一行最小 56dp，对 ~28dp 的工具卡标题行过重一倍 |
| 右侧动作钮 | **保留 M3 IconButton（22dp）** | 已是 M3 原生；22dp 固定约束使内置 48dp 最小触达未生效（§2.4），是既定紧凑取舍 |
| 展开内容行 | **保留自绘**（ToolGroupList/TodoItemRow）；计数文本可换 M3 Badge（可选） | ListItem 56dp 行高硬伤；Badge 16dp 圆角胶囊适合「读 N」「2/5」计数 |

**对主会话现状描述的三处修正**（§1.4）：

1. TokenUsageCard 实为裸 `Surface`（TokenUsageCard.kt:35-38），不是 M3 Card——真 M3 Card 用户仅 ToolProgressCard 与 MessageBubble（气泡层）。
2. 活跃 QuestionCard **与**历史 CollapsibleQuestionPart 都已完成 scaffold 语言迁移（QuestionCard.kt:164、QuestionPartContent.kt:99）——**EmbeddedCardContainer 现存用户只剩 FileCard 一个**，v2 中「保留组件名改内部规格」的迁移面比预想小。
3. SyntheticNotificationCard 的容器是**气泡层** MessageBubble（透明 + outline 边框变体，SyntheticNotificationCard.kt:152-161），非气泡内嵌卡——它应统一的是 C2 行为（本体点击=展开 + 右侧跳转箭头），容器不必并入 scaffold。

---

## 0. 版本基线（必查项 1）

**实际生效版本 = material3 1.5.0-alpha26**：

- app/build.gradle.kts:161 经 BOM（`compose-bom:2026.05.01`，:157）解析到 1.4.0；
- :164 显式覆盖：`implementation("androidx.compose.material3:material3:1.5.0-alpha26")`，注释标明升级动机是 M3 Toolbars（HorizontalFloatingToolbar）。

对组件可用性的影响（均经 sources jar 核对）：

| 能力 | 状态 |
|---|---|
| Card 三态（Filled/Elevated/Outlined） | ✅ 自 1.0 稳定 |
| ListItem 重写版（content lambda + leading/trailing/supporting/overline + shapes 状态变形 + 长按 + selected） | ✅ 1.5.0-alpha26 新 API，**public 重载已无 @ExperimentalMaterial3Api**（旧 headlineContent 重载 @Deprecated 过渡） |
| Badge / BadgedBox | ✅ 稳定 |
| SuggestionChip / FilterChip 等 | ✅ 稳定（32dp 固定高） |
| HorizontalFloatingToolbar | ✅ 已在用（升级动机本身） |

---

## 1. 现状容器语言核对（代码位置索引）

| 容器语言 | 代码位置 | 规格要点 | 现存用户 |
|---|---|---|---|
| ① ToolCardScaffold | tools/cards/ToolCardScaffold.kt:114-120 | AmoledSurface + ShapeTokens.smallMedium(6dp) + surface + tonal 1dp；AMOLED 纯黑 + AmoledDefaultBorder；内容 padding 4dp（:121）；标题行 16dp 图标 + labelMedium；右侧 22dp IconButton 组（:203/:219）；AnimatedVisibility 展开（:234） | 13 工具卡 + ContextToolGroupCard（ContextToolGroupCard.kt:53）+ 降级卡；**活跃 QuestionCard（QuestionCard.kt:164-169）与历史 CollapsibleQuestionPart（QuestionPartContent.kt:99-104）也已迁此语言**；PermissionCard 同构（PermissionRequestCard.kt:68-74，errorContainer 语义底色） |
| ② EmbeddedCardContainer | ui/components/EmbeddedCardContainer.kt:40-49 | surfaceContainerLow + 1dp outlineVariant 边框（AMOLED @HIGH，普通 @AMOLED）+ medium(12dp) + tonal 0 | **仅剩 FileCard**（FileCard.kt:40） |
| ③ 完全自绘 Surface | TodoListCard.kt:94-100（small 8dp + surface + tonal 1dp + AMOLED 边框）；ReasoningBlock.kt:121-129（shape=none + surfaceContainer@MEDIUM + 2.5dp 左强调条）；CompactionCard.kt:65-97（分割线形态；展开区 :101-111 透明 + outline 边框）；SyntheticNotificationCard.kt:152-161（MessageBubble 透明 + outline 语义色边框；输出区 :295-297 extraSmall + toolOutputContainerColor） | 各自规格 | 如左 |
| ④ M3 Card / 裸 Surface | ToolProgressCard.kt:47-52（真 M3 Card + surfaceVariant@FAINT 0.35 半透明 + shapes.small）；TokenUsageCard.kt:35-38（裸 Surface + small + surfaceContainerLow） | — | 如左 |

气泡层背景：MessageBubble 本身就是「M3 Card 全参数覆写」的实证（MessageBubble.kt:70-75：Card + containerColor 参数 + elevation 0 + shape medium）；assistant 气泡底色 = surfaceVariant（MessageCardAssistant.kt:160）。

### 1.4 对主会话描述的修正

见执行摘要。要点：①EmbeddedCardContainer 只剩 FileCard 一个用户（提问卡两态 2026-08-18 三轮修正后均已迁移，代码注释链完整）；②TokenUsageCard 非 M3 Card；③SyntheticNotificationCard 的容器在气泡层，属「气泡语言变体」而非「卡片容器漂移」。

---

## 2. M3 容器组件逐一评估（源码核对，必查项 2）

以下默认值全部取自 material3-android-1.5.0-alpha26-sources.jar（commonMain/androidx/compose/material3/）。

### 2.1 Card 三态

| 组件 | 默认容器色 | 默认阴影 | 默认形状 | 边框 | 对我们的适配性 |
|---|---|---|---|---|---|
| Card (Filled) | **surfaceContainerHighest**（FilledCardTokens） | **Level0 = 0dp**（无阴影，修正「Card 默认带阴影」预设） | CornerMedium → MaterialTheme.shapes.medium | null（可传） | 底色过重：嵌在 surfaceVariant 气泡内层级倒挂；须覆写 |
| ElevatedCard | surfaceContainerLow | Level1 阴影 | 同上 | 固定 null | 阴影在 AMOLED 纯黑上不可见，普通主题与我们「tonal 0 / 无投影」策略冲突；**无 border 参数**（源码 :201/:258 硬编码 null）→ AMOLED 边框语义无法表达，直接排除 |
| OutlinedCard | surface | Level0 | 同上 | outlineVariant 1dp（全强度） | 最接近 EmbeddedCardContainer，但：容器色非纯黑、边框无 alpha 分档（我们 AMOLED 用 @MEDIUM 0.70、EmbeddedCardContainer 用 @HIGH 0.92）→ 仍需全参数覆写 |

**关键结构性事实（Card.kt:89-96）**：Card 的实现是

```kotlin
Surface(
    modifier, shape,
    color = colors.containerColor(enabled = true),
    contentColor = colors.contentColor(enabled = true),
    shadowElevation = elevation.shadowElevation(...).value,
    border = border,
) { Column(content = content) }
```

- shape / containerColor / contentColor / border **完全透传**（回答任务书问题：「M3 Card 能否传自定义 shape/color 满足 AMOLED」——**能**，MessageBubble.kt:70 即现成实证）；
- **没有 tonalElevation 参数**：CardElevation 只解析 shadowElevation。v2 的「surface + tonalElevation 1dp」层级配方**只能用裸 Surface 表达**；
- Card 另有 onClick 重载（整卡点击 + 全卡涟漪），与 C1「标题行点击=展开、右侧钮独立动作」的局部交互语义不匹配。

**一个 Card 家族的真实优点（记录备用）**：CardDefaults.shape 经 ShapeKeyTokens → **MaterialTheme.shapes** 解析。本仓库 AMOLED 主题安装 AmoledShapes（Theme.kt:166，medium=2dp 近直角），用 Card 的组件在 AMOLED 下圆角自动变锐；而 ShapeTokens.smallMedium 是常量，AMOLED 下保持 6dp。若未来想要「AMOLED 卡片自动锐角」，应改用 MaterialTheme.shapes.xxx 而非引入 Card。

### 2.2 Surface（及 AmoledSurface 的定位）

Surface 暴露 color/contentColor/tonalElevation/shadowElevation/border 全参数（Surface.kt:100-106 等）。**AmoledCard.kt:98-114 的 AmoledSurface 就是它的 10 行薄参数化**（AMOLED: 纯黑 + 边框 + tonal0；普通: normalColor + tonalN），与 M3 Card 对 Surface 的包装方式同构。仓库另有 AmoledCard/AmoledElevatedCard（AmoledCard.kt:43-82）包装 Card 家族——先例说明：选 AmoledSurface 不是「绕开 M3」，而是这些卡片规格（tonal 1dp）Card 表达不了。

### 2.3 ListItem（1.5.0-alpha26 重写版）

新 API（ListItem.kt:341-361）：`ListItem(onClick, leadingContent, trailingContent, overlineContent, supportingContent, shapes, colors, elevation, contentPadding, content)`；无实验注解。

| 默认（源码） | 值 | 对卡内行的影响 |
|---|---|---|
| 一行最小高度 | **56dp**（ListTokens.ItemOneLineContainerHeight；两行 72dp、三行 88dp） | 现自绘 ToolGroupList 行 ≈26dp（16dp icon + labelMedium + 4dp 纵 padding）、TodoItemRow ≈28dp（20dp Checkbox）——换 ListItem 行高**翻倍**，展开 5 行的 ContextToolGroup 从 ~130dp 涨到 ~280dp |
| ContentPadding | 水平 10dp（ItemStartSpace/EndSpace），纵向 12dp | 卡内嵌套后水平累计 20dp+，气泡内卡片进一步缩窄内容区 |
| 容器色/层级 | Surface + elevation 0；shapes 支持按压/选中状态变形（圆角胶囊 morph） | 状态涟漪/变形是真实优点——但这些行的点击目标是「打开文件」这类次要动作，不需要 56dp 触达 |

判断：**ListItem 是「全屏列表行」规格，不是「卡内行」规格**。它的价值（48dp+ 触达、状态 morph、a11y 组合语义）恰是聊天流卡片要压缩掉的维度。保留自绘行；若未来出现「卡内主操作行」（如文件 diff 跳转行）可个案采用。

### 2.4 Badge

源码（Badge.kt:151-175）：无内容 = 6dp 圆点；有内容 = defaultMinSize(16dp) + CornerFull 胶囊；**默认 containerColor = Error**（BadgeDefaults）——作计数用时必须覆写 `containerColor = MaterialTheme.colorScheme.primary`（或 surfaceVariant 系），参数齐全、AMOLED/动态取色兼容好。

适配对象：ContextToolGroupCard 标题里的「读 N · 搜索 M」（现为纯文本拼接，ContextToolGroupCard.kt:41-51）、TodoListCard 的「2/5」（TodoListCard.kt:131-135）。视觉上比纯文本更有「计数徽章」语义，也符合 M3 First。**可选采纳**（非必需——纯文本在 labelMedium 标题行里更轻）。

### 2.5 其他容器组件（快速排除）

| 组件 | 结论 |
|---|---|
| SuggestionChip / FilterChip（32dp 高，ChipsTokens.ContainerHeight） | 芯片语义 + 固定高度，作卡片容器密度/层级都不符；计数/标签场景已有 CompactTag。排除 |
| ElevatedCard 见 §2.1（无 border 参数，排除）；ExposedDropdownMenu / Slider 等 | 输入控件，与容器问题无关 |
| HorizontalDivider / LinearProgressIndicator / Checkbox / IconButton | 已是各卡片内容层在用的 M3 原生件（内容层 M3 化程度已高） |

### 2.6 IconButton 与触达面积（22dp 的真相）

IconButtonImpl 内置 `minimumInteractiveComponentSize()`（IconButton.kt:245）保障 48dp 最小触达；但 scaffold 传入的 `modifier.size(22.dp)` 在链上**先固定约束**（:241-243 modifier 最前），48dp 扩展不再生效——**22dp 即实际触达面积**。这是紧凑密度与 M3 触达规范的显式取舍（历史上「点展开变复制」误触 bug 与该区域拥挤直接相关，ToolCardScaffold.kt:214-216 注释）。v2 的「去冗余 chevron」会直接缓解此问题，与本调研一致。

---

## 3. 分类对比：M3 原生方案 vs 现自绘（必查项 4）

紧凑度基准：scaffold 标题行（16dp icon + labelMedium）≈ 24-28dp；M3 Card + ListItem 标题行 ≥ 56dp + Card 内容边距。

| 类 | 卡片 | M3 原生最优解 | 现自绘 | 紧凑度 | 视觉层级 | 主题兼容 | 交互语义 | 迁移成本 | 可维护性 | 裁决 |
|---|---|---|---|---|---|---|---|---|---|---|
| C1 可折叠信息卡 | 13 工具卡 / ContextToolGroup / TodoList / Reasoning / Synthetic(行为) / 降级卡 | Card(onClick) + ListItem 行 | ToolCardScaffold | M3 −（行高×2） | M3 −（surfaceContainerHighest 倒挂） | 平（覆写后等价） | M3 −（整卡点击≠标题行展开） | 高（全量重排） | 平 | **自绘** |
| C2 跳转卡 | TaskToolCard / SyntheticNotification | Card + trailing IconButton | scaffold + rightSideExtras 箭头 | 同上 | 同上 | 平 | 平（两者都可做行点击+尾部钮；Card 需禁用整卡 onClick 再自绘行点击，反而绕路） | 高 | 平 | **自绘** |
| C3 瞬态进度卡 | ToolProgressCard | M3 Card（现状） | — | 平（单行卡） | 平（半透明 surfaceVariant@FAINT 本就是覆写） | 平 | 平（无交互） | **低——唯一建议动**：换 AmoledSurface 统一容器，半透明色作 normalColor 传入即可 | 升（并入统一语言） | **迁容器** |
| C4 静态信息卡 | TokenUsageCard / FileCard | OutlinedCard ≈ EmbeddedCardContainer | 裸 Surface / EmbeddedCardContainer | 平 | 平 | 平（均需覆写边框 alpha） | 平（无交互） | FileCard 低（v2 已定：EmbeddedCardContainer 内部改 scaffold 规格）；TokenUsage 低 | 升 | **按 v2 迁** |
| C5 输入型 | 活跃 QuestionCard / PermissionCard | 无对应（M3 无内嵌表单卡组件） | AmoledSurface scaffold 规格（已就位） | — | — | — | — | 0（已迁移） | — | **维持** |
| 语义例外 | 警示卡 / Compaction 分割线 / Reasoning 左条 | M3 无对应形态（无分割线卡/强调条卡组件） | 自绘 | — | — | — | — | — | — | **例外合理，维持** |

气泡内嵌套观感专项：assistant 气泡 = M3 Card(surfaceVariant, elevation 0)（MessageBubble.kt:70-75）→ 内嵌卡 = Surface(surface + tonal1dp)。M3 官方对嵌套容器的机制就是 surface 色阶 + tonal elevation（无 nested-card 组件），本方案与之同向；AMOLED 下层级退化为「纯黑 + 边框承担」，同样是 AmoledSurface 既定行为。唯一观感风险点：FileCard 从「surfaceContainerLow + 双主题边框」改为「无边框 + tonal」后，普通主题下与气泡（surfaceVariant）的对比度略降——属 v2 既定方向的细节，留 V6 像素验收（见 §7）。

---

## 4. M3 原生硬伤清单（必查项 3）

1. **Card 无 tonalElevation 参数**（Card.kt:89-96 仅透传 shadowElevation）——结构性缺口，参数覆写无法绕过；v2 层级语言只能经裸 Surface 表达。
2. **ListItem 固定最小行高 56/72/88dp**（ListTokens）——卡内行信息密度硬伤，展开区高度翻倍。
3. **ElevatedCard 无 border 参数**（源码硬编码 null）——AMOLED 边框语义不可表达；其 Level1 阴影在纯黑背景不可见，双重不适配。
4. **任务书预设修正**：Filled Card 默认**无阴影**（Level0）——「Card 默认 elevation 阴影在 AMOLED 不可见」不成立；AMOLED 的真实问题是层级表达（tonal 不可用 + 纯黑上色阶失效），我们现有「边框承担层级」正是正确对策。
5. **Badge 默认 Error 色**——计数场景必须覆写 containerColor（一行参数，小成本）。
6. **无嵌套卡片组件**——M3 把嵌套层级交给 surface 色阶/tonal，与 v2 的 AmoledSurface 路线一致，无更原生的选项可抄。

---

## 5. 对统一方案 v2 的校验结论

- **容器层：不推翻，维持「单容器语言 = AmoledSurface + smallMedium + surface + tonal 1dp（AMOLED 纯黑 + 边框）」**。M3 Card 家族无一是更优解（§2.1、§4）；AmoledSurface 即 M3 Surface 的参数化包装，与「M3 First」不冲突。EmbeddedCardContainer「保留组件名、内部改 scaffold 规格」方案成立，且因只剩 FileCard 一个用户，迁移面极小。
- **行为层**（本体点击=展开/收起 + 去冗余 chevron）：与本调研无冲突，且 chevron 精简直接缓解 22dp 钮拥挤（§2.6）。
- **建议增量（不阻塞 v2）**：
  1. ToolProgressCard / TokenUsageCard 迁统一容器（C3/C4 无交互卡，成本最低，可作 v2 首批试点验证观感）；
  2. TodoListCard 容器 small(8dp)→smallMedium(6dp) 对齐（其 surface+tonal1dp 已同规格）；
  3. SyntheticNotificationCard 只统一 C2 行为，容器留在气泡层不动；
  4. 可选：ContextToolGroup「读 N」/ TodoList「2/5」换 M3 Badge（覆写 containerColor）；
  5. 远期可评估把 ShapeTokens.smallMedium 换成 MaterialTheme.shapes 系（获得 AMOLED 自动锐角），涉及全卡观感回归，单独立项。

## 6. 最终分层推荐（必查项 4 的答案归纳）

| 层 | 最优选择 | 一句话理由 |
|---|---|---|
| 容器 | 自定义 AmoledSurface（M3 Surface 参数化） | Card 表达不了 tonal 1dp；覆写后等价，徒增一层 |
| 标题行 | 自绘 Row（M3 Icon + Text + ripple clickable） | M3 无此密度档组件；ListItem 56dp 过重 |
| 右侧动作钮 | M3 IconButton 22dp（复制/打开文件/跳转箭头）+ 单一 chevron | 已是 M3 原生；去冗余 chevron 改善拥挤 |
| 展开内容 | 自 AnimatedVisibility + 自绘行；计数可上 M3 Badge；分割线/进度条/Checkbox 已 M3 | 行密度是核心价值；Badge 是低风险原生增益 |

## 7. 待人工验收清单（V6 像素项，非阻塞）

- FileCard 迁 scaffold 规格后，普通主题下与 surfaceVariant 气泡的边界感（对比度略降风险）；
- ToolProgressCard 半透明底（surfaceVariant@FAINT）在统一容器下的进行中观感；
- Badge 替换纯文本计数后标题行的拥挤度（若采纳 §5.4）。

---

## 附：取证材料索引

- M3 源码：`~/.gradle/caches/modules-2/files-2.1/androidx.compose.material3/material3-android/1.5.0-alpha26/…-sources.jar` → /tmp/m3src/commonMain/androidx/compose/material3/{Card.kt, ListItem.kt, ListItemDefaults.kt, Badge.kt, IconButton.kt, Surface.kt, tokens/*.kt}
- 版本：app/build.gradle.kts:157,161,163-164
- 应用侧：文中已逐一标注 `文件:行号`
