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
