# #390 断连 UX 复现核查报告（2026-09-12）

> 核查员：clean-context 复现核查（只读 + 设备操作，未改任何产品代码，未跑 Gradle）
> 卡面：backlog #390「服务器断连时会话页空白/弹回服务器管理无重连提示」P3 `resilience`
> 对照：#267「双界面条幅」（2026-09-01 已完结，journal docs/journal/2026-09-01-disconnect-awareness.md）
> 设备：emulator-5554（sdk_gphone64_x86_64，locale zh-CN）独占
> 结论一句话：**三处指控均不成立；#267 条幅在 Chat/会话列表均有实测命中。服务器管理（Home）界面无该条幅（#267 双界面声明范围之外）且无手动重连按钮；恢复耗时因 5min SSE 冷却达 ~4m38s（非 #390 三指控，附带记录）。**

---

## 0. 环境与方法

| 项 | 值 |
|----|----|
| 包名 | `dev.leonardo.ocbeacon.dev` |
| APK 路径 | `/data/app/~~RwzyM41WJRrMnh9_sjVQ8w==/dev.leonardo.ocbeacon.dev-KTXOabCMTSgmDkUGLwVIVg==/base.apk` |
| APK md5 | `cc7fa9f8ebd3491cffc09043cac857e2` ✅ 与前置要求一致（未重装） |
| 服务器 | OpenCode @ `http://127.0.0.1:4199`（`adb reverse tcp:4199 tcp:4199`），user=opencode |
| 确认 loopback 路径 | logcat `SSE connection failed: Failed to connect to /127.0.0.1:4199`（断连窗口）——排除 LAN 直连干扰 |
| 观测手段 | `uiautomator dump` + `exec-out cat`；`adb logcat -v time`；`logcat -b crash -d` |
| 断连动作 | `adb -s emulator-5554 reverse --remove tcp:4199`（保留 3080/3081 不动） |
| 证据目录 | `docs/acceptance/2026-09-12-390-disconnect/evidence/` |

关键代码锚点（只读引用）：
- 条幅组件 `ui/components/ServerLinkBanner.kt`：errorContainer + CloudOff 14dp + 单行文案，**零交互**（无 onClick/按钮）。
- ChatScreen.kt:699-701 → `serverLinkState != Connected` 时渲染 `ServerLinkBanner()`。
- SessionListScreen.kt:194-207 → Scaffold **topBar** 内条件渲染（覆盖 pager page 0 会话 / page 1 设置）。
- HomeScreen（服务器管理）`ui/screens/home/HomeScreen.kt` **无** ServerLinkBanner 引用（grep 全仓仅 Chat/SessionList 两处调用）。
- 文案 `res/values-zh-rCN/strings.xml:856` = 「服务器已断开，正在重连…」；英文源 `values/strings.xml:936`。

---

## 1. 步骤 0 — 基线（Connected）

- 起点：debug 通道进入后停在该服务器的一个**已有内容**会话（标题「一加一等于二的提问」）。
- 基线 dump：`evidence/baseline-current.xml`
  - 资源节点：`chat-message-list`、`chat-input`、`chat-send`、`more_vert` 均在；
  - 文本节点 18 个，含用户/助手实体内容（如 `IDLECTRLR2 please reply OK`、`你好！😊 需要我帮你做什么？…`）；
  - **不含**「服务器已断开，正在重连…」 → 基线为 Connected，✅ 满足前置。

---

## 2. 步骤 1 — 断连 90s 逐点记录（指控①②③）

- 拆除 reverse：**23:47:40.198**（epoch 1789141660.198）。
- 每 5s dump 一次，19 帧：`evidence/disc-t000.xml` … `disc-t090.xml`；汇总 `evidence/dumps-summary.txt`；logcat `evidence/logcat-disconnect.txt`。

### 2.1 时间线（逐帧）

| 帧(t) | 横幅 | chat-message-list | chat-input | 文本节点数 | 导航 |
|-------|------|-------------------|-----------|-----------|------|
| disc-t000 | 否 | 是 | 是 | 18 | 会话页 |
| disc-t005 | **是** | 是 | 是 | 18 | 会话页 |
| disc-t010 ~ disc-t090 | 是 | 是 | 是 | 18 | 会话页（恒定，无导航） |

- 横幅首帧：`disc-t005.xml`（t=5s，距拆隧 9.2s），bounds `[95,139][1038,184]`；`disc-t000.xml` 无横幅 → **首次出现落在 (2.1s, 9.2s] 窗口**。
- 由 logcat 精确锚定：`23:47:45.669 W/SseConnManager: Transport failure reported for server 10afe73c-67e8-4631-88c1-b2fc9200fecc, kicking reconnect` → **横幅实际首现 ≈ 23:47:45.7，即拆隧后 ~5.5s**（与 #267 真机记录的「~9s」同量级，更快）。
- 横幅 19 帧全程在（直到观测窗结束仍未恢复，符合断连态）。

