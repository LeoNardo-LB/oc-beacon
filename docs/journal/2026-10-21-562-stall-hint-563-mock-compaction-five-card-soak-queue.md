# 562 stall hint + 563 mock compaction + five-card soak queue（2026-10-21）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #563 mock compaction 剧本（装置完成）

- mock_v2.py 新增 MOCK_COMPACT=ok|fail|hold 三模式（#539 现网 2.0.26 实录 wire 形态：started{reason,inputID,recent}→delta{text}×N→ended{text 全文,recent}/failed{error.message}；ended 后历史替换=末条 user+type=compaction 摘要消息）+ /api/provider+/api/model 端点（压缩前置：SessionActionsDelegate 需 selectedProvider/ModelId 非空——空模型列表时「压缩会话」点击即拒，首轮 FAIL 定罪）+ Mock.start 改按端口定向清孤儿（旧全路径 pkill 会误杀并行域实例，soak 队列在跑时实测风险）。
- runner.py 新增 scenario_563/563_fail/563_hold（_tap_chat_menu 打开 ChatTopBar 更多菜单点「压缩会话」；断言链=进行中横幅「正在压缩上下文」→「上下文已压缩」分割线卡→mock compacts 计数；fail=「会话压缩失败」snackbar+横幅终结；hold=横幅 12s 驻留+无终态卡）。
- 验证（真机 192.168.1.110:44569，端口 4499 与 soak 4399 域隔离）：**三剧本全 PASS**（ok=横幅+分割线卡+compacts=1；fail=snackbar+横幅不残留；hold=驻留+无终态）+ 宿主纯 curl 冒烟三模式事件族/历史替换/计数全对齐。rounds 证据 e2e/runs/20261021-14*（三目录 dump/mock_state 齐备）。
- 首轮 FAIL 教训：mock 无模型端点→选择器空→压缩前置拒绝→零 HTTP（dump 全空 text 是表象）；mock 域补端点即愈。

## #562 会话停滞降级提示（实现+真机验证）

- 实现（commit 07a0edb3）：evaluateStalled 纯函数（Busy && now-lastEventAt>STALL_HINT_MS 120s && 非 #191 waitingFresh 打标）挂 SessionStateService L2 staleness tick（5s）派生 _stalledSessions StateFlow——单一真相源旁路，不写 FSM，zombie interrupt/waiting 判定路径零改动；链路 stalledSessions(repo) → ChatViewModel.stalledCurrent(combine sessionIdFlow) → ChatScreenBottomBar → ChatInputBar 上方 StallHintBanner（ShellModeHintBanner 同款视觉语言，tertiary 色 HourglassEmpty）——ChatScreen.kt 零触碰（编辑协议未触发）。
- 阈值锚点：正常流式事件间隔≪秒级、标题生成静默 #553 干净样本 ~9s ≪ 120s ≪ ZOMBIE_BUSY_MS 3min（提示先于僵尸中断）。
- 验证四层全绿：①单测 7 用例（三分支语义边界+waiting 窗过期回归+他会话打标隔离+混合集默认阈值锚点）②lint exit 0 ③i18n-check 890 keys×14 lang（意语撇号转义坑：aapt 报行号是 merged 缓存行号非源行，od -c 字节级定罪 `po\.'` 反斜杠错位）④真机双场景（4096 reverse 域 4499）：A 正例=MOCK_NO_END 悬停→横幅于发送后 137s 出现（预期 123-135s 窗内，tick 粒度 5s 内符合）→点「停止」→Idle→横幅消失 PASS；B 负例=正常流+完成后 90s 静默零误报 PASS。证据 /tmp/562-verify/（dump-a-banner.xml+mock.log）。
- 停滞恢复语义验证点：banner 出现后事件恢复（如 SSE 恢复流式）≤5s 出集自动消失——由 tick 全量重估设计保证（单测「混合会话集」锚定），真机 A 场景以「停止→消失」等价验证状态转移清除路径。

## 五卡观察 soak 队列总收（用户 10-21 委托：5 卡各一 subagent≥5h 模拟器复现尝试；串行门 454→550→486→464→553，2026-10-21 13:44 派发 → 10-22 16:21 全收官，累计 ~26.5h）

- **总结果表**：#454（720 轮 0 MISS 未复现，局限=硬键流未覆盖软键盘 IME 路径/busy 时序未构造）；#550（**真 HIT=非空栈型组合内容真空**，五证链+配方复现 3/3=100%「giant 流完成→下一 turn ≤40s 渐进真空」，#545 姐妹形态守护盲区，升格建议待用户裁决）；#486（360 轮 81 变体×4+遍零 HIT，负 d 谱 -280px 单值零方差，盲区收窄实证+复发判读锚点沉淀）；#464（487 轮 159 tap 断言全 ok 零 MISS）；#553（giant 150/150 残留全 <2s+hold 对照 36 驻留 36 恢复，零 HIT）。报告均在 /tmp/soak-<N>/FINAL-REPORT.md（tmp 清理风险：精华已提取入卡与本节）。
- **队列纪律沉淀**（首晚门事故链的根修成果）：串行门=文件信号+ORDER 声明；DONE 门禁=实跑≥17800s 才写/崩溃写 CRASH/宿主仲裁例外；门谓词=曾缺席后出现+mtime 判据（防陈旧标记）+60s 双检；编排=flock 单实例+断点续跑（T0 墙钟锚定）+mock health 守卫+watchdog 独立守护（注意 epoch/相对秒不可比——#454 收口竞态教训）。
- **环境事故编年**：①13:47 门连锁抢跑（#486 误仲裁→垃圾 DONE 三卡互污→宿主强杀重排 454 首位）；②14:32 systemd-oomd 杀 gnome-shell（gnome 桌面闪断似重启；qemu 死亡/模拟器无头恢复；setsid 编排大多幸存——教训=宿主通报须逐进程验尸，/proc starttime 反算法）；③21:05 模拟器 a11y dump 层挂死（真空 app 高频交互诱因嫌疑；重启恢复需 boot+90s 稳定期；队列级预警=dump 空≠屏幕空）；④10:31 装包 VERSION_DOWNGRADE（时间戳 versionCode 与快照错位，-d 修复）。
- **harness 回灌**（commit a16b337ef）：open_session 节点中心 tap（固定 x=300 落非可点区）/SEND_DIAG 自引用拼接 bug/Mock.start 自建 workdir（三卡同踩首启崩根因）。
- **#550 HIT 的判读边界**（升格裁决依据）：mock giant 流形态与真实服务器等价性未证；模拟器 AOSP 与真机 MIUI 差异；程序化 force-stop/BACK 连环在诱因链中（但 R#35 干净环境样本削弱此怀疑）；真机 10h 浸泡+beta-12 日常零复发与 mock 复现的解释=流形态差异+每轮复位消灭真空窗。若升格，根修前取证项=流形态等价性+真空起点定位（流完成后释放路径嫌疑域）。
- **内存纪律**：13Gi 宿主承载单模拟器+soak 期间禁止 gradle 并发（14:32 事故根因）；模拟器无头模式（-no-window）为长跑稳定形态。

## #550 升格裁决（2026-10-22 23:08 用户批准）

- 观察卡升格「已复现待根修（mock 配方域）」；证据链=soak 五证链 HIT+配方复现 3/3+干净环境样本；根修前取证项两项（流形态等价性+真空起点定位）已入卡。

## 已完结卡片迁入（2026-10-23）

### **#563 mock_v2 compaction 剧本装置（E2E 离线覆盖 compaction 域回归）** `e2e` `infra`
  - mock_v2.py 现零 compaction（#543 验收被迫走真机真服务器）；#539 已消费三档事件（started/delta(text)/ended(text+recent)+failed），app 侧实现齐备可测
  - 补 MOCK compaction 场景：POST /api/session/:id/compact 触发 SSE compaction 事件族（复刻 4096 实测 wire 形态）；纯装置零 app 风险，验证=模拟器 E2E 消费链
  - 2026-10-21 完成并验证（真机三剧本 ok/fail/hold 全 PASS，journal §#563）：compact 事件族+历史替换+provider/model 端点+E2E 三场景+E2E 并行安全（Mock.start 定向清孤儿）。装置收益：compaction 域回归离线可测（不再依赖 4096 真服务器）；hold 模式复用为 #562 停滞验证装置。等用户验收后迁移。
  - 迁入依据：装置卡 AI 自行验收（用户 2026-10-23 00:02 预授权）：三剧本×2 重复 6/6 PASS+边界 LINES=20 PASS/LINES=1 判读豁免（横幅 0.85s 窗<uiautomator 采样 1.8s 物理限制，终态双断言 PASS=消费链正常）+既有卡 544_acc/551 回归零破坏+真机三剧本 PASS（昨日）；compaction 域回归离线可测达成（backlog.sh migrate 2026-10-23）

### **#562 会话 Busy 且长时间无 SSE 事件的停滞降级提示（#553 附带体验优化，用户 2026-10-21 批准开工）** `v2` `ui` `sse`
  - #553 定罪服务器侧执行挂起（running 无事件无推进，app FSM 干净按 zombie 策略等待）——app 域无可修，但可做体验兜底：Busy 且 N 分钟无 SSE 事件时 UI 降级提示「连接可能已停滞」
  - 约束：SessionStateService 单一真相源纪律不动 FSM 语义，stall 为派生观察流；阈值保守（正常流式事件间隔≪秒级、标题生成静默窗几十秒）；验证走真机 4096
  - 2026-10-21 完成并验证（commit 07a0edb3；单测 7/lint/i18n/真机双场景全 PASS，journal §#562）：120s 停滞横幅+waiting 排除+5s 自愈消失；zombie/waiting 判定零改动。等用户验收后迁移。
  - 迁入依据：AI 自行验收（用户预授权）：真机双场景 PASS（昨日）+模拟器双场景×2 重复 PASS（正例 140s 出现/停止即消/负例 90s 零误报）+单测 7 fresh 全绿+lint exit 0+回归零破坏；#553 服务器侧挂起的 app 侧体验兜底语义达成；残余观察=真实使用中横幅频率（轻 UI 面已复刻 ShellModeHintBanner 既有语言，主观不适可后续微调）（backlog.sh migrate 2026-10-23）

### **#553 V2 长答案完成后「停止」按钮残留 ~2 分钟（疑标题生成保持 busy）——终验观察项** `v2` `ui` `sse`
  - 模拟器终验 r81 遗留观察：p2 长答案完成后停止按钮残留约 2 分钟才消失，非滚动/展开域违规；疑 V2 会话标题生成阶段持续置 busy 状态所致；复发时抓 SessionState FSM 状态流取证再定修向
  - 证据：/home/leonardo/.cache/540emu/FINAL-EMU-REPORT.md 遗留观察节
  - 2026-10-20 凌晨主动复测（模拟器+4096 v2 长答案 60s 流）：停止按钮残留实测 ~9s（t+2s 在场→t+9s 消失），非 ~2 分钟——疑已被后续批次顺带治愈（r81 观察后 ChatScreen 域动过 #547/#544）；FSM 时间线干净（SseIdle→Idle [force-complete] 一刀切），无 Title agent 保持 busy 迹象（服务器标题生成独立于会话 FSM）。观察阈值更新：残留 >15s 即复发信号，届时抓 FSM 流+dump 复测。取证 /tmp/553/{full,fsm,title}.log
  - 2026-10-21 真机复测腿+根因归属修正：本轮「停止按钮 42s+ 在场」定罪为**服务器侧执行挂起**（/api/session/active 显 running 7 分钟无推进无事件；app 侧 FSM 干净——L3 校验发现 server-Busy/无 SSE 矛盾后按 zombie 策略保守等待，停止按钮=Busy 正确反映；app 点停止即恢复，服务器 active 清空）。结合模拟器 9s 干净样本：**#553 现象主体=服务器侧 turn 状态收敛慢/挂起（provider 链无超时兜底，上游域），app 域无可修**（可选体验优化=无事件 N 分钟 UI 降级提示，另议）。观察维持，复发以 [SessionStateService] server says Busy but no SSE events 日志+curl /api/session/active 双证定界
  - 2026-10-22 模拟器 soak 5.3h（队列串行腿5，/tmp/soak-553/FINAL-REPORT.md）：giant 150/150 CLEAN **残留全部 <2s**（比 10-20 干净谱 ~9s 更快，分布退化单点零在场采样）+hold 对照 36 轮满 75s 驻留+tap-stop→gone 36/36——**HIT=0 未复现，维持 P3 观察**（与根因归属一致：现象主体=服务器侧收敛慢，app FSM 干净；mock 腿为 app 侧负证增厚，复发定界仍按卡内双证）。装包事故=INSTALL_FAILED_VERSION_DOWNGRADE（-d 降级装修复+坚持旧快照防 #562 停滞提示代码污染判读）；装置沉淀七条（hold 天然对照裁定法/MOCK_HOLD_OPEN 真实驱动变量/hold 轮恢复路径缺失等）
  - 迁入依据：观察收口（用户 2026-10-23 批准评估方案）：app 域无可修定罪成立（服务器侧执行挂起，/api/session/active running 7 分钟无推进双证+FSM 干净+点停止即恢复）+mock 负证增厚（队列腿5 giant 150/150 残留全 <2s+hold 对照 36/36 tap-stop 恢复）+体验兜底已交付（#562 停滞横幅=该场景 app 侧可达的语义终点）。复发时按卡内双证定界（server says Busy but no SSE events 日志+curl /api/session/active）；若复发频率高再议服务器侧上游反馈（backlog.sh migrate 2026-10-23）

