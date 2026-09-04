# #326 busy 输入区单按钮统一 验收 checklist

- 卡片：backlog #326（单键承担发送/入队，对齐 web 主按钮；2026-09-04 用户定规）| 日期：2026-09-05
- 构建：commit `555c2ba4`（含单键状态机）| 包名：`dev.leonardo.ocbeacon.dev`
- 设备：WiFi adb `192.168.110.239:5555`（一律显式 -s；本机以 USB mDNS 双注册）
- 服务器：主验=DSH prod-3080（V012，busy 排队/steer 语义）；B1 回归=Host-4199（V2）
- **分类：UIUX**（交互形态变更）→ AI 三步验收全绿后**追加人工验收清单**（与 #327 同域汇总提交）
- 回归域：会话交互（发送/中断/排队）· composer 稳定性 · FAB 菜单 · shell 模式（V2）
- 证据目录：`/tmp/acc326_logs/`（logcat）、`/tmp/acc326_shots/`（截图）；探针无需

## 执行纪律（同 #308：一次一项、逐项记录、禁止分析；dump 定位后 tap；input text 空格=%s 禁特殊字符；等态轮询 2s/次上限 60s——**例外：A3 判定窗 120s、A4 观测窗 90s**（按各 item 写明窗口轮询，勿提前放弃）；每项前后 logcat -c/-d 存档）

## 前置

### P0 设备与新构建安装
- 操作：`adb -s 192.168.110.239:5555 devices`；确认宿主机已产出 `app/build/outputs/apk/dev/debug/app-dev-debug.apk`（主 agent 构建后由主 agent 装——执行员只验证）；装后 `dumpsys package dev.leonardo.ocbeacon.dev | grep -E "versionName|lastUpdateTime"`
- 期望：versionName=0.3.0、lastUpdateTime=2026-09-05 07:09:28（主 agent 已装 555c2ba4 构建并核实——不符则记 ✘ 停卡报告）
- 判定：输出记录在案
- 实测记录：✔（2026-09-05 07:12:41）
  - `adb devices -l`：`192.168.110.239:5555 device product:houji model:23127PN0CC transport_id:2` + `adb-e69a99d8-yzT17Y._adb-tls-connect._tcp device product:houji model:23127PN0CC transport_id:1`（双 transport 在线，全程显式 `-s 192.168.110.239:5555`）
  - dumpsys package：`versionName=0.3.0`、`lastUpdateTime=2026-09-05 07:09:28` —— **与期望一致（555c2ba4 单键构建），不停卡**
  - 宿主机 APK 产物在场：`app/build/outputs/apk/dev/debug/app-dev-debug.apk` 38,951,748 字节，mtime 2026-09-05 07:09（主 agent 已装，执行员仅验证）
  - 证据目录就绪：`/tmp/acc326_logs/`（P0.log 65,676 行，`FATAL EXCEPTION|Process: dev.leonardo`=0）、`/tmp/acc326_shots/`（空）

### P1 连接 prod-3080 并进入测试会话
- 操作：force-stop 冷启（**注意 runbook 坑：debug-entry.sh 默认重指 4199——需按 runbook ⚠️节连 prod-3080**，参考 #308 验收 P1 补充事实：Home 页 prod-3080 卡片）→ 进会话「用 bash 执行 echo g2-once」（只读档,便于 A5 触发审批）
- 期望：会话打开，输入区可用，无断连横幅
- 判定：dump 正常 + FATAL=0
- 实测记录：✔（2026-09-05 07:14:09）
  - 避开 debug-entry.sh 4199 重指坑：**未跑 debug-entry.sh**，改为 force-stop 后**无 intent 冷启**（`am start -n dev.leonardo.ocbeacon.dev/dev.leonardo.ocbeacon.MainActivity`，07:13:22）→ 落 Home 页；先 `adb reverse tcp:3080 tcp:3080`（reverse --list 含 3080/4199 两项）
  - Home dump（/tmp/acc326_p1_home.xml）：Host-4199 卡「已连接」（自动重连）；prod-3080 卡（http://127.0.0.1:3080 · DSH 徽标）「连接」按钮@[593,1865][679,1925] → tap 中心 (636,1895) 07:13:41
  - 07:13:44 dump：prod-3080 卡变「已连接 · DSH」；卡上「会话」按钮@[337,1865][423,1925] → tap (380,1895)；logcat：`ConnLifecycle: Connecting to server: prod-3080 (http://127.0.0.1:3080)` + `[prod-3080] SSE connection attempt #1`
  - 会话列表 dump（/tmp/acc326_p1_sessionlist.xml）：「用 bash 执行 echo g2-once」行@[168,968][704,1024] → tap (436,996) 07:13:57
  - 会话 dump（/tmp/acc326_p1_chat.xml）：标题「用 bash 执行 echo g2-once」@[168,176][781,240]；权限药丸「只读」@[72,2387][141,2430]（只读档 ✓ 便于 A5）；输入区占位「提问…」@[96,2498][244,2562] 在场可用；content-desc「发送」@[1056,2508][1116,2568] 在场；无「停止」节点
  - 断连横幅 grep：断开=0 / disconn=0 / 错误=0 / 重试=0
  - logcat 存档 /tmp/acc326_logs/P1.log（20,491 行）：`FATAL EXCEPTION`=0、`Process: dev.leonardo`=0；截图 /tmp/acc326_shots/P1.png

