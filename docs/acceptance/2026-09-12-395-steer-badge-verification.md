# 复验：#395 steer（插话）徽标（模拟器 emulator-5554）

- 卡片：#395 —— 忙碌时「立即发送（steer/插话）」上屏的用户消息缺标识徽标，本批恢复
- 日期：2026-09-11（设备/宿主时钟；文件名按任务给定 2026-09-12）
- 设备：emulator-5554（sdk_gphone64_x86_64，Android 16/SDK36，1080x2400@420dpi），本会话独占
- 构建：dev flavor（`app/build/outputs/apk/dev/debug/app-dev-debug.apk`，构建时间 09-11 23:00）
  - commit：HEAD `605d2194`（feat(chat): #395 恢复 steer（插话）上屏徽标 + 修 V2 input.admitted delivery 漏读）
  - 安装：`adb -s emulator-5554 install -r …` → Success（**未卸载**；dev versionCode=1789137192 覆盖安装）
  - 已装包 md5 = `a842943ea4c554cfbc4517d11c09f910`（pm path 后 md5sum 实测，与任务给定值一致）
- 服务：OpenCode **V2** @4199（`--es debug_server_type opencode`，user=opencode，debug_name E2E-OC，HTTP Basic）
  - `adb reverse` → tcp:4199、tcp:3080
  - App locale：`cmd locale set-app-locales dev.leonardo.ocbeacon.dev --user 0 --locales zh-CN`
- 观测通道：uiautomator dump（text + bounds）+ 宿主 curl（V2 REST 原始载荷）+ logcat
- 只读 + 设备操作；未改任何产品代码；未运行 Gradle
- 证据目录：`docs/acceptance/2026-09-12-395-steer-badge/`（原始 dump / logcat / server json，见文末）

---

## 前置校验

| 项 | 判定 | 证据 |
|---|---|---|
| install -r 成功（不卸载） | PASS | `Performing Streamed Install / Success` |
| 已装包 md5 == a842943ea4c554cfbc4517d11c09f910 | PASS | `a842943ea4c554cfbc4517d11c09f910  /data/app/…/base.apk` |
| adb reverse 4199/3080 | PASS | `host-21 tcp:4199 tcp:4199` / `host-21 tcp:3080 tcp:3080` |
| App locale zh-CN | PASS | 命令成功；UI 文案中文 |
| debug intent 起会话列表 | PASS | 初始 dump：会话列表 + 「E2E-OC」 |

---

## 结果总表

| 步骤 | 判定 | 证据 | 一句话 |
|---|---|---|---|
| A 会话进入忙碌 | PASS | `02-busy-stop-key.xml` | 发送长输出提示后出现停止键 `chat-stop`（cx=980,1443） |
| B 长按发送触发 steer | PASS | `04-steer-text-typed.xml`；logcat `Sent prompt … queueRow=false`(23:11:47.056) | 忙碌+非空文本长按 `chat-send`，SSE 回显用户消息，无报错 |
| C 徽标渲染于 steer 气泡且正文匹配（≤30s 窗口内的字面断言） | PASS（瞬时） | `05`/`06`/`10` | t+2s～t+12s dump 命中 `text="插话"`，同气泡正文=`STEERPROBE396`/`395` |
| **C' 徽标在 30s 窗口内持续** | **FAIL** | `07`/`11`、`09`、`08` | t+16s 徽标消失，30s 末已无（根因见 F） |
| D 负向对照（空闲普通发送无徽标） | PASS | `01-negative-control-no-badge.xml` | 气泡正文 `IDLECTRL395…`，全 dump `插话` 计数=0 |
| E 崩溃回归 | PASS | `adb logcat -d -b crash` | crash buffer 0 行，`grep -i ocbeacon` 无输出 |

**总体：FAIL（核心渲染路径通过；但徽标约 16s 后即丢失，「恢复」的持续语义不达标——C' 失败）**

---

## A 会话进入忙碌 — PASS

打开 OpenCode 会话 `ses_f7b59d9f3ffeqyWmJpSjIxsXRm`，输入长输出提示
`Please list the numbers from 1 to 300 one on its own line and add the English spelling for each number Do not summarize`，
点发送。随后 dump 出现 `resource-id="chat-stop"`（忙碌停止键）→ 会话忙碌。

