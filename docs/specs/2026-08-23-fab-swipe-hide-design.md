# 双 FAB 会话级滑动隐藏/展示设计（#192）

> 状态：已定案（2026-08-23 grilling 两轮七问全结）· 待实现
> 关联：backlog #192 · docs/journal/2026-08-23-acceptance-closeout.md

## 1. 背景与需求

主对话屏两个 FAB（ChatScreen.kt:860-877 挂载区，!isTerminalMode 门控）：

- **左下** `ChatScrollBottomFab`（跳到底部）：滚离底部自动出现、回底自动消失（`if (isAtBottom) return`）
- **右下** `ChatFabMenu`（ToggleFloatingActionButton + 堆积/TODO/智能体/Shell 四入口）：收起态带未读总数角标，展开态外点/BackHandler 收起

用户需求：两 FAB 可滑动隐藏与展示（**会话级别**）；隐藏后对应屏幕边缘留半透明小拉杆；右菜单展开态需先收拢成按钮再隐藏。

价值：长会话阅读时 FAB 遮挡内容，可整会话收走，需要时一拉即回。

## 2. 定案决策（grilling 2026-08-23，用户逐项确认）

| # | 决策 |
|---|------|
| D1 | **仅会话内生效**：状态内存级，离开会话/进程重启复位为显示；**不落盘** |
| D2 | **主/子会话独立记忆**：子会话是独立导航入口（独立 ChatViewModel），状态互不影响 |
| D3 | **手动隐藏优先**：左 FAB 隐藏期间「滚离底部自动出现」暂停；拉回后恢复自动显隐 |
| D4 | **恢复手势双通道**：点按拉杆即展开；或按住向屏内拖（跟手位移、松手过半自动展开+回弹） |
| D5 | **右 FAB 两段式**：展开态右划=收拢成按钮（与 back/外点同语义）；收起态右划=滑出隐藏 |
| D6 | **拉杆保留角标**：右拉杆显示四入口未读总数（实时更新）；左拉杆无角标 |
| D7 | **拉杆形态**：贴左/右屏缘、与原 FAB 同底边（bottom 16dp）；半圆凸出约 10dp、高约 28dp、半透明 secondaryContainer 系 |
| D8 | **动画**：隐藏=向屏缘滑出+渐隐收拢；恢复=反向；拖拽跟手位移由 Animatable 回弹 |

## 3. 设计

### 3.1 状态与归属

`ChatFabVisibilityState`（每导航入口一份，随该入口 ChatViewModel 存续——主/子会话各自 VM 实例即 D2 天然成立）：

- `bottomFabHidden: Boolean`（左）、`menuFabHidden: Boolean`（右）
- 左 FAB 显示条件 `!bottomFabHidden && !isAtBottom`（D3：hidden 挂起自动显隐）
- 无持久化；VM 随返回栈弹出清理即复位（D1）

### 3.2 手势

- 两 FAB 各挂 `detectHorizontalDragGestures`（水平累计位移超 ~40dp 判定）：
  - 左 FAB：向左过阈值 → 隐藏（向左缘滑出动画）
  - 右 FAB：`expanded` 时向右 → 仅收拢（复用现有 collapse 路径，不隐藏）；收起态向右 → 隐藏
- 拉杆：`clickable`（点按恢复）+ 拖拽（向屏内跟手 offset，release 按越过半程判定恢复/回弹，Animatable 收尾）
- 垂直滚动互不抢占：仅水平位移主导时消费（awaitTouchSlopOrCancellation 方向判定）

### 3.3 拉杆组件

`FabEdgeTab(side, badge, onRestore)`：Box 半圆（RoundedCornerShape 单侧半圆）+ 角标（badge>0 时 Badge 小号）；右拉杆 badge=totalBadge 实时重组；左拉杆 badge=null。

### 3.4 挂载点与参数

- ChatScreen 860-877：两 FAB 条件渲染（hidden → 渲染对应 EdgeTab）
- `ChatScrollBottomFab` 增 `hidden/onHide`；`ChatFabMenu` 增 `hidden/onHide`（expanded 内部自持，右划收拢走既有状态）
- 终端模式门控、键盘弹出自然遮挡语义、菜单外点收起层与 BackHandler 全部不变

### 3.5 明确不改动

FAB 现有视觉规格（44dp/描边/色系/同径修复）、菜单行为、SSE 滚动稳定性铁律（不触列表管线）、通知/红点数据源。

## 4. 测试与验证

- **JVM**：`ChatFabVisibilityStateTest`——D3 暂停语义（hidden 时 isAtBottom 变化不显形）/ 两 FAB 独立 / 恢复后自动显隐回归
- **真机 E2E**：左滑隐藏→拉杆→拖拽恢复；点按恢复；右 FAB 展开态右划收拢不隐藏、再划隐藏；角标实时；进子会话独立、返回主会话保持；杀进程复位
- **维度 5**：手势手感/动画观感/拉杆可发现性——用户验收
- ChatScreen.kt 编辑协议全程遵守（Read → Edit → compileDevDebugKotlin → commit 循环）

## 5. Out of Scope

- 持久化到磁盘（D1 显式否决）
- 拉杆位置/尺寸/透明度设置项
- 长按、双击等额外手势
- FAB 自动隐藏策略（如阅读模式定时隐藏）
