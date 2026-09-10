# UI 约定 — OC Beacon

> 本文件是 UI/主题约定的详细参考，由 AGENTS.md 索引。AGENTS.md 只保留索引行，细节在此维护。

## 服务器类型交互统一铁律（2026-09-07 用户裁决）

- **单一交互语言**：DSH 服务器面与 OpenCode 服务器面 UIUX 高度统一——服务器类型只产生**能力位差异**（某功能有无，经 `ServerCapabilities` 门控），**不产生交互模式差异**（同一功能在两种服务器上手势/形态/入口不同=违规）。
- **参照系 = 本 app 的 OpenCode 服务器面**（oc-beacon 既有交互语言，2026-09-07 用户二次澄清定音）：DSH 面的形态/交互/入口向 app 内 opencode 面看齐——**不是** opencode 官方 web 端，**更不是** DSH web 端（#326 曾以 dsh web primaryStops 校准单键行为=参照系错位教训）。
- **实现与形态解耦**：UIUX 全局统一（按 opencode 面模式），背后实现各自最优——**DSH 后端有原生接口就用 DSH 的**（如 session/queue），**opencode 后端没有的就由 oc-beacon 自行实现**（客户端模拟）；对用户完全不可见。范例：排队机制——opencode 面=oc-beacon 自实现队列，DSH 面=服务器 queue 域，两者 UIUX 同构。
- **DSH 独有能力的呈现**（agentPreset 向导/provider 目录/配对/goal 等 opencode 面没有的）：用统一组件库的既有模式呈现（标准对话框/底部 sheet/长按菜单），不发明 DSH 专属交互；能力位门控隐藏即可，不为它改共享交互。
- **考据纪律**：断言「opencode 面的行为」前以**本仓库代码/git 史**为准（那是参照本体）；opencode/DSH 官方客户端源码仅作实现接口与能力语义参照。

### 通用与私有的差异分级（2026-09-10 补充，落地 ServerAdapter 架构）

- **差异只能来自能力，不能来自类型**：同一功能在不同服务器面必须同入口 / 同手势 / 同组件壳 / 同动效语义；服务器类型只决定"有没有这个能力"。
- **L0 形态层（禁止差异）**：入口位置、手势、组件壳、动效语义、主题令牌（色 / 字 / 距 / 形）。
- **L1 能力层（允许，须声明式）**：条目的有无、启用 / 禁用、能力专属标签 / 图标（同排版）、不可用原因；由统一贡献注册表的能力门控声明表达，不得写成服务器类型分支。
- **L2 内容层（允许私有）**：能力专属内容 / 表单 / 预览，渲染在统一壳内，必须复用令牌与统一 scaffold；不得引入新交互模式、不得为同一功能提供第二套壳。
- **隐藏 vs 禁用**：附加型动作不支持则隐藏；核心交互中被用户预期的步骤不支持则禁用并给出原因；两者都不改入口 / 手势。
- **实现来源不可见，但语义必须等价**：同一能力可能由服务器原生提供、也可能由客户端实现（含模拟）；两者形态必须一致，但**客户端实现必须覆盖到可观察语义**（重启保留、跨客户端可见性、顺序与去重、错误语义）——覆盖不到的差异**不得假装同构**，必须按 L1 显式降级（隐藏 / 禁用 / 标注「仅本地」）。默认优先服务器原生，服务器无该能力时才用客户端实现。
- **强制**：Android Lint 自定义规则（既有 lint 门禁 + baseline）拦"通用界面 / 通用壳的服务器类型分支""通用界面 import 具体服务器类型组件""硬编码色 / 距 / 时长绕过令牌""服务器类型引用超出白名单"；无法静态判定的（同一功能同一组件、L0/L1/L2 归属、隐藏 vs 禁用）由统一审计清单把关（底稿=既有服务器类型交互统一审计矩阵，BAD 归零）。

## 命令选择回填铁律（2026-09-09 用户裁决，#372）

- **面板/建议列表中选择命令一律回填输入框**（"/name " 带尾空格、光标置尾），由用户补参数后手动发送——**不得 tap 即直达派遣**。适用范围：三面（OpenCode V1/V2、DSH）一切 server 型命令与 skill 节；例外仅两类：client 本地动作（/rename 对话框、/shell 模式切换等非文本命令）与参数恒空的专用快捷钮（plan-off chip、权限预设切换等独立 UI 动作）。
- **依据**：三面后端命令均支持参数（V1/V2 `/command` arguments 字段、DSH `commands/execute` 整行 line）——回填永不丧失直达能力（直接发送即直达），反之直达丧失补参机会。
- **requiresInput（input.hint）位仅作提示展示**（如占位提示可带参数），不改变选择行为。
- 新增命令入口（面板/快捷键/语音等）一律遵守本规则；违例=交互模式差异，同「服务器类型交互统一铁律」处置。

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
