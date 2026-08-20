# 真机测试 Runbook — OC Beacon

> 2026-08-20 用户方针：**后续测试一律真机优先，模拟器只作后备**。本文档是真机 E2E/回归测试的操作手册（装包、服务器连通、一键配置）。
> 历史背景：本 runbook 在「主对话抽屉高度统一」批次的真机 E2E 中打通，证据存 `/tmp/sheet75r/`。

## 测试设备

| 项 | 值 |
|---|---|
| 机型 | 小米 23127PN0CC（houji，HyperOS） |
| serial | `e69a99d8`（USB 连接） |
| 屏幕 | 1200x2670 @480dpi |

所有命令带 `-s e69a99d8`；**严禁误触 `emulator-5554`**（若模拟器同时在线）。

## 一次性准备（已配置，换机才需重做）

1. 开发者选项已开启、USB 调试已授权
2. MIUI「USB 安装」已开启（`adb install` 首次授权用；日常走下方 pm install 静默法则用不到）
3. 屏幕常亮已设 `adb -s e69a99d8 shell svc power stayon usb`（2026-08-20 用户决策：**保持常态，不用恢复**）

## 装包：pm install 静默法（标准姿势，无人工确认）

MIUI/HyperOS 的安装确认弹窗只挂在 **`adb install`** 的流式安装会话（PackageInstaller UI）上；**`adb shell pm install`** 以 shell 用户直调包管理器，不经过该会话，**完全静默**（实测 0.4-0.5s/次，锁屏亦可）。

```bash
adb -s e69a99d8 push <apk路径> /data/local/tmp/t.apk
adb -s e69a99d8 shell pm install -r /data/local/tmp/t.apk    # 降级装加 -d
adb -s e69a99d8 shell rm /data/local/tmp/t.apk
```

备选 `adb install -r`：会弹 MIUI 确认窗，需**屏幕解锁亮屏 + 人工点「安装」**；锁屏状态下弹窗无法显示，直接报 `INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`。不要用「关闭 MIUI 优化」换取 adb install 静默（副作用大：权限管理/双开等特性退化，且开关位置深）。

常见错误：

| 报错 | 含义 / 处置 |
|---|---|
| `INSTALL_FAILED_USER_RESTRICTED` | adb install 弹窗被取消（锁屏或未点）→ 改用 pm install 静默法 |
| `INSTALL_FAILED_VERSION_DOWNGRADE` | dev flavor versionCode 是 Unix 时间戳，旧构建装不上去 → `pm install -r -d`（debug 构建可降级；release 构建非 debuggable 不可降级，只能卸载重装） |

## 服务器连通：adb reverse

真机访问宿主机 opencode server（4199）用端口反向代理（设备侧 `127.0.0.1:4199` → 宿主机 `4199`）：

```bash
adb -s e69a99d8 reverse tcp:4199 tcp:4199
```

注意：**App 卸载重装后 reverse 不受影响，但重启 adb/设备后会清空**——每次测试前跑一遍并确认 `reverse --list` 有该项。模拟器才用 `10.0.2.2`，真机不走这个。

## 一键配置服务器：debug intent

debug 构建支持 intent 直达（`MainActivity.handleDebugProfileIntent`，仅 `BuildConfig.DEBUG` 生效）：幂等保存服务器 → 版本探测 → 连接 → 直达会话列表。**免去手工输入 URL/密码**（`input text` 被禁用，手工输 URL 很痛苦）：

```bash
adb -s e69a99d8 shell am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity \
  --es debug_url http://127.0.0.1:4199 \
  --es debug_username opencode \
  --es debug_password <密码> \
  --es debug_name 'Host-4199'
```

成功标志（logcat）：`Debug channel activated` + `NavGraph: Debug channel → SessionList`。

## 签名体系备忘（与装包相关）

- 本地机器**没有** release keystore（`app/keystore/` 为空，仅 CI Secrets 存留，2026-08-20 确认失联）→ 本地一切构建（含 devRelease）都是 debug 签名
- 后果：本地 debug 包 ↔ CI release 包（GitHub Release 的 dev 包）**互不覆盖**，切换需卸载重装一次（会清 App 数据/服务器配置，用上面 intent 秒恢复）
- 若未来找回 release.jks：放回 `app/keystore/` + signing.properties，本地即出同 CI 签名的包

## E2E 操作纪律（真机差异点）

- 禁止 `input text`（合成键盘事件触发预测性 back 伪影）——只 `input tap / swipe / keyevent`，打字需求用 [/tmp/type.sh keyevent 脚本] 或干脆用 intent 传参绕过
- MIUI 上 `uiautomator dump` 约 2-3s/次，耐心重试；Compose 弹层（Popup/sheet 内自绘组件）节点不可见，按 content-desc/文本定位
- MIUI 首启权限弹窗不一定出现，dump 检查后点「允许」即可
- 截图取证：`adb -s e69a99d8 exec-out screencap -p > x.png`（exec-out 避免换行污染）
- 每轮测试前后 `logcat -c` / `-d` 存档，grep FATAL/AndroidRuntime 计数