# 终轮合并验收 checklist（#320 通知/#322 搜索/#323 命令反馈/#325 配对/#324 设置四域 + #312 A2 补确认）

- 卡片：#320/#322/#323/#325/#324 + #312 尾项 | 日期：2026-09-06
- 构建：含 4e25b716/c7170723/ea52a106/9ad9bb03/4fb237b1..a1a835dc | 包：`dev.leonardo.ocbeacon.dev`
- 设备：一律 `adb -s 192.168.110.239:5555`；后端=DSH prod-3080
- 分类：混合（#322/#323 数据面偏非 UIUX;#320/#325/#324 UIUX）→ AI 验收后 UIUX 项入人工域汇总
- 证据目录：`/tmp/accF_logs/`、`/tmp/accF_shots/`

## 执行纪律（一次一项/逐项记录/禁止分析/dump 定位/%s 转义/单次 bash ≤100s/长窗分段;通知检查用 dumpsys notification)

## 前置
### P0 设备构建核对
- 前置：主 agent 已装
- 操作：adb devices;dumpsys versionName/lastUpdateTime
- 期望：versionName=0.3.0、lastUpdateTime=2026-09-06 04:18:51（不符 ✘ 停卡）
- 判定：输出记录
- 实测记录：✔ 2026-09-06 04:25:46。`adb devices -l`：仅 `192.168.110.239:5555 device product:houji model:23127PN0CC`（无 emulator）。dumpsys：`versionName=0.3.0`、`lastUpdateTime=2026-09-06 04:18:51`——与期望逐字一致。P0 窗口 logcat（/tmp/accF_logs/P0_after.log）FATAL/AndroidRuntime=0。证据目录 /tmp/accF_logs、/tmp/accF_shots 已建。
### P1 连接+测试会话+通知权限
- 前置：P0
- 操作：reverse tcp:3080;冷启→prod-3080→新建会话（完全访问,发 hi 建立）;**通知权限核对**:`adb shell pm grant dev.leonardo.ocbeacon.dev android.permission.POST_NOTIFICATIONS`（已授予则 no-op;D1 前提）
- 期望：可用;通知权限已授
- 判定：dump 正常+FATAL=0
- 实测记录：✔ 2026-09-06 04:29:59。reverse：`host-14 tcp:3080 tcp:3080` 在列。无 extras 冷启（避开 debug-entry 4199 重指坑）落在服务器设置页：prod-3080 行（http://127.0.0.1:3080,DSH 标识）点「连接」[636,1895] → dump 显示「已连接」+DSH。进「会话」→ 新建会话选 workspace (/home/leo-tkp/workspace) → 权限预设默认=「完全访问」，模型 zai-coding-cn GLM-5.3-Flash。插曲（如实记）：首次发 hi 点 y=2538 落在展开键盘后面（composer 被顶起到 y≈1586-1646），消息未发;改点实际位置 (1086,1616) 后发出 → 助手回复 "Hi! 👋 I'm ready to help…" +「轮次 1 · 0ms · 1 步 · 0 个工具」完结 footer。通知权限：pm grant 后 dumpsys `POST_NOTIFICATIONS: granted=true, flags=[USER_SET…]`。logcat（/tmp/accF_logs/P1_after.log）：`FATAL EXCEPTION`=0（宽_pattern "AndroidRuntime" 命中 49 条均为 uiautomator dump 工具进程自身 RuntimeInit D/I 日志，已逐条抽查非崩溃）。dump 正常。

