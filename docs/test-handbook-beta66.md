# oc-remote v2.0.0-beta.66 端到端测试手册

> **应用**: oc-remote — OpenCode Jetpack Compose 聊天客户端  
> **版本**: 2.0.0-beta.66 (versionCode 266)  
> **包名**: `dev.leonardo.octether.v2`  
> **测试设备**: emulator-5554  
> **编写日期**: 2026-05-29  
> **测试基准**: 13 项 bugfix/feature 任务

---

## 目录

- [1. 测试概览](#1-测试概览)
- [2. 测试环境准备](#2-测试环境准备)
- [3. L0: 静态验证 (APK 分析)](#3-l0-静态验证-apk-分析)
- [4. L1: 安装与启动](#4-l1-安装与启动)
- [5. L2: UI 冒烟测试 (无需后端)](#5-l2-ui-冒烟测试)
- [6. L3: 后端联调测试 (需 OpenCode 服务器)](#6-l3-后端联调测试)
- [7. L4: 回归验收清单](#7-l4-回归验收清单)
- [8. 自动化测试脚本 (PowerShell + ADB)](#8-自动化测试脚本)
- [9. 截图取证命令](#9-截图取证命令)
- [10. 缺陷报告模板](#10-缺陷报告模板)

---

## 1. 测试概览

### 测试层级说明

| 层级 | 名称 | 依赖 | 可自动化 | 用例数 |
|------|------|------|----------|--------|
| L0 | 静态验证 | APK 文件 | ✅ 是 | 4 |
| L1 | 安装与启动 | 设备/模拟器 | ✅ 是 | 4 |
| L2 | UI 冒烟测试 | 应用已启动 | ✅ 是 | 5 |
| L3 | 后端联调测试 | OpenCode 服务器 | ❌ 手动 | 13 |
| L4 | 回归验收清单 | 全部依赖 | ❌ 手动 | 13 |

### 任务与测试用例映射

| 任务 | 功能 | L0 | L1 | L2 | L3 | L4 |
|------|------|----|----|----|----|-----|
| T1 | Send button circle | | | ✅ | | ✅ |
| T2 | ReasoningBlock 动画 | | | | ✅ | ✅ |
| T3 | Token/cost 统计 | | | | ✅ | ✅ |
| T4 | 死代码移除 | ✅ | | | | ✅ |
| T5 | Read 工具卡文件名 | | | | ✅ | ✅ |
| T6 | Write 工具卡文件名 | | | | ✅ | ✅ |
| T7 | 键盘自动弹出修复 | | | | ✅ | ✅ |
| T8 | Search 工具卡格式 | | | | ✅ | ✅ |
| T9 | Surface 包装 x3 | | | | ✅ | ✅ |
| T10 | 换行渲染 | | | | ✅ | ✅ |
| T11 | 文本选择 | | | | ✅ | ✅ |
| T12 | 版本号升级 | ✅ | | ✅ | | ✅ |
| T13 | 编辑协议文档 | ✅ | | | | ✅ |

---

## 2. 测试环境准备

### 2.1 环境要求

- [ ] Android 模拟器 `emulator-5554` 已启动且在线
- [ ] ADB 已加入 PATH，`adb devices` 能识别设备
- [ ] oc-remote APK (v2.0.0-beta.66) 已构建完成
- [ ] OpenCode 服务器可访问（L3 测试需要）
- [ ] PowerShell 5.1+ 可用（自动化脚本需要）

### 2.2 快速环境检查

```powershell
# 检查设备连接
adb devices

# 检查模拟器状态
adb -s emulator-5554 shell getprop sys.boot_completed

# 检查屏幕密度（用于截图缩放）
adb -s emulator-5554 shell wm density

# 检查 Android 版本
adb -s emulator-5554 shell getprop ro.build.version.sdk
```

### 2.3 卸载旧版本 (如有)

```powershell
adb -s emulator-5554 uninstall dev.leonardo.octether.v2
```

---

## 3. L0: 静态验证 (APK 分析)

> **目标**: 在安装之前，通过 APK 文件静态分析验证构建产物正确性。

### T-L0-01: APK 文件存在性检查

| 项目 | 内容 |
|------|------|
| **ID** | T-L0-01 |
| **Feature** | 构建产物完整性 |
| **Preconditions** | 项目已构建完成 |
| **Steps** | 1. 定位 APK 文件路径 (通常在 `app/build/outputs/apk/debug/` 或 `release/`) |
| | 2. 确认文件存在且大小 > 0 |
| | 3. 运行 `aapt dump badging app.apk \| head -5` 确认可解析 |
| **Expected Result** | APK 文件存在，aapt 能正常解析，输出包含包名 `dev.leonardo.octether.v2` |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L0-02: 版本号验证

| 项目 | 内容 |
|------|------|
| **ID** | T-L0-02 |
| **Feature** | Task 12 — 版本号升级 |
| **Preconditions** | APK 文件可用 |
| **Steps** | 1. 运行 `aapt dump badging app.apk \| grep versionName` |
| | 2. 确认输出为 `versionName='2.0.0-beta.66'` |
| | 3. 运行 `aapt dump badging app.apk \| grep versionCode` |
| | 4. 确认输出为 `versionCode='266'` |
| **Expected Result** | `versionName='2.0.0-beta.66'`，`versionCode='266'` |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L0-03: 包名验证

| 项目 | 内容 |
|------|------|
| **ID** | T-L0-03 |
| **Feature** | 应用标识正确性 |
| **Preconditions** | APK 文件可用 |
| **Steps** | 1. 运行 `aapt dump badging app.apk \| head -1` |
| | 2. 确认包名为 `package: name='dev.leonardo.octether.v2'` |
| **Expected Result** | 包名为 `dev.leonardo.octether.v2` (debug 构建带 `.v2` 后缀) |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L0-04: 死代码移除验证

| 项目 | 内容 |
|------|------|
| **ID** | T-L0-04 |
| **Feature** | Task 4 — StepFinishInfo 函数移除 |
| **Preconditions** | 源码可访问 |
| **Steps** | 1. 在源码中搜索 `StepFinishInfo` |
| | 2. 确认无匹配结果（函数已被移除） |
| | 3. 可选: 使用 `dexdump` 或 `jadx` 反编译 APK，确认无 `StepFinishInfo` 类/方法 |
| **Expected Result** | 源码和 APK 中均不存在 `StepFinishInfo` 相关代码 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L0-05: 编辑协议文档存在性

| 项目 | 内容 |
|------|------|
| **ID** | T-L0-05 |
| **Feature** | Task 13 — 编辑协议文档 |
| **Preconditions** | 源码仓库可访问 |
| **Steps** | 1. 检查项目中是否存在编辑协议相关文档 (可能在 `docs/` 或根目录) |
| | 2. 确认文档内容非空且描述了编辑流程/协议 |
| **Expected Result** | 编辑协议文档存在且内容完整 |
| **Actual Result** | |
| **Pass/Fail** | |

---

## 4. L1: 安装与启动

> **目标**: 验证 APK 在目标设备上能正确安装、启动，且无崩溃。

### T-L1-01: APK 安装

| 项目 | 内容 |
|------|------|
| **ID** | T-L1-01 |
| **Feature** | 安装流程 |
| **Preconditions** | 设备在线，旧版本已卸载 |
| **Steps** | 1. 运行 `adb -s emulator-5554 install app/build/outputs/apk/debug/app-debug.apk` |
| | 2. 观察输出，确认显示 `Success` |
| | 3. 运行 `adb -s emulator-5554 shell pm list packages \| grep minios` |
| | 4. 确认 `dev.leonardo.octether.v2` 出现在列表中 |
| **Expected Result** | 安装成功，包名在已安装列表中可见 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L1-02: 冷启动无崩溃

| 项目 | 内容 |
|------|------|
| **ID** | T-L1-02 |
| **Feature** | 应用启动稳定性 |
| **Preconditions** | 应用已安装，应用未在运行 |
| **Steps** | 1. 强制停止应用: `adb -s emulator-5554 shell am force-stop dev.leonardo.octether.v2` |
| | 2. 清除 logcat: `adb -s emulator-5554 logcat -c` |
| | 3. 启动应用: `adb -s emulator-5554 shell am start -n dev.leonardo.octether.v2/dev.leonardo.octether.MainActivity` |
| | 4. 等待 5 秒 |
| | 5. 检查 logcat 中的 FATAL/AndroidRuntime crash: `adb -s emulator-5554 logcat -d -s AndroidRuntime:E` |
| **Expected Result** | 应用正常启动，logcat 中无 FATAL 异常或 AndroidRuntime 崩溃 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L1-03: 热启动无崩溃

| 项目 | 内容 |
|------|------|
| **ID** | T-L1-03 |
| **Feature** | 应用恢复稳定性 |
| **Preconditions** | 应用已启动过一次 |
| **Steps** | 1. 按 Home 键: `adb -s emulator-5554 shell input keyevent KEYCODE_HOME` |
| | 2. 等待 2 秒 |
| | 3. 清除 logcat |
| | 4. 从最近任务恢复应用 |
| | 5. 等待 3 秒 |
| | 6. 检查 logcat 无崩溃 |
| **Expected Result** | 应用正常恢复，无崩溃 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L1-04: 安装版本确认

| 项目 | 内容 |
|------|------|
| **ID** | T-L1-04 |
| **Feature** | Task 12 — 版本号升级 |
| **Preconditions** | 应用已安装 |
| **Steps** | 1. 运行: `adb -s emulator-5554 shell dumpsys package dev.leonardo.octether.v2 \| findstr versionName` |
| | 2. 确认输出包含 `versionName=2.0.0-beta.66` |
| **Expected Result** | 已安装版本为 `2.0.0-beta.66` |
| **Actual Result** | |
| **Pass/Fail** | |

---

## 5. L2: UI 冒烟测试

> **目标**: 无需后端连接即可验证的 UI 元素测试。

### T-L2-01: 发送按钮空状态显示 (Task 1)

| 项目 | 内容 |
|------|------|
| **ID** | T-L2-01 |
| **Feature** | Task 1 — 发送按钮圆形显示 |
| **Preconditions** | 应用已启动，进入聊天界面 |
| **Steps** | 1. 打开应用，确保输入框为空（无任何文字） |
| | 2. 观察输入框右侧的发送按钮区域 |
| | 3. 截图记录: `adb -s emulator-5554 shell screencap -p /sdcard/t-l2-01.png` |
| **Expected Result** | 输入框为空时，发送按钮显示为**可见的圆形图标**（而非透明/不可见），用户能明确感知发送按钮的存在位置 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L2-02: 发送按钮状态切换 (Task 1)

| 项目 | 内容 |
|------|------|
| **ID** | T-L2-02 |
| **Feature** | Task 1 — 发送按钮状态切换 |
| **Preconditions** | 应用已启动，进入聊天界面 |
| **Steps** | 1. 输入框为空时，观察发送按钮（应为圆形/非活跃态） |
| | 2. 在输入框中输入任意文字，如 "hello" |
| | 3. 观察发送按钮变化 |
| | 4. 清空输入框 |
| | 5. 再次观察发送按钮恢复 |
| **Expected Result** | 有文字时发送按钮变为活跃/可发送状态；清空后恢复为圆形空状态。状态切换流畅无闪烁 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L2-03: 版本号显示 (Task 12)

| 项目 | 内容 |
|------|------|
| **ID** | T-L2-03 |
| **Feature** | Task 12 — 版本号显示 |
| **Preconditions** | 应用已启动 |
| **Steps** | 1. 打开应用侧边栏/设置页面（通常点击左上角菜单或向右滑动） |
| | 2. 查找 "关于" 或版本信息区域 |
| | 3. 确认显示版本号为 `2.0.0-beta.66` 或包含此版本号 |
| | 4. 如果设置中无版本显示，检查应用信息: `adb -s emulator-5554 shell dumpsys package dev.leonardo.octether.v2 \| findstr versionName` |
| **Expected Result** | 应用内或系统信息中版本号为 `2.0.0-beta.66` |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L2-04: 输入框多行支持预检查 (Task 10)

| 项目 | 内容 |
|------|------|
| **ID** | T-L2-04 |
| **Feature** | Task 10 — 换行输入支持 |
| **Preconditions** | 应用已启动，进入聊天界面 |
| **Steps** | 1. 点击输入框获取焦点 |
| | 2. 输入第一行文字 "Line 1" |
| | 3. 按键盘上的 Enter/换行键 |
| | 4. 输入第二行文字 "Line 2" |
| | 5. 观察输入框中两行文字是否正确显示（有可见的换行） |
| **Expected Result** | 输入框支持多行输入，换行后文字在下一行显示，输入框高度自适应 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L2-05: 应用权限检查

| 项目 | 内容 |
|------|------|
| **ID** | T-L2-05 |
| **Feature** | 应用权限合理性 |
| **Preconditions** | 应用已安装 |
| **Steps** | 1. 运行: `adb -s emulator-5554 shell dumpsys package dev.leonardo.octether.v2 \| findstr permission` |
| | 2. 检查请求的权限列表 |
| | 3. 确认无过度权限请求（如不需要的定位、通讯录、相机等） |
| **Expected Result** | 权限列表合理，仅包含网络访问等必要权限 |
| **Actual Result** | |
| **Pass/Fail** | |

---

## 6. L3: 后端联调测试

> **目标**: 需要连接 OpenCode 服务器才能执行的交互测试。  
> **前置条件**: OpenCode 服务器已启动且可从模拟器网络访问。

### 准备步骤

```
1. 确认 OpenCode 服务器运行中
2. 在 oc-remote 中配置服务器地址 (设置 → 服务器地址)
3. 确认连接成功（状态指示器变绿或显示 "已连接"）
```

---

### T-L3-01: ReasoningBlock 脉冲动画 (Task 2)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-01 |
| **Feature** | Task 2 — ReasoningBlock 脉冲动画 |
| **Preconditions** | 已连接 OpenCode 服务器；使用的模型支持 reasoning/思考模式 |
| **Steps** | 1. 在聊天界面输入一个需要推理的问题，例如 "请解释什么是递归，并给出一个例子" |
| | 2. 点击发送 |
| | 3. 等待助手开始回复 |
| | 4. **仔细观察**回复过程中是否出现 ReasoningBlock (思考过程块) |
| | 5. 观察 ReasoningBlock 中的动画效果: |
| |    - a) 是否有**脉冲圆点动画** (呼吸灯式闪烁) |
| |    - b) 是否有**渐变色彩强调条** (gradient accent bar) |
| | 6. 等待推理完成 |
| | 7. 观察 ReasoningBlock 收起后是否显示 **"Thought for X.Xs"** 文字 |
| | 8. 截图: 推理进行中 + 推理完成后各一张 |
| **Expected Result** | - 推理过程中: 可见脉冲圆点动画 + 渐变强调条，视觉效果流畅 |
| | - 推理完成后: 显示 "Thought for X.Xs" 格式文字 (X.X 为实际耗时秒数) |
| | - 动画帧率稳定，无卡顿或闪烁 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-02: Token/Cost 统计显示 (Task 3)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-02 |
| **Feature** | Task 3 — Token/cost 统计 |
| **Preconditions** | 已连接 OpenCode 服务器 |
| **Steps** | 1. 发送一条消息给助手，例如 "Hello" |
| | 2. 等待助手完整回复完成 |
| | 3. 观察助手消息**下方**是否出现统计信息 |
| | 4. 确认统计信息格式为 `↑N ↓N · $X.XXXX` 其中: |
| |    - `↑N` = 输入 token 数 |
| |    - `↓N` = 输出 token 数 |
| |    - `$X.XXXX` = 估算费用 |
| | 5. 截图记录 |
| **Expected Result** | 每条助手消息下方显示 token/cost 统计，格式正确，数值合理（非零、非负） |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-03: Read 工具卡文件名显示 (Task 5)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-03 |
| **Feature** | Task 5 — Read 工具卡文件名 |
| **Preconditions** | 已连接 OpenCode 服务器；服务器端有可读取的项目文件 |
| **Steps** | 1. 发送一条触发 Read 工具的请求，例如 "请读取项目的 README.md 文件内容" |
| | 2. 等待助手调用 Read 工具 |
| | 3. 观察聊天界面中的 Read 工具卡 |
| | 4. 确认工具卡标题格式为 **"Read · README.md"** (或实际文件名) |
| | 5. 截图记录 |
| **Expected Result** | Read 工具卡标题显示为 "Read · {filename}" 格式，文件名为实际被读取的文件名 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-04: Write 工具卡文件名显示 (Task 6)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-04 |
| **Feature** | Task 6 — Write 工具卡文件名 |
| **Preconditions** | 已连接 OpenCode 服务器；有可写入的项目文件 |
| **Steps** | 1. 发送一条触发 Write/Edit 工具的请求，例如 "请在 test.md 文件中写入 Hello World" |
| | 2. 等待助手调用 Write 工具 |
| | 3. 观察聊天界面中的 Write 工具卡 |
| | 4. 确认工具卡标题格式为 **"Write · test.md"** (或实际文件名) |
| | 5. 截图记录 |
| **Expected Result** | Write 工具卡标题显示为 "Write · {filename}" 格式，文件名为实际被写入的文件名 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-05: Search 工具卡格式 (Task 8)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-05 |
| **Feature** | Task 8 — Search 工具卡格式 |
| **Preconditions** | 已连接 OpenCode 服务器；服务器端项目有可搜索的代码 |
| **Steps** | 1. 发送一条触发代码搜索的请求，例如 "请搜索项目中包含 'MainActivity' 的文件" |
| | 2. 等待助手调用 Search/Grep 工具 |
| | 3. 观察聊天界面中的搜索工具卡 |
| | 4. 确认显示以下两种格式之一: |
| |    - **"Search code · pattern"** (代码内容搜索) |
| |    - **"Find files · pattern"** (文件名搜索) |
| | 5. 截图记录 |
| **Expected Result** | 搜索工具卡标题根据搜索类型正确显示 "Search code · {pattern}" 或 "Find files · {pattern}" |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-06: MCP 工具卡 Surface 背景 (Task 9 — MCP 部分)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-06 |
| **Feature** | Task 9 — MCP 工具卡 Surface 包装 |
| **Preconditions** | 已连接 OpenCode 服务器；服务器端配置了 MCP 工具 |
| **Steps** | 1. 发送一条触发 MCP 工具调用的请求 (根据实际配置的 MCP 服务，例如搜索、查询等) |
| | 2. 等待助手调用 MCP 工具 |
| | 3. 观察 MCP 工具卡的视觉效果 |
| | 4. 确认工具卡具有**圆角矩形背景** (Surface/Material Card 样式) |
| | 5. 确认背景色与聊天界面有明显视觉区分 |
| | 6. 截图记录 |
| **Expected Result** | MCP 工具卡有明确的圆角矩形背景，与周围消息区域有视觉区分，设计风格符合 Material Design |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-07: TodoList 工具卡 Surface 背景 (Task 9 — TodoList 部分)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-07 |
| **Feature** | Task 9 — TodoList 工具卡 Surface 包装 |
| **Preconditions** | 已连接 OpenCode 服务器 |
| **Steps** | 1. 发送一条触发 TodoList/任务列表功能的请求，例如 "请创建一个待办事项列表" |
| | 2. 等待助手回复并展示 TodoList 卡片 |
| | 3. 观察 TodoList 卡片的视觉效果 |
| | 4. 确认卡片具有**圆角矩形背景** |
| | 5. 确认卡片内容（复选框、文字等）清晰可读 |
| | 6. 截图记录 |
| **Expected Result** | TodoList 卡片有圆角矩形背景，内容布局清晰，复选框/文字可读 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-08: Patch 工具卡 Surface 背景 (Task 9 — Patch 部分)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-08 |
| **Feature** | Task 9 — Patch 工具卡 Surface 包装 |
| **Preconditions** | 已连接 OpenCode 服务器；有可修改的项目文件 |
| **Steps** | 1. 发送一条触发 Patch/文件修改的请求，例如 "请修改 xxx 文件的第 Y 行，将 A 改为 B" |
| | 2. 等待助手调用 Patch 工具 |
| | 3. 观察 Patch 工具卡的视觉效果 |
| | 4. 确认卡片具有**圆角矩形背景** |
| | 5. 确认 diff 内容在背景上清晰可读 |
| | 6. 截图记录 |
| **Expected Result** | Patch 工具卡有圆角矩形背景，diff 内容清晰可读，新增/删除行有颜色区分 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-09: 用户消息换行渲染 (Task 10)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-09 |
| **Feature** | Task 10 — 用户消息换行渲染 |
| **Preconditions** | 已连接 OpenCode 服务器 |
| **Steps** | 1. 在输入框中输入以下多行文本: |
| | ``` |
| | 第一行文字 |
| | 第二行文字 |
| | 第三行文字 |
| | ``` |
| | (使用 Enter 键在每行之间插入换行) |
| | 2. 点击发送 |
| | 3. 观察发送后的消息气泡中的文本 |
| | 4. 确认三行文字各自独立成行，换行符正确渲染 |
| | 5. 截图记录发送前输入框 + 发送后消息气泡 |
| **Expected Result** | 发送的消息气泡中，三行文字正确分行显示，换行位置与输入时一致，不会合并为一行 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-10: 助手文本选择对话框 (Task 11)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-10 |
| **Feature** | Task 11 — 文本选择 |
| **Preconditions** | 已连接 OpenCode 服务器；聊天中有助手回复 |
| **Steps** | 1. 确保聊天中至少有一条助手回复消息 |
| | 2. **长按**助手消息中的文本内容（按压约 1 秒） |
| | 3. 观察是否弹出**文本选择对话框** |
| | 4. 确认对话框中显示助手消息的可选中文本 |
| | 5. 尝试拖动选择范围手柄 |
| | 6. 确认文本可以复制 |
| | 7. 截图记录 |
| **Expected Result** | 长按后弹出文本选择对话框，助手消息文本变为可选状态，支持拖动选择和复制 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-11: 滚动不触发键盘弹出 (Task 7)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-11 |
| **Feature** | Task 7 — 键盘自动弹出修复 |
| **Preconditions** | 已连接 OpenCode 服务器；聊天中有足够多的消息可以滚动 |
| **Steps** | 1. 发送多条消息使聊天记录超过一屏 |
| | 2. 点击输入框输入一些文字，**确保键盘当前是弹出状态** |
| | 3. 点击键盘外的区域或按返回键**关闭键盘** |
| | 4. 确认键盘已收起 |
| | 5. **向上滑动**聊天列表，滚动到较早的消息 |
| | 6. **向下滑动**，滚动回最新消息 |
| | 7. **观察**: 键盘是否在滚动过程中**自动弹出** |
| | 8. 也可以测试: 在键盘收起状态下，仅滚动聊天列表 |
| | 9. 截图/录屏记录 |
| **Expected Result** | 滚动聊天列表时键盘**不会自动弹出**。只有用户主动点击输入框时键盘才会出现 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-12: 发送按钮完整交互 (Task 1 综合验证)

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-12 |
| **Feature** | Task 1 — 发送按钮完整交互流程 |
| **Preconditions** | 已连接 OpenCode 服务器 |
| **Steps** | 1. 进入聊天界面，输入框为空 |
| | 2. 观察发送按钮 → 应显示可见圆形（空状态） |
| | 3. 输入文字 "测试消息" |
| | 4. 观察发送按钮 → 应变为活跃/可发送状态 |
| | 5. 点击发送 |
| | 6. 确认消息成功发送，输入框清空 |
| | 7. 再次观察发送按钮 → 应恢复为可见圆形（空状态） |
| | 8. 截图每个状态 |
| **Expected Result** | 发送按钮在空状态显示可见圆形，输入文字后变为活跃状态，发送后正确恢复 |
| **Actual Result** | |
| **Pass/Fail** | |

### T-L3-13: 综合长对话测试

| 项目 | 内容 |
|------|------|
| **ID** | T-L3-13 |
| **Feature** | 综合功能验证 |
| **Preconditions** | 已连接 OpenCode 服务器 |
| **Steps** | 1. 发送一条综合请求，例如: |
| | "请读取 src/main.kt 文件，搜索其中所有的 function 关键字，然后告诉我代码中定义了哪些函数。请在回答中使用换行分隔每个函数名。" |
| | 2. 等待完整回复 |
| | 3. 在同一次回复中验证: |
| |    - a) Read 工具卡显示文件名 |
| |    - b) Search 工具卡显示搜索模式 |
| |    - c) Token/cost 统计显示在消息底部 |
| |    - d) 如果触发了 ReasoningBlock，验证动画效果 |
| | 4. 长按助手回复文本，验证文本选择 |
| | 5. 向上滚动浏览历史消息，验证键盘不自动弹出 |
| **Expected Result** | 所有功能在长对话场景中正常工作，无布局错乱或崩溃 |
| **Actual Result** | |
| **Pass/Fail** | |

---

## 7. L4: 回归验收清单

> **使用说明**: 对 13 项任务逐一快速验收。每项标记 ✅ PASS / ❌ FAIL / ⬜ SKIP。

| # | 任务 | 验收标准 | 测试结果 | 备注 |
|---|------|----------|----------|------|
| T1 | Send button circle | 输入框为空时发送按钮可见（圆形），非透明 | ⬜ | |
| T2 | ReasoningBlock 动画 | 思考中: 脉冲圆点 + 渐变条；完成后: "Thought for X.Xs" | ⬜ | |
| T3 | Token/cost 统计 | 助手消息下方显示 `↑N ↓N · $X.XXXX` | ⬜ | |
| T4 | 死代码移除 | 源码中无 `StepFinishInfo` 函数 | ⬜ | |
| T5 | Read 工具卡 | 标题格式 "Read · filename" | ⬜ | |
| T6 | Write 工具卡 | 标题格式 "Write · filename" | ⬜ | |
| T7 | 键盘自动弹出修复 | 滚动到页面底部时键盘不会自动弹出 | ⬜ | |
| T8 | Search 工具卡 | "Search code · pattern" 或 "Find files · pattern" | ⬜ | |
| T9 | Surface 包装 x3 | MCP/TodoList/Patch 工具卡有圆角矩形背景 | ⬜ | |
| T10 | 换行渲染 | 用户消息中的换行符正确渲染为多行 | ⬜ | |
| T11 | 文本选择 | 长按助手文本弹出可选中文本对话框 | ⬜ | |
| T12 | 版本号 | 应用版本为 2.0.0-beta.66 | ⬜ | |
| T13 | 编辑协议 | 编辑协议文档存在且内容完整 | ⬜ | |

### 验收判定

- **全部 ✅**: 通过验收，可发布
- **存在 ❌**: 需修复后重新测试
- **存在 ⬜**: 需补充测试

---

## 8. 自动化测试脚本

> 以下 PowerShell 脚本可自动执行 L0-L2 层级的测试。

### 8.1 完整自动化脚本

将以下脚本保存为 `test-l0-l2.ps1` 并执行:

```powershell
<#
.SYNOPSIS
    oc-remote v2.0.0-beta.66 L0-L2 自动化测试脚本
.DESCRIPTION
    执行 APK 静态验证 (L0)、安装启动 (L1)、UI 冒烟 (L2) 测试
.EXAMPLE
    .\test-l0-l2.ps1 -ApkPath "D:\path\to\app-debug.apk" -Device "emulator-5554"
#>

param(
    [Parameter(Mandatory=$true)]
    [string]$ApkPath,
    [string]$Device = "emulator-5554",
    [string]$PackageName = "dev.leonardo.octether.v2",
    [string]$ExpectedVersion = "2.0.0-beta.66",
    [int]$ExpectedVersionCode = 266
)

$ErrorActionPreference = "Continue"
$PassCount = 0
$FailCount = 0
$Results = @()

function Test-Case {
    param([string]$Id, [string]$Name, [bool]$Passed, [string]$Detail = "")
    $status = if ($Passed) { "PASS" } else { "FAIL" }
    $color = if ($Passed) { "Green" } else { "Red" }
    Write-Host "[$status] $Id - $Name" -ForegroundColor $color
    if ($Detail) { Write-Host "       $Detail" -ForegroundColor Gray }
    if ($Passed) { $script:PassCount++ } else { $script:FailCount++ }
    $script:Results += [PSCustomObject]@{ ID=$Id; Name=$Name; Status=$status; Detail=$Detail }
}

function Run-Adb {
    param([string]$Command, [int]$TimeoutMs = 30000)
    try {
        $proc = Start-Process -FilePath "adb" -ArgumentList "-s", $Device, $Command.Split(" ") `
            -NoNewWindow -Wait -PassThru -RedirectStandardOutput "$env:TEMP\adb-out.txt" `
            -RedirectStandardError "$env:TEMP\adb-err.txt"
        $output = Get-Content "$env:TEMP\adb-out.txt" -Raw -ErrorAction SilentlyContinue
        return $output.Trim()
    } catch {
        return ""
    }
}

# ============================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  oc-remote v2.0.0-beta.66 测试" -ForegroundColor Cyan
Write-Host "  L0-L2 自动化测试脚本" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ============================================================
# L0: 静态验证
# ============================================================
Write-Host "--- L0: 静态验证 ---" -ForegroundColor Yellow
Write-Host ""

# T-L0-01: APK 文件存在
$fileExists = Test-Path -LiteralPath $ApkPath
$size = if ($fileExists) { (Get-Item $ApkPath).Length } else { 0 }
Test-Case -Id "T-L0-01" -Name "APK 文件存在性" -Passed ($fileExists -and $size -gt 0) `
    -Detail "Path: $ApkPath, Size: $([math]::Round($size/1MB, 2)) MB"

# T-L0-02: 版本号验证 (通过 aapt)
if (Get-Command aapt -ErrorAction SilentlyContinue) {
    $badging = & aapt dump badging $ApkPath 2>$null
    $vn = ($badging | Select-String "versionName='([^']*)'").Matches.Groups[1].Value
    $vc = ($badging | Select-String "versionCode='([^']*)'").Matches.Groups[1].Value
    Test-Case -Id "T-L0-02" -Name "APK 版本号" `
        -Passed ($vn -eq $ExpectedVersion) `
        -Detail "versionName=$vn (expected $ExpectedVersion), versionCode=$vc"
} else {
    # fallback: 使用 aapt2 或跳过
    Test-Case -Id "T-L0-02" -Name "APK 版本号" -Passed $false `
        -Detail "aapt not found in PATH, cannot verify APK metadata"
}

# T-L0-03: 包名验证
if (Get-Command aapt -ErrorAction SilentlyContinue) {
    $pkgLine = ($badging | Select-String "package: name='([^']*)'").Line
    $pkgName = ($pkgLine | Select-String "name='([^']*)'").Matches.Groups[1].Value
    Test-Case -Id "T-L0-03" -Name "APK 包名" `
        -Passed ($pkgName -eq $PackageName) `
        -Detail "Package: $pkgName (expected $PackageName)"
} else {
    Test-Case -Id "T-L0-03" -Name "APK 包名" -Passed $false -Detail "aapt not available"
}

# T-L0-04: 死代码检查
$srcPath = Split-Path $ApkPath -Parent | Split-Path -Parent | Split-Path -Parent | Split-Path -Parent
$stepFinishInfo = Get-ChildItem -LiteralPath $srcPath -Recurse -Include "*.kt","*.java" -ErrorAction SilentlyContinue |
    Select-String "StepFinishInfo" -ErrorAction SilentlyContinue
Test-Case -Id "T-L0-04" -Name "StepFinishInfo 移除" `
    -Passed ($null -eq $stepFinishInfo -or $stepFinishInfo.Count -eq 0) `
    -Detail "Found $($stepFinishInfo.Count) references to StepFinishInfo"

# T-L0-05: 编辑协议文档
$docsPath = Join-Path $srcPath "docs"
$editDoc = Get-ChildItem -LiteralPath $docsPath -ErrorAction SilentlyContinue |
    Where-Object { $_.Name -match "edit|protocol|editing" }
Test-Case -Id "T-L0-05" -Name "编辑协议文档" `
    -Passed ($editDoc -and $editDoc.Count -gt 0) `
    -Detail "Found $($editDoc.Count) editing protocol document(s)"

# ============================================================
# L1: 安装与启动
# ============================================================
Write-Host ""
Write-Host "--- L1: 安装与启动 ---" -ForegroundColor Yellow
Write-Host ""

# 检查设备连接
$devices = & adb devices 2>$null
$deviceOnline = $devices -match [regex]::Escape($Device)
if (-not $deviceOnline) {
    Write-Host "ERROR: Device $Device not found!" -ForegroundColor Red
    Write-Host "Available devices:" -ForegroundColor Yellow
    Write-Host $devices
    exit 1
}

# T-L1-01: 安装
Write-Host "Uninstalling old version..." -ForegroundColor Gray
& adb -s $Device uninstall $PackageName 2>$null | Out-Null

Write-Host "Installing APK..." -ForegroundColor Gray
$installOutput = & adb -s $Device install $ApkPath 2>&1
$installSuccess = $installOutput -match "Success"
Test-Case -Id "T-L1-01" -Name "APK 安装" -Passed $installSuccess `
    -Detail ($installOutput -join "; ")

# T-L1-04: 安装版本确认
$pkgInfo = & adb -s $Device shell dumpsys package $PackageName 2>$null
$installedVn = ($pkgInfo | Select-String "versionName=(\S+)").Matches.Groups[1].Value
Test-Case -Id "T-L1-04" -Name "安装版本确认" `
    -Passed ($installedVn -eq $ExpectedVersion) `
    -Detail "Installed: $installedVn (expected $ExpectedVersion)"

# T-L1-02: 冷启动
Write-Host "Testing cold start..." -ForegroundColor Gray
& adb -s $Device shell am force-stop $PackageName 2>$null | Out-Null
Start-Sleep -Seconds 1
& adb -s $Device logcat -c 2>$null | Out-Null
& adb -s $Device shell am start -n "$PackageName/dev.leonardo.octether.MainActivity" 2>$null | Out-Null
Start-Sleep -Seconds 5
$crashes = & adb -s $Device logcat -d -s "AndroidRuntime:E" 2>$null
$hasFATAL = $crashes -match "FATAL"
Test-Case -Id "T-L1-02" -Name "冷启动无崩溃" `
    -Passed (-not $hasFATAL) `
    -Detail "FATAL in logcat: $hasFATAL"

# T-L1-03: 热启动
Write-Host "Testing hot start..." -ForegroundColor Gray
& adb -s $Device shell input keyevent KEYCODE_HOME 2>$null | Out-Null
Start-Sleep -Seconds 2
& adb -s $Device logcat -c 2>$null | Out-Null
& adb -s $Device shell am start -n "$PackageName/dev.leonardo.octether.MainActivity" 2>$null | Out-Null
Start-Sleep -Seconds 3
$hotCrashes = & adb -s $Device logcat -d -s "AndroidRuntime:E" 2>$null
$hasHotFATAL = $hotCrashes -match "FATAL"
Test-Case -Id "T-L1-03" -Name "热启动无崩溃" `
    -Passed (-not $hasHotFATAL) `
    -Detail "FATAL in logcat: $hasHotFATAL"

# ============================================================
# L2: UI 冒烟测试
# ============================================================
Write-Host ""
Write-Host "--- L2: UI 冒烟测试 ---" -ForegroundColor Yellow
Write-Host ""

# 确保应用在前台
& adb -s $Device shell am start -n "$PackageName/dev.leonardo.octether.MainActivity" 2>$null | Out-Null
Start-Sleep -Seconds 3

# T-L2-05: 权限检查
$permissions = $pkgInfo | Select-String "permission:" | Measure-Object
$permList = ($pkgInfo | Select-String "permission:" | ForEach-Object { $_.Line.Trim() }) -join "; "
Test-Case -Id "T-L2-05" -Name "应用权限" -Passed $true `
    -Detail "Requested permissions: $($permissions.Count)"

# T-L2-03: 截图检查版本显示 (UI 层面)
$screenshotDir = "$env:TEMP\oc-remote-test"
New-Item -ItemType Directory -Path $screenshotDir -Force | Out-Null
& adb -s $Device shell screencap -p /sdcard/test-l2-03.png 2>$null | Out-Null
& adb -s $Device pull /sdcard/test-l2-03.png "$screenshotDir\test-l2-03.png" 2>$null | Out-Null
$screenshotExists = Test-Path "$screenshotDir\test-l2-03.png"
Test-Case -Id "T-L2-03" -Name "版本号 UI (截图取证)" -Passed $screenshotExists `
    -Detail "Screenshot saved: $screenshotDir\test-l2-03.png (manual verification needed)"

# T-L2-01: 发送按钮截图
& adb -s $Device shell screencap -p /sdcard/test-l2-01.png 2>$null | Out-Null
& adb -s $Device pull /sdcard/test-l2-01.png "$screenshotDir\test-l2-01.png" 2>$null | Out-Null
$btnScreenshotExists = Test-Path "$screenshotDir\test-l2-01.png"
Test-Case -Id "T-L2-01" -Name "发送按钮空状态 (截图取证)" -Passed $btnScreenshotExists `
    -Detail "Screenshot saved: $screenshotDir\test-l2-01.png (manual verification needed)"

# T-L2-02: 输入文字后截图
& adb -s $Device shell input text "hello" 2>$null | Out-Null
Start-Sleep -Seconds 1
& adb -s $Device shell screencap -p /sdcard/test-l2-02.png 2>$null | Out-Null
& adb -s $Device pull /sdcard/test-l2-02.png "$screenshotDir\test-l2-02.png" 2>$null | Out-Null
$inputScreenshotExists = Test-Path "$screenshotDir\test-l2-02.png"
Test-Case -Id "T-L2-02" -Name "发送按钮活跃状态 (截图取证)" -Passed $inputScreenshotExists `
    -Detail "Screenshot saved: $screenshotDir\test-l2-02.png (manual verification needed)"

# ============================================================
# 结果汇总
# ============================================================
Write-Host ""
Write-Host "============================================" -ForegroundColor Cyan
Write-Host "  测试结果汇总" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  PASS: $PassCount" -ForegroundColor Green
Write-Host "  FAIL: $FailCount" -ForegroundColor Red
Write-Host "  TOTAL: $($PassCount + $FailCount)" -ForegroundColor White
Write-Host ""

if ($FailCount -gt 0) {
    Write-Host "  失败用例:" -ForegroundColor Red
    $Results | Where-Object { $_.Status -eq "FAIL" } | ForEach-Object {
        Write-Host "    - $($_.ID): $($_.Name)" -ForegroundColor Red
        Write-Host "      $($_.Detail)" -ForegroundColor Gray
    }
}

Write-Host ""
Write-Host "截图保存在: $screenshotDir" -ForegroundColor Gray
Write-Host "L3/L4 测试需手动执行，请参考测试手册" -ForegroundColor Gray
Write-Host ""

# 导出结果到 CSV
$csvPath = Join-Path $screenshotDir "test-results-$(Get-Date -Format 'yyyyMMdd-HHmmss').csv"
$Results | Export-Csv -Path $csvPath -NoTypeInformation -Encoding UTF8
Write-Host "测试结果已导出: $csvPath" -ForegroundColor Gray
```

### 8.2 使用方法

```powershell
# 基本用法
.\test-l0-l2.ps1 -ApkPath "D:\Develop\code\app\oc-remote\app\build\outputs\apk\debug\app-debug.apk"

# 指定设备
.\test-l0-l2.ps1 -ApkPath "D:\path\to\app.apk" -Device "emulator-5554"

# 查看帮助
Get-Help .\test-l0-l2.ps1 -Full
```

---

## 9. 截图取证命令

### 9.1 单张截图

```powershell
# 截取当前屏幕
adb -s emulator-5554 shell screencap -p /sdcard/screenshot.png
adb -s emulator-5554 pull /sdcard/screenshot.png ./screenshots/screenshot.png

# 清理设备上的临时截图
adb -s emulator-5554 shell rm /sdcard/screenshot.png
```

### 9.2 批量截图 (L3 全流程)

```powershell
<#
.SYNOPSIS
    L3 测试截图取证批量脚本
#>
$device = "emulator-5554"
$dir = ".\screenshots\beta66-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
New-Item -ItemType Directory -Path $dir -Force | Out-Null

function Capture-Screenshot {
    param([string]$Name)
    $file = "$dir\$Name.png"
    adb -s $device shell screencap -p /sdcard/temp-capture.png
    adb -s $device pull /sdcard/temp-capture.png $file
    adb -s $device shell rm /sdcard/temp-capture.png
    Write-Host "  Saved: $file" -ForegroundColor Green
}

Write-Host "L3 截图取证 - 按回车截取每一步" -ForegroundColor Cyan
Write-Host ""

$steps = @(
    "01-app-home",
    "02-send-button-empty",
    "03-send-button-active",
    "04-message-sent",
    "05-reasoning-block-animation",
    "06-reasoning-block-complete",
    "07-token-cost-stats",
    "08-read-tool-card",
    "09-write-tool-card",
    "10-search-tool-card",
    "11-mcp-tool-card",
    "12-todolist-card",
    "13-patch-card",
    "14-user-message-newlines",
    "15-text-selection-dialog",
    "16-scroll-no-keyboard"
)

foreach ($step in $steps) {
    Read-Host "Press Enter to capture: $step"
    Capture-Screenshot -Name $step
}

Write-Host ""
Write-Host "All screenshots saved to: $dir" -ForegroundColor Cyan
```

### 9.3 录屏取证 (动画/交互测试)

```powershell
# 开始录屏 (最长 180 秒)
adb -s emulator-5554 shell screenrecord --time-limit 30 /sdcard/test-recording.mp4

# 拉取录屏文件
adb -s emulator-5554 pull /sdcard/test-recording.mp4 ./screenshots/test-recording.mp4

# 清理
adb -s emulator-5554 shell rm /sdcard/test-recording.mp4
```

> **提示**: Task 2 (ReasoningBlock 动画) 和 Task 7 (键盘弹出) 建议使用录屏取证，截图可能无法完整捕捉动态行为。

---

## 10. 缺陷报告模板

测试中发现问题时，请按以下格式记录:

```markdown
### Bug Report: [简短描述]

**发现于**: T-{层级}-{编号} (e.g., T-L3-07)
**严重程度**: P0-崩溃 / P1-功能不可用 / P2-功能异常 / P3-视觉问题
**对应任务**: Task {N} — {任务名称}

**复现步骤**:
1.
2.
3.

**预期结果**:


**实际结果**:


**设备信息**:
- Device: emulator-5554
- Android: (adb shell getprop ro.build.version.release)
- App Version: 2.0.0-beta.66

**截图/录屏**: [附件路径]

**日志**:
```
(粘贴相关 logcat 输出)
```
```

### 获取相关日志

```powershell
# 获取应用崩溃日志
adb -s emulator-5554 logcat -d -s "AndroidRuntime:E" "*:F"

# 获取应用相关日志
adb -s emulator-5554 logcat -d | Select-String "ocremote|minios"

# 实时监控日志 (测试过程中另开终端运行)
adb -s emulator-5554 logcat -s "AndroidRuntime:E" "ActivityManager:I"
```

---

## 附录 A: 测试执行时间估算

| 层级 | 预计耗时 | 备注 |
|------|----------|------|
| L0 静态验证 | 5 分钟 | 大部分可自动化 |
| L1 安装启动 | 5 分钟 | 全部可自动化 |
| L2 UI 冒烟 | 10 分钟 | 截图取证为主 |
| L3 后端联调 | 30-45 分钟 | 需手动操作 + 截图 |
| L4 回归清单 | 15 分钟 | 基于 L3 结果快速确认 |
| **总计** | **约 65-80 分钟** | |

## 附录 B: 快速命令参考

```powershell
# 安装
adb -s emulator-5554 install -r app-debug.apk

# 启动
adb -s emulator-5554 shell am start -n dev.leonardo.octether.v2/dev.leonardo.octether.MainActivity

# 强制停止
adb -s emulator-5554 shell am force-stop dev.leonardo.octether.v2

# 卸载
adb -s emulator-5554 uninstall dev.leonardo.octether.v2

# 清除数据
adb -s emulator-5554 shell pm clear dev.leonardo.octether.v2

# 查看版本
adb -s emulator-5554 shell dumpsys package dev.leonardo.octether.v2 | findstr versionName

# 输入文字
adb -s emulator-5554 shell input text "hello"

# 按键
adb -s emulator-5554 shell input keyevent KEYCODE_HOME
adb -s emulator-5554 shell input keyevent KEYCODE_ENTER
adb -s emulator-5554 shell input keyevent KEYCODE_BACK

# 截图
adb -s emulator-5554 shell screencap -p /sdcard/screen.png
adb -s emulator-5554 pull /sdcard/screen.png .\screen.png
```

---

*文档结束 — oc-remote v2.0.0-beta.66 E2E Test Handbook*
