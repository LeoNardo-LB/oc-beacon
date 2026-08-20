# UI 约定 — OC Beacon

> 本文件是 UI/主题约定的详细参考，由 AGENTS.md 索引。AGENTS.md 只保留索引行，细节在此维护。

## Material 3 First

- **优先使用 Material 3 原生组件和原生样式**。能用 `LinearProgressIndicator`、`CircularProgressIndicator`、`IconButton` 等原生组件解决的，不要自定义 Canvas 绘制。
- **优先使用 Material 3 原生配色和动效**。颜色用 `MaterialTheme.colorScheme` 中的语义色，间距用 `dp` 常量或 Material token，不要硬编码。
- **仅在原生组件无法满足需求时才自定义**（如特殊动画效果），自定义组件也应尽量复用 Material token 系统。
- **禁止引入额外 UI 依赖库**（如 Accompanist），除非有充分的理由并经过讨论。

## Theme Token System

### Alpha tokens (Alpha.kt)

7 个语义透明度常量 — SELECTED(0.12) / DIFF_BG(0.10) / FAINT(0.35) / MUTED(0.50) / MEDIUM(0.70) / HIGH(0.80) / AMOLED(0.92). 用它们代替硬编码的 `.copy(alpha = Xf)`。

### Spacing tokens (Spacing.kt)

6 个网格常量 — XS(4) / SM(8) / MD(12) / LG(16) / XL(24) / XXL(32)。标准间距用 `SpacingTokens.LG.dp` 代替硬编码 `16.dp`。

### Shape tokens (Shape.kt)

`AppShapes` 用于 MaterialTheme，`ShapeTokens` 对象用于组件级直接引用。

### Motion tokens (Motion.kt)

语义化时长常量（BREATH_CYCLE, PULSE_CYCLE, TERMINAL）。用它们代替硬编码的 `AnimationSpec` 时长。

### Button tokens (ButtonTokens.kt)

集中式按钮样式 — `filledColors()` / `dangerColors()` / `amoledBorder()` + `CompactPadding` / `StackSpacing` / `RowSpacing`。代替每次调用 `ButtonDefaults.colors` 和临时的 border 规格。导入：`dev.leonardo.ocbeacon.ui.theme.ButtonTokens`。

### ListItem tokens (ListItemTokens.kt)

Material 3 `ListItem` 内容 padding 的三种密度级别 — `ContentPaddingSmall` / `ContentPaddingMedium` / `ContentPaddingLarge`。代替 ListItem 内容上的硬编码 `padding`。

### Sheet tokens (SheetTokens.kt)

主对话抽屉（ModalBottomSheet）统一高度 — `SheetTokens.ChatSheetHeightFraction = 0.75f`（2026-08-20 用户决策：主对话内所有抽屉屏占比一致，min = max = 75% 屏高，固定高度——内容少时留白不塌缩，内容多时内部滚动）。标准三件套：抽屉内容根 `Modifier.height(LocalConfiguration.current.screenHeightDp.dp * SheetTokens.ChatSheetHeightFraction)` + 内部列表 `weight(1f)` + `rememberModalBottomSheetState(skipPartiallyExpanded = true)`（避免固定高度先落半展开锚点）。现覆盖 TaskSheet / ModelPickerDialog / QuickNavigateSheet / PendingTodoSheet；新增主对话抽屉必须遵循。

### 暗色主题

信任 Material3 `darkColorScheme()` 默认值。只在 Theme.kt 中覆盖 6 个品牌差异化 token。

### Colors (Color.kt)

品牌常量 + 语义化 `DiffAdded`/`DiffRemoved`。无死代码。

## Markdown 表格渲染（两端一致性）

文件浏览（WebView）与主对话流（Compose）的表格必须保持同一动态列宽上限公式 `cellCap = max(容器宽 ÷ 列数, MIN_CELL)`，MIN_CELL 两端统一 **120dp（Compose）/ 120px（WebView CSS）**。改一端必须同步另一端；代码块（`pre` / mikepenz code 组件）保持容器内滚动、不主动换行。设计细节见 `docs/archive/specs/2026-08-04-markdown-table-wrap-design.md`。