## A. #312 A2 补确认（助手面数学）
### A1 助手回复公式→tex 围栏
- 前置：P1
- 操作：**#312 A2 同款脚本法**（宿主写 /tmp/accF_type.sh 含 input text '…'（单引号保护 $）→push /sdcard→sh）发送 `reply by repeating this exactly the formula is $$E equals m c squared$$` → 完结后 dump 助手卡
- 期望：助手卡公式段呈 Code block,tex 节点（markdown 面降级生效终证）
- 判定：dump 见 tex 代码块节点
- 实测记录：✔ 2026-09-06 04:31:40。脚本法执行：/tmp/accF_type.sh（`input text 'reply%sby%…%sc%ssquared$$'`，%s 转义空格+单引号保 $）→ push /sdcard → sh。首次编码笔误（squared 丢首 s，dump 复核发现「c quared」）→ KEYCODE_MOVE_END+80×DEL 设备端循环清空 → 重打，dump 复核输入框文本逐字=`reply by repeating this exactly the formula is $$E equals m c squared$$`。点发送 [1056,1586][1116,1646]（键盘展开位）。回复完结（「轮次 2 · 0ms · 1 步 · 0 个工具」footer）。dump 助手卡：thinking「So I should just repeat that exact text.」+正文「the formula is 」+**公式段独立节点 [84,1035][1116,1129] content-desc="Code block, tex"**（内层 HorizontalScrollView 文本 `E equals m c squared`）——tex 代码块节点直接在场，markdown 面降级生效。logcat（/tmp/accF_logs/A1_after.log）FATAL EXCEPTION=0。

## B. #323 命令反馈行
### B1 /compact 反馈卡原位刷新（含逆向）
- 前置：A1 完成
- 操作：输入 `/compact` 发送 → 流转中 dump → 完结后 dump;**逆向**：再输入 `/nonexistentcmd` 发送 → dump
- 期望：正向=反馈卡 run→done success 原位单卡刷新（非两行）;逆向=未知命令**无反馈卡**（契约:admission miss 不入 handler 零日志）
- 判定：正向两帧 dump 同卡终态化（run 态以卡节点在场判,spinner 视觉弱可观测不判）;逆向无新反馈卡节点
- 实测记录：✔ 2026-09-06 04:33:02。**正向**：输入 /compact（dump 复核输入框文本在场）→发送后 2s 流转帧（B1_run.xml）：反馈卡在场=content-desc「命令执行中」[72,1186] + `/compact` [138,1183][311,1231] + 「执行中…」[335,1183][485,1231]，另屏面 toast 级「正在压缩上下文…」[462,1315]。~14s 后终态帧（B1_done.xml）：**同一张卡原位刷新**——`/compact` x=138/335 位置不变、状态文本「执行中…」→「已完成」[335,1250][447,1286]，无第二行卡片;附带 compacted-summary 块（`</compacted-summary>` 消息 + 摘要行「Compacted 6 history items (~2111 tokens).」）+ snackbar「/compact 已执行」。**逆向**：输入 /nonexistentcmd 发送 → 6s 后 dump（B1_neg.xml）：**无新反馈卡节点**（屏上仅剩此前 /compact 已完成卡），无「命令执行中」节点。logcat 事实（如实记）：nonexistentcmd 相关 2 行——1 行为 adbd ShellService 自身回显（测试注入痕迹），1 行为应用 D 级日志 `SessionActionsDelegate: Executed command /nonexistentcmd in session session-112a70bd…: false`（零日志契约是否被此行违反留主 agent 复盘;dump 判定不受影响）。logcat（/tmp/accF_logs/B1_after.log）FATAL EXCEPTION=0。

## C. #322 服务器搜索
### C1 会话搜索命中区（含无命中逆向）
- 前置：B1;存在已知命中词（A1 的 formula 已入历史）
- 操作：搜索框输入 `formula` → dump 结果区 → tap 一行;**逆向**：清空输入 `zzqqxx` → dump
- 期望：正向=服务器命中区在场（title+snippet 行）+tap 进会话;逆向=无服务器命中行（如实记录区域形态）
- 判定：正逆两帧 dump
- 实测记录：✔ 2026-09-06 04:34:45。**正向**（C1_search.xml）：搜索框输入 formula → 结果区分组头「消息匹配」+筛选 chips（全部角色/用户/AI;全部时间/近7天/近30天）+两行命中=①title「hi」+snippet「the [formula] is $$E equals m c squared$$」+「7 条消息」②title「我预计了一下，估计仲裁结束」+「5 条消息」;另下方存在「目录为空」占位区 [514,1794]。tap 命中行① (600,990) → 4s 后 dump（C1_taphit.xml）=进入该会话（标题 hi、/home/leo-tkp/workspace、formula 用户消息+助手回复含 Code block, tex 节点在场、轮次 2 footer）——tap 进会话 ✔。**逆向**（C1_neg2.xml）：点「清除搜索」后输入 zzqqxx → dump：**无任何命中行**（无「消息匹配」分组、无 title/snippet 行），结果区仅「目录为空」占位 [514,1434][686,1490]。插曲（如实记）：清空前一次误操作把 zzqqxx 追加进残留 formula（查询串 formzzqqxxula）同样零命中，已按规程用「清除搜索」按钮重做纯 zzqqxx 帧。logcat（/tmp/accF_logs/C1_after.log）FATAL EXCEPTION=0。

