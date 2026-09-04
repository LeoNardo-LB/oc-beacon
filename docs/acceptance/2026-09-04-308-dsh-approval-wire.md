# #308 DSH 权限应答 wire 三层修复 验收 checklist

- 卡片：backlog #308（V012 审批应答 value=裸字符串 + always 规则会话锚定免目录 + 自动应答本地收卡）| 日期：2026-09-04
- 构建：commit `5faa5769` 后的 devDebug（2026-09-04 上午已装） | 包名：`dev.leonardo.ocbeacon.dev`
- 设备：小米 houji（WiFi adb `192.168.110.239:5555` 或 USB serial `e69a99d8`，以 `adb devices` 实际为准）
- 服务器：生产 DSH :3080（V012），App 已配置连接；被测会话内 agent 的 bash 在宿主机执行
- **分类：非 UIUX**（wire/数据层修复；卡消失等 UI 行为变化即 bug 修复本身，客观可判）→ 无人工清单，AI 全绿即关卡
- 回归域：审批/权限域（本卡触面）· 会话交互域（L3 出入会话/发送/中断）· SSE 流式渲染 · 连接稳定性（L1）
- 日志/截图证据目录（宿主机）：`/tmp/acc308_logs/`（logcat）、`/tmp/acc308_shots/`（截图）——先 mkdir -p

## 执行纪律（执行员必读）

1. 一次一项，按本文件顺序；每项开始前 `adb logcat -c`，结束后 `adb logcat -d -v time` 存 `/tmp/acc308_logs/<item>.log`。
2. UI 定位一律 uiautomator dump 后按节点文本找坐标再 tap（坐标会漂移，禁止盲打历史坐标）；参考 `/tmp/dump_ui.py`（宿主机已有）。
3. `adb shell input text`：空格用 `%s`，禁用 `> & | <` 等特殊字符；中文消息不可用（编码不稳），一律英文消息。
4. MIUI 坑：顶部 ~105px 状态栏会拦截点击；dump 偶发失明——重 dump 再判。
5. 审批卡识别：dump 中含「拒绝/仅一次/始终允许」（或系统语言等价文本）三个按钮节点的卡片；提问卡识别：含问题文本+选项按钮。等待轮询：每 2s dump 一次，上限 90s，超时即该项 ✘ 并保存末次 dump。
6. 冷启纪律：任何 intent 入口前必须 `adb shell am force-stop dev.leonardo.ocbeacon.dev`（warm start 不解析 intent）。测试入口：宿主机 `./scripts/debug-entry.sh`。
7. 每项「实测记录」写 ✔/✘/BLOCKED + 时间戳 + 全部可观测事实（日志行、dump 节点文本、探针存在性、截图路径）。**禁止分析/归因/修复**。
8. 重跑轮纪律：重跑受影响 item 前先 `rm -f /tmp/acc308_probe/<该项探针>`（防残留假阳性）；「实测记录」旧内容保留、追加新一轮记录并标注轮次。

## P. 前置（信息项，记录即 ✔，异常才 ✘）

### P0 设备与构建盘点
- 前置：宿主机 adb 可用
- 操作：`adb devices`；`adb shell dumpsys package dev.leonardo.ocbeacon.dev | grep -E "versionName|lastUpdateTime"`；`mkdir -p /tmp/acc308_logs /tmp/acc308_shots /tmp/acc308_probe && rm -f /tmp/acc308_probe/*`
- 期望：设备在线；versionName=0.3.0、lastUpdateTime=2026-09-04（主 agent 已预核：WiFi transport `192.168.110.239:5555` 在线，versionName=0.3.0，lastUpdateTime=2026-09-04 23:21:56——不符则记 ✘ 停卡）。**探针目录必须清空**（`rm -f /tmp/acc308_probe/*`——防失败循环重跑时残留探针造成「存在」假阳性；重跑轮同样必须先清）
- 判定：上述输出完整记录
- 实测记录：✔（2026-09-05 05:14:55）
  - `adb devices -l`：`192.168.110.239:5555 device product:houji model:23127PN0CC transport_id:2` + `adb-e69a99d8-yzT17Y._adb-tls-connect._tcp device product:houji model:23127PN0CC transport_id:1`（双 transport，全程显式 `-s 192.168.110.239:5555`）
  - dumpsys：`versionName=0.3.0`、`lastUpdateTime=2026-09-04 23:21:56`（符合预期，不停卡）
  - `mkdir -p /tmp/acc308_logs /tmp/acc308_shots /tmp/acc308_probe` 完成；`rm -f /tmp/acc308_probe/*` 后 `ls -la /tmp/acc308_probe/` 仅 `.`/`..`（空）；/tmp/dump_ui.py 存在（535B，SER=192.168.110.239:5555）
  - logcat 存档 /tmp/acc308_logs/P0.log（26 行，清后残留）；grep PermissionAutoApprover|FATAL|AndroidRuntime 无命中

