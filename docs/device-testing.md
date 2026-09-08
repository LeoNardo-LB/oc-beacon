# 测试环境 Runbook — OC Beacon（真机 + 模拟器）

> 2026-09-09 #382 文档整合：本文档由 real-device-testing.md（主体）+ e2e-testing-workflow.md（模拟器环境节）合并而成（real-device-testing 更名本文件，e2e-testing-workflow 保留 tombstone）。

## 环境选择

> 2026-08-20 用户方针：**后续测试一律真机优先，模拟器只作后备**。

- **真机（默认环境）**：一切 E2E/回归测试优先在真机执行；装包、服务器连通、一键配置的操作手册见下文「真机测试」章。
- **模拟器（后备环境）适用场景**：
  - UI 调试走查（tap/截图/logcat）——委派 subagent 执行，避免主会话上下文溢出；
  - 需要从模拟器访问宿主机服务——模拟器访问宿主机用 `10.0.2.2`（真机不走这个，真机用 adb reverse，见真机章「服务器连通：adb reverse」节）。

## 真机测试（优先）

> 本章是真机 E2E/回归测试的操作手册（装包、服务器连通、一键配置）。
> 历史背景：本 runbook 在「主对话抽屉高度统一」批次的真机 E2E 中打通，证据存 `/tmp/sheet75r/`。

### 测试设备

| 项 | 值 |
|---|---|
| 机型 | 小米 23127PN0CC（houji，HyperOS） |
| serial | `e69a99d8`（USB 连接） |
| 屏幕 | 1200x2670 @480dpi |

所有命令带 `-s e69a99d8`；**严禁误触 `emulator-5554`**（若模拟器同时在线）。

### 标准测试入口（第一优先级）：debug intent 直达会话列表

**所有真机测试会话从这里开始**（2026-08-25 用户定规：debug 进入会话列表的方法优先级提一级）。一条命令得到确定起点：已连接服务器 + 停在会话列表，免手工导航：

```bash
./scripts/debug-entry.sh [serial] [包名]     # 默认 e69a99d8 + dev 包
```

脚本做三件事：`adb reverse tcp:4199`（幂等）→ force-stop 冷启 → 校验 logcat 出现 `Debug channel activated` + `NavGraph: Debug channel → SessionList`（失败即非零退出）。密码从 `/persistent/home/leo-tkp/.config/opencode/service.json` 读取（`OCBEACEN_SERVICE_JSON` 可覆盖）。

**为什么禁止手工导航**（2026-08-25 #222 E2E 实证连续踩坑）：Settings → Sessions → 行点击链坐标易错（awk 解析 bounds 曾算错中心点进错会话）；BACK 键易把应用退到桌面；`uiautomator dump` 失败时静默返回旧文件误导判断。手工导航只留作 debug intent 不可用时（非 debug 构建）的后备。

手工等价命令（脚本本质）见下文「一键配置服务器：debug intent」节。

### 一次性准备（已配置，换机才需重做）

1. 开发者选项已开启、USB 调试已授权
2. MIUI「USB 安装」已开启（`adb install` 首次授权用；日常走下方 pm install 静默法则用不到）
3. 屏幕常亮已设 `adb -s e69a99d8 shell svc power stayon usb`（2026-08-20 用户决策：**保持常态，不用恢复**）

### 装包：pm install 静默法（标准姿势，无人工确认）

MIUI/HyperOS 的安装确认弹窗只挂在 **`adb install`** 的流式安装会话（PackageInstaller UI）上；**`adb shell pm install`** 以 shell 用户直调包管理器，不经过该会话，**完全静默**（实测 0.4-0.5s/次，锁屏亦可）。

```bash
adb -s e69a99d8 push <apk路径> /data/local/tmp/t.apk
adb -s e69a99d8 shell pm install -r /data/local/tmp/t.apk    # 降级装加 -d
adb -s e69a99d8 shell rm /data/local/tmp/t.apk
```

备选 `adb install -r`：会弹 MIUI 确认窗，需**屏幕解锁亮屏 + 人工点「安装」**；锁屏状态下弹窗无法显示，直接报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。不要用「关闭 MIUI 优化」换取 adb install 静默（副作用大：权限管理/双开等特性退化，且开关位置深）。

#### 全新安装的弹窗自动点穿（无人值守首装，2026-08-21 实证）

