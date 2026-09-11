# #403 标签遮蔽修复复验报告（system/message 显式 source.kind → kind 派生标签）

- 日期：2026-09-12（本地）
- 设备：`emulator-5554`（sdk_gphone64_x86_64，Android 16，独占）
- 应用：`dev.leonardo.ocbeacon.dev` versionName=0.3.0，versionCode=1789143472
- 安装包：`app/build/outputs/apk/dev/debug/app-dev-debug.apk`，md5=`78e800a0fa1e3b4d8f571fccff7367e7`
  - 已装包 md5（`/data/app/.../base.apk`）= `78e800a0fa1e3b4d8f571fccff7367e7`（一致）
  - 安装方式：`adb -s emulator-5554 install -r`（**未卸载**）
- DSH 服务器：`http://127.0.0.1:3080`（`adb reverse tcp:3080 tcp:3080` 已确认；token 读 `~/.dsh/token`）
- debug intent：`--es debug_url http://127.0.0.1:3080 --es debug_name E2E-DSH --es debug_server_type dsh --es debug_token <redacted>`
- 验证会话：标题「请检索一下是否有GIS、GEO的MCP」→ `session-a84edbf7-5247-4751-a52a-d70b1c028438`（travel 工作区）
- 修复 commit：`e6a4a3e3` fix(chat): #403 system/message 显式 source.kind 时用 kind 派生标签
- 复验性质：**只读 + 设备操作**；未改产品代码、未跑 Gradle。

## 一、结论

**判定：PASS**（#403 标签遮蔽已修复）

| 判据 | 结果 | 证据 |
|------|------|------|
| 顶部首张系统注入卡标签 = 「插件配置」（injectionKind=plugin） | **PASS** | `top_located_r1.xml`：TextView `插件配置` bounds=[98,354][207,391]，同卡时间 `2026-09-07 08:19:44`；卡点击区 bounds=[32,310][1048,436] |
| 该卡不再是「工具目录已变更」 | **PASS** | 同一 dump 全树不含字符串「工具目录已变更」；展开后 `exp403.xml` 同样不含 |
| 渲染确经 `role=="system"` 分支（非 user 注入分支） | **PASS** | `SysMsgDiag: #234 event-card branch RENDER id=0f4ff178 textLen=35651`（id 后 8 位 0f4ff178 = 该卡），`render-branch-logs-403.txt` |
| 回归：展开后正文含 `You are an AI agent powered by DeepSeek Harness` | **PASS** | `exp403.xml`：TextView bounds=[74,296][1006,391]，len=35651，首段精确命中 |
| 回归：crash buffer 无 ocbeacon | **PASS** | `logcat -d -b crash \| grep -i ocbeacon` = 0 行（`crash-check-403.txt`） |
| 步骤 3：injectionKind=="system"（无 source.kind）系统卡仍为「工具目录已变更」 | **N/A** | 本会话无此类卡：DB 该会话 7 条 role=system 全部 injectionKind=plugin（见下）；全库 role=system 的 injectionKind 仅 NULL(58)/plugin(38)，无 "system" 值 |

## 二、首张系统注入卡的定位与节点级证据

该会话共 467 条 cached_messages，**最早一条**即系统注入卡：

```
sqlite3：SELECT COUNT(*) FROM cached_messages WHERE sessionId=... AND created < (rank0.created) → 0
  #1 2026-09-07 08:19:44 | system | plugin | dsh-sys-v2-to-v3-system-...0f4ff178  ← rank 0（首条）
  #2 2026-09-08 10:13:14 | system | plugin | ...d937c5e1
  #3 2026-09-08 13:13:22 | system | plugin | ...e09767c0
  #4 2026-09-08 13:15:05 | system | plugin | ...de9ced07
  #5 2026-09-09 07:59:59 | system | plugin | ...8a33d822
  #6 2026-09-09 20:05:44 | system | plugin | ...f23e1c58
  #7 2026-09-10 18:56:30 | system | plugin | dsh-sys-2ee10337-...
```

滚动到转录顶部后，首张卡（rank 0）折叠态 dump（`top_located_r1.xml`）：

```
android.widget.TextView bounds=[98,354][207,391]  len=4  "插件配置"
android.widget.TextView bounds=[403,358][676,388] len=19 "2026-09-07 08:19:44"
clickable bounds=[32,310][1048,436]   ← 与修复前证据同一张卡
```

展开该卡（tap 540 373）后（`exp403.xml`）：

```
android.widget.TextView bounds=[74,296][1006,391] len=35651
  "You are an AI agent powered by DeepSeek Harness.\n\nThe DeepSeek Harness implement..."
ASSERT_FULL("You are an AI agent powered by DeepSeek Harness") = True
```