## A. 修复效果（单键形态与语义）

### A1 空闲+文本=单发送键（含空文本禁用态）
- 操作：输入框敲入 `hello%scheck`（不发送）→ dump 输入行 → 清空输入（全选删除或逐字退格）→ tap 发送键 → dump 转录区
- 期望：有文本时发送/停止区**仅一个按钮**=发送键（飞机图标）；无停止键；**空文本时发送键存在但禁用**（tap 无效果：转录不新增消息）
- 判定：dump 中 send-stop 区单按钮节点（testTag chat-send 在,chat-stop 不在）+ 空文本 tap 后转录区无新用户消息（禁用态生效）
- 实测记录：✔（2026-09-05 07:15:25）
  - 07:14:30 tap 输入框（占位「提问…」区域）→ `input text 'hello%scheck'` → dump /tmp/acc326_a1_text.xml（07:14:55）：输入框 text="hello check"@[96,1540][960,1684]；**resource-id `chat-send`=1、`chat-stop`=0**；发送/停止区仅一个按钮=content-desc「发送」@[1056,1586][1116,1646] enabled=true；无「停止」节点
  - 清空：keyevent 67（退格）×14 → dump /tmp/acc326_a1_empty.xml：输入框 text="提问…"（空，占位恢复）；**chat-send=1、chat-stop=0**（空文本时发送键仍在场；节点 enabled 属性仍=true——禁用态不由 a11y enabled 反映，以行为判定）
  - 空文本 tap：07:15:00 tap「发送」中心 (1086,1616) → 07:15:10 dump /tmp/acc326_a1_aftertap.xml + 复核 dump /tmp/acc326_a1_aftertap2.xml（07:15:25）：**转录区 "hello check" 出现次数=0**（无新用户消息）；chat-send=1、chat-stop=0；输入框空（占位轮换为「重构…」@[96,1576][244,1640]，focused=false 键盘已收）→ **禁用态生效**（tap 无效果）
  - logcat /tmp/acc326_logs/A1.log：无 promptAsync/发送分发行；`FATAL EXCEPTION`=0；截图 /tmp/acc326_shots/A1.png