- 证据：`02-busy-stop-key.xml` 节点 `''|desc=''|rid=chat-stop|click=True|long=True|cx=980,1443`
- logcat：`23:11:47.056 D/ChatSendDelegate: Sent prompt to session … (1 parts, queueRow=false)`
- 备注：该 server 的 assistant 轮次极短（转录摘要 `轮次 N · 4~8ms · 1 步 · 0 个工具`，busy 窗口约 1s），但仍在窗口内完成了 B 的长按。

## B 长按发送触发 steer — PASS

忙碌期间在输入框输入 `STEERPROBE396`（`04`：`chat-input` 文本=STEERPROBE396，同时出现 `chat-send`），
执行 `adb shell input swipe 980 1443 980 1443 1000` 长按（长按中心 = `chat-send` 中心）。

- logcat 紧接：`23:11:47.056 Sent prompt … queueRow=false`
  （忙时**普通**发送会 `queueRow=true` 且不上转录、只进 inbox；`queueRow=false` 即 steer 分支特征）
- SSE 回显用户消息 id `msg_0910659f4001dHdPzE6NF5DQNw`
- steer 窗口内无 `E/`、无 `Failed to send`、无 `Exception`、无错误弹窗
- 判定：steer 被服务器受理，触发路径（忙碌长按发送键）成立

## C 徽标渲染与正文匹配 — PASS（瞬时）

两次独立运行分别用 `STEERPROBE395`（第一次）与 `STEERPROBE396`（第二次）复现：

- 第一次：长按后 t+11s dump `10-run1-t11s-badge-present.xml` 命中 `text="插话"`，气泡正文 `STEERPROBE395`。
- 第二次：t+2s（`05`）、t+12s（`06`）均命中；节点（同气泡：header y≈1003 / 正文 y≈1069 / 统计栏徽标 y≈1161）：

```
'STEERPROBE396' … cx=216,1069 b=[74,1048][359,1091]
'插话'          … cx=114,1161 b=[85,1141][144,1181]
```

徽标位于正文下方的统计栏（statsBar，`MessageCardUser.kt:159-163`）→ 属该 steer 气泡的 `SteerBadge`；文案取自 `chat_steer`（zh-rCN=插话），与 app locale zh-CN 一致。

## C' / F 徽标持久性 — FAIL（含根因）

### 时间序列（第二次运行，长按 23:11:48.2，每 ~1.6s dump）

| 采样 | 时刻 | `插话` | `STEERPROBE396` | `chat-stop` |
|---|---|---|---|---|
| t0 | 23:11:50 | 1 | 1 | 0 |
| t1 | 23:11:53 | 1 | 1 | 0 |
| t2 | 23:11:57 | 1 | 1 | 0 |
| t3 | 23:12:00 | 1 | 1 | 0 |
| **t4** | **23:12:04** | **0** | 1 | 0 |
| t5…t15 | 23:12:08…23:12:44 | 0 | 1 | 0 |

第一次运行同构：t+11s 在、t+18s 无（`10`→`11`）。

关键：丢失的是**徽标**，气泡正文 `STEERPROBE396` 始终在（消息未消失，仅 `viaSteer` 标记丢失）。

### 机制链（logcat `09` + server REST 载荷 `08`）

1. 长按 steer 后消息经 SSE 上屏，带 `viaSteer=true` → 徽标可见。
2. `23:12:02.897 W/SessionStateService: … L2 stale for 15841ms` → 触发 L3 兜底 REST 刷新：
   `listMessages RESPONSE … msgs=50/50` → `L3 fallback refresh: 50 msgs` → `MessageStore upsert n=50` → 重新渲染。
3. 该 REST 重建出的 `Message.User` 不再带 `viaSteer` → 徽标随即消失（t4 实测 0）。

server 持久化载荷（`curl -u opencode:*** /api/session/ses_f7b59d9f…/message?limit=6`）：

```json
{"id":"msg_0910659f4001dHdPzE6NF5DQNw","time":{"created":1789139507755},"text":"STEERPROBE396","agents":[{"name":"build"}],"type":"user"}
{"id":"msg_09103fc5c001cOn69f6OuJp4T7","time":{"created":1789139352721},"text":"STEERPROBE395","agents":[{"name":"build"}],"type":"user"}
```