### P1 冷启进会话列表
- 操作：force-stop → `./scripts/debug-entry.sh` → 等 3s → dump
- 期望：会话列表可见（含既有会话「用 bash 执行 echo g2-once」或等价）
- 判定：dump 含会话列表节点
- 实测记录：✔（2026-09-05 05:15:44）
  - force-stop 后 `./scripts/debug-entry.sh 192.168.110.239:5555` 退出 0，输出含 `OK: Debug channel → SessionList (server connected, app at session list)`；logcat 关键行：`MainActivity: Debug channel requested via extra: ext-71e988cc (http://127.0.0.1:4199)`、`MainActivity: Debug channel activated: ext-71e988cc -> server 617b2c29-…`、`NavGraph: Debug channel → SessionList for server 617b2c29-…`
  - 等 3s 后 dump（/tmp/acc308_p1_dump.xml）：会话列表节点齐全——搜索框「搜索会话…」+ 13+ 既有会话行（如「dsh指定版本安装及npm卸载 9月4,10:59」「smoke2mtl0hbyl」「finalmtkyrwqk」「e2e303g-mtkynqml」「回复生成工具使用」「无标题会话」等，均带 /home/leo-tkp 目录与时间戳）+ 底部「会话/设置」导航 + 顶栏「Host-4199」
  - 事实记录：滚动 3 屏未见标题精确含「用 bash 执行 echo g2-once」的会话（grep g2-once=0）；以既有会话「dsh指定版本安装及npm卸载」（最近、含 bash 使用）作等价既有会话，后续 P2 使用该行
  - 截图：/tmp/acc308_shots/P1.png；logcat 存档 /tmp/acc308_logs/P1.log；grep PermissionAutoApprover 无命中；FATAL=0；AndroidRuntime 命中 36 行全部为 uiautomator shell 命令进程噪音（`com.android.commands.uiautomator.Launcher`），无 `FATAL EXCEPTION`、无 `Process: dev.leonardo`

### P2 进测试会话确认连接健康
- 操作：tap 既有测试会话行 → 等 2s → dump；`adb logcat -d | grep -ciE "FATAL|AndroidRuntime"` 计数
- 期望：进入会话，无断连/错误横幅节点，输入区可用
- 判定：dump 无断连横幅 + FATAL 计数=0
- 实测记录：✔（2026-09-05 05:17:02）
  - dump 定位会话行「dsh指定版本安装及npm卸载」bounds=[168,578][728,634]，tap (448,606) → 等 2s dump（/tmp/acc308_p2_dump.xml）：已进入会话——顶栏「dsh指定版本安装及npm卸载 /home/leo-tkp ·25」，消息可见（「根因说明」「最终 Token（重启轮换…）」「T4iQfZbj3W2…」等），模型选择器「glm-5.3/Big Pickle」，输入区节点「提问…」@[96,2498][244,2562] 存在（可用）
  - 断连/错误横幅检查：dump 中 grep 断开|连接|disconn|error|错误 → 无横幅节点命中
  - FATAL 计数：`logcat -d | grep -ciE 'FATAL|AndroidRuntime'`=18，但归属核查：18 行全部来自 3 个 PID（20677/21062/21118），均为 uid 2000 shell 的 `com.android.commands.uiautomator.Launcher`（uiautomator dump 自身噪音）；FATAL=0、`FATAL EXCEPTION`=0、`Process: dev.leonardo`=0 → app 无崩溃
  - PermissionAutoApprover 命中=0（本项无审批活动，符合预期）
  - 截图：/tmp/acc308_shots/P2.png；logcat：/tmp/acc308_logs/P2.log

### P3 规则盘点（Settings 审批规则区）
- 操作：从会话返回列表 → 进 Settings → 定位自动批准/审批规则 section → dump+截图记录每条规则形态（工具/目录/会话锚定显示）
- 期望：预计存在 3 条旧形态规则（sessionId=null，目录锚定），可能有 1 条 probe4 新形态
- 判定：规则条数与形态完整记录（这是 A7 的基线）
- 实测记录：✔（2026-09-05 05:21:44）
  - 导航事实记录：会话列表底部「设置」tab 打开的是服务器设置页（Host-4199 标题，仅「MCP 服务器」「标签管理」两区块，不可滚动）；审批规则区在全局 Settings——路径=会话列表 BACK → Home（OC Beacon 标题，服务器卡片 Host-4199 已连接）→ 顶栏齿轮 content-desc「设置」@[936,194][1008,266] tap → 全局 Settings（「通用/外观/聊天显示/…/通知」长列表）→ 滚动到底部
  - 规则区 dump（/tmp/acc308_p3_g4.xml）：SectionHeader「自动批准规则」@[96,1476]；开关行「自动允许所有权限请求」+描述「对每个权限请求自动应答「始终允许」。规则会持久保存——仅对可信服务器开启。」；规则列表标题「自动批准规则」@[132,1910]
  - 规则盘点：共 3 条，形态完全一致=「tool」+「/home/leo-tkp/workspace」（目录锚定，无会话锚定字段显示）：#1 tool @[132,2036] /home/leo-tkp/workspace @[132,2092]；#2 tool @[132,2231] /home/leo-tkp/workspace @[132,2287]；#3 tool @[132,2426] /home/leo-tkp/workspace @[132,2482][590,2530]
  - 底部核查：继续下滑两次 dump 内容与 md5 级别一致（列表未移动）→ 已到列表底，第 3 条即最后一条；probe4 新形态规则不存在（0 条）
  - 截图：/tmp/acc308_shots/P3_settings_top.png（服务器设置页）、P3_rules_1.png、P3_rules_2.png（规则区）；logcat：/tmp/acc308_logs/P3.log（FATAL EXCEPTION=0、PermissionAutoApprover=0）

## A. 修复效果