### A2 忙碌+空白=单停止键,点击中断
- 操作：发送 `count%sfrom%s1%sto%s15%sone%snumber%sper%slinesthen%sreply%sdone`（制造 ~30s 忙碌）→ 等 3s（流式中）→ dump → tap 停止键
- 期望：忙碌+输入空白时**仅一个停止键**；tap 后轮次中断,状态回闲
- 判定：dump 单按钮=chat-stop + 中断后 10s 内 dump 输入区回发送键 + logcat 无 FATAL
- 实测记录：✔（2026-09-05 07:16:56）
  - 执行注记：首轮脚本引号错误中断于 dump 后（tap/输入未执行），07:16:29 复核 dump /tmp/acc326_a2_typed.xml：输入框仍持有 `count from 1 to 15 one number per line then reply done`（count=1）未发送 → 07:16:36 tap「发送」(1086,1616) 原文发出
  - 发送后 +5s dump /tmp/acc326_a2_busy.xml（07:16:41）：**chat-stop=1、chat-send=0**——忙碌+输入空白时发送/停止区仅一个停止键（content-desc「停止」无独立节点文本,以 testTag 计数）;流式在场证据：A2 窗口 MessagePartDelta/MessagePartUpdated/MessageUpdated 计 10 行
  - tap「停止」中心 (1086,1616)（tap 下行 ≈07:16:41.0）→ logcat：`07:16:40.953 EventDispatcher: [dispatch] SessionIdle -> SessionEventHandler sid=session-d252` → `07:16:41.544 SessionStateService: Idle --ClientAbort--> Idle [force-complete]` → `07:16:41.561 SessionActionsDelegate: Aborted session session-d25241a4-10ea-4137-b33c-57e9db239fe8`（轮次中断生效）
  - 中断后 dump /tmp/acc326_a2_post.xml（≈07:16:50，tap 后 ~9s，10s 窗口内）：**chat-send=1、chat-stop=0**，content-desc「发送」@[1056,1586][1116,1646] 恢复、「停止」find=NONE——输入区回发送键 ✓
  - 附带观测：07:16:28 三条历史消息（a3f021ec/ff670b7c/efc83cd6）L3 REST validation `server says Busy`，07:16:43 `ff670b7c … zombie display-fix only (auto interrupt disabled per official semantics)`（zombie 清理路径,无 UI 影响）
  - logcat /tmp/acc326_logs/A2.log：`FATAL EXCEPTION`=0；截图 /tmp/acc326_shots/A2.png；dump×3（typed/busy/post）留档

### A3 忙碌+文本=仅发送键（无双键!）,点击=服务端排队
- 操作：再发数数消息制造忙碌 → 流式中输入 `queued%smessage%scheck` → dump（**关键断言：发送/停止区仅一个=发送键,无停止键**）→ tap 发送
- 期望：输入框清空；消息入服务端队列（FAB 角标链 #327 已知断裂,不判角标——判 logcat promptAsync mode=queue 受理）;本轮结束后队列消息被消费:转录出现该消息+agent 对其回应
- 判定：忙+文本 dump 单发送键 + tap 后 120s 内转录出现 queued message check 的用户消息与 agent 回应（消费证据）
- 实测记录：✔（2026-09-05 07:19:40）
  - 制忙：07:18:00 发送数数消息（count#2,同 A2 模板）；+3s tap 输入框 → `input text 'queued%smessage%scheck'` → **流式中 dump /tmp/acc326_a3_busytext.xml（07:18:08,发出后 +8s）**：**chat-send=1、chat-stop=0——忙碌+文本时发送/停止区仅一个发送键,无停止键**（关键断言 ✓）;输入框持有 "queued message check"
  - tap 发送 (1086,1616) 07:18:14（tap 时会话 busy）→ postsend dump /tmp/acc326_a3_postsend.xml（07:18:20）：**输入框已清空**（转录区出现用户消息 "queued message check"@[84,700][551,756]）;清空后忙碌+空白 → **chat-stop=1、chat-send=0**（单停止键,与 A2 一致）
  - wire 受理证据：logcat `07:18:14.331 Ktor Client: REQUEST: http://127.0.0.1:3080/api/session/prompt` + `07:18:14.352 ChatSendDelegate: Sent prompt to session session-d25241a4… (1 parts)`——busy 中 prompt 被受理无报错（注：logcat 无字面 `mode=queue` 行,V012 wire 形态为 /api/session/prompt;排队语义以下列消费时序佐证）
  - 消费证据（120s 窗口内,实测 ~44s）：poll1 dump（07:18:58）已回闲（chat-send=1）;转录 tail：agent 回复 **"Queued-message check complete — nothing pending:"** + "• Background jobs: none running or finished." + "• Subagents: none." + **"• Queued messages: this turn (\"queued message check\") is the only outstanding message; nothing else was waiting for delivery."**（回复显式引用 queued message check,耗时 5.8s）——数数轮结束后队列消息被消费,转录含该用户消息+agent 回应 ✓
  - poll2/3（07:19:12/07:19:28）复核一致（/tmp/acc326_a3_poll*.xml）;logcat /tmp/acc326_logs/A3.log：`FATAL EXCEPTION`=0;截图 /tmp/acc326_shots/A3.png

