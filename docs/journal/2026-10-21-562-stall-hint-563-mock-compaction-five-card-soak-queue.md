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