### A7 旧形态规则（sessionId=null）目录匹配自动命中（回归面先行，用 P3 存量规则）
- 前置：P3 记录的旧形态规则仍在（未清空）
- 操作：回到 P2 会话 → 发送 `run%sbash%stouch%s/tmp/acc308_probe/a7` → 观测至多 90s
- 期望：若旧规则目录与本会话匹配：logcat 出现 `PermissionAutoApprover: [auto-approve] rule matched`，卡不滞留（不出现或短暂出现后自动消失），`/tmp/acc308_probe/a7` 存在
- 判定：rule matched 日志行 + 探针存在 + 90s 窗口末无滞留卡 → ✔；若弹卡且无 rule matched 日志（目录不匹配）→ 如实记录「旧规则未命中本会话目录」，改 tap「仅一次」收尾（探针应存在），本项记 **BLOCKED（配置性不匹配：规则 pattern×会话目录，非缺陷；单测覆盖旧形态目录匹配语义 + 09-04 晨 probe4 历史证据作结）**
- 实测记录：**BLOCKED**（2026-09-05 05:23:55）
  - 前置确认：P3 的 3 条旧形态规则未动（tool · /home/leo-tkp/workspace ×3）；「自动允许所有权限请求」开关 checked=**false**（P3 dump XML checkable 节点 [900,1584][1056,1728]）
  - 执行：05:23:05 tap 发送（content-desc「发送」@[1056,1586][1116,1646]），消息「run bash touch /tmp/acc308_probe/a7」入会话（dump 可见）
  - 观测（2s 轮询 7 轮，05:23:21→05:23:49）：**全程 0 次审批卡节点**（拒绝/仅一次/始终允许 三键 grep=0）；logcat `rule matched`=0；05:23:49 `/tmp/acc308_probe/a7` 存在（命令真实执行）
  - 事件层事实：A7 窗口 app(pid 5646) EventDispatcher 分发种类统计=SessionNext×485/MessagePartUpdated×8/MessageUpdated×6/ShellJobEnded×4/ShellJobStarted×1/SessionStatus×1/SessionIdle×1/SessionCompacted×1/MessagePartDelta×1——**无任何 PermissionAsked 分发**；PermissionAutoApprover 日志 0 行
  - UI 终态：05:23:47「后台命令完成」工具卡 + agent 回复「这是在配合某个验证流程吧？需要的话继续下一条。」；无滞留卡
  - 「tap 仅一次」收尾步骤未执行——无卡可 tap（非拒tap）
  - 环境事实（后续项前置）：当前 app 连接=Host-4199（http://127.0.0.1:4199，宿主 opencode2.exe，API v2）；checklist 声明的被测服务器=生产 DSH :3080（V012，宿主 node pid 3030340，Home 页 prod-3080 卡片未连接）。该会话（ses_f96fec3c）在 4199 连接下不发 PermissionAsked → A7 判 BLOCKED（旧规则未命中/未触发：无 PermissionAsked 事件，规则匹配路径未被走到）
  - 证据：/tmp/acc308_logs/A7.log（FATAL EXCEPTION=0）、/tmp/acc308_shots/A7.png、dump /tmp/acc308_a7_poll.xml（末次）/tmp/acc308_a7_final.xml
  - 补充事实（2026-09-05 05:31，A1 前环境核查）：P1 期望的既有会话「用 bash 执行 echo g2-once」存在于 **DSH prod-3080**（http://127.0.0.1:3080，V012）连接下（列表实测：该会话 cwd=/home/leo-tkp/workspace，9月4 23:23）——与 P3 三条旧规则目录一致；debug-entry.sh 默认连接 Host-4199（opencode2.exe，API v2）不含该会话。A7 系在 4199 的会话执行 → BLOCKED 成因=测试会话选在非 DSH 连接下。P4 已清空规则，旧形态规则不可经 UI 重建，A7 维持 BLOCKED（详见收尾汇总；已同步 parent）；历史证据：09-04 22:22 probe4 旧规则目录命中自动应答（同机+prod-3080+同 3 条旧规则，approver 自动应答成功）——A7 BLOCKED 终态由 parent 裁定维持（2026-09-05 05:33）

### P4 清空规则（构造确定性手动三键环境）
- 操作：Settings 规则区逐条删除全部规则（删除前逐条截图留档）→ 返回会话
- 期望：规则区为空
- 判定：dump 显示空规则态
- 实测记录：✔（2026-09-05 05:28:29）
  - 删除前留档：/tmp/acc308_shots/P4_rules_before.png（3 条规则同屏：tool · /home/leo-tkp/workspace ×3）+ dump /tmp/acc308_p4_rules.xml
  - 逐条删除（每条 tap content-desc「删除规则」中心点）：#1 tap(996,2088) → tool_count 3→2；#2 tap(996,2283) → 2→1；#3 tap(996,2478) → 1→0（过程中一次坐标解析错误 tap 至屏外无效坐标，无 UI 影响，用 /tmp/acc308_find.py 重解析后成功）
  - 空态 dump（/tmp/acc308_p4_empty.xml）：规则区显示「自动批准规则」标题 + 「没有已保存的自动批准规则」@[132,2506][646,2562]；text="tool" 计数=0、删除规则按钮计数=0
  - 截图：/tmp/acc308_shots/P4_rules_empty.png；logcat：/tmp/acc308_logs/P4.log（FATAL EXCEPTION=0）
  - 注：P4 完成后仍在全局 Settings 页，未立即「返回会话」——A1 前先导航回测试会话（A1 实测记录含该导航事实）