覆盖安装（同签名 `-r`）走上面的静默法即可；但**全新安装**（卸载后 / 新包名）MIUI 必弹用户确认——`pm install` 同样被拦，无人点击约 25s 后报 `INSTALL_FAILED_USER_RESTRICTED`。无人值守方案：后台跑 `pm install`，同时轮询 uiautomator 树把确认按钮点掉（实测 2s 内点一次「继续安装」即过）。**前提：设备解锁亮屏**（锁屏下弹窗不显示，直接失败）。

一键脚本（按钮文案 zh 匹配，其他语言需补 `BUTTONS` 文案）：

```bash
./scripts/miui-install.sh <apk路径> [serial] [额外 pm install 参数...]
# 例: ./scripts/miui-install.sh app/build/outputs/apk/dev/debug/app-dev-debug.apk
#     ./scripts/miui-install.sh foo.apk e69a99d8 -r -d
```

核心命令（脚本本质，手动执行用）：

```bash
adb -s e69a99d8 push app.apk /data/local/tmp/t.apk
adb -s e69a99d8 shell pm install /data/local/tmp/t.apk &   # 后台
# 循环（2s 一次）直到包出现：dump → 找「继续安装」/「安装」/「确定」按钮 bounds → input tap 中心点
adb -s e69a99d8 shell uiautomator dump /sdcard/ui.xml
adb -s e69a99d8 shell cat /sdcard/ui.xml   # 在其中找 text="继续安装" 节点的 bounds="[x1,y1][x2,y2]"
adb -s e69a99d8 shell input tap $(( (x1+x2)/2 )) $(( (y1+y2)/2 ))
```

常见错误：

| 报错 | 含义 / 处置 |
|---|---|
| `INSTALL_FAILED_USER_RESTRICTED` | 弹窗被取消（锁屏或未点）。覆盖安装 → 改用 pm install 静默法；全新安装 → `scripts/miui-install.sh` 自动点穿（见上节），或人工点「安装」 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | dev flavor versionCode 是 Unix 时间戳，旧构建装不上去 → `pm install -r -d`（debug 构建可降级；release 构建非 debuggable 不可降级，只能卸载重装）。**2026-08-27 插桩测试插曲：跑过 `connectedDevDebugAndroidTest` 后设备是 devDebug 更高时间戳，随后装 devRelease 报 "older than current"——`pm uninstall --user 0` 会静默残留（`pm list packages` 仍列出），必须 `adb uninstall` 重试到清零再装** |
| 插桩测试装 test 包被拦 | `connectedDevDebugAndroidTest` 的卸载/安装编排会被 MIUI 拦（"failed to uninstall test APK"）。绕行：`adb uninstall` 清两包 → 手动装主包 + test 包（`app-dev-debug-androidTest.apk`，用 `miui-install.sh` 点穿）→ `adb shell am instrument -w <pkg>.test/dev.leonardo.ocbeacon.HiltTestRunner` 直跑（runner 名以 `pm list instrumentation` 为准，不是 AndroidJUnitRunner） |

#### 降级装 CI 产物的正确姿势（2026-08-23 实证：验证 Release 包场景）

想装回**更旧的 CI 包**（如验证某 tag 产物）而设备已是更新的本地构建：`-r -d` 对 release 包无效；`pm uninstall -k`（保数据卸载）后仍记 versionCode 地板（`DELETE_KEEP_DATA` 状态照拦降级，实测）。**唯一通路 = 完整卸载重装**：

```bash
adb -s e69a99d8 uninstall dev.leonardo.ocbeacon.dev   # 数据清掉（dev 包数据=测试服务器配置，可用 debug intent 秒恢复）
./scripts/miui-install.sh <CI 包.apk> e69a99d8          # 全新安装必弹 MIUI 确认，脚本自动点穿
```

保数据变体（仅同签名适用）：`pm uninstall -k` + `miui-install.sh` 装**更新**的包——实测数据/服务器配置全保留。预防：要验证 CI 产物时，**先装 CI 包再做任何本地构建**，避免时间戳被顶高。

### 服务器连通：adb reverse

真机访问宿主机 opencode server（4199）用端口反向代理（设备侧 `127.0.0.1:4199` → 宿主机 `4199`）：

```bash
adb -s e69a99d8 reverse tcp:4199 tcp:4199
```

注意：**App 卸载重装后 reverse 不受影响，但重启 adb/设备后会清空**——每次测试前跑一遍并确认 `reverse --list` 有该项。模拟器才用 `10.0.2.2`，真机不走这个。

### 一键配置服务器：debug intent