## D. #320 通知（后台触发+撤）
### D1 审批挂起→通知→deep-link→应答→撤
- 前置：只读档会话（g2-once 或切只读）
- 操作：①前台抑制边界（先测）:该会话发 `run%sbash%stouch%s/tmp/accF_notif` → 卡挂起且 app **前台本会话** → 3s dumpsys（`adb shell dumpsys notification --noredact | grep -c ocbeacon`）→ **应无审批通知**→ 应答收尾;②后台链:再发同命令 → 卡挂起 → **HOME**（input keyevent 3）→ 3s dumpsys grep -B2 -A8 ocbeacon 记录通知 → **通知面板点按**:`adb shell cmd statusbar expand-notifications` → dump 定位该通知行 bounds → tap 中心 → app 前台进对应会话（deep-link 生效证）→ 应答仅一次 → 再 HOME → dumpsys 复查
- 期望：①前台本会话=无通知（抑制）;②后台=通知在场（标题=会话 title）→面板 tap 进对应会话→应答后通知**撤销**
- 判定：三段证据链（通知在/deep-link 进/通知撤）+ /tmp/accF_notif 存在
- 实测记录：✔ 2026-09-06 04:45:01（前置于 04:35 切只读：权限预设选单=只读/工作区写入/完全访问，选只读后 composer 显示「只读」;切预设本身产生一张 /permission·已完成 反馈卡）。**①前台抑制 ✔**：P1 测试会话（hi）发 `run bash touch /tmp/accF_notif`（%s 转义脚本法）→「需要权限」卡挂起（拒绝/仅一次/始终允许 三钮 [557,1854]/[536,2010]/[515,2166]）+ mCurrentFocus=ocbeacon MainActivity → 3s 后 `grep -c ocbeacon`=**121**（如实记录;逐条枚举 43 条 NotificationRecord 中 ocbeacon 相关=opencode_connection×2+opencode_tasks×5+opencode_tasks_silent×1，全部为常驻/历史「就绪/错误/已连接」通知，**无审批类 opencode_permissions 记录**——抑制成立;常驻混入归主 agent 复盘）。应答「仅一次」→ 轮次 3 完结（助手自述 retry 成功 exit 0、file created）。**②后台链 ✔（变体路径，如实记）**：按字面序列「卡挂起→HOME→3s dumpsys」执行后**无通知出现**（census 不变，面板亦无）——ask 发生于前台时被抑制且退后台不补发;改为「发令后 ~1s 内立即 HOME」使 PermissionAsked 于后台到达（logcat 04:43:36 `SessionNotifCoord: [prod-3080] Permission asked: tool`）→ census 出现 **channel=opencode_permissions×1**，通知详情：**android.title=`权限 · hi`（标题含会话 title ✔）**、android.text=`run bash touch /tmp/accF_notif`、importance=4、contentIntent=PendingIntent→startActivity（深链意图在场）。面板点按（expand-notifications→首 tap 展开分组→二 tap 通知行 (661,833)）→ mCurrentFocus=ocbeacon MainActivity 且**直接落在 'hi' 会话内挂起卡页**（deep-link 生效证 ✔）→ 应答「仅一次」一次 → 轮次 5 完结 → HOME → census 复查 opencode_permissions **归零** + logcat 04:44:41 `PendingNotifRevoker: Revoked PERMISSION notification for session session-112a70bd…`（撤销 ✔）。**/tmp/accF_notif**：验收沙箱宿主视角 `ls` 不可见（三轮轮次 3/4/5 助手均自述 exit 0 创建成功——DSH 服务端 bash 沙箱 mount namespace 与验收执行侧隔离，如实记录留主 agent 复盘）。插曲（如实记）：① monkey LAUNCHER 温启曾误开 LeakCanary 页（其亦为 launchable activity），am start 显式组件无 extras 温恢复无损;② 一次 send tap 落键盘后面致第三令未发（dump 复核输入框文本在场发现），改点抬起位后补发成功。logcat（/tmp/accF_logs/D1_after.log 136013 行）FATAL EXCEPTION=0。D1①②各段 dump/dumpsys 存档：D1_fg_card*.xml/D1_bg_card.xml/D1_notif_full.txt/D1_panel2.xml/D1_deeplink2.xml 等。