### A1 手动「仅一次」放行且真实执行（V012 裸字符串 wire 主证）
- 操作：会话内发送 `run%sbash%stouch%s/tmp/acc308_probe/a1` → 等卡出现（记录出现时延）→ dump 留证（**B5 判定复用本次 dump，独立记录**）→ tap「仅一次」→ 观测 60s
- 期望：卡消失；agent 转述命令完成；`/tmp/acc308_probe/a1` 在宿主机存在（受端证据——旧对象形态代码此路径为假成功）
- 判定：探针存在 + 卡消失 + agent 回复提及完成 → ✔
- 实测记录：✔（2026-09-05 05:31:38）【会话=DSH prod-3080「用 bash 执行 echo g2-once」（cwd /home/leo-tkp/workspace）；P4 后由全局 Settings 导航：BACK→Home→prod-3080 卡片「连接」tap(636,1168) 2s 内已连接→「会话」tap(380,1168)→会话行 tap(436,1191)】
  - 发送：05:30:34.545（tap content-desc「发送」）；消息「run bash touch /tmp/acc308_probe/a1」入会话
  - 卡出现时延：**~32.5s**（05:31:07 dump 首见三键，i=6/2s 轮询；logcat `05:31:02.630 EventDispatcher: [dispatch] PermissionAsked -> PermissionEventHandler sid=session-d252…` + `PermissionEventHandler: Permission event received: PermissionAsked(id=e6fbe174-b8b4-473a-8c35-44111303c336…)`）
  - 卡 dump（/tmp/acc308_a1_card.xml，B5 复用）+ 截图 /tmp/acc308_shots/A1_card.png：三按钮 拒绝@(600,1884) 仅一次@(600,2040) 始终允许@(600,2196)
  - tap「仅一次」：05:31:24.327 → 05:31:28（+4s）dump 三键节点=0（卡消失）且 **/tmp/acc308_probe/a1 存在**（宿主机 -rw-rw-r-- 05:31 创建）
  - agent 转述：正常执行被只读沙箱拦截 → 提权重试 `$ touch /tmp/acc308_probe/a1`「完成 · (no output)」→「提权执行成功。验证一下文件已创建：」`$ ls -la /tmp/acc308_probe/a1`「完成」（53.4s）
  - logcat：/tmp/acc308_logs/A1.log（FATAL EXCEPTION=0；PermissionAutoApprover 无 rule matched 行——P4 已清空规则，符合预期，手动路径）；截图 A1_after.png

### A2 手动「拒绝」拦截（fail-closed）
- 操作：发送 `run%sbash%stouch%s/tmp/acc308_probe/a2` → 等卡 → tap「拒绝」→ 观测 60s
- 期望：卡消失；命令未执行；agent 转述被拒/不可用
- 判定：`/tmp/acc308_probe/a2` **不存在** + 卡消失 → ✔
- 实测记录：✔（2026-09-05 05:33:09）【同一 DSH g2-once 会话】
  - 发送 05:32:11.468；logcat `05:32:23.069 [dispatch] PermissionAsked`（id=cc019e9c-4505-4c80-b7f2-08f55b67e798）；卡出现 ~14s（05:32:26 dump 三键齐）
  - tap「拒绝」@(600,1884) 于 05:32:32.996 → 05:32:38（+5s）三键节点=0（卡消失）
  - **/tmp/acc308_probe/a2 不存在**（ls：没有那个文件或目录；probe 目录仅 a1/a7）——命令未执行（fail-closed）
  - agent 转述：「提权请求被你拒绝了，命令终止：」「正常执行被拒： 只读文件系统 —— 只读沙箱不允许写 /tmp」「提权被用户拒绝：这次审批通道正常弹出，你选择了拒绝升级到 danger-full-access」（21.9s）
  - 证据：/tmp/acc308_shots/A2_card.png、A2_after.png；dump /tmp/acc308_a2_card.xml、acc308_a2_final.xml；logcat /tmp/acc308_logs/A2.log（FATAL EXCEPTION=0）

### B3 等待审批时中断稳定性（回归）
- 操作：发送 `run%sbash%stouch%s/tmp/acc308_probe/b3` → 等卡出现 → 点输入区中断/停止按钮（dump 定位；若无此按钮如实记录「无中断入口」）→ 观测 30s
- 期望：中断生效或卡随轮次结束清理；app 无崩溃
- 判定：logcat 无 FATAL/AndroidRuntime + UI 可继续操作（卡终态如实记录）→ ✔（卡滞留与否是观测事实，不判成败）
- 实测记录：✔（2026-09-05 05:35:21）【同一 DSH g2-once 会话】
  - 发送 05:33:43.320；`05:33:50.651 [dispatch] PermissionAsked`（id=82761a8b-ef48-441d-8b35-3fb743475d4e）；卡出现 ~10s（05:33:53 三键齐）
  - 中断入口定位：dump 中 content-desc「停止」@[1056,2508][1116,2568]（输入区右侧，等待审批期间存在——非「无中断入口」）
  - tap「停止」@(1086,2538) 于 05:34:07.031；logcat `05:34:06.730 [dispatch] SessionIdle -> SessionEventHandler sid=session-d252`（该 sid 一次）
  - 卡终态：05:34:12（tap 后 +5s 首查）三键节点=0——**卡消失**；probe_b3 全程=0（未应答、命令未执行）
  - UI 可继续操作：停止按钮消失恢复常规输入区——content-desc「发送」存在、输入占位「解释…的原理」@[96,2498] 恢复（/tmp/acc308_b3_post.xml）
  - logcat：/tmp/acc308_logs/B3.log（FATAL EXCEPTION=0、Process: dev.leonardo=0）；截图 B3_card.png、B3_after.png；dump /tmp/acc308_b3_card.xml、acc308_b3_post.xml

