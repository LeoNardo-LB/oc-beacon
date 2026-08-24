# 聊天流卡片体系统一设计（容器语言 + 交互契约）

- **状态**：已立项（backlog #215），未实施
- **日期**：2026-08-24
- **来源**：用户规划诉求（卡片行为统一 + 容器共存性重审）；主会话 20+ 卡片组件全量调研；M3 适配调研（docs/research/2026-08-24-m3-card-container-fit.md）
- **裁决**：用户授权推翻 2026-08-18「两套容器语言并存」裁决的残留态（见 §2）

## 1. 问题

聊天流随 SSE 出现 20+ 种卡片，存在两组不统一：

1. **容器语言 4 套并存**（漂移，无功能分工）：① ToolCardScaffold（AmoledSurface + smallMedium 6dp + surface + tonal 1dp 无边框）② EmbeddedCardContainer（surfaceContainerLow + 1dp 边框 + 12dp）③ 自绘 Surface（TodoList 8dp / SyntheticNotification 12dp / Compaction 分割线 / ReasoningBlock 强调条）④ 裸 Surface/Card（ToolProgressCard、TokenUsageCard）
2. **点击行为三态**：scaffold 13 卡本体点击=展开（另有冗余 chevron）；TaskToolCard 本体=跳转（#181 修复产物，chevron 承担展开）；SyntheticNotification/ReasoningBlock/Compaction 本体不可点（2026-08-16「职责分离」规范产物，仅按钮可点）

## 2. 历史裁决重审（推翻依据）

2026-08-18 三次修正时间线（git 取证）：

| 时间 | commit | 内容 | 用户反馈 |
|------|--------|------|---------|
| 11:15 | a90dbead | 建 EmbeddedCardContainer，提问卡跟 FileCard | 「提问卡应有基础容器」 |
| 11:36 | d9cbb252 | 全家迁移至 EmbeddedCardContainer | 「统一尽量做」 |
| 三修 | 回滚 d9cbb252 | 提问卡反向看齐 scaffold 语言 | 「方向做反了」——提问卡跟工具卡，非工具卡跟提问卡 |

**结论**：回滚否决的是基准选错，不是统一本身；「两套并存」是方向纠正后的中间残留态。结构性证据：两组卡片嵌套位置相同（同在 assistant MessageBubble 内，无功能分野）；d9cbb252 自己保留的例外（语义警示卡）依然有效。用户 2026-08-24 授权推翻，按纠正后的方向做完整。

## 3. M3 适配调研结论（详见 research 文档）

- **容器层维持 AmoledSurface**：material3 1.5.0-alpha26 的 Card 只透传 shadowElevation、**无 tonalElevation**，v2 层级语言只能裸 Surface 表达；全参数覆写 Card ≡ 现有 AmoledSurface（后者本就是 M3 Surface 薄参数化，不违反 M3 First）。ElevatedCard 无 border 参数排除
- **ListItem 不采用**：一行 min 56dp，现自绘行 ~26-28dp，高度翻倍
- **Badge 可选用于计数**（「读 N」「2/5」）：默认 Error 色必须覆写
- **IconButton 22dp 隐患**：size() 先固定约束使 M3 48dp 最小触达未生效——统一时顺带决策
- 现状修正：TokenUsageCard 是裸 Surface 非 M3 Card；**EmbeddedCardContainer 只剩 FileCard 一个用户**（活跃+历史提问卡均已迁 scaffold 语言）；SyntheticNotificationCard 容器在气泡层（MessageBubble 透明+边框）

## 4. 分类与统一契约

| 类 | 成员 | 契约 |
|----|------|------|
| **C1 可折叠信息卡** | 10 专属工具卡 + ToolCallCard + ContextToolGroupCard + TodoListCard + CompactionCard(展开态) + ReasoningBlock | **本体点击=展开/收起唯一入口**（去 chevron）；右侧标准钮组；容器统一 |
| **C2 跳转卡** | TaskToolCard、SyntheticNotificationCard | 本体=展开（同 C1）；跳转=右侧统一箭头钮；SyntheticNotification 保留 [定位]；容器不并入（气泡层）只统一行为 |
| **C3 瞬态进度卡** | ToolProgressCard | 非交互维持；容器统一 |
| **C4 静态信息卡** | TokenUsageCard、FileCard | 非交互；容器统一 |
| **C5 输入型交互卡** | 活跃 QuestionCard、PermissionCard | 表单语义不参与折叠统一；容器已是 scaffold 语言 |

**例外清单（不收敛，语义优先）**：①语义警示卡 RevertBanner/RetryBanner/ErrorPayload（颜色即含义）②CompactionCard 收起态分割线形态（流分隔符）③C5 表单卡交互。

## 5. 统一规范

- **容器**：AmoledSurface + ShapeTokens.smallMedium(6dp) + surface + tonalElevation 1dp、无边框、AMOLED 纯黑+边框——参数组提为共享常量（单一真相源，防再漂移）；AmoledSurface 边框 alpha 疑似反置顺带修
- **交互**：本体点击=展开/收起（含触觉反馈）；running 无内容时本体点击无操作；跳转/打开文件/定位=右侧钮；复制钮维持（Snackbar 通道 #137）
- **右侧钮规格**：IconButton 22dp + 14dp icon 统一；评估 48dp 最小触达（minimumInteractiveComponentSize）启用后的布局影响——若启用导致行高膨胀，记录取舍后维持 22dp
- **动画**：展开统一 tween(150)（spring 收起回弹问题，SyntheticNotification 已实证）
- **EmbeddedCardContainer**：FileCard 迁统一容器后**删除该组件**（迁移面仅 1 用户）

## 6. 实施分批

| 批 | 内容 | 风险 |
|----|------|------|
| **批1 试点** | ToolProgress/TokenUsage 迁统一容器 + TodoList 圆角 8→6dp | 纯视觉低风险，快速验证容器语言 |
| **批2 行为核心** | scaffold 家族去 chevron + TaskToolCard 跳转挪右侧箭头钮 + running 态反馈 | 动 testTag/断言；须先于本批跑完全量插桩定基线 |
| **批3 收编** | FileCard 迁移+删 EmbeddedCardContainer；ReasoningBlock/TodoList/Compaction 展开态容器统一 + 本体点击=展开（推翻 08-16 规范，代码注释同步勘误） | SSE 滚动铁律回归必跑 |

批2/批3 涉及 #211 刚修的 androidTest 断言面（testTag/可见性），**实施前全量插桩必须绿**（当前 135 测 3 败中 #214 未决——#214 修复先行）。

## 7. 验收标准

- C1/C2 全卡：本体点击=展开/收起，无冗余 chevron；TaskToolCard 跳转走右侧钮
- 容器语言唯一（例外清单外）；亮/暗/AMOLED 三主题 E2E 截图对比
- 全量插桩绿 + 单测绿 + SSE 慢速流式回归（滚动稳定性铁律）+ Maestro 主流程
