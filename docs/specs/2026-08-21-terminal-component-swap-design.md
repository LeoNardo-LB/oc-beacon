# 终端组件换件设计：termlib → Termux terminal-view/emulator（#189）

> 日期：2026-08-21 · 状态：实施中
> 来源：批次验收问题② + 用户明确指令「有关终端，我觉得bug挺多的，最好引入主流的终端组件」
> 取证：真机 houji（本文 §1）

## 1. 问题取证（2026-08-21 真机）

现状架构（换件前）：

```
WebSocket PTY（OpenCode 服务器 /api/shell，ServerTerminalWorkspace 保留层）
   ↕ PtyToTermlibAdapter
termlib 0.1.0 TerminalEmulator（VT 内核，Maven 依赖）
   ↕ termlib Compose Terminal 组件（Canvas 渲染 + BasicTextField IME + 选择 + 缩放）
   + KeyboardOverlay（自研额外按键条，保留层）
```

真机复现的缺陷（vim 为试金石）：

| 症状 | 细节 | 定位 |
|------|------|------|
| vim 插入模式打字无回显 | 屏幕出现输入法「全部」模式标签——keyevent 被 IME 组合输入拦截，未直达 PTY | termlib Compose 组件 IME 处理 |
| ESC 键无响应 | KEYCODE_ESCAPE 后仍处插入模式 | termlib 键盘层 keycode 映射 |
| 历史主观报告 | 用户「bug 挺多」（多轮会话累积） | — |

根因定性：termlib 是 ConnectBot 2024 年新拆的库（0.1.0，Maven 最新），键盘/IME 处理不成熟；且是**外部依赖不可修**。这不是集成层 bug，是组件代际问题——换件即根因修复。

## 2. 选型

| 候选 | 结论 |
|------|------|
| **Termux terminal-view + terminal-emulator**（vendored） | ✅ 采用：Android 终端事实标准（10+ 年、亿级设备）；渲染/手势/IME/文本选择/缩放全套；持续维护 |
| 修 termlib | ❌ 依赖闭源，不可修；上游无更新（0.1.0 即最新） |
| ConnectBot 主仓 TerminalView | ❌ 未单独发布 maven；与其 app 架构耦合更深 |
| jackpal Android-Terminal-Emulator | ❌ 2016 年起停更（termux/connectbot 皆其 fork 后代） |

### 许可证（关键决策依据，已核验原文）

termux-app 仓库根 `LICENSE.md`：整体 **GPLv3 only**，但明确例外条款——

> Exceptions
> - Terminal Emulator for Android code is used which is released under Apache 2.0 license. Check [`terminal-view`](terminal-view) and [`terminal-emulator`](terminal-emulator) libraries.

即这两个模块为 **Apache 2.0**（继承自 jackpal 原始许可）。本仓库 MIT，Apache 2.0 库 vendored 引入合法（保留原始版权与许可声明）。

### Termux 组件不可 maven 引用

termux-app 无官方 maven 发布（jitpack 不可靠且会构建整个 GPLv3 仓库）→ **vendored 源码**是唯一干净路径，附带收益：bug 可自修、无上游破坏性变更风险。

## 3. 目标架构

```
WebSocket PTY（ServerTerminalWorkspace —— 保留，仅 adapter 内部实现替换）
   ↕ RemoteTerminalSession（新增，~150 行）
termux TerminalEmulator（vendored，VT 内核：append() 解析 PTY 输出）
   ↕ termux TerminalView（vendored View：渲染 + 键盘 + 手势 + 选择 + 缩放）
AndroidView 包裹（SessionTerminalInline 重写）
   + KeyboardOverlay（自研，保留）
```

### 关键耦合面（已核实源码）

TerminalView 对 session 的全部调用仅 **4 个方法**（grep 实证）：
`write(String)` ×3、`getEmulator()` ×2、`writeCodePoint(boolean,int)`、`updateSize(int,int)`

→ 定义接口 `TerminalSessionBridge`（vendored 改造点：TerminalView 字段/参数类型 TerminalSession → 接口；~6 行改动），RemoteTerminalSession 实现之：

- `write/writeCodePoint` → WebSocket 发送（键盘输入直达服务器，回显由远程 shell 负责——远程 PTY 正确模型；termlib 的本地 writeInput 回显路径删除）
- `getEmulator` → termux TerminalEmulator 实例（PTY 输出经 `emulator.append(byte[], len)` 解析）
- `updateSize` → emulator.resize + WebSocket resize 消息

### vendored 裁剪

- terminal-emulator 33 文件 8312 行：**排除** `TerminalSession.java`（本地进程模型：JNI.createSubprocess/本地 PTY 线程——我们不用的部分）与 `JNI.java`
- terminal-view 8 文件 2833 行：全量（TerminalView/TerminalRenderer/TerminalViewPager 等）
- 保留原始 LICENSE/NOTICE（Apache 2.0 合规）+ 来源 commit 哈希记录

## 4. 实施阶段（每段编译+提交）

1. **Vendor**：源码入 `app/src/main/java/com/termux/`（Java 源集）+ 裁剪 + LICENSE/NOTICE + TerminalSessionBridge 接口化改造
2. **桥接**：RemoteTerminalSession（WebSocket ↔ emulator）+ ServerTerminalWorkspace adapter 内部替换（对外 API 不变：activeFontSizeSp/activeState/tabs 不动）
3. **UI**：SessionTerminalInline 重写为 AndroidView(TerminalView) + TerminalViewClient 实现（复制粘贴/缩放/字体回调）；ChatTerminalView 对接调整；KeyboardOverlay 挂接保留
4. **清理**：删 termlib 依赖 + TermlibModifierManager + 死代码；字号设置映射（textSize px ↔ sp）
5. **验证**：JVM 单测（workspace adapter 桥接语义）+ 真机 E2E（§5）

## 5. 验证计划（4+1 维）

1. 编译/静态：compileDevDebugKotlin + lint vendored 无告警累积
2. JVM：RemoteTerminalSession 桥接单测（append→buffer、write→WebSocket、resize 双路）
3. 真机 E2E（试金石复测）：
   - vim：打开/插入打字回显/ESC/:q! 退出（本次取证的两症状必须消失）
   - htop/less：alternate screen + 实时刷新
   - ls --color、echo ANSI 转义：颜色
   - 滚动回看（transcript）、双指缩放、文本长按选择复制
   - 字号设置联动、tab 抽屉多终端切换
4. 回归：键盘条（Ctrl/Esc/方向键）经 KeyboardOverlay 输入直达
5. 维度 5：观感由用户验收

## 6. 风险与对策

| 风险 | 对策 |
|------|------|
| vendored 代码量大（11k 行 Java）进主 module | 独立包路径 com/termux/* 隔离；不与 Kotlin 混排；后续可拆 library module |
| View 体系嵌入 Compose 性能 | TerminalView 自绘单 SurfaceView 级 View，AndroidView 互操作是官方标准路径；终端本非 Compose 优势区 |
| 键盘条与 Termux IME 交互重复 | KeyboardOverlay 只做「特殊键发送」（Ctrl-/Esc/箭头），文本输入全交 Termux |
| 字号/主题联动断裂 | 阶段 4 显式映射 + 设置页真机复验 |

## 7. Further Notes

- vendored 基线：termux-app master@<commit>（引入时记录哈希）
- termux 上游后续修复可选择性地 cherry-pick 进 vendored 副本
- 若 ConnectBot termlib 未来成熟（≥1.0 + 键盘修复），不排除回迁 Compose 原生组件——本次换件保持 UI 层隔离（AndroidView 单点），回迁面可控