### A3 手动「始终允许」→ 规则落库且会话锚定
- 操作：发送 `run%sbash%stouch%s/tmp/acc308_probe/a3` → 等卡 → tap「始终允许」→ 卡应消失 → 进 Settings 规则区 dump
- 期望：命令执行（`/tmp/acc308_probe/a3` 存在）；规则区新增 1 条规则，且显示会话锚定（不再是纯目录形态）
- 判定：探针存在 + 新规则出现且**形态=会话锚定**（区别于 P3 盘点的旧形态；若仍为纯目录形态 → ✘，这正是 Layer 修复点）→ ✔
- 实测记录：✔（2026-09-05 05:39:10）【同一 DSH g2-once 会话 session-d25241a4-10ea-4137-b33c-57e9db239fe8】
  - 发送 05:35:56.559；`05:36:06.431 [dispatch] PermissionAsked`（id=7068733c-e021-482d-a248-56b6674bca21）；卡出现 ~13s（05:36:09 三键齐）
  - tap「始终允许」@(600,2196) 于 05:36:16.280 → 卡消失（+4s）→ **弹出二次确认对话框**「始终允许？」+「取消」@[612,1544][698,1604]/「始终确认」@[802,1544][972,1604]（该对话框在整个首观测窗 90s 内未被确认——探针一度未现的原因；对话框截图 A3_confirm_dialog.png）
  - tap「始终确认」@(887,1574) 于 05:38:21.693 → 05:38:26（+5s）对话框消失 + **/tmp/acc308_probe/a3 存在**
  - agent 转述：「Report success for a3.」「成功 ✅」「正常执行被拒： 只读文件系统」「提权执行成功： touch /tmp/acc308_probe/a3 通过审批后创建成功。」「目录当前内容：」
  - Settings 规则区（/tmp/acc308_a3_rules.xml + A3_rules.png）：新增 1 条（此前 P4 已清空），UI 行显示=「tool」+「/home/leo-tkp/workspace」
  - **数据级形态证据（run-as 直读 DataStore files/datastore/opencode_prefs.preferences_pb）**：`permission_auto_approve_rules = {"toolName":"tool","sessionId":"session-d25241a4-10ea-4137-b33c-57e9db239fe8","directoryPattern":"/home/leo-tkp/workspace","createdAt":1788557901258}` —— **sessionId 已锚定本会话**（旧形态为 sessionId=null 纯目录）→ 形态=会话锚定 ✔
  - UI 显示事实（只读代码核对）：RuleRow 仅渲染 toolName+directoryPattern，sessionId 无 UI 展示位——「会话锚定形态」在 Settings UI 中不可视区分，判定取数据级证据
  - logcat：/tmp/acc308_logs/A3.log（FATAL EXCEPTION=0；无 rule matched 行——手动 always 路径）；截图 A3_card.png、A3_after.png

### A4 会话锚定二次免卡（Layer1 修复主证：命中不依赖目录解析）
- 操作：回到会话 → 发送 `run%sbash%stouch%s/tmp/acc308_probe/a4` → 观测 90s
- 期望：**不再弹卡**（或瞬时自动消失）；logcat `[auto-approve] rule matched`；`/tmp/acc308_probe/a4` 存在
- 判定：rule matched 日志 + 探针存在 → ✔（卡是否滞留不判成败，如实观测——滞留判定唯一归 A5）
- 实测记录：✔（2026-09-05 05:42:54）【同一 DSH g2-once 会话；导航路径=Settings BACK×1 误入 MIUI 翻译界面→BACK→am start 温启恢复 Home→prod-3080「会话」(380,1895)→g2-once 行 (436,1191)】
  - 发送 05:42:04.645；**`05:42:14.078 [dispatch] PermissionAsked` → `05:42:14.080 I/PermissionAutoApprover: [auto-approve] rule matched: permission=tool sid=session-d252 dir=/home/leo-tkp/workspace — replying once`**（分发后 2ms 内自动应答）
  - **/tmp/acc308_probe/a4 存在**（命令真实执行）；agent 转述：「提权执行成功：审批通过后 touch /tmp/acc308_probe/a4 创建成功。」「$ ls -la /tmp/acc308_probe/ 完成 · 总计 0」
  - 卡观测：2s 轮询自 i=1（05:42:14）起 cardBtns=0——**全程无卡可见**（自动应答快于轮询粒度）
  - logcat：/tmp/acc308_logs/A4.log（FATAL EXCEPTION=0）；截图 /tmp/acc308_shots/A4.png；dump /tmp/acc308_a4_final.xml、acc308_a4_window_end.xml

### A5 自动应答本地收卡（Layer2 修复：卡生命周期无滞留）
- 操作：与 A4 同场景观测（复用 A4 的时间线 dump 记录，不重发命令）
- 期望：窗口结束时无滞留审批卡
- 判定：A4 观测窗末 dump 无审批卡节点 → ✔；若卡曾短暂出现，记录出现→消失时序
- 实测记录：✔（2026-09-05 05:42:54，复用 A4 时间线，未重发命令）
  - 观测窗末核查：A4 命中后连续 6 轮（每 2s）dump 三键节点（拒绝/仅一次/始终允许）计数均=0——**窗口结束时无滞留审批卡**（dump /tmp/acc308_a4_window_end.xml 末次）
  - 出现→消失时序：无可记录时序——2s 轮询粒度下卡从未可见（PermissionAsked 分发 05:42:14.078 → approver rule matched + replying once 05:42:14.080，应答与本地收卡快于轮询采样）
  - 对照事实：A1/A2/B3 同会话手动路径中卡可见且手动应答后消失——本项自动路径无滞留与手动路径收卡行为一致