### A4 忙碌+文本长按发送键=直发插话（steer,#309④ 保留）
- 操作：再发数数消息 → 流式中输入 `steer%scheck%sreply%sSTEERED` → **长按**发送键（`input swipe <sendX> <sendY> <sendX> <sendY> 700`）→ 观测 90s
- 期望：消息直发插话（不排队）：转录较快出现该消息,agent 在同轮或下轮回应含 STEERED
- 判定：90s 内转录见该消息 + agent 回复含 STEERED（与 A3 的排队消费时序可区分：steer 不等轮末）
- 实测记录：**✔（终态=语义放行,parent 裁定 2026-09-05 07:27）**——本项被测特性=「忙碌长按发送键→mode=steer 直发插话」,核心断言以 wire 证据为准（Busy/Streaming 中 ClientSendParts 直发+2-4s 入转录+与 A3 排队时序可区分）三者齐备即 ✔;「agent 正文含 STEERED」系对 LLM 行为假设的证据装饰而非特性断言,thinking-only/force-complete 现象如实记录为观测附注。初判曾记 ✘（07:24:30,因正文 body 空）,维持原始观测如下：
  - 尝试 1（07:20:24-07:20:44,如实记录）：发数数 count#3（07:20:30）→ 流式中输入 steer 文本 → dump /tmp/acc326_a4_busytext.xml（07:20:39）chat-send=1/chat-stop=0 ✓,但该 dump 中发送键 content-desc 失明（desc「发送」find=NONE,chat-send rid 在场）→ swipe 空参报错 `Invalid arguments for command: swipe` 未执行;且 count#3 轮仅 ~6s（07:20:36 即回复 1-15+Done,07:21:08 dump 复核已 idle）——忙碌窗口关闭,**重试**
  - 尝试 2（成功执行长按）：清空输入（keyevent 67×26 单 shell 循环）→ 07:21:48 发 count#4（FSM:`07:21:48.309 Idle --ClientSendParts--> Busy/Waiting` → `Busy/Waiting --TextStarted--> Busy/Streaming`）→ 立即 tap 输入框+`input text 'steer%scheck%sreply%sSTEERED'`（07:21:49）→ **长按发送键 `input swipe 1086 1616 1086 1616 700`**（坐标取自 a4_state dump 的 chat-send rid 节点 (1014,1544)(1158,1688) 中心,echo 07:21:50）
  - **wire 证据（直发不等轮末）**：`07:21:49.366 ChatSendDelegate: Sent prompt to session session-d252…（1 parts）`+FSM `Busy/Streaming --ClientSendParts--> Busy/Streaming`——steer prompt 于 Busy/Streaming 中直发（对照 A3 排队消费在轮末后）;`07:21:52.445 MessageRemoved/MessageUpdated`（count#4 回复提前完结 seq-8588）+ 新用户消息 seq-8592 入转录
  - 转录较快出现该消息 ✓：poststeer dump（07:21:54）用户消息 "steer check reply STEERED"@[84,1188]（长按后 ~2-4s）;输入框已清空
  - **agent 回应观测（90s 窗口,长按 07:21:50 → 窗末 07:23:20+）**：agent 块（07:21:54）仅含「思考完毕 · 0ms」+ thinking 文本 `The user says "steer check reply STEERED". They want me to reply with exactly "STEERED".`@[120,1007][1086,1151] + 状态 chip「已响应」@[77,1144]——**无含 STEERED 的正文 body**（poll1-5 dump 07:22:16→07:23:55 稳定无变化,chat-send=1 idle;wire:`07:21:53.812 Busy/Streaming --SseStatus--> Idle [force-complete]`,`07:21:53.815 MessageUpdated seq-8625 role=assistant completed`,thinking-only 空 body）;「下轮回应」未发生（窗内无后续轮次）
  - 判定核对：转录见该消息 ✓;agent 回复含 STEERED —— 仅 thinking 部分引用 STEERED ×2,正文 body 为空 → 判定后半 ✘（STEERED 字样在 agent 块内但非回应正文,是否算「回复含 STEERED」留 parent 裁定;直发插话语义本身与 A3 时序可区分且成立）
  - 执行注记：本轮发送键 content-desc 间歇失明（尝试1 NONE/尝试2 用 rid 定位成功）——A1-A3 同位置 desc 均在,观测事实记录
  - logcat /tmp/acc326_logs/A4.log：`FATAL EXCEPTION`=0;截图 /tmp/acc326_shots/A4.png;dump×9（busytext/state/poststeer/poll1-5）留档