debug 构建支持 intent 直达（`MainActivity.handleDebugProfileIntent`，仅 `BuildConfig.DEBUG` 生效）：幂等保存服务器 → 版本探测 → 连接 → 直达会话列表。**免去手工输入 URL/密码**（`input text` 被禁用，手工输 URL 很痛苦）：

```bash
adb -s e69a99d8 shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://127.0.0.1:4199 \
  --es debug_username opencode \
  --es debug_password <密码> \
  --es debug_name 'Host-4199'
```

成功标志（logcat）：`Debug channel activated` + `NavGraph: Debug channel → SessionList`。

### 签名体系备忘（与装包相关）

- 本地机器**没有** release keystore（`app/keystore/` 为空，仅 CI Secrets 存留，2026-08-20 确认失联）→ 本地一切构建（含 devRelease）都是 debug 签名
- 后果：本地 debug 包 ↔ CI release 包（GitHub Release 的 dev 包）**互不覆盖**，切换需卸载重装一次（会清 App 数据/服务器配置，用上面 intent 秒恢复）
- **2026-08-20 keystore 更换（用户决策）**：旧 release.jks 确认丢失（git 历史从未提交、本机全盘无副本、CI Secrets 只写不读），已生成**新 keystore**（同 DN：CN=OC Beacon, OU=Development, O=LeoNardo-LB, C=CN；alias=oc-beacon（2026-08-20 由 oc-tether 改名，密钥材料不变）；有效期 30 年）→ 本地 `app/keystore/`（gitignore 保护，不入库）+ CI Secrets 三件套已同步更新（2026-08-20T03:09Z）。
- **切换时序**：v0.3.1-dev.18 为旧签名最后一版；**下一起 CI 构建起新签名生效**——已装 CI 签名包（≤dev.18）升级新包时需卸载重装一次（0.x 阶段用户仅开发者本人，代价已接受）
- **keystore 备份指引**：`app/keystore/release.jks` + `signing.properties` 建议私有备份一份（密码管理器/私有网盘）；再丢一次同样只能换 keystore + 全员卸载重装
- **2026-08-29 #259 debug 签名身份钉死**：`app/keystore/debug.jks` **入库**（指纹 8F:7A:EC:81…，密码公开惯例 "android"，非机密）——debug 构建不再随环境解析漂移（`$XDG_CONFIG_HOME/.android` 8f7a vs `~/.android` 3fdd 两把并存曾致同日构建身份互斥、覆盖安装 INSTALL_FAILED_UPDATE_INCOMPATIBLE）。本地/CI/任意终端 dev 构建同指纹；CI devDebug 首次改用库内身份，历史 CI 签名包升级需卸载重装一次

### E2E 操作纪律（真机差异点）

- 禁止 `input text`（合成键盘事件触发预测性 back 伪影）——只 `input tap / swipe / keyevent`，打字需求用 `./scripts/type.sh "text" [serial]`（纯 keyevent，已入库）或干脆用 intent 传参绕过
- MIUI 上 `uiautomator dump` 约 2-3s/次，耐心重试；Compose 弹层（Popup/sheet 内自绘组件）节点不可见，按 content-desc/文本定位
- MIUI 首启权限弹窗不一定出现，dump 检查后点「允许」即可
- 截图取证：`adb -s e69a99d8 exec-out screencap -p > x.png`（exec-out 避免换行污染）
- **聊天页滚动方向**（2026-08-21 教训，曾致 0 帧误判两轮）：进入会话默认停在底部（最新消息）；**手指向下滑（如 `input swipe 600 500 600 1600`）才是看更旧消息**；`1600→500` 是向“最新以下”滑——无内容、列表不滚、gfxinfo 记 0 帧。滚动测量前先用「滑动前后 dump 可见时间戳 diff」或帧数 sanity（>100）确认真的滚了
- **测试入口纪律**（2026-08-25 定规）：每轮真机测试开始一律 `./scripts/debug-entry.sh` 直达会话列表（见「标准测试入口」节）；force-stop/重启 adb/重装后**先跑脚本再继续**（reverse 会被清空）。禁止从 Settings 页手工点进会话列表
- **IME 在场判定与注入陷阱**（2026-09-07 #345 定性沉淀）：Doubang 输入法为**浅色主题**——screencap 下半屏主色与 app surface 同族 (247,250,253)，像素分析无法区分键盘与界面，误判「无 IME」后 tap 全打在键盘上=看似失活。权威判定：`adb shell dumpsys input_method | grep mInputShown`。IME 抬起期 `uiautomator dump` 恒 ~7 节点（输入法安全窗致盲，正常现象勿当 UI 损坏）；此态下 composer 发送键在 (1086,1616)，IME 收起后在 (1086,2538)
- **注入 tap 间歇丢弃**（MIUI 平台行为，#345）：注入 tap 可能被静默丢弃（shade 组卡子卡 E4② 先例；2026-09-07 疑似会话内两案，其一已翻案为浅色键盘误读）。tap「失活」时先 `dumpsys input_method`（IME 状态）+`keyevent 4`（键通道是否活）二分定位；确属注入丢弃 → `./scripts/debug-entry.sh` 冷启重置任务栈即恢复。勿据此立 app 缺陷卡，除非真手指复现