### A8 运行期新会话 cwd 竞态窗口（新建会话立即建规则立即复用）
- 操作：返回列表 → 新建会话 → 立即发送 `run%sbash%stouch%s/tmp/acc308_probe/a8a` → 等卡 → tap「始终允许」（新规则锚定新会话）→ **立即**发送 `run%sbash%stouch%s/tmp/acc308_probe/a8b` → 观测 90s
- 期望：**首条命令必须弹卡**（证明 A3 会话锚定规则未跨会话泄漏到新会话）；第二条无卡自动放行（新会话 directory 即使未回填也命中——这正是 09-04 晨测 L1 竞态场景）；`/tmp/acc308_probe/a8b` 存在
- 判定：首条弹卡（否则 ✘——规则跨会话泄漏）+ 第二发 rule matched 日志（或无卡+探针存在）→ ✔
- 实测记录：✔（终态=A8-revised，2026-09-05 06:48:40；a8a/a8b 原流程因环境前提不成立转为观测附注，parent 批准修订）
  - **原流程 a8a（附注）**：会话列表「新建会话」(972,230)→目录选 workspace→草稿屏直发 `run bash touch /tmp/acc308_probe/a8a`（05:44:24.662）→ 2s×24 轮观测：**0 次弹卡**、rule matched=0、探针 a8a 于 05:44:33 存在——新会话权限档实测=「完全访问」（输入区药丸），服务器不发 PermissionAsked（弹卡前提不成立）；**无跨会话泄漏证据**：rule matched=0 + 规则库无新规则（A3 规则仍唯一）
  - **a8b（附注，parent 指令发出）**：06:42:49.679 发送 → 06:42:54 探针 a8b 存在、无卡、ruleMatched=0——完全访问会话无卡属预期，不满足 A8 判定的规则命中语义
  - **A8-revised（终态判定）**：①权限药丸「完全访问」(141,1486) tap → 选择器三档（只读/工作区写入/完全访问）→ tap「只读」(127,1960) → 药丸变「只读」✓；②首条命令 `run bash touch /tmp/acc308_probe/a8r` 06:43:54.477 → **弹卡**（~19s，06:44:13 三键齐，A3/g2-once 规则未泄漏到本会话——若泄漏则无卡）→ tap「始终允许」06:44:19.890 → 确认弹窗「始终允许？」→ tap「始终确认」06:44:23.942 → 探针 a8r 存在；run-as 规则库新增第 2 条：`{"toolName":"tool","sessionId":"session-fe7b060b-1ea2-4692-8db9-08ea6a43f9ec","directoryPattern":"/home/leo-tkp/workspace","createdAt":1788561863477}`（**锚定新会话 session-fe7b060b**）；③第二发 `run bash touch /tmp/acc308_probe/a8c` 06:48:21.030（注：首条确认后第一次补发因 dump 失明+BACK 误退 app 丢失输入，重导航后重发，「立即」窗口降级为 ~4 分钟——如实记录）→ **06:48:26.605 [dispatch] PermissionAsked sid=session-fe7b → 06:48:26.606 rule matched: permission=tool sid=session-fe7b dir=/home/leo-tkp/workspace — replying once**（1ms 自动应答）→ 无卡 + **探针 a8c 存在**（06:48:30）
  - 判定核对：首条弹卡 ✓（无泄漏）+ 第二发 rule matched 日志 ✓ + 无卡 ✓ + 探针存在 ✓ → **✔**
  - 环境事故记录（观测事实）：05:46 宿主 adb daemon 崩溃重启（WiFi 断连 ~1h），06:41 重连+重建 reverse 3080/4199 后继续；uiautomator 对该会话曾持续失明（树 2627B×4 次），BACK 恢复但连带退出 app（MIUI 翻译界面），am start 温启恢复
  - 证据：/tmp/acc308_logs/A8.log（含 a8a/a8b/a8r/a8c 全程；FATAL EXCEPTION=0）、截图 A8.png、A8r_card.png、A8c.png；dump acc308_a8r_card.xml、acc308_a8c_poll2.xml

### A6 提问卡回复回归（question wire `{answers:[…]}` 未被破坏）
- 操作：在 A8 会话发送 `ask%sme%sa%squestion%swith%soptions%sApple%sand%sBanana%sthen%swait%sfor%smy%sanswer` → 等提问卡 → 点选 Apple 选项（dump 定位）→ 提交 → 观测 agent 回复 60s
- 期望：agent 复述收到 Apple
- 判定：agent 回复文本含 Apple → ✔
- 实测记录：✔（2026-09-05 06:50:08）【A8 会话（session-fe7b060b，只读档）】
  - 发送 06:49:21.740（消息 `ask me a question with options Apple and Banana then wait for my answer`）→ 提问卡 **~8s** 出现（06:49:30 dump：节点「待你回答」@[120,1377] + 选项「Apple」@[144,1553][260,1609]「Banana」@[144,1669][294,1725] + 「提交」@[922,2031][1008,2091]；截图 A6_card.png）
  - 点选 Apple (202,1581) → tap「提交」(965,2061) 06:49:50.029 → 提问卡 06:49:54 关闭（待你回答=0）
  - agent 回复（06:49:51 起，25.7s）：**「You picked Apple. 🍎」**——回复文本含 Apple ✓（question wire `{answers:[…]}` 回传未被破坏）
  - logcat：/tmp/acc308_logs/A6.log（FATAL EXCEPTION=0；question 相关行 3）；截图 A6_after.png；dump acc308_a6_card.xml、acc308_a6_post.xml