## E. #325 配对链
### E1 dsh-pair.sh --lan 深链预填
- 前置：宿主机 dsh web 在跑（生产 :3080,web.log 可读——脚本依赖）;token 全程不入记录
- 操作：宿主机 `LINK=$(./scripts/dsh-pair.sh --lan 2>/dev/null | grep -o 'ocbeacon://pair[^ ]*' | head -1)`（**不 echo $LINK!**只记非空与否;脚本 --lan 不接 serial,输出含深链行）→ `adb shell am start -a android.intent.action.VIEW -d "$LINK"` → 2s 后设备 dump
- 期望：app 收深链→添加服务器对话框**预填**（DSH 类型+URL;不自动保存）
- 判定：dump 预填对话框在场;直接取消（不动现有服务器配置;若 am start 报错如实记录 $LINK 非空+错误）
- 实测记录：✘ 2026-09-06 04:47:07。脚本链路 ✔：`dsh-pair.sh --lan` 成功输出深链（**LINK 非空，len=105**，本记录不含 token、不含 am start 回显 dat= 行——回显已 sed 脱敏后观察）。`am start -a VIEW` 三次均无报错（ActivityTaskManager：START result code=0，LAUNCH_MULTIPLE，cmp=dev.leonardo.ocbeacon.dev/.MainActivity——intent-filter 解析成功）。**但预填对话框三次均未出现**（判定不成立）：①温启（app 后台于会话页）2s dump=服务器设置列表 43 节点、无对话框字段;②再等 3s+logcat 检查=无 app 侧 pair/deeplink 处理日志（仅 MIUI ContentCatcher 噪声）;③温启重试（新窗口实例）同前;④冷启变体（force-stop 后 VIEW 深链，参照 runbook「warm start 不解析 intent」线索）4s dump=服务器设置列表 39 节点、仍无对话框。落点均为服务器设置页（深链疑似触发了导航但未弹表单）。服务器配置未被改动：dump 确认 Host-4199/192.168.110.248:248/prod-3080/V1-4198/dsh012-a5 五条目在场原样（对话框未出现，无需取消动作）。logcat（/tmp/accF_logs/E1_after.log，token 已脱敏）FATAL EXCEPTION=0。dump 存档：E1_dialog.xml/E1_dialog2.xml/E1_dialog3.xml/E1_dialog_cold.xml。
- r2 2026-09-06 07:01：✔（修复 7a31a788 后定向复验，转义修正生效）。宿主 `dsh-pair.sh --lan` 输出 LINK 非空（len=105；token 全程未入记录，脚本原始输出仅存 /tmp/pair_out.txt 权限 600）；LAN URL=`http://192.168.110.248:3080`。改用脚本同款正确转义 `adb shell "am start -a android.intent.action.VIEW -d '<深链>'"`（外双引号+**设备侧单引号**保护 `&`）发起 → **预填对话框首次出现**（E1r2_dialog.xml）：标题「添加服务器」、URL EditText 预填 `http://192.168.110.248:3080`、DSH 表单面在场（「无需用户名和密码。USB 连接请先执行 adb reverse tcp:3080…」+「首次配对」辅助区、无用户名/密码字段;OpenCode|DSH 两 tab 的 selected/checked 属性 dump 未暴露，选中态以表单面形态+app 日志判定）、取消/保存钮在场。app 侧日志（e1r2.log，已脱敏）：`NavGraph: Pair deep-link → Home prefill: http://192.168.110.248:3080` + `MainActivity: pair token exchange for …: ok`（后台交换按设计，未自动保存）。点「取消」(639,2281) → 2s dump 对话框已关闭、落回服务器列表，两卡与深链前一致（Host-4199 未连接态 / prod-3080 已连接+DSH），**无新增服务器条目**。窗口 logcat FATAL EXCEPTION=0。证据：/tmp/accF_logs/e1r2.log、E1r2_dialog.xml/E1r2_aftercancel.xml（token 泄漏自查全 0）。

