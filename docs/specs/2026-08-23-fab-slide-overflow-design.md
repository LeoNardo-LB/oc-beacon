# FAB 贴边滑动越界与展开溢出处理设计 Spec

> 状态：已定案待实现（grilling 会话 Round 1–3 全部定案，用户逐项拍板）
> 日期：2026-08-23
> 来源：用户 bug 报告 + 拷问对齐（Q1–Q11，含 Q10 用户纠偏「顶到顶部」语义）
> 关联：#192 v6（fabEdgeVerticalSlide 引入批次，见 ChatFabMenu.kt 头注）

## Problem Statement

**Bug 1（钻顶栏）**：`ChatFabMenu` / `ChatScrollBottomFab` 共用的 `fabEdgeVerticalSlide` 上限公式
`(screenHeightDp - 160.dp).toPx()` 用**整屏高**算**容器内**位移。FAB 真实容器是 Scaffold
content Box（高 = 屏高 − 状态栏 − 顶栏 64dp − 底部 inset）。两坐标系差一整个顶栏+inset：
拖到极限时按钮顶缘屏幕 Y ≈ 92dp − 底部inset，而顶栏底缘 ≈ 165dp+ → 按钮钻进顶栏底下
70–100dp（houji 量级推算）。魔法数 160 想避开顶栏但减错了基准。

**Bug 2（菜单出界）**：M3 `1.5.0-alpha26` 的 `FloatingActionButtonMenu`（实验 API）无展开
方向参数，items 固定向按钮上方排。按钮停高位时 4 项药丸（4×44dp + 间距 ≈ 224dp）整列
顶出容器。

## 定案设计（用户逐项拍板）

### D1 上限根修：容器实测高度（Q1）

- `fabEdgeVerticalSlide` 内读**真实容器高度**（`Modifier.layout` / `onSizeChanged`），
  上限 = 容器高 − 按钮高 − 8dp 边距；魔法数 160 移除。任何机型/横屏/分屏/键盘态天然正确。
- 容器尺寸变化（键盘、分屏）时对 `rememberSaveable` 的存量 `offsetY` 重新 coerce，
  防陈值越界。

### D2 展开溢出：保留官方组件 + 溢出量整体下移（Q2/Q6/Q10）

- 官方 `FloatingActionButtonMenu` / `ToggleFloatingActionButton` /
  `FloatingActionButtonMenuItem` 样式零改动，不自研翻转容器。
- 新增第二个 offset 分量 `expandShift`（临时态，不持久化）：

  - **判定时机（Q3）**：只在点击展开那一瞬间计算一次；拖动过程中菜单收起、不存在连续抖动。
  - **计算**：剩余空间 = 按钮顶缘到容器顶（由 offsetY + 实测尺寸得出）；
    menuHeight = 菜单内容高（实测优先，避免官方内部间距常量漂移）；
    若 剩余 < menuHeight：`expandShift = menuHeight − 剩余 + 8dp` 顶边距，否则 0。

- **语义（Q10 用户纠偏定案）**：「顶到顶部」而非「钉在顶部」——菜单从按钮向上长，
  长到撞顶为止，**溢出量**把整体（按钮+items）顶下来。用户示例：菜单高 100、剩余空间
  60 → 下移 40+边距。最终几何 = items 顶缘贴容器顶+8dp、按钮在其下，但由「溢出推动」
  推导而非「钉顶锚定」。
- **边界连续性**：空间恰好够时 expandShift=0 且 items 自然达顶，与溢出路径在临界点
  几何一致，无模式跳变。

### D3 动画编排：同时进行，平滑不闪现（Q8/Q11 + 用户强调）

- **展开**：items 交错浮现的**同时**整体下滑就位（~300ms tween，与官方展开节奏对齐）——
  视觉即「菜单长高把按钮顶下来」；严禁瞬移闪现。
- **收起**：items 消退 + expandShift 动画回 0，按钮滑回停放位（offsetY 保持不动）。

### D4 展开中拖动：收起并继续拖（Q7）

- drag 开始：`expanded=false`，并把当前 expandShift **瞬时并入** offsetY（位置连续、
  不双计），expandShift 归零；此后拖动直接跟手，松手 coerce 回边界。

### D5 ⬇ 滚动到底 FAB（Q4）

- 共用修好上限的 `fabEdgeVerticalSlide`；两 FAB 位移各自独立（rememberSaveable 分开）；
  无菜单/溢出逻辑参与。

## 验收清单（Q5：houji 真机人工，维度 5）

1. 拖到最顶：不钻顶栏，留 8dp 边距；
2. 高位停放后点开：四项全可见、顶到容器顶+8dp、按钮被**平滑**顶下（~300ms，无闪现）；
3. 中低位点开：维持现状向上展开；
4. ⬇ FAB 同边界约束，位移独立；
5. 键盘弹起状态无异常。

## 实现注记

- `expandShift` 只作用于 `ChatFabMenu`（⬇ FAB 不参与）。
- 外点收起层 `fillMaxSize` 不随平移变化，保持现状。
- `BackHandler(enabled = expanded)` 收起语义不变。
- M3 版本约束：`FloatingActionButtonMenu` 为实验 API（当前 1.5.0-alpha26），
  升级时留意参数面变化。