### A5 被阻塞（等待审批）=停止键+输入禁用
- 操作：本会话为只读档——发送 `run%sbash%stouch%s/tmp/acc326_blocked` → 轮询 dump 等审批卡（卡特征：含「需要权限」头 + 三按钮节点「拒绝」「仅一次」「始终允许」——dump 后按节点文本取 bounds 中心 tap，勿用历史坐标盲打）→ 卡在场时 dump 输入行 → tap「仅一次」按钮中心 → 观测 30s
- 期望：审批挂起期间发送/停止区=**停止键**（blocked 分支）;输入框禁用;应答「仅一次」后卡消失、恢复发送键+输入可用、命令执行
- 判定：卡挂起 dump=chat-stop 单键（chat-send 不在）+ 输入框不可输入（tap 输入框后 dump 焦点/光标无变化如实记录）;应答后 dump=chat-send 恢复 + **宿主机直查** `ls -la /tmp/acc326_blocked`（agent 的 bash 在生产服务器=宿主机本机执行,执行员用自己的 bash 查）存在
- 实测记录：✔（2026-09-05 07:27:14）【g2-once 只读档会话】
  - 07:25:53 发送 `run bash touch /tmp/acc326_blocked`（tap chat-send rid 中心 1086,1616）→ logcat `07:26:02.910 [dispatch] PermissionAsked -> PermissionEventHandler sid=session-d252`（id=fd2389aa-c55c-49d3-b8c3-67035dadab92, permission=tool）+ InSessionFeedback play type=PERMISSION
  - 卡出现 ~10s：poll2 dump（07:26:05）三键齐——「需要权限」@[156,1658][326,1714] + 拒绝@[557,1854][643,1914] / 仅一次@[536,2010][664,2070] / 始终允许@[515,2166][685,2226];卡上方并见 `$ touch /tmp/acc326_blocked`「完成」5.7s Shell 块（首次尝试记录,如实）
  - **blocked 分支 dump（卡在场,/tmp/acc326_a5_poll2.xml）：chat-stop=1、chat-send=0**——发送/停止区=单停止键 ✓（另 content-desc「停止」在场,输入区占位「帮我处理…」）
  - **输入禁用实测**：07:26:29 tap 输入框中心 (219,2530) → 07:26:34 dump /tmp/acc326_a5_inputtap.xml：**无任何 focused="true" 节点**、占位「帮我处理…」原样（无光标/无文本变化）、三键卡原样在场、chat-stop=1 不变——tap 无效果 ✓（不可输入）
  - tap「仅一次」中心 (600,2040) 07:26:40 → logcat `07:26:40.398 SessionActionsDelegate: [Permission] replyToPermission: id=fd2389aa… reply=once` → post1（07:26:50,+10s）卡消失（仅一次=0/需要权限=0,仍 chat-stop=1 busy 执行中）→ **post2（07:26:58,+18s）chat-send=1、chat-stop=0 发送键恢复**;post3（07:27:07）稳定 idle,输入区可用（占位「帮我处理…」@[96,2498][343,2562] +「发送」@[1056,2508][1116,2568]）✓
  - **宿主机直查（执行员自身 bash）**：`ls -la /tmp/acc326_blocked` → `-rw-rw-r-- 1 leo-tkp leo-tkp 0 9月 5日 07:26 /tmp/acc326_blocked` —— **存在**（命令真实执行）✓
  - logcat /tmp/acc326_logs/A5.log：`FATAL EXCEPTION`=0;截图 /tmp/acc326_shots/A5_card.png（卡在场）/ A5_after.png（恢复后）;dump×6（poll1-2/inputtap/post1-3）留档

## B. 回归