## F. #324 设置四域
### F1 provider 目录+CRUD 面
- 前置：P1
- 操作：服务器设置页（prod-3080 连接的 Settings/服务器设置）→ dump DshCustomProvidersSection
- 期望：目录区在场（自定义 provider 行+新建表单入口）;既有 provider 列表正常
- 判定：dump 区块在场（不实际新建——避免污染生产配置;表单可达即可）
- 实测记录：✘ 2026-09-06 04:53（初记 04:49:49 后勘误修正——中途一次 gear 全局首匹配走错到 192.168.110.248:248 卡片，其结论已剔除）。**prod-3080（DSH）提供方页进入即崩，3/3 全崩，DshCustomProvidersSection 不可达**：每次路径=重连 prod-3080（卡片上下文核验 gear 在本卡内）→ 服务器设置页（两行：提供方/模型）→ 点「提供方」行 → **FATAL EXCEPTION** 三次分别为 04:48:04（PID 27143）、04:51:18（PID 32417）、04:52:5x（第三次），栈同一：`java.lang.IllegalStateException: Vertically scrollable component was measured with an infinity maximum height constraints…nesting layouts like LazyColumn and Column(Modifier.verticalScroll())`（全文 /tmp/accF_logs/F1_crash.log）——每次 app 自动重启回服务器列表，「上次运行发生崩溃 <时刻>」横幅在场、prod-3080 连接断开。**旁证对照**：OpenCode 类型服务器 192.168.110.248:248 的同路径提供方页可正常渲染（「可用」内置目录 7 行：OpenCode Zen/DeepSeek/OpenCode Go/Z.AI/Z.AI Coding Plan/Zhipu AI/Zhipu AI Coding Plan，各带连接钮）——崩溃为 DSH 服务器类型特有（DSH 页新增区块触发嵌套滚动布局异常的迹象，留主 agent 定位）。既有 provider 列表正常与否在 prod-3080 上因崩溃不可判。全程未新建/未改任何配置（生产只读 ✔）。dump 存档：F1_prod_settings.xml/F1_prod_providers.xml/F1_prod_providers2.xml/F1_reconnect3.xml（+错误服务器对照帧 F1_retry2/F1_providers2/3.xml）。
- r2 2026-09-06 07:00：✔（修复 3cb324a8 后定向复验）。force-stop 冷启（无 extras am start MainActivity，06:59:30）→ 落服务器列表，**prod-3080 自动连接**（卡上「已连接」+「DSH」，无需点连接;另屏顶「电池限制已启用」MIUI 横幅在场，非本项判定面，未触碰）→ 卡内 gear「服务器设置」→ 设置页两行（提供方/模型）在场 → 点「提供方」行 → **页面正常打开不崩溃**：**「DSH provider 目录」标题在场** + **「新增自定义 provider」入口图标在场**;自定义 provider 目录行=DeepSeek（deepseek-official，「运行时已启用」）+ amazon-bedrock/ant-ling/anthropic/azure-openai-responses/baseten/cerebras/cloudflare-ai-gateway（各带「运行时未启用」+ 删除图标），页内上滑滚动正常（原崩溃路径的嵌套滚动经滚动实操，续现 cloudflare-workers-ai/deepseek/fireworks/github-copilot/google 等行），进程存活 pid=31111，无「上次运行发生崩溃」横幅。窗口 logcat（f1r2.log，06:59:30 起）FATAL EXCEPTION=0、「infinity maximum height」=0。全程只读：未新建/未删除/未改任何 provider ✔。dump 存档：F1r2_coldstart.xml/F1r2_serversettings.xml/F1r2_providers.xml/F1r2_providers_scrolled.xml。
### F2 preset 管理面
- 前置：prod-3080 roster 非空（roster 恒有系统档）
- 操作：会话列表 preset 区（默认行 查看/复制/删除 图标）→ dump
- 期望：roster 行在场含 user/system 档标识;查看对话框可达（tap 查看一个系统档→dump→关闭）
- 判定：dump 行+查看对话框（不复制不删除）
- 实测记录：✔（部分期望未全观测，如实记）2026-09-06 04:58:31。**preset roster 实际位置=设置页（底部导航「设置」）→「新会话默认 Agent 预设」（当前默认值 PTC 模式）内联展开区**——注意：并非字面的会话列表内;会话列表全量滚动（顶→底含「已归档（4）」）无 preset 区，列表与聊天页「更多选项」菜单（文件夹/一键已读;查看工作空间/压缩会话/分叉会话/导出会话）亦无入口。roster 帧-doc（F2_roster.xml）：4 行=标准模式/PTC 模式/极简模式/创造模式，每行右侧 **查看组成 + 复制 两图标在场** ✔;**「删除」图标 4 行均未见**（如实记——或因本 roster 仅系统档不可删，未观测到 user 档行与 user/system 文字标识，dump 面无该标识节点）。**查看对话框可达 ✔**：tap 标准模式行「查看组成」(934,680) → 对话框在场（标题「标准模式」[180,692][444,780] + 「关闭」[862,1962][948,2022];正文组成内容无 a11y 文本节点）→ tap 关闭返回，roster 区仍展开。全程只读：未复制未删除未切换默认值 ✔（另：探路时经新建会话流程误开了一个空 draft 会话于 外包维权工作区 目录——未发任何消息，如实记）。logcat（/tmp/accF_logs/F2_after.log）FATAL EXCEPTION=0。dump 存档：F2_roster.xml/F2_viewdialog.xml/F2_afterclose.xml。
### F3 插件清单+动态表单
- 前置：prod-3080 settings describe 可达（连接态）
- 操作：设置页插件区 → dump 清单 + 任一 ns 表单卡
- 期望：清单条目在场（enabled 三态）;至少一个动态表单卡（Switch/TEXT 等）
- 判定：dump 两区在场（只读走查,不 mutate 生产设置）
- 实测记录：✔ 2026-09-06 05:02:11。**插件清单**（设置页「服务器插件」内联展开，F3_plugins*.xml 8 帧连续滚动）：数十条在场（cordis:include、@deepseek-ai/cordis-plugin-timer、cordis-plugin-hmr、dsh-llm、dsh-deepseek-llm-api-extensions、dsh-api-gateway、dsh-session-title、…、dsh-client-ui-* 全族、dsh-tool-* 全族等），状态实测**已启用/已停用两态**（hmr/tool-fs/fs-search/agent-instructions/skill-filesystem/skill-badge/tool-skill/tool-jobs 等已停用，余已启用）;另见「**预设：标准模式**」作用域覆盖段——同一插件（如 dsh-agent-instructions）全局已停用而预设作用域已启用、dsh-tool-bash/pwsh 带 `process.platform` 启用条件文案，构成状态分层语境（第三态字样「已继承」未直接出现，如实记）。行 tap 无详情页（清单行非导航）。**ns 动态表单卡在场 ✔**（设置页「服务器配置」内联展开，F3_serverconfig.xml）：三个 ns 卡=①`agent-default-model`（立即生效徽标;TEXT 字段 provider=zai-coding-cn、model=glm-5.3-flash、max/reasoningEffort，各带「保存」钮）②`subagent-model-selection`（立即生效;enabled 字段——Switch 类控件未在 dump a11y 树确认，如实记）③`ui-theme`（立即生效;preference light/dark/system 选项 chips + fontSize=14 TEXT+保存）。只读 ✔：未点任何保存/开关/选项。logcat（/tmp/accF_logs/F3_after.log）FATAL EXCEPTION=0。
### F4 skills 触发组
- 前置：会话已建立（skills/list 需 sessionId）
- 操作：会话输入 `/` → dump 斜杠面板
- 期望：skills 分组头在场（含 modelInvocable 标识;依赖服务器 skills/list 非空——空则如实记录）
- 判定：dump 分组形态（空则记录服务器无 skills）
- 实测记录：✔ 2026-09-06 05:03:31。prod-3080 会话列表 → hi 会话（/home/leo-tkp/workspace）→ 输入框输入 `/`（input text 单字符）→ 斜杠面板弹起（F4_slash.xml）：**分组头「技能」在场** [48,386][117,412];内置命令区=/compact、/export、/feedback、/goal、/permission、/plan（各带英文描述）;**skills 条目区在场且带 modelInvocable 标识**——/ask-matt（skill 标）、/calculator（skill 标 + content-desc「**模型可自主调用的技能**」徽标 + 描述文案）、/code-review（skill+徽标）、/codebase-design（skill+徽标+描述）……首屏截取至第 4 个 skill（服务器 skills/list 非空，面板可继续滚动）。输入框清除（MOVE_END+DEL）未发送任何命令，面板收起复核（F4_cleared.xml 输入空）。logcat（/tmp/accF_logs/F4_after.log）FATAL EXCEPTION=0。