### V1 测试服务器快速搭建（2026-08-25 实战定稿）

本机 `opencode` 二进制即 1.18.18（V1）；与 V2 服务（@opencode-ai/cli）共存互不干扰。隔离启动配方：

```bash
# 1. 隔离环境（不隔离会撞现有 DB 报 Database is not empty）
mkdir -p /tmp/v1srv/{data/opencode,config/opencode,home}
# 2. 自定义 provider（opencode.json 放 config 目录）；auth.json 放 **data** 目录
#    （V1 的 Global.Path.data）——放 config 目录不生效（401 无 Authorization 实测）
#    key 可从 V2 凭据库提取：sqlite3 <v2db> "SELECT value FROM credential WHERE integration_id='zhipuai-coding-plan'"
# 3. 启动（nohup setsid 防会话中断连带被杀——裸 setsid+& 曾被杀，实测）
nohup setsid env XDG_DATA_HOME=/tmp/v1srv/data XDG_CONFIG_HOME=/tmp/v1srv/config HOME=/tmp/v1srv/home \
  OPENCODE_SERVER_PASSWORD=<密码> opencode serve --hostname 127.0.0.1 --port 4198 \
  > /tmp/v1srv/serve.log 2>&1 < /dev/null & disown
# 4. adb -s e69a99d8 reverse tcp:4198 tcp:4198
# 5. App 接入：force-stop 后 debug intent（debug-entry.sh 改 URL/名字等价；warm start 不解析 intent，必须冷启）
```

V1 契约要点（实测）：prompt 走 `POST /session/{id}/prompt_async`，body **扁平** `{"parts":[{"type":"text","text":...}]}`（无 data 包装、必带 parts）；compact 产物 = `assistant(agent=compaction)` 常规消息（无 Part.Compaction），app 端渲染为普通气泡——与 V2 的分割线形态是**服务器语义差异**非客户端缺陷。
- 每轮测试前后 `logcat -c` / `-d` 存档，grep FATAL/AndroidRuntime 计数

### 插桩测试（am instrument）特有前置（2026-08-24 #210 实证）

1. **「后台弹出界面」权限——卸载重装后必查**：MIUI DeviceGuard 在应用无前台窗口时拦截后台 Activity 启动（logcat 标志 `MIUILOG- Permission Denied Activity` + `Abort background activity starts`，START result code=102）。`am instrument` 起新进程后首个测试 Activity（如 HiltEntryActivity）即被拦，`startActivitySync` 对 aborted launch 无超时 → **插桩测试静默挂死**（0% CPU 全线程 sleeping，无任何异常输出）。该权限随包卸载重置——跨签名切换/降级装 CI 包的卸载重装流程之后必须重新授予：`./scripts/miui-grant-bal.sh dev.leonardo.ocbeacon.dev`（需解锁亮屏；adb 无法绕过：HyperOS 无 `bg_activity_start` appop、无 `pm set-app-locales`）。快速自检：`adb shell dumpsys window | grep mCurrentFocus` 看测试 Activity 是否起来。
2. **挂死取证（无 root）**：debug 构建进程 debuggable → JDWP 可用——`adb jdwp` 找 pid → `adb forward tcp:8700 jdwp:<pid>` → `jdb -attach localhost:8700`（openjdk@21 自带）。`thread <id>` 可能报无效线程，用 `suspend` + `where all` 取全栈（会冻结进程，测毕 `resume`）。
3. **测试语言与设备系统语言解耦（chat.* 族已修）**：HiltEntryActivity 强制 en-US（#210）——英文资源断言（"Stop" 等）不再随系统语言漂移；其余 createComposeRule 族测试类仍依赖系统 locale=英文（#211）。