### B1 V2 后端（4199）单键形态与常规发送
- 操作：切 Host-4199 连接（Home 卡片）→ 任一会话输入 `hi` → dump → 发送
- 期望：单发送键形态一致;消息正常发送有回复
- 判定：dump 单键 + 回复可见 + FATAL=0
- 实测记录：✔（2026-09-05 07:31:40）【V2=Host-4199（API v2 · 0.0.0-beta-17823）,会话「dsh指定版本安装及npm卸载」（ses_f96fec3c…,22 条历史消息）】
  - 切换导航：会话内 BACK×2 → Home（07:27:42 dump：Host-4199 卡「已连接 · API v2」）→ 卡上「会话」(380,693) → 会话列表（搜索框+既有会话行齐全）→ 行「dsh指定版本安装及npm卸载」tap (448,606) 07:27:58
  - 进会话 dump /tmp/acc326_b1_chat.xml（07:28:10）：**chat-send=1、chat-stop=0（V2 单发送键形态一致 ✓）**,chat-send bounds [1014,2466][1158,2610];占位「提问…」在场
  - 输入 `hi` → typed dump（07:28:22）：chat-send=1/chat-stop=0（有文本仍单键）→ tap 发送 (1086,1616) 07:28:22;busy 期 poll1-7（07:28:33→07:29:48）chat-stop=1/chat-send=0（V2 忙碌+空白=单停止键,同 prod-3080 形态）
  - **回复可见 ✓（三维证据）**：①wire：SseClientV2 `[recv] MessageUpdated`×多轮 + `[msg] MessageUpdated sid=ses_f96fec3c id=msg_06ec078e2001 role=assistant`（07:28:24→07:28:53）+ ItemDiag `Turn item key=t_msg_06ec078e2001 role=assistant`;turn 完成 `07:29:53.594 Busy/Streaming --RestValidation--> Idle [force-complete]`（07:29:53 回闲）;②像素探针（/tmp/acc326_probe.py,指南§2 路数）：「hi」气泡（198,232,253）下方 y≈2210-2350 渲染有回复内容——黑底代码块（彩色语法高亮字形 (0,0,153)/(235,3,1)/(37,255,152)/(112,84,252) 等）+ 浅底文本行,重进会话后（B1_reenter.png,07:31:20+）同带内容仍在;③uiautomator dump 对该 Markdown/代码块**失明**（回复区 0 text 节点——已知 Compose a11y 失明区,两轮 dump+重进会话一致,如实记录;「回复可见」以像素+wire 判）
  - FATAL=0（/tmp/acc326_logs/B1.log 无 FATAL EXCEPTION）;截图 /tmp/acc326_shots/B1.png、B1_reenter.png;dump×12（home/list/chat/typed/poll1-8/final/list2/reenter）留档

### B2 V2 空闲长按发送键=shell 模式切换（Dsh 门控关闭,仅 V2 有）
- 操作：V2 会话空闲+无文本 dump 定位发送键 bounds → **长按**：`input swipe <sendX> <sendY> <sendX> <sendY> 700` → dump
- 期望：shell 模式横幅/占位出现（长按语义未因单键重构丢失）
- 判定：dump 见 shell 横幅或占位变化;再长按切回
- 实测记录：✔（2026-09-05 07:32:22）【V2 会话（同 B1）,空闲+无文本】
  - 前置 dump /tmp/acc326_b2_pre.xml（07:32:03）：chat-send=1/chat-stop=0（空闲单发送键）,chat-send rid 定位 (1086,2538)
  - **长按 #1**：`input swipe 1086 2538 1086 2538 700`（07:32:04）→ toggled dump（07:32:09）：**Shell 模式激活**——横幅「Shell 模式已激活。长按发送可切换回原模式。」@[150,2394][917,2442] + 输入占位变「输入 shell 命令…」@[96,2502][484,2566] + 发送键 content-desc 变「发送 Shell 命令」@[1056,2508][1116,2568] + 「终端」图标 desc@[90,2397][132,2439] ✓（长按语义未因单键重构丢失）
  - **长按 #2 切回**：`input swipe 1086 2538 1086 2538 700`（07:32:17）→ back dump（07:32:22）：横幅=0、「输入 shell 命令…」=0,占位恢复常规建议「帮我处理…」,发送键 desc 恢复「发送」,chat-send=1/chat-stop=0 ✓
  - logcat /tmp/acc326_logs/B2.log：`FATAL EXCEPTION`=0;截图 B2_toggled.png / B2_back.png;dump×3（pre/toggled/back）留档