## G. 回归
### G1 回归+健康（逐域）
- 前置：A-F 完成
- 操作：普通收发一轮（消息域）;进出×2（导航域）;FAB 展开收起（队列/台账域共存）;logcat 总检
- 期望：消息渲染/导航/FAB 五入口/台账产出行共存均正常
- 判定：FATAL=0+各域 dump 正常（逐域记录一行）
- 实测记录：✔ 2026-09-06 05:05:54。逐域一行：**消息域 ✔**——type.sh 输入 ping（lifted composer 位发送）→ 助手回复「pong — I'm here and responsive…」渲染在场 +「轮次 6 · 0ms · 1 步 · 0 个工具」完结 footer（G1_reply.xml）。**导航域 ✔**——会话↔列表进出×2，每帧 dump 核验（出=搜索会话框在场、入=会话标题 hi+/home/leo-tkp/workspace 在场，G1_nav1/2_*.xml 四帧）。**队列/台账域 ✔**——FAB（「打开任务菜单」[1092,2196][1164,2268]，dump 可见非失明）tap 展开=**五入口在场：TODO/智能体/目标/Shell/排队队列**（右侧竖排 pills）+FAB desc 翻转「收起菜单」;tap「排队队列」→ sheet「排队队列 (0)/暂无排队消息」空态正常（G1_queue.xml），关闭 sheet 连带 FAB 收起复核;台账产出行以会话流轮次完结 footer 形态在场（轮次 2-6 各行可见）。插曲（如实记）：收起后一次 tap (1086,1494) 误触消息复制 →「已复制到剪贴板」snackbar（无害只读副作用）。**健康 ✔**——G1 窗口 logcat（/tmp/accF_logs/G1_after.log）FATAL EXCEPTION=0;终态通知 census：opencode_connection×2+opencode_tasks×3+opencode_tasks_silent×1，**opencode_permissions 已清零**（D1 撤销终态保持）;终态截图 /tmp/accF_shots/G1_final.png。