### **#486 SSE retry 重建帧 watch（原#471④）：断连续传+滚出视口弃树+ever=false 理论盲区，复发再战勿主动开工** `scroll` `chat`
  - 原 #471④ 用户裁决 c 降级观察（v1+v2 四场景不可复现，331k 行取证 Loading=0，#472 hold/registry/preParsed 防御栈有效）；#471 整卡迁移后观察线独立成卡（spec §8：watch 不关）
  - 理论盲区：断连续传+滚出视口弃树重建+ever=false 帧（0 触发）；若复发，残余只剩 staged/换装差（-1330px ③族已消失）——spec §8 残余收窄注记
  - 判读取证：MDResize 负 d / RESETKEY / nonPrefix / rawTail 探针均保留 DEBUG-only（2026-09-30 用户裁决）
  - 2026-10-09 用户裁决：改「等复现」为 soak 长时主动狩猎→转 #535；本卡保留判读上下文。关键勘误：2026-09-30 实验 A/B 均未含「滚动离屏」第三要素（longcat 限流未跑成三联场景）——「0 触发」实为「0 试验」，见 journal 2026-09-30-471-4 §4.2
  - 2026-10-10 B2评估：根修（LRU账本wasStreamed+终态缓存LRU32，coldReveal单帧揭示）已落地并全验证——同内容冷重入0空窗（仪器化mainClock 0帧+模拟器/真机交互面）；两晚soak（3.6h+1h）零复发。残余理论面收窄为：流式中途销毁的部分内容缓存miss→coldReveal 1帧（设计底线）与主题切换重着色（已由skip守卫同一性判定保全）。无新线索→维持watch（复发再战），勿主动开工语义不变。
  - 2026-10-20 三联构造器备货+首跑（scripts/probe_486_sse_rebuild.py，借 harness 成熟原语 Mock/launch/open_session）：断流 pause 2.5s+流式卡滚出视口+恢复编排——2 轮实跑全家族探针触发（R2: MDResize×8/RESETKEY×1/nonPrefix×4/rawTail×1/452-display×2，证据 /tmp/486/20261020-025903/）——2026-09-30 勘误的「0 试验」盲区现有即战装置；复发时跑该脚本对照 #471 spec §8 判读（探针命中组合 vs 正常流式基线差分）。坑位入档：模拟器英文 UI 需双语言发送键匹配、mock 场景须 create_session
  - 2026-10-22 模拟器 soak 5h（队列串行腿3，/tmp/soak-486/FINAL-REPORT.md 终版七节）：360 轮 81 变体全覆盖×4+遍（断流 pause 2.5/8/20s×滚出 half/full/double×恢复 still/rollback/nextflow 三联矩阵+32 基线轮）**零 HIT**——机器判据五条全零（负 d 谱 112 个全部=-280px 单值零方差全来自 rollback 变体/RESETKEY 最大 1 每轮/无 452 崩塌/无 FATAL/无进程死）；探针谱与 2026-10-20 首跑基线同族（MDResize 8.6 每轮 vs 基线 8）。spec §8 判读：三联窗内重建帧确实发生（RESETKEY 112 次）但无异常组合，2026-09-30 勘误的「0 试验」盲区现有 328 轮三联实扫数据；-1330px ③族未回归。**判定：维持 watch，盲区收窄实证落袋**；新判读锚点沉淀（复发时对照谱形：MDResize≈8.6/轮、RESETKEY≤1/轮、负d=-280 单值、452 零崩塌——谱形偏离即升级信号）。事故链（误仲裁→无效收口→互污强杀→重跑）完整入报告 §1
  - 迁入依据：观察收口（用户 2026-10-23 批准）：三晚 soak 零复发证据链完整（B2 两晚+队列腿3 360 轮 81 变体×4+遍三联矩阵+32 基线）——机器判据五条全零（负 d 谱 -280px 单值零方差/RESETKEY≤1 每轮/无 452 崩塌/无 FATAL/无进程死）+根修防线（LRU 账本 wasStreamed+终态缓存）在位+理论残余=设计底线（流式中途销毁部分内容缓存 miss→coldReveal 1 帧）。谱形锚点已沉淀（MDResize≈8.6/轮、RESETKEY≤1、负d=-280 单值、452 零崩塌——偏离即升级信号），复发时跑 probe_486_sse_rebuild.py 对照（backlog.sh migrate 2026-10-23）