### 2.2 logcat 关键行（`evidence/logcat-disconnect.txt`）

```
09-11 23:47:45.669 W/SseConnManager(26933): Transport failure reported for server 10afe73c-67e8-4631-88c1-b2fc9200fecc, kicking reconnect
09-11 23:47:45.682 W/SseConnManager(26933): Disconnected from server 10afe73c-67e8-4631-88c1-b2fc9200fecc
09-11 23:47:45.686 D/SseConnManager(26933): [Host-4199] Reconnecting in 1000ms (attempt #1)
09-11 23:47:47.824 W/SseConnManager(26933): [Host-4199] SSE connection failed: Failed to connect to /127.0.0.1:4199
09-11 23:47:48.775 W/SseConnManager(26933): [Host-4199] Entering SSE cooldown after 5 consecutive timeouts
```

- 未出现 `EventDispatcher` / `releaseSessionData` 任何行（91s logcat 全量 grep 为空）→ 卡面所称「会话数据释放」**在本复现中未发生**。
- 应用进程 pid `26933` 拆隧前后不变（无进程死亡/Activity 重建）。
- `ServerLinkState` 为纯派生（ServerLinkState.kt:26 `derive(connected, connecting)`，无独立日志）；触发链 = 传输失败 → `Disconnected/Connecting` → 条幅。

---

## 3. 步骤 2 — 服务器管理/设置界面横幅核查（#267「双界面」之外的第三面）

正常导航（非 debug intent 重置），断连态下逐一 dump：

| 界面 | 证据 | 横幅 | 备注 |
|------|------|------|------|
| 会话页 ChatScreen | `disc-t005.xml` 等 | **有** | #267 声明面 |
| 会话列表 SessionListScreen（会话 tab） | `step2-afterback2.xml` | **有** | 横幅 bounds `[95,139][1038,184]`；标题 `Host-4199`，卡片错误行 `Failed to connect to /127.0.0.1:4199` |
| 会话列表「设置」tab（ServerSettingsContent，pager page 1） | `step2-settings-tab.xml` | **有** | 同一 Scaffold topBar，横幅在 pager 之上 |
| **服务器管理 HomeScreen（服务器列表）** | `step2-home.xml` | **无** | Host-4199 卡片状态 = `正在连接…` + `取消` 按钮（无该条幅） |

- HomeScreen 是导航图 `startDestination = Screen.Home.route`（NavGraph.kt:277），即卡面所称「服务器管理」界面。
- 该界面提供 per-card 状态「正在连接…」与「取消」，但**无** `ServerLinkBanner` 文案/图标。
- 与 #267 声明对照：journal 明示条幅落在「Chat / 会话列表」双界面，**Home 从未在 #267 范围内**；故 Home 无条幅属「声明范围外」，非 #267 回归。

---

## 4. 步骤 3 — 恢复

- reverse 回加：**23:51:53.925**（`evidence/logcat-recovery.txt`）。
- 恢复 UI：`evidence/recovery-sessionlist.xml`（横幅=否）、`evidence/recovery-chat2.xml`（横幅=否，chat-message-list/chat-input 在，文本节点 31，转录完整）。
- **但恢复并非 ≤60s**，而是 **~4m38s**：
  ```
  23:52:01.809 D/SseConnManager(26933): [Host-4199] SSE in cooldown, waiting 30000ms
  ... 23:53:01 / 23:54:01 / 23:54:31 / 23:55:01 / 23:56:01（每 30s 一次，共 ~4min 冷板凳）
  23:56:31.860 I/SseConnManager(26933): Connected to server 10afe73c-...   ← 恢复
  23:56:32.128 I/SseConnManager(26933): [Host-4199] Pre-loaded 550 sessions across 11 projects
  23:56:33.022 I/SseConnManager(26933): [Host-4199] Recovered messages for 50/50 sessions
  ```
- 期间 HTTP 已通：`23:52:24.765 REQUEST http://127.0.0.1:4199/api/form/request → 200 OK`，证明服务器可达而 **SSE 仍卡在冷却**。
- 机理（只读代码）：`SseClientDefaults.COOLDOWN_DURATION_MS = 300_000`（SseClient.kt:314），连续 5 次超时进入 5min 冷却；`runSseConnectionLoop` 冷却期内每 30s 只 `delay+continue`，**不发起连接**（SseConnectionManager.kt:443-447）；`reconnectAll/reconnectServer`（网络恢复回调）才会 `reset()` 冷却（:324），而 `adb reverse` 恢复不产生 Android 网络变化事件，故不触发。
- 该现象**不属于 #390 三条指控**（非「无横幅/空白/弹回」），但与「用户无路可走」的体验相关，附带记录。

---

## 5. 步骤 4 — 回归