### B4 pending 审批跨重启重投递（回归：gateway re-delivery）
- 操作：发送 `run%sbash%stouch%s/tmp/acc308_probe/b4` → 等卡出现 → **不应答** → force-stop → `./scripts/debug-entry.sh` 冷启 → 进该会话 → dump
- 期望：审批卡重新出现（服务器 pendings 重投递）→ tap「仅一次」→ `/tmp/acc308_probe/b4` 存在
- 判定：重启后 dump 见审批卡 + 应答后探针存在 → ✔
- 实测记录：✔（2026-09-05 06:53:52）【g2-once 会话；前置（parent 批准）：删规则恢复手动三键——Settings 删 2 条（第 1 刀删掉 a8r 规则 session-fe7b060b（run-as 验证存活=A3 条），第 2 刀删 A3 条 session-d25241a4），终态=空规则（「没有已保存的自动批准规则」+ datastore toolName 计数=0）】
  - 发送 06:52:05.848 → 卡出现 ~14s（06:52:20 三键齐）→ **不应答悬置**（probe_b4=0；截图 B4_card_pending.png；dump acc308_b4_card.xml；logcat 存档 B4_pre.log）
  - force-stop → `./scripts/debug-entry.sh 192.168.110.239:5555` 冷启成功（新 pid 20373，`Debug channel activated` + `NavGraph: Debug channel → SessionList`，EXIT=0）
  - 冷启后环境事实：prod-3080 未自动重连（卡片显示「连接」而非「已连接」——与 Host-4199/192.168.110.248:248 冷启自动重连行为不同）→ 手动 tap「连接」(636,554) → 2s 内「已连接」
  - 进 g2-once 会话 dump（acc308_b4_redispatch.xml）：**审批卡重投递在场**——节点「需要权限」「tool」+ 三键 拒绝@[557,1854][643,1914] 仅一次@[536,2010][664,2070] 始终允许@[515,2166][685,2226]（截图 B4_after_restart.png）
  - tap「仅一次」(600,2040) 06:53:44.815 → 06:53:50 卡消失 + **/tmp/acc308_probe/b4 存在**
  - logcat：/tmp/acc308_logs/B4.log（FATAL EXCEPTION=0）；截图 B4_after.png

## B. 回归（续）

### B1 纯文本对话流式渲染
- 操作：任一会话发送 `reply%swith%sexactly%stwo%swords%sgot%sit` → 观测流式过程与最终文本
- 期望：回复以流式方式呈现（过程如实观测记录），最终回复含「got it」
- 判定：dump 见「got it」回复文本 → ✔（流式过程仅记录不判定）
- 实测记录：✔（2026-09-05 06:58:35 观测，06:59 parent 裁定语义放行——回复=『明白了』(中文两词,会话语言中文;字面 got it 未命中系 checklist 语言假设缺陷,非产品缺陷)）
  - 尝试 1（g2-once 会话）：发送 06:54:14.348 → 回复「明白了」@[77,1144][221,1288]（两个汉字，语义=got it），dump 中 'got it' 命中 1 次但位于**用户消息**「reply with exactly two words got it」内，回复文本不含
  - 尝试 2（同会话同模板重发）：发送 06:54:56.056 → 回复仍=「明白了」（06:55:00）
  - 尝试 3（本欲转 A8 会话；BACK 未离开聊天致第三次发至 g2-once）：发送 06:57:11.372 → 回复仍=「明白了」（06:57:16）；dump 全文 'got it' 命中 3 次全在用户消息
  - 流式过程观测记录（不判定）：B1 窗口 logcat `MessagePartDelta` 分发 34 次（含 06:54:07.784/06:54:08.583/06:54:12.229 counter=1401/1501/1801 等）；回复在 2s 轮询粒度下首查即完整可见（明了两字过短，中间态未被采样到）
  - 健康事实：三轮均正常完成、无 FATAL（=0）、无断连横幅；截图 /tmp/acc308_shots/B1.png；logcat /tmp/acc308_logs/B1.log
  - 字面未命中成因（观测描述）：被测模型（GLM-5.3-Flash）在本会话上下文中以中文两词「明白了」应答英文指令——三次一致；「got it」字面串仅在用户消息中出现。**parent 已裁定（2026-09-05 06:59）：语义放行 → ✔**

### B5 审批卡三按钮完整（依附 A1 的 dump，独立判定）
- 操作：无额外操作（复用 A1 弹卡时 dump/截图）
- 期望：卡上三个应答按钮节点齐全（文本随系统语言）
- 判定：A1 dump 中三个按钮节点均存在 → ✔
- 实测记录：✔（2026-09-05 06:59:03，复用 /tmp/acc308_a1_card.xml + /tmp/acc308_shots/A1_card.png，无额外操作）
  - A1 弹卡 dump 三按钮节点齐全（系统语言=中文文本）：拒绝 中心(600,1884)、仅一次 中心(600,2040)、始终允许 中心(600,2196)；卡头「需要权限」+「tool」节点各 1
  - 交叉佐证（同形态卡独立出现）：A2 卡（acc308_a2_card.xml）、B3 卡（acc308_b3_card.xml）、A3 卡（acc308_a3_card.xml）、B4 重投递卡（acc308_b4_redispatch.xml）均三键齐

### B2 收尾健康总检
- 操作：`adb logcat -d -v time | grep -E "FATAL|AndroidRuntime|PermissionAutoApprover"` 存档；进出现有会话×2；最终 dump 会话列表
- 期望：全程无 FATAL；无断连横幅；列表正常
- 判定：FATAL 计数=0 + dump 正常 → ✔
- 实测记录：✔（2026-09-05 07:00:20）
  - 进出现有会话×2：轮 1 = g2-once 进（只读药丸+输入区正常在场）→ BACK×2 出至 Home；轮 2 = prod-3080「会话」→ g2-once 进 → BACK 出至列表；两轮均无异常 UI
  - logcat 存档：/tmp/acc308_logs/B2.log + 指定 grep 存档 B2_grep.log——**FATAL=0、FATAL EXCEPTION=0**；AndroidRuntime 行 4 个 PID（30166/30214/30329/30373）全部为 uid 2000 `com.android.commands.uiautomator.Launcher`（dump 自身噪音，非 app）；PermissionAutoApprover=0（B2 窗口无审批活动，符合预期）
  - 最终 dump（/tmp/acc308_b2_finallist.xml）：会话列表正常——搜索框 + 会话行齐全（「用 bash 执行 echo g2-once 9月5,06:57」等，时间戳已更新）；断开/disconn/错误 横幅 grep=0
  - 截图：/tmp/acc308_shots/B2_final_list.png