→ **REST 持久化消息不含 `delivery` 字段**（仅 `id/time/text/agents/type`）。steer 档位只存在于 SSE 事件
（`input.admitted` / `inbox.enqueued`，由 `V2SseMapper` 读取）与客户端本地播种。

而 `Message.User.viaSteer` 是 `@Transient`（不入序列化/缓存；`Message.kt:63` 注释假定「V2 delivery=steer 保留标记」）。
该假设对本 V2 server 的 REST 载荷不成立：**任何按 REST/DB 重建转录的路径（SSE 静默 15.84s 兜底刷新、重进会话、分页回补）都会丢掉徽标**，与 steer 内容无关。

### 影响与建议

- 徽标仅在 steer 后约 **16s**（= SSE 静默 15.84s 触发 L3 刷新的延迟）内可见，30s 窗口末必然不可见；
  用户重进会话或滚动回补后同样不可见 → 「恢复徽标」的持续语义未达成。
- 建议（实现方裁决）：
  a. 让 server REST 消息持久化 `delivery`（超出客户端范围）；或
  b. 客户端对 steer 发送路径做本地持久化（按 messageId 记录并回填 `viaSteer`，重进会话与 REST 重建时保留），不要只依赖 `@Transient` 内存态；或
  c. 从 SSE 之外的稳定来源（如 inbox steering 记录）派生标记。
- 修复后按 C / C' 复验。

## D 负向对照（空闲普通发送无徽标）— PASS

空闲时普通发送 `IDLECTRL395 please reply OK`，dump `01-negative-control-no-badge.xml`：
用户气泡正文 `IDLECTRL395 please reply OK` 上屏，**全 dump `插话` 文本节点计数=0**。

该对照在任何 steer 消息产生之前执行，故「全局无 `插话`」为强证据；随后出现 steer 消息才使全局计数非零（`10`），
反向印证徽标具备判别性、并非无差别渲染。

## E 崩溃/异常回归 — PASS

- `adb -s emulator-5554 logcat -d -b crash | grep -i ocbeacon` → **无输出**（crash buffer 计 0 行）
- steer 窗口（23:11:46–23:12:10）内 logcat 无 `E/`、无 `Failed to send`、无 `Exception`

## 附：DSH（3080）路径未触发

任务规定「若 OpenCode 侧 steer 不被受理（无插话徽标且出现报错）」才转 DSH 试一次。
本批 OpenCode V2 侧 steer **被受理**（消息上屏 + 徽标出现 + 无报错），故未执行 DSH 对照。
OpenCode 侧的问题不是「不受理」，而是「徽标不持久」，属不同性质缺陷。
若需确认 DSH 侧行为，可另行安排（`DshEventMapperTest` 提示 DSH 无 wire 标记时随本地播种消息消失，预计同样不持久）。

---

## 证据路径

证据目录：`docs/acceptance/2026-09-12-395-steer-badge/`

| 文件 | 内容 |
|---|---|
| `01-negative-control-no-badge.xml` | D 负向对照终态 dump（无插话，正文=IDLECTRL395…） |
| `02-busy-stop-key.xml` | A 忙碌态（`rid=chat-stop`） |
| `03-longprompt-typed.xml` | A 长输出提示已输入 |
| `04-steer-text-typed.xml` | B 忙碌中输入 STEERPROBE396 + `chat-send` 就位 |
| `05-steer-t0-badge-present.xml` | C 长按后 t+2s，插话+正文同气泡 |
| `06-steer-t3-badge-present.xml` | C 长按后 t+12s，插话仍在 |
| `07-steer-t4-badge-lost.xml` | C' 长按后 t+16s，正文在、插话已无 |
| `08-server-messages.json` | server REST 原始载荷（steer 消息无 delivery 字段） |
| `09-logcat-2nd-steer.txt` | L2 stale → L3 REST 刷新 → upsert → 重渲染（徽标丢失链） |
| `10-run1-t11s-badge-present.xml` | 第一次运行 t+11s（插话在） |
| `11-run1-t18s-badge-lost.xml` | 第一次运行 t+18s（插话无） |

会话 sid=`ses_f7b59d9f3ffeqyWmJpSjIxsXRm`；steer 消息 id：`msg_09103fc5c001cOn69f6OuJp4T7`（395）、`msg_0910659f4001dHdPzE6NF5DQNw`（396）。

原始中间 dump/logcat 另存于宿主 `/tmp/ocbeacon-395/`（非持久）。