- `adb logcat -b crash -d | grep -i ocbeacon` = **0 行**（核查开始与结束各一次）。
- events buffer 无 `am_crash`/`am_proc_died`（ocbeacon）；pid `26933` 全程存活。
- AppLogger 无 `E/` 级异常行。

---

## 6. 判定（逐条）

| # | 指控 | 判定 | 证据 |
|---|------|------|------|
| ① | 断连时无重连横幅/提示 | **不成立** | ChatScreen 横幅于拆隧后 ≈5.5s（logcat 23:47:45.669 锚定）首现，19/19 帧在；`disc-t000.xml`（无）vs `disc-t005.xml`（有，bounds [95,139][1038,184]） |
| ② | 转录变空白 | **不成立** | 19/19 帧 `chat-message-list`+`chat-input` 均在，文本节点恒 18（含横幅），用户/助手实体文本全程可读；logcat 无 `releaseSessionData`；pid 无重启。**注意**：本次 dump 明确报出文本，故 journal §十四 所述「dump 语义盲区致假空白」前提不适用 |
| ③ | 弹回服务器管理界面 | **不成立** | 断连全程停在 ChatScreen（resids 恒定，无导航）；进入会话列表/Home 均系我手动 BACK/tap 触发，非自动弹回 |
| 附加 | 服务器管理（Home）界面有无横幅 | **无** | `step2-home.xml` 横幅=否；Host-4199 卡片仅「正在连接…」+「取消」。**属 #267 双界面声明范围之外**，是范围外小缺口，非 #267 回归 |

---

## 7. 结论与建议

1. **#390 三条指控在本次仪器复现中均不成立**：条幅在场（~5.5s），转录不空白，无自动弹回。可判定 **#390 已被 #267 覆盖 → 建议关闭**（或降级为「已覆盖，保留观察」由用户拍板）。
2. **残余范围外点（1 处）**：服务器管理 HomeScreen 不渲染 `ServerLinkBanner`。该界面已有 per-card「正在连接…」状态，但无 #267 同款条幅；#267 规格本就只声明「双界面」，是否补第 3 面属**新范围决策**，建议**另登记小卡/由用户裁决**，不阻塞 #390 关闭。
3. **零交互 vs 重连按钮**：卡面原文「需断连 UX 兜底（提示+重连入口）」。本次实测提示已在；若用户仍要「手动重连按钮」，**与 #267「零交互（不设按钮，自动重连循环）」的用户裁决直接冲突**，须用户重新裁决后方可动工。
4. **附带发现（独立于 #390，建议另登记）**：非网络切换型的瞬断（如反向隧道拆除/恢复）恢复路径可能落在 5min SSE 冷却内，实测恢复时延 ~4m38s（HTTP 已通但 SSE  waits 30s×N）。冷却设计见 SseClient.kt:314 / SseConnectionManager.kt:443、:324。

---

## 8. 局限与未覆盖

- 单次复现（1 轮 90s 断连 + 1 轮恢复）；未覆盖真实移动网络切换/飞行模式（会触发 `reconnectAll`，恢复路径可能更快）。
- 未做视觉（VLM/截图）复核：断连窗未落截图；但本复现 dump **明确报出**转录文本，与「uiautomator 语义盲区」的假空白前提相反，故不以视觉为准心也能判定②不成立。
- 恢复期观测到会话轮次编号由基线「轮次 15/14」变为恢复后「轮次 14/13/12」（recoverMessages/REST 对账后重排），属**另一独立现象**，未在本卡范围内定性。
- 未改动任何产品代码，未运行 Gradle。

---

## 9. 证据文件清单（`docs/acceptance/2026-09-12-390-disconnect/evidence/`）

| 文件 | 内容 |
|------|------|
| `baseline-current.xml` | 步骤0 基线（Connected，转录非空、无横幅） |
| `disc-t000.xml` … `disc-t090.xml` | 步骤1 断连 19 帧 |
| `dumps-summary.txt` | 断连逐帧摘要（含 reverse_removed_epoch） |
| `logcat-disconnect.txt` | 断连窗全量 logcat（13k+ 行） |
| `step2-sessionlist.xml` | 会话列表（断连态，有横幅） |
| `step2-afterback2.xml` | 会话列表（同上，含 loopback 错误行） |
| `step2-settings-tab.xml` | 会话列表「设置」tab（断连态，有横幅） |
| `step2-home.xml` | 服务器管理 Home（断连态，**无横幅**） |
| `logcat-recovery.txt` | 恢复窗 logcat（冷却→Connected） |
| `recovery-poll.txt` + `recovery-poll-*.xml` | 恢复轮询（poll 7 = 已连接） |
| `recovery-sessionlist.xml` | 恢复后会话列表（无横幅） |
| `recovery-chat2.xml` | 恢复后会话页（无横幅，转录在） |
| `recovery-home-connected.xml` | 恢复后 Home（Host-4199=已连接） |
| `run-disconnect.sh` / `run-recovery-poll.sh` | 本次使用的只读取证脚本 |