### B3 FAB 菜单与收尾健康
- 操作：回 prod-3080 会话展开 FAB 菜单 dump;进出会话×2;`logcat -d | grep -cE "FATAL|AndroidRuntime"`（排除 uiautomator 噪音归属）
- 期望：FAB 五入口正常;全程无崩溃
- 判定：FAB 入口节点在 + FATAL=0
- 实测记录：✔（2026-09-05 07:35:40）【prod-3080 g2-once 会话】
  - 导航回主验会话：V2 会话 BACK×2 → Home（07:32:45 prod-3080 卡「已连接 · DSH」）→「会话」(380,1168) → g2-once 行 (436,801) → 07:33:05 在会话（chat-send=1/chat-stop=0）
  - **FAB**：dump 定位 content-desc「打开任务菜单」@[1092,2196][1164,2268]（FAB 节点在场 ✓）→ tap 中心 (1128,2232) → **像素 diff 硬证据（指南§2 路数,闭合态 B3_now.png vs 展开态 B3_open2.png）**：右侧 x820-1190 出现 **y=1425-2150（h~725px）连续变化带 + FAB 自身 y=2205-2265 图标变化带**——菜单展开覆盖层在场;展开态采样行色簇 ≥5 族（红 (233,67,70)/蓝紫 (87,73,178)/橄榄 (120,137,63)/橙黄 (236,189,29)+(160,68,33)/蓝灰 (205,222,234)）与多彩药丸堆栈一致;**药丸标签/计数为 a11y 失明区**（指南§0/§1 已知失明区:展开药丸——两轮 dump 无 pill 节点,如实记录;「五入口」精确计数不可经 dump 机器验证,以像素色簇+展开行为记）
  - 展开态 dump /tmp/acc326_b3_open2.xml + 截图 B3_open2.png;闭合对照 B3_now.png/B3_fabclosed.png;首次展开截图 B3_fabmenu.png
  - **进出会话×2**：轮1 BACK→列表→g2-once 行 (436,801) 进（07:35:06）→ BACK;轮2 列表→同行再进（07:35:13）→ 终态在会话 dump /tmp/acc326_b3_final.xml（07:35:18）：标题「用 bash 执行 echo g2-once」在场、chat-send=1/chat-stop=0、无横幅——两轮均无异常 UI
  - **收尾健康**：logcat /tmp/acc326_logs/B3.log——`FATAL EXCEPTION`=0、`Process: dev.leonardo`=0;`grep -cE "FATAL|AndroidRuntime"` 原始计数 54 行,**归属核查全部为 `AndroidRuntime(18173 等): START com.android.internal.os.RuntimeInit uid 2000`**（uiautomator dump 命令自身 runtime 噪音,9 行含 uiautomator 字样,其余为同 PID 的 RuntimeInit 生命周期行;无 app 进程崩溃行）→ FATAL=0 ✓;截图 B3_final.png

## 人工验收清单（UIUX;与 #327 汇总提交,此处登记项）
- [ ] 忙碌时输入文本:单发送键形态观感（无并排停止键是否影响中断可达性认知——清空文本即见停止键）
- [ ] 长按直发插话(steer)的可发现性与反馈
- [ ] 排队发送后的输入框清空反馈与「已排队」感知（角标链 #327 修复后复看）

## 执行汇总（2026-09-05 07:36,纯净执行员终态）

**终态：P0 ✔ · P1 ✔ · A1 ✔ · A2 ✔ · A3 ✔ · A4 ✔（parent 裁定语义放行）· A5 ✔ · B1 ✔ · B2 ✔ · B3 ✔ —— 10 ✔ + 0 ✘ + 0 BLOCKED**

