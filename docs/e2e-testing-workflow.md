# E2E 测试工作流 — OC Beacon

> 本文档记录 OC Beacon 的端到端（E2E）测试工作流，可直接复用于后续功能/修复验证。
> 适用场景：通知行为、会话列表、多语言、连接状态等涉及 UI 交互的改动。
> 最后执行：2026-08-05（连接状态通知 / 会话分组 / 通知开关 / 多语言 / 崩溃回归，5/5 通过）

---

## 1. 何时使用

以下改动类型必须执行本工作流（至少 TC1/TC2/TC5）：
- 通知相关（持久通知、事件通知、渠道、去重、抑制）
- 会话列表/分组/聚合逻辑
- 导航/深链（通知点击、路由）
- 字符串/多语言
- 连接状态机（SSE 连接、重连、断开）

## 2. 前置环境

| 项 | 值 | 说明 |
|----|-----|------|
| 模拟器 AVD | `Medium_Phone` | 已存在，无头/最小化启动 |
| 测试服务器 | `10.0.2.2:4096` | 宿主机 opencode serve；模拟器经 10.0.2.2 访问 |
| 凭据 | `opencode` / `$OPENCODE_SERVER_PASSWORD` | 密码从环境变量读取（Windows: `$env:OPENCODE_SERVER_PASSWORD`） |
| 测试包 | `dev.leonardo.ocbeacon.dev` | dev flavor debug APK |
| 工具 | replicant MCP（adb/ui-query/ui-action/ui-capture）+ adb | `~/Android/Sdk/platform-tools/adb`（Windows: `C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe`） |

## 3. 启动模拟器

```bash
# 1) 启动（后台分离）
# Windows: $sdk = "C:\Users\Administrator\AppData\Local\Android\Sdk"; Start-Process -FilePath "$sdk\emulator\emulator.exe" -ArgumentList "-avd","Medium_Phone","-no-snapshot-save","-no-boot-anim","-gpu","auto" -WindowStyle Minimized
sdk=~/Android/Sdk
nohup "$sdk/emulator/emulator" -avd Medium_Phone -no-snapshot-save -no-boot-anim -gpu auto >/dev/null 2>&1 &

# 2) 等待 boot 完成（最多 4 分钟）
# Windows: $adb = "$sdk\platform-tools\adb.exe"; do { $out = & $adb shell getprop sys.boot_completed 2>&1; if ($out -match "1") { break }; Start-Sleep -Seconds 5 } while ($true)
adb="$sdk/platform-tools/adb"
while [ "$($adb shell getprop sys.boot_completed 2>/dev/null)" != "1" ]; do sleep 5; done
```

## 4. 构建 + 安装

```bash
./gradlew :app:assembleDevDebug   # 构建（超时 300s）  Windows: .\gradlew.bat :app:assembleDevDebug
# 安装（replicant adb-app install，或）：
# Windows: & $adb install -r app\build\outputs\apk\dev\debug\app-dev-debug.apk
$adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

## 5. 测试用例矩阵

| TC | 覆盖点 | 关键断言 | 验证手段 |
|----|--------|---------|---------|
| TC1 | 连接状态持久通知 + 点击导航 | 通知文本 = "Connected to xxx"（非 Connecting）；点击进入会话列表 | `adb shell dumpsys notification --noredact` + UI 交互 |
| TC2 | 会话按目录分组 | 分组节点为目录；无 "global" 聚合文件夹 | ui-query dump 列表结构 |
| TC3 | 通知总开关 | 开关切换正常、设置页无崩溃 | UI 交互 |
| TC4 | 多语言字符串 | 中/英切换无硬编码残留 | 语言切换 + 截图 |
| TC5 | 崩溃回归 | 冷/热启动无 FATAL | `adb logcat -b crash` + 启动验证 |

## 6. 执行方式（委派 subagent）

模拟器 UI 交互应委派 `general` subagent 执行，避免主会话上下文污染。
委派 prompt 必须包含：环境信息（模拟器 ID、服务器地址、凭据来源）、逐条测试用例、断言方法、
输出格式（TC 编号 + PASS/FAIL + 实际观察值 + 截图路径）。

## 7. 断言速查

### 7.1 通知栏文本（TC1）
```bash
# Windows: & $adb shell dumpsys notification --noredact | Select-String -Pattern "Connected|Connecting|opencode_connection"
$adb shell dumpsys notification --noredact | grep -E "Connected|Connecting|opencode_connection"
```
- 期望：`text="Connected to <server>"`，channel=`opencode_connection`
- 回归信号：文本停留在 "Connecting…" 超过连接建立后数秒 = 状态未刷新 bug

### 7.2 会话分组（TC2）
- 文件夹视图分组标题为目录 basename（如 `oc-beacon`、`workspace`）
- 分组键为完整目录路径（不同目录不合并）
- **禁止出现 "global" 聚合文件夹**（服务器 /project 的全局项目名）

### 7.3 通知点击导航（TC1 子项）
- 点击持久通知 → 进入服务器**会话列表**（顶栏显示服务器地址）
- 回归信号：点击后停留在主页 = 深链未生效

### 7.4 崩溃（TC5）
```bash
# Windows: & $adb logcat -b crash -d
$adb logcat -b crash -d
```
- 期望：无 FATAL/AndroidRuntime；E 级系统噪声可忽略（SurfaceFlinger 等）

## 8. 截图归档

- 目录：`screenshots/e2e/`
- 命名：`<序号>_<场景>.png`（如 `01_home_launch`、`09_zh_settings`）
- 用 replicant ui-capture 的 `localPath` 保存到该目录

## 9. 本次执行结果（2026-08-05）

| TC | 结果 | 关键观察 |
|----|------|---------|
| TC1 | ✅ | 通知 "Connected to 10.0.2.2:4096"；点击 → 会话列表 |
| TC2 | ✅ | 目录分组 oc-beacon/workspace；无 global |
| TC3 | ✅ | 开关 ON↔OFF 正常 |
| TC4 | ✅ | 中英切换无残留 |
| TC5 | ✅ | 冷/热启动无崩溃 |

## 10. 注意事项

- 服务器凭据含特殊字符时，bash 中用单引号 `'` 包裹整个 URL 规避 shell 解释（Windows 附注：PowerShell 中 `"` 需转义）；URL 用 `http://10.0.2.2:4096`
- 通知抽屉操作在部分 Android 版本需先下拉通知栏（`cmd statusbar expand-notifications`）
- 若服务器无会话数据，TC2 退化为"空列表 + 无 global"断言
- 修改 ChatScreen.kt 后仍需遵守 `docs/chatscreen-editing-protocol.md`