### **#464 UI 暖态下列表/卡片点击偶发失效(冷启可靠)** `chat-ui`
  - 2026-09-29 #461/#462 取证副产物:force-stop 冷启后输入 tap 可靠命中(会话行/卡标题),同一 app 暖运行数分钟后点击同坐标零效果(无日志无 UI 变化,vibrator 反馈存在=命中可点击元素但未触发业务);两次独立取证会话复现,冷启后恢复。疑点:点击消费被某 overlay/焦点态拦截或状态门;影响面=自动化测试可靠性,人工使用未报告。待真机复现窗定罪(diagnosing-bugs 流程),暂无用户主诉不阻塞。
  - 2026-09-29 审计顺带实证:一次拖动送达零响应不再现(见 #465 注记);#477 dump 全盲为坐标类判读新增干扰源——本卡与 #465 高度重复,建议合并为单卡观察项待裁决
  - 2026-09-29 用户裁决:吸收 #465 合并,本卡为该域唯一观察卡(暖态间歇 input 送达零业务响应;今日一次拖动零响应不再现实证在案;#477 dump 全盲为坐标判读新干扰源)——再撞上即现场抓 input dispatcher+app 双侧日志
  - 2026-10-09 用户裁决：转 #535 soak 主动狩猎（tap 效果断言+失配现场抓取 dumpsys input/logcat/meminfo；dump+截图双通道防 #477 dump 全盲假阳性）
  - 2026-10-10 B2评估：两晚soak探索tap共9起可疑全裁定良性（目标均为clickable语义但无动作的列表文本项——'7×15=105'等TextView，属harness注释预期的合法no-op误捕；InputDispatcher无吞证据）。原症状（暖态偶发点击失效）未再复现，无根因线索→维持观察；判读工具沉淀：dumpsys input消费记录+前后签名对比可复跑。
  - 2026-10-22 模拟器 soak 5.1h（队列串行腿4，/tmp/soak-464/FINAL-REPORT.md）：487 轮巡检 159 次 tap 断言全 ok **零 MISS**（T1 进会话/T2 思考卡展开收起翻转/T4 BACK+busy 窗 41 轮机会 tap 其中有动作 19）——原症状（暖态偶发点击零业务响应）未复现，观察维持（与 B2 评估两晚 soak 结论一致）。假阴性源全排除（态守卫仅 idle 断罪/新鲜 dump 坐标/失败重试 1 次再定罪）；Compose clickable=false 语义陷阱入坑位（会话行实为可点但 dump 语义否——no-op 防御须按业务动作断言非 clickable 语义，B2 教训实质化）。无效首跑勘误节+门谓词 break 缺失根因入报告
  - 迁入依据：观察收口（用户 2026-10-23 批准）：两轮 soak 零复发证据链完整（B2 两晚 9 起可疑全裁定良性 TextView no-op+队列腿4 487 轮 159 tap 断言全 ok 零 MISS，假阴性源全排除：态守卫仅 idle 断罪/新鲜 dump 坐标/失败重试再定罪）+判读工具沉淀（dumpsys input 消费记录+前后签名对比可复跑）。Compose clickable=false 语义陷阱与业务动作断言法已入坑位档案（backlog.sh migrate 2026-10-23）