- **P0 ✔**（07:12:41）WiFi transport 192.168.110.239:5555 双注册在线;versionName=0.3.0、lastUpdateTime=2026-09-05 07:09:28 与 555c2ba4 单键构建预期一致;APK 产物 38,951,748B 在场
- **P1 ✔**（07:14:09）避 debug-entry 4199 坑:无 intent 冷启 → Home prod-3080 卡「连接」tap → 已连接 → g2-once 只读档会话打开;输入区可用/无横幅/FATAL=0
- **A1 ✔**（07:15:25）空闲+文本:chat-send=1/chat-stop=0 单发送键;清空后空文本 tap 发送→转录 0 新消息（禁用态生效）;无 prompt 分发
- **A2 ✔**（07:16:56）忙碌+空白:chat-stop=1/chat-send=0 单停止键;tap → SessionIdle/ClientAbort/Aborted session;~9s 内回发送键;FATAL=0
- **A3 ✔**（07:19:40）**关键断言:忙碌+文本 dump chat-send=1/chat-stop=0（无双键）**;tap 发送→输入清空+busy 中 prompt 受理（07:18:14.331 REQUEST）;~44s 后队列消费——agent 回复显式引用 queued message check（120s 判定窗内）
- **A4 ✔（终态=语义放行,parent 裁定 07:27）**核心断言以 wire 为准:Busy/Streaming --ClientSendParts--> Busy/Streaming（07:21:49.366 busy 中直发）+ 消息 ~2-4s 入转录 + 与 A3 排队时序可区分——三者齐备。观测附注:agent 回应 thinking-only（引用 STEERED ×2 但正文 body 空,SseStatus→Idle force-complete）;尝试1 失败留存记录（busy 窗口 ~6s 关闭+swipe 空参报错,desc 间歇失明以 rid 兜底）
- **A5 ✔**（07:27:14）审批卡在场:chat-stop=1/chat-send=0（blocked 分支）;tap 输入框无 focus/光标变化（输入禁用）;tap「仅一次」→ +10s 卡消失 → +18s chat-send 恢复;**宿主机直查 /tmp/acc326_blocked 存在**（07:26 创建）;FATAL=0
- **B1 ✔**（07:31:40）V2=Host-4199:单发送键形态一致（空闲/有文本 chat-send=1;忙碌 chat-stop=1）;hi 发送有回复——回复可见以三维证据判（wire MessageUpdated role=assistant + ItemDiag + 像素探针:hi 下方渲染含代码块彩色字形;uiautomator 对该 Markdown 内容失明,如实记录）;FATAL=0
- **B2 ✔**（07:32:22）V2 空闲长按发送键:Shell 模式激活（横幅「Shell 模式已激活…」+占位「输入 shell 命令…」+发送键 desc「发送 Shell 命令」）;再长按切回（横幅=0/占位恢复/desc 恢复「发送」）——长按语义未因单键重构丢失
- **B3 ✔**（07:35:40）FAB 节点在场（desc「打开任务菜单」）+ 菜单展开像素 diff 硬证据（725px 连续变化带+FAB 图标带;药丸标签 a11y 失明区如实记录,色簇 ≥5 族）;进出会话×2 无异常;FATAL|AndroidRuntime 54 行全为 uid 2000 uiautomator runtime 噪音,FATAL=0

**关键证据要点**
1. 单键状态机四态全验：idle+empty（禁用态,A1）/ idle+text（发送键,A1）/ busy+empty（停止键,A2、A3 postsend、A5 blocked）/ busy+text（发送键排队,A3;长按 steer,A4）——全程无双键并排形态
2. 排队 vs 直发时序可区分：A3 队列消息在轮末后消费（agent 引用 queue 状态）;A4 steer 于 Busy/Streaming 中 ClientSendParts 直发+2-4s 入转录（FSM 行佐证）
3. blocked 分支形态=停止键+输入禁用（tap 无 focus 变化）,应答后恢复+命令真实执行（宿主机探针存在）
4. V2 回归：单键形态一致、shell 长按切换往返正常、常规发送回复正常（回复渲染区 dump 失明,以 wire+像素判）
5. 全程 0 FATAL/0 崩溃;AndroidRuntime 行均为 uiautomator 自身噪音（uid 2000）

**证据产物**：/tmp/acc326_logs/（P0,P1,A1-A5,B1-B3 共 10 个 .log）; /tmp/acc326_shots/（16 个 .png:P1,A1,A2,A3,A4,A5_card,A5_after,B1,B1_reenter,B2_toggled,B2_back,B3_fabmenu,B3_fabclosed,B3_now,B3_open2,B3_final）; dump 中间件 /tmp/acc326_*.xml（a1-a5/b1-b3 全流程）;探针 /tmp/acc326_blocked 存在;工具 /tmp/acc326_ui.py（dump 定位）+/tmp/acc326_probe.py（PNG 像素探针）