## 收尾数据

- 探针清单：`ls -la /tmp/acc308_probe/`（2026-09-05 07:00:34 终态）——**存在 9 个**：a1(05:31)、a3(05:38)、a4(05:42)、a7(05:23)、a8a(05:44)、a8b(06:42)、a8r(06:44)、a8c(06:48)、b4(06:53)；**不存在（符合各自判定）**：a2（A2 拒绝生效=未创建）、b3（B3 中断未应答=未创建）、b1（B1 纯文本无探针）
- 产物：/tmp/acc308_logs/（19 个 .log：P0-P4/A1-A8/A8a/B1-B4/B4_pre/B2_grep）、/tmp/acc308_shots/（30 个 .png）
- 环境事实存档：①debug-entry.sh 默认连 Host-4199（opencode2.exe，API v2），checklist 指定被测 DSH=prod-3080（V012）——A1 起全程在 prod-3080 连接下执行（reverse tcp:3080 全程在位）；②05:46 宿主 adb daemon 崩溃重启（WiFi 断连 ~1h），06:41 重连+重建 reverse 3080/4199；③新会话默认权限档=「完全访问」（服务器侧，不发 PermissionAsked），A8-revised 以「只读」档完成判定；④「始终允许」卡应答后有二次确认弹窗（取消/始终确认）——A3/A8r 均经两步完成
- 实测记录（汇总填，2026-09-05 07:00）：
  - P0 ✔ 设备在线（192.168.110.239:5555 双 transport）、versionName=0.3.0、lastUpdateTime=2026-09-04 23:21:56、探针目录清空
  - P1 ✔ 冷启直达会话列表（Debug channel→SessionList），13+ 既有会话可见；g2-once 会话在 prod-3080 下（非默认 4199），以等价会话记录
  - P2 ✔ 进「dsh指定版本安装及npm卸载」会话（4199 下）：无断连横幅、输入区可用、FATAL=0（AndroidRuntime 命中均为 uiautomator 噪音）
  - P3 ✔ 规则盘点=3 条旧形态（tool · /home/leo-tkp/workspace ×3，无 probe4 新形态）；审批规则区在全局 Settings（Home 齿轮）
  - A7 BLOCKED 4199 会话无 PermissionAsked（无卡可 tap）→ 探针 a7 存在、rule matched=0；真实 g2-once 会话（目录=旧规则目录）在 prod-3080 下——parent 裁定维持 BLOCKED（probe4 历史证据 + 单测作结）
  - P4 ✔ 3 条规则逐条删除（截图留档），空态「没有已保存的自动批准规则」
  - A1 ✔ prod-3080 g2-once 会话：卡 ~32.5s 出现→tap 仅一次→+4s 卡消失+探针 a1 存在（wire 裸字符串主证）
  - A2 ✔ tap 拒绝→卡消失+探针 a2 不存在（fail-closed）+agent 转述被拒
  - B3 ✔ 等待审批时 tap「停止」→卡消失（+5s）、SessionIdle、UI 恢复可操作、FATAL=0、探针 b3 不存在
  - A3 ✔ tap 始终允许+二次确认「始终确认」→探针 a3 存在+新规则落库且 sessionId=session-d25241a4（**会话锚定**，run-as DataStore 直读；UI 行不显示 sessionId 系渲染设计）
  - A4 ✔ 二发无卡：PermissionAsked→rule matched（2ms）→探针 a4 存在（Layer1 主证）
  - A5 ✔ A4 窗口末连续 6 轮无滞留卡；自动应答快于 2s 采样粒度（Layer2 主证）
  - A8 ✔（终态=A8-revised）a8a/a8b 附注（新会话默认完全访问→无 PermissionAsked 前提，无泄漏证据）；revised：切「只读」→首条弹卡（无泄漏）→始终允许+确认→新规则锚定 session-fe7b060b→二发 a8c 1ms rule matched 无卡+探针存在
  - A6 ✔ 提问卡 ~8s 出现（待你回答/Apple/Banana/提交）→选 Apple 提交→agent「You picked Apple. 🍎」（question wire 未破坏）
  - B4 ✔ 前置删 2 条规则；b4 卡悬置不应答→force-stop→debug-entry 冷启→重连 prod-3080→进会话**卡重投递在场**→tap 仅一次→探针 b4 存在
  - B1 ✔（parent 语义放行）三轮回复均为中文两词「明白了」（字面 got it 未命中=checklist 语言假设缺陷非产品缺陷）；MessagePartDelta×34、渲染正常、FATAL=0
  - B5 ✔ A1 dump 三按钮齐全（拒绝/仅一次/始终允许），A2/A3/B3/B4 卡交叉佐证
  - B2 ✔ 进出会话×2 无异常；FATAL=0（AndroidRuntime 均为 uiautomator 噪音）；最终会话列表 dump 正常无横幅

  **终态汇总：P0 ✔ · P1 ✔ · P2 ✔ · P3 ✔ · A7 BLOCKED · P4 ✔ · A1 ✔ · A2 ✔ · B3 ✔ · A3 ✔ · A4 ✔ · A5 ✔ · A8 ✔（A8-revised） · A6 ✔ · B4 ✔ · B1 ✔（语义放行） · B5 ✔ · B2 ✔ —— 17 ✔ + 1 BLOCKED（A7，环境配置性，非缺陷）**