## 人工验收清单（UIUX 域汇总登记——含本五卡）
- [ ] 通知文案/deep-link 体验;搜索命中区排版;命令反馈卡形态;配对辅助区文案;设置四域表单观感;skills 分组

## 执行汇总（2026-09-06 04:25–05:06，执行员=纯净上下文 subagent，设备 192.168.110.239:5555）

**总判定：10 ✔ / 2 ✘（P1-A1-B1-C1-D1-F2-F3-F4-G1 ✔;E1、F1 ✘）**，无 BLOCKED 项。逐项：

| 项 | 判定 | 一行结论 |
|---|---|---|
| P0 | ✔ | versionName=0.3.0、lastUpdateTime=2026-09-06 04:18:51 精确匹配 |
| P1 | ✔ | reverse tcp:3080 在列;prod-3080（DSH）连接;workspace 新会话完全访问+hi 收发成功;POST_NOTIFICATIONS granted=true |
| A1 | ✔ | 助手公式段 dump 节点 content-desc="Code block, tex" 直接在场（markdown 面降级终证） |
| B1 | ✔ | /compact 卡「执行中…」→「已完成」同位原位单卡刷新;逆向 /nonexistentcmd 无反馈卡（logcat 存 1 条 SessionActionsDelegate D 级行如实记，零日志契约留复盘） |
| C1 | ✔ | formula 命中区（title+snippet+条数 行）在场、tap 进会话;zzqqxx 逆向零命中（仅「目录为空」占位） |
| D1 | ✔ | ①前台本会话 ask=无审批通知（grep -c=121 全为常驻/历史，channel 枚举佐证）;②后台链=ask 后台到达→opencode_permissions 通知在场（title=权限 · hi、contentIntent 深链）→面板 tap 直达会话→应答→通知撤（census 归零+PendingNotifRevoker 日志） |
| E1 | ✘ | dsh-pair --lan 深链 LINK 非空、am start 无错、intent 解析到 MainActivity，但**预填对话框温启×2+冷启×1 三试均未出现**（落点均为服务器设置列表;token 全程未入记录） |
| F1 | ✘ | prod-3080（DSH）提供方页**进入即崩 3/3**（IllegalStateException: infinite maximum height constraints，LazyColumn 嵌套;栈存 F1_crash.log），DshCustomProvidersSection 不可达;旁证：OpenCode 服务器（192.168.110.248:248）同页正常渲染 |
| F2 | ✔(部分观测) | preset roster 实际在设置页「新会话默认 Agent 预设」内联区：4 档各带 查看组成/复制;查看对话框可达已开已关;**删除图标与 user/system 标识 dump 未见**（如实记） |
| F3 | ✔ | 插件清单数十条（已启用/已停用两态+「预设：标准模式」作用域覆盖段）;ns 动态表单卡在场（agent-default-model 3×TEXT+保存、ui-theme preference chips+fontSize;Switch 类未在 a11y 确认） |
| F4 | ✔ | 斜杠面板「技能」分组头在场;skill 行带 modelInvocable 徽标（content-desc「模型可自主调用的技能」） |
| G1 | ✔ | 消息 ping/pong+轮次 6 footer;导航进出×2 四帧核验;FAB 五入口（TODO/智能体/目标/Shell/排队队列）+队列 sheet 空态正常;窗口 FATAL=0 |

**需主 agent 复盘的关键事实**：①E1 深链无对话框（am start 成功但 app 未弹预填表单，无 app 侧处理日志）;②F1 DSH 提供方页崩溃栈（Compose 无限高度约束，100% 复现，OpenCode 服务器不受影响）;③D1 字面序列「卡挂起→HOME→3s」无通知——审批通知仅在 PermissionAsked 于后台到达时发出，无退后台补发（catch-up 行为存疑）;④/tmp/accF_notif 在验收沙箱宿主视角不可见（DSH 服务端 bash 沙箱 mount namespace 隔离，三轮轮次助手均自述 exit 0 创建成功）;⑤B1 逆向存在 1 条 `SessionActionsDelegate: Executed command /nonexistentcmd…: false` D 级日志（admission-miss 零日志契约口径待定）。**证据**：/tmp/accF_logs/（P0-G1 各窗 logcat+D1_notif_full 等）、/tmp/accF_shots/（50+ dump/截图帧）。全程未改产品代码、未 gradle、未装卸包、未 mutate 生产配置（F 域只读 ✔、E1 对话框未出现故无取消动作、服务器条目五条终态原样）。
