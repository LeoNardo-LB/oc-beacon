# 435-height-engine-unification（2026-09-25）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #435 实现与验证

- 实现: StreamingGrowLedger/streamingGrowPairing(ScrollCompensation.kt 重写)+ChatMessageList 5 挂载点迁移+steady flush 贴底豁免+PreRenderShiftChannel/DeferredRevealCompensator/stream-instant 退役(净 -326 行)。spec: docs/specs/2026-09-25-435-height-engine-unification-design.md;调研三篇 docs/research/433-*。
- 单测: StreamingGrowLedgerTest 16 用例(规则四构型+账本协议),全量套件绿。
- 真机核心判决(GLM-5.3-Flash 会话,带 run_code,DSHProd):
  - GUARD reanchor = 0(震荡签名消失);视口全程钉底(零 atBot 翻转/零 LEAP)
  - SGR-435 drop(append/reading-away) fii=0 fiso=0 ×14,与 RESIZE 增长(62~92px/批)逐批联动=贴底免派发按设计工作
  - 录屏 806 帧运动分析:无锯齿(持续 100-200px 平滑追加,大幅值均为一次性渲染)
  - turn 完整完成(run_code 执行 21.1s)
- 残余 V6(设备自动化被 adb 无线抖动阻断,单测已锁规则逻辑): 尾段阅读构型(SGR pair 线)与读历史冻结构型的真机复核——用户下次流式时:上滑进最新消息应见内容纹丝不动;上滑两屏读历史应零拖拽。

## #436 断连自愈(用户指令同窗加入)

- 测绘: docs/research/436-reconnect-map.md(根因候选四档,代码行号)
- 活体取证: 服务器 04:52 重启+tcp:3080 reverse 丢失→app 无限重试(正确)→隧道恢复 4s 内自动重连+574 会话预载;LAN 403=web trust fence(服务端 --trusted-host 未含 LAN authority)
- 修复: token 持久化+recoverAuth 自动重交换(WS 401/probe AUTH_REQUIRED 两路径)+HTTP-rejected 探针 30s 下限分类
- 真机: 垃圾 token 优雅/正确 token (cookie+token persisted)/隧道反弹 19ms 恢复/localhost 真 AUTH_REQUIRED→TokenNeeded UI
- V6: 用户重启 dsh-web 服务观察 app 自愈;LAN 访问需服务端加 --trusted-host 192.168.110.123:3080