同轮滚动中还直接看到 rank 0 之外的插件配置卡：`probe_hit2_r3.xml` 含「插件配置」+ 时间 `2026-09-09 20:05:44`（= 系统卡 #6）。

## 三、修复前后对照（同一张卡）

| 项 | 修复前（#400 C2 证据） | 修复后（本报告） |
|----|------------------------|------------------|
| 证据文件 | `docs/acceptance/2026-09-12-400-c2-literal-verification.md` §三/§四 | `docs/acceptance/2026-09-12-403/top_located_r1.xml` |
| 卡 | rank 0，时间 2026-09-07 08:19:44，bounds [32,310][1048,436] | 同一张卡 |
| 标签 | **「工具目录已变更」**（`chat_event_tool_catalog_changed`） | **「插件配置」**（`chat_injection_plugin`） |
| 渲染分支 | `role=="system"` 分支固定「工具目录已变更」 | `role=="system"` 分支 → `injectionKindLabel("plugin")` |
| 正文 | 展开含 DeepSeek Harness 字面 | 展开含同一字面（回归保持） |

修复前 §四原文：「`SysMsgDiag: #234 event-card branch RENDER id=...f23e1c58 ...` → 全部走 role=="system" 分支，渲染为 EventCard，标签 chat_event_tool_catalog_changed＝「工具目录已变更」」。

## 四、渲染分支取证（证明走 system 分支而非 user 注入分支）

logcat（tag `SysMsgDiag`，DEBUG 构建，`render-branch-logs-403.txt`）：

```
W SysMsgDiag: #234 event-card branch RENDER id=0f4ff178 textLen=35651
W SysMsgDiag: #234 event-card branch RENDER id=d937c5e1 textLen=35661
```

`0f4ff178` = 首张系统卡（rank 0），`d937c5e1` = 系统卡 #2。二者均在 `role=="system"` 分支被渲染；本卡时间/点击区与折叠态标签「插件配置」同节点链，排除 user/message 路径。

代码依据（HEAD `e6a4a3e3`，`ChatMessageList.kt` L1683–1694）：system 分支取 `(message as? Message.User)?.injectionKind`，非 null 且 ≠ "system" 时用 `injectionKindLabel(kind)`；`injectionKindLabel`（L2305）为单一映射源，plugin→`chat_injection_plugin`=「插件配置」。

## 五、步骤 3 判定说明（N/A）

`injectionKind=="system"`（无 source.kind）的历史语义为「工具目录已变更」，修复逻辑对此保持回退（`sysInjectionKind == null || == "system"` → `chat_event_tool_catalog_changed`）。但：

- 本会话 7 条 role=system 全部 `injectionKind=plugin`，**不存在** injectionKind=="system" 的系统卡，故按派单记 **N/A**。
- 全库 census：role=system 的 `injectionKind` 仅 NULL(58) / plugin(38)，无字符串 "system"（NULL 与 "system" 均走回退，「工具目录已变更」语义未丢）。

## 六、证据清单（均位于 `docs/acceptance/2026-09-12-403/`）

| 文件 | 说明 |
|------|------|
| **`top_located_r1.xml`** | 转录顶部折叠态；首张系统卡标签「插件配置」+ 时间 2026-09-07 08:19:44 |
| **`exp403.xml`** | 展开首张系统卡后；正文含 DeepSeek Harness 字面（len=35651） |
| **`label-nodes-403.txt`** | 节点级抽取（折叠/展开标签节点 + DB 事实 + SysMsgDiag） |
| `render-branch-logs-403.txt` | SysMsgDiag 渲染分支日志（0f4ff178 / d937c5e1） |
| `crash-check-403.txt` | crash buffer 回归结果（0 行） |
| `probe_hit2_r3.xml` | 系统卡 #6（2026-09-09 20:05:44）标签「插件配置」 |
| `probe_hit_r5.xml` | 中段 user/message 注入卡（上下文注入/工作区指令）对照帧 |
| `dump0.xml` / `enter1.xml` | 会话列表与进入会话落点 |
| `qj1..qj5.xml` / `top_located_r1.xml` | 快速定位面板与滚动过程帧 |
| `expanded-top-card.png` | 展开后截图 |
| `jump-after.png` / `fresh1.xml` 等 | 跳转/滚动过程帧（辅助） |

## 七、局限与说明

- 步骤 3 因本会话无 injectionKind=="system" 卡，未能做设备端字面复验，仅以 DB census + 代码回退分支说明，记 N/A（符合派单「找不到就记 N/A」）。
- 首张卡展开后其折叠行标题被列表自动滚动顶出视口，故「标签」证据取自折叠态 `top_located_r1.xml`、「正文」证据取自展开态 `exp403.xml`；两者同一卡（同 bounds/时间链）。
- 本判定只覆盖卡片【标签】与字面可见性回归，不主张其它注入类型（agent-instructions/skill-catalog）在本会话的完整矩阵。