> **元素定位/判定/手势的完整方法**（dump 失明区、像素探针、vision 纪律、slop/返回手势区坑、签名速查）见 [`docs/probing.md`](probing.md)（2026-08-23 #192 E2E 实战定稿；#382 整合自 android-ui-probing-guide）。

#### ⚠️ debug-entry.sh 会重指后端（2026-09-05 #308 验收实证）

`./scripts/debug-entry.sh` 默认把 app 连接重指到 **Host-4199（opencode V2）**——DSH 生产(:3080)测试若直接跑该入口，会进错后端（会话/权限事件全然不同，PermissionAsked 不发）。DSH 生产测试流程：改脚本参数（或入口后手动切服务器）到 `http://127.0.0.1:3080` + `adb reverse tcp:3080 tcp:3080`，并在验收 P0 阶段**核验 app 当前连接 URL=预期后端**（服务器页/设置页可见）再开跑。

### 真机独占与串行纪律（2026-09-04）

真机是**单一独占资源**：同一时刻只允许一个验收执行流操作设备——一次一卡、卡内一次一项，**禁止多个 subagent 并发操作真机**。不碰真机的工作（checklist 生成/审查、代码分析、单测）可并行；Gradle 构建禁并发与真机锁相互独立（AGENTS.md）。完整协议见 [`ai-acceptance-workflow.md`](ai-acceptance-workflow.md) §5。

## 模拟器测试（后备）

### 前置环境

| 项 | 值 | 说明 |
|----|-----|------|
| 模拟器 AVD | `Medium_Phone` | 已存在，无头/最小化启动 |
| 测试服务器 | `10.0.2.2:<port>` | 宿主机 opencode serve（现行 V2=4199）；模拟器经 10.0.2.2 访问（2026-09-09 #382 勘误：源文档快照为 4096 已过时） |
| 凭据 | `opencode` / service.json | 用户名 `opencode`，密码见 `/persistent/home/leo-tkp/.config/opencode/service.json` `password` 字段（2026-09-09 #382 勘误：环境变量口径已废） |
| 测试包 | `dev.leonardo.ocbeacon.dev` | dev flavor debug APK |
| 工具 | replicant MCP（adb/ui-query/ui-action/ui-capture）+ adb | `~/Android/Sdk/platform-tools/adb`（Windows: `C:\Users\Administrator\AppData\Local\Android\Sdk\platform-tools\adb.exe`） |

### 启动模拟器（等待 boot 完成）

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

### 构建 + 安装

> **2026-08-13 规则（用户决策）**：测试构建**禁止卸载重装**——dev flavor 的 versionCode 用 Unix 时间戳（build.gradle.kts 动态计算），每次构建自动递增，`adb install -r` 即可覆盖安装并**保留 App 数据/服务器配置**（省去重新配置服务器的开销）。若报 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，先核对 debug keystore 一致性，不要卸载重装。

```bash
./gradlew :app:assembleDevDebug   # 构建（超时 300s）  Windows: .\gradlew.bat :app:assembleDevDebug
# 安装（replicant adb-app install，或）：
# Windows: & $adb install -r app\build\outputs\apk\dev\debug\app-dev-debug.apk
$adb install -r app/build/outputs/apk/dev/debug/app-dev-debug.apk
```

> 模拟器无 MIUI 安装确认弹窗，`adb install -r` 直接静默可用；真机装包必须走真机章「装包：pm install 静默法」。

## 测试用例矩阵

用例矩阵见 [`docs/dialogue-e2e-test-plan.md`](dialogue-e2e-test-plan.md)。

### E2E 执行规范（2026-09-09 #382 审计回补，迁自 e2e-testing-workflow §1/§6/§7）

**触发类型**——以下改动必须执行 E2E 工作流（至少 TC1/TC2/TC5）：
- 通知相关（持久通知、事件通知、渠道、去重、抑制）
- 会话列表/分组/聚合逻辑
- 导航/深链（通知点击、路由）
- 字符串/多语言
- 连接状态机（SSE 连接、重连、断开）

**委派执行**：模拟器 UI 交互应委派 subagent 执行，避免主会话上下文污染。委派 prompt 必须包含：环境信息（模拟器/设备 ID、服务器地址、凭据来源）、逐条测试用例、断言方法、输出格式（TC 编号 + PASS/FAIL + 实际观察值 + 截图路径）。

**断言要点（会话分组域）**：目录视图分组标题为目录 basename；分组键为完整目录路径（不同目录不合并）；**禁止出现 "global" 聚合目录**（服务器 /project 的全局项目名）。
