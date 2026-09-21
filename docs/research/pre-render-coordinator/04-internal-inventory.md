# 04 · 内部盘点 — 渲染前计算/视口补偿/高度配对/滚动锚定代码点清单

> 为 PreRenderCoordinator(#432, spec 见 `docs/specs/2026-09-21-pre-render-coordinator-design.md`)接入范围评估提供的原料盘点。
> 方法:grep 多路线索词扫描(scrollToItem/dispatchRawDelta/onGloballyPositioned/isAtBottom/补偿/配对/账本/episode/drain…) + 逐文件精读(源码在 `app/src/main/kotlin/dev/leonardo/ocbeacon/`,注意主源码在 `kotlin/` 而非 `java/`)。
> 只盘点,不含实现代码。行号为 2026-09-21 工作区快照。

## 0. 关键背景事实

- **PreRenderCoordinator 已存在但零调用点**:`ui/screens/chat/scroll/PreRenderCoordinator.kt:24-45` 是 #432 Phase 1 的视口租约骨架(`activeCount` + `withEpisode`),全仓 grep 无任何 `withEpisode` 调用方——CardExpandReveal episode 尚未注册,守卫尚未让位。
- **先例模块**:RenderSupplyCoordinator(渲染供给)/JumpNavigationController(跳转状态机)/AutoLoadPolicy(分页决策)已完成「分散 effect → 纯逻辑协调器」的收拢,是本模块的架构同构参照。
- 渲染前计算分为**五个域**:锚定决策(谁有权动视口)、SSE 流式补偿(铁律豁免)、卡片揭示引擎(#420-#431 十一轮收口)、跳转视口主权、预测量供给。

## 1. 分层总览

```
[决策层] 谁在什么条件下锚定/让位视口
  ChatScrollController(MSGEFFECT/守卫/force/pending/autoScroll 再武装)
  ChatMessageList(shouldCompensate 门控、banner 锚底、压缩横幅)
[引擎层] 高度变化→滚动配对的具体计算
  CardExpandReveal 时钟 episode(#420-#431)  ←—— Phase 1 迁移主体
  DeferredRevealCompensator + PreRenderShiftChannel(SSE 流式,铁律豁免) ←—— Phase 3
[运输层] 位移的物理注入
  dispatchRawDelta / requestScrollToItem / 反射 requestScrollToItemNoCancel
[主权层] 视口租约的既有形态
  JumpNavigationController.jumpLockActive(跳转)  +  PreRenderCoordinator(待接线)
[预测量层] 让高度在进入视口前就正确(不配对,只消除变化源)
  RenderReadiness / RenderSupplyCoordinator / ScrollSpeedPrefetchStrategy / MarkdownChunking 双向索引
```

---

## 2. 域 A — 锚定决策层(ChatScrollController + ChatMessageList effects)

### A1 isAtBottomState 判定基准
- **位置**:`ChatScrollController.kt:106-111`
- **职责**:derivedStateOf 计算「贴底」(`firstVisibleItemIndex==0 && offset<100`),全链贴底判据的单一来源(100px 阈值被 CardExpandReveal `DEPARTURE_THRESHOLD_PX`、PreRenderShiftChannel 120px 对齐引用)。
- **渲染前计算**:锚定校验的布尔基准,无副作用。
- **独有状态**:derivedStateOf 实例。
- **交互**:A2/A3/A4/A8/B3 全部读它;阈值 100 与 C 域 departure 阈值、B4 的 120 是三个手抄副本。
- **风险**:冷区(简单派生,但阈值是隐形契约)。
- **接入**:不接入,0 行(建议顺手把三处阈值常量化,~5 行)。

### A2 autoScroll 再武装 effect(铁律 4 实现点①)
- **位置**:`ChatScrollController.kt:119-146`(#301 collectLatest 去抖调 A7)
- **职责**:双 key `snapshotFlow{ isScrollInProgress to isAtBottom }` → 滚动置 autoScroll=false;贴底稳定 250ms 再武装。
- **渲染前计算**:无配对计算,是「用户意图」状态机(再武装的判定输入)。
- **独有状态**:`autoScrollEnabled`(rememberSaveable)。
- **交互**:与 A4 守卫构成 #301 修复的自持拉底循环两侧;被 C 域 departure 回调关闭。
- **风险**:**铁律豁免区**(AGENTS.md 明文「不要把 isAtBottom 从 key 中移除」;实现已等价改写为 snapshotFlow 双值流,B-F5 注释存档)。
- **接入**:不加逻辑(rearm 不在 spec Phase 1 让位清单);0 行。

### A3 MSGEFFECT 新消息锚底
- **位置**:`ChatScrollController.kt:148-195`(fling 等待 166-170、重校验 171-176、`requestScrollToItem(0)` 184)
- **职责**:messageCount 变化且 autoScroll 开启时,等 fling 停止→重校验 autoScroll→下一帧布局前注册位置请求锚底。
- **渲染前计算**:**配对 dispatch 的对偶——锚定预测**。requestScrollToItem 在 effect 相(apply 后、layout 前)注册,下一帧布局直接按位置定位,消除「旧 key 锚定偏移一帧再拉回」闪烁。
- **独有状态**:无(effect 闭包 + autoScroll)。
- **交互**:与 C 域 episode **直接竞态史**——2026-08-30 下跳回归根修注释:守卫与展开补偿通过共享视口互搏(±H 战争);jumpLockActive 已让位(E2)。
- **风险**:**热区**(LEAP 0↔2000px 弹跳、下跳回归、#301 去抖均为本案历史)。
- **接入**:**纯适配 ~10 行**(两个判定点各加 `&& !PreRenderCoordinator.hasActiveTransactions`,即 spec Phase 1「MSGEFFECT 锚底让位」)。

### A4 漂移守卫(跟随模式全周期)
- **位置**:`ChatScrollController.kt:196-228`(Triple snapshotFlow 196-203、四条件复查 + `requestScrollToItem(0)` 223)
- **职责**:autoScroll 开启期间,任何「非滚动 + 离底」漂移(异步内容长高 600ms-数秒)经 250ms 去抖重新锚定;用户滚动立即让位。
- **渲染前计算**:锚定校验 + 去抖预测(漂移量级 190-444px 的经验判据)。
- **独有状态**:无(读 autoScroll/listState)。
- **交互**:**与 C 域 episode 是 spec 点名的互搏双方**(「±H 战争」);与 E2 jumpLock 互斥;依赖 A7 去抖。
- **风险**:**热区**(守卫 vs episode 十一轮收口的正面战场)。
- **接入**:**纯适配 ~10 行**(同 A3 加租约让位,spec Phase 1「守卫重锚让位」)。

### A5 ForceScrollExecutor 强滚执行器
- **位置**:`ChatScrollController.kt:232-243`(tick effect)+ `329-374`(等增长→等 fling→`requestScrollToItem(0)` 353→等一帧校验→未到位重滚)
- **职责**:发送后跟随。锚定后一帧校验、未到位再滚——**锚定校验闭环**的现存实现。
- **渲染前计算**:锚定预测 + 布局后校验重滚(VERIFY_TIMEOUT 1s)。
- **独有状态**:无(gate 门面);`ScrollListGate/LazyListStateGate`(289-310)为 JVM 可测缝。
- **交互**:与 A3 同源(autoScroll 强制开启);超时路径会与守卫重叠触发。
- **风险**:热区边缘(死代码根修史 2026-08-16,逻辑已被单测覆盖)。
- **接入**:纯适配 ~10 行(gate.execute 前查租约或 execute 内 reanchor 前查)。

### A6 pendingCount 拉底
- **位置**:`ChatScrollController.kt:245-272`(`animateScrollToItem(0)` 269)
- **职责**:问题卡/权限卡注入时平滑下滑揭示(2026-08-30 由 snapToBottom 瞬跳改 animate,方向裁决)。
- **渲染前计算**:无,显式滚动决策。
- **独有状态**:无。
- **交互**:与 A3 同 fling 等待+重校验模板;动画期间与守卫天然互斥(atBottom 判定)。
- **风险**:冷区(改动少、有用户裁决背书)。
- **接入**:纯适配 ~5 行(挂起动画前查租约,spec Phase 1「PENDING 拉底让位」)。

### A7 AutoScrollArbiter 去抖纯函数
- **位置**:`ChatScrollController.kt:383-419`
- **职责**:贴底再武装/守卫重锚共用的「稳定 ≥250ms 且复查仍成立」去抖语义(#301)。
- **渲染前计算**:纯判定,无。
- **独有状态**:无(常量外)。
- **交互**:A2/A4 的公共下半身。
- **风险**:冷区(纯函数有单测)。
- **接入**:0 行(条件由调用方传入)。

### A8 shouldCompensate 门控 effect(铁律 4 实现点②)
- **位置**:`ChatMessageList.kt:511-520`(状态源 `CompensateState` 483)
- **职责**:同款双 key snapshotFlow——滚动中置 shouldCompensate=true,贴底重置 false;驱动 B 域四路补偿。
- **渲染前计算**:补偿语境开关(不是配对本身)。
- **独有状态**:`CompensateState.lastHeight/shouldCompensate`(`ScrollCompensation.kt:16-19`)。
- **交互**:B2/B3/B7 的总闸;「否则每个 SSE token 都触发 requestScrollToItemNoCancel → 视口抖动」(507)。
- **风险**:**铁律豁免区**(明文勿动)。
- **接入**:0 行(Phase 3 registerStreaming 时随 B 域整体退役)。

### A9 banner 锚底 effect(#420 后已退役大半)
- **位置**:`ChatMessageList.kt:687-723`(`requestScrollToItem(0)` 720)
- **职责**:reverseLayout 贴底时横幅插入点在锚之下不可见 + isAtBottom 翻假 → bannerCount 驱动显式锚底;#420 B 类恒驻化后仅剩压缩尾部兜底(`revealBannerCount` 700-702)。
- **渲染前计算**:锚定预测(requestScrollToItem 同 A3 语义)。
- **独有状态**:`revealBannerCount` remember。
- **交互**:与 A3 模板同源(fling 等待+重校验);恒驻化后与 C 域 BannerReveal 分工(出现/消失走 C,插入走本 effect)。
- **风险**:冷区(残留路径窄)。
- **接入**:纯适配 ~5 行。

### A10 键盘收起滚动探测
- **位置**:`ChatScreen.kt:421-432`
- **职责**:仅真实滚动(index 变化)收起键盘;程序化滚动不收。
- **渲染前计算**:无(消费 isScrollInProgress/index)。
- **独有状态**:`lastScrollIndex`。
- **交互**:程序化滚动(锚定/补偿)会喂给它 index,但 index 不变时不触发——设计上已隔离。
- **风险**:冷区。
- **接入**:0 行。

### A11 FAB 显隐消费
- **位置**:`ChatScreen.kt:995`(isAtBottomState 下传)、`1078-1081`(FabSlotHeightReveal)
- **职责**:isAtBottom 驱动 FAB 滑出动画。
- **渲染前计算**:无。
- **风险**:冷区。
- **接入**:0 行。

---

## 3. 域 B — SSE 流式高度补偿(铁律豁免区)

> AGENTS.md「SSE 滚动稳定性铁律」+ `docs/research/sse-scroll-stability-iron-laws.md` 铁律 3/4/5 直辖。**item 级 COMP-MSG 补偿的具体代码位置即本域 B7**。Phase 3(registerStreaming)之前禁止触碰。

### B1 CompensateState
- **位置**:`ScrollCompensation.kt:16-19`;实例在 `ChatMessageList.kt:483`(以 streamingMsgId 为 key 重置)
- **职责**:lastHeight + shouldCompensate 可变状态(旧版 layout{} 补偿遗产,现仅 shouldCompensate 承重)。
- **风险**:铁律豁免区。接入:Phase 3 随迁。

### B2 DeferredRevealCompensator 延迟揭示状态机
- **位置**:`ScrollCompensation.kt:43-134`(onMeasure 决策 81-133:冷启动 88-91 / holdReveal 100-105 / shiftApplied 竞态门 108-110 / 贴底全揭示清欠 113-119 / 增长注入 120-127 / 收缩复位 128-131;reset 77-80)
- **职责**:「消费先于揭示」的渲染前最强语义——增长遍只上报已消费基准(clipToBounds 裁掉未补偿几何,**未补偿状态永不被放置**),揭示遍与锚点位移几何严格对齐。
- **渲染前计算**:**逐遍测量决策账本**:reportedHeight(已消费基准)/ injectedPending(待应用增量)/ version(配对失效信号)。
- **独有状态**:version(mutableStateOf)、reportedHeight、injectedPending;实例 ×3(`ChatMessageList.kt:486-494`:msgReveal/toolReveal/compactionReveal,各自独立基准、共享 shouldCompensate)。
- **交互**:holdReveal 读 B3 的同步 isScrollInProgress;shiftApplied 读 B4.shiftSettled;#239 修复(滚动中既不注入也不全揭示)与 #258(同帧重测插队)两竞态均在本域收口。
- **风险**:**铁律豁免区 + 热区**(FATAL 崩溃史 #222/#239/#258 全在本域)。
- **接入**:Phase 3 重写 >100 行(spec:registerStreaming 替代 COMP-MSG;铁律矩阵需整体迁移验证)。

### B3 Modifier.deferredRevealCompensation(layout{} 钩子)
- **位置**:`ScrollCompensation.kt:141-190`(version 订阅 147-150、**同步读 isScrollInProgress** 161-165、决策 166-171、enqueue 185、按 reportHeight 上报 187)
- **职责**:measure 块内无界测量→状态机决策→上报决策高度;measure 内读 isScrollInProgress 建立订阅,滚动结束下降沿自动复测。
- **渲染前计算**:预测量(每遍拿到真实高度)+ 注入入队(渲染前)。
- **风险**:铁律豁免区(铁律 3「layout{} 补偿只应用于流式 turn」的载体)。
- **接入**:Phase 3。

### B4 PreRenderShiftChannel 帧界排空通道
- **位置**:`PreRenderShiftChannel.kt:46-140`(pending/generations/wakeSignals 49-58、enqueue 64-69、awaitPending 77-79、shiftSettled 85-88、drain 95-139:贴底收缩放弃 106-112、mid-list `dispatchRawDelta` 124、异常降级 132-138)
- **职责**:全 App 唯一流式补偿注入入口:measure 内入队→帧界(MonotonicFrameClock 回调相)排空→request-position 下一遍遍首应用。代计数 [已入队,已落地] 供揭示方竞态门。
- **渲染前计算**:**配对 dispatch 账本**(每列表累计器 + 代计数)+ 物理守恒决策(贴底收缩无处可去→放弃,上方内容承担)。
- **独有状态**:WeakHashMap×3(仅主线程访问)。
- **交互**:B3 enqueue ↔ B5 泵 drain;B6 是其底层注入器;「滚动中照常排空」与 A 域守卫无互斥(对 drag 无断言)。
- **风险**:铁律豁免区 + 热区。
- **接入**:Phase 3。

### B5 帧界排空泵
- **位置**:`ChatMessageList.kt:1236-1242`
- **职责**:`awaitPending → withFrameNanos → drain` 循环(#412 改待排空信号驱动,空闲不起帧,保 Compose 测试 idling)。
- **风险**:铁律豁免区。接入:Phase 3。

### B6 LazyListReflection.requestScrollToItemNoCancel
- **位置**:`ScrollCompensation.kt:206-274`(字段探测 208-222、执行 255-274、降级 273)
- **职责**:反射设置待定位置而**不取消 fling**(绕过官方 requestScrollToItem 的 scroll{} 互斥锁);Compose 升级需人工核对成员存在(注释 199-205)。scrollToBeConsumed 直写已随 #258 删除。
- **渲染前计算**:位置注入的运输层。
- **风险**:铁律豁免区 + 热区(版本脆弱性单点)。
- **接入**:Phase 3(退役或保留为运输细节)。

### B7 COMP-MSG/COMP-CMP/COMP-TOOL 四挂载点
- **位置**:`ChatMessageList.kt:1575-1585`(COMP-MSG,itemModifier,`if (isStreamingMsg)` 分支=铁律 3 位置)、`1644-1649`(COMP-CMP v1 摘要)、`1980-1989`(COMP-CMP msg)、`2295-2300`(COMP-TOOL 工具进度);实例与 reset 在 486-494/2309-2312
- **职责**:三路补偿器的挂载点(各自独立基准、共享 shouldCompensate 在底意图);clipToBounds 同链防越界绘制(#231)。
- **风险**:**铁律豁免区**(`isStreamingMsg` 判定 = 铁律 3 的 multi-message turn `.any{}` 语义,1567/2415-2417)。
- **接入**:Phase 3 退役时逐点摘除,~50 行(删包装即回归裸 item)。

### B8 streamingMsgId 判定(铁律 5)
- **位置**:`ChatMessageList.kt:399-403`(completed 时间戳判定;395-396 注释明令「不要再加 takeIf(sessionMeta)」)
- **风险**:铁律豁免区。接入:0 行。

---

## 4. 域 C — 卡片揭示引擎 CardExpandReveal(#420-#431 十一轮收口,Phase 1 迁移主体)

> 文件 `components/CardExpandReveal.kt`(850 行)。spec Phase 1:「引擎迁入协调器;arming/canClose/end-restore 分布式状态废除」。

### C1 CardExpandClock 纯时钟状态机
- **位置**:`CardExpandReveal.kt:142-282`
- **职责**:fraction 驱动 + 双态账本。advance(204-210,δ=量化后目标−上报,整数守恒)/onMeasure(221-226,上报 f·H)/primeLedger(229-231)/absorb(234-236,账本只记实际消费)/snap(243-248)/warmup(251-253)/driveTo(256-258)/requestRemeasure(279-281)。
- **渲染前计算**:**账本核心**——lastReportedH(上一帧上报)、absorbedF(吸收账本浮点原子,逐帧取整 telescoping ≤1px)、episodeDisplacement(本集累计位移)。
- **独有状态**:fraction/lastMeasuredH(mutableState,measure 订阅)、tweening/measureCount/remeasureEpoch/animating/userScrollCancelled/departureFired。
- **交互**:C2/C3/C8/C10 全部读写;#425 教训固化在字段注释(指令账本 vs 吸收账本)。
- **风险**:**热区之最**(±H 战争、−366px 净漂、−26px 子像素泄漏、218 帧 consumed=0 死锁均在此收口)。
- **接入**:**重写 >100 行**——迁移为 PreRenderTransaction(absorbed/reportH/phase),时钟推进归协调器帧管线。

### C2 drainPhaseA 同帧配对排干
- **位置**:`CardExpandReveal.kt:346-385`(触发点 600-603 onGloballyPositioned + 440-444 逐帧兜底)
- **职责**:A 阶段增长落地帧(放置完成、draw 前)补发配对位移——dispatchRawDelta 有空间可消费→消费即失效重排→同帧净位移为零,#426 瞬态从构造上消失。基准=f·H 落地部分而非全高(352 防锚点乱斗)。
- **渲染前计算**:**per-component onGloballyPositioned 调和**——spec 帧管线 RECONCILE 相要收编的第一个对象(「各组件各自为政」→列表根单点有序)。
- **独有状态**:`phaseADrain`(334)、`drawFraction`(329)。
- **交互**:多事务并发时与其它卡片的 drain **通过共享视口互搏**(#430 战争根因,spec 帧管线关键差异段)。
- **风险**:**热区**。
- **接入**:重写 >100 行(并入协调器 reconcile() 有序队列)。

### C3 episode 主循环
- **位置**:`CardExpandReveal.kt:400-574`
- **职责**:visible 转换→手写帧循环(非 Animatable,#420 追诊:conflated 合并 + finally 尾帧吞 δ 两处致命竞态):预热→settle(420)→A 阶段一次性布局+排干(421-445)→B 阶段纯绘制揭示(446-462)→收起向闭环逐帧(463-514,虚拟时钟+二分步进+指令钳制)→收尾 flush(516)→finally(end-restore 548-560、迟到增量补偿 533-542、锚点携带 570、不变量兜底 524)。
- **渲染前计算**:**两阶段状态机 + 渲染前反馈闭环**(closedLoopCommand 每帧指令=目标−已吸收;残量下帧重试不丢失)+ 迟到增量预测量对账。
- **独有状态**:`carriedAnchor`(319,#425 连点竞态)、episodeStart/skipClosedLoopTail 局部。
- **交互**:spec Phase 1「withEpisode 注册」的宿主——**当前零注册**,守卫(A4)随时可插入拉底。
- **风险**:**热区之最**。
- **接入**:重写 >100 行;外层包 `PreRenderCoordinator.withEpisode` 本身仅 ~5 行,但引擎迁入是大头。

### C4 cancel-on-scroll 用户取消
- **位置**:`CardExpandReveal.kt:580-594`(读当下 visible 的 rememberUpdatedState 576-580 修过空白卡死态)
- **职责**:isScrollInProgress 上升沿→snap 目标(阅读位置优先权铁律=spec I4)。
- **风险**:热区(语义必须保留为协调器取消路径)。
- **接入**:随 C3 迁移,协调器「用户滚动→取消事务」内建;组件侧 ~10 行适配。

### C5 Box 修饰链(实测锚点 + 纯绘制揭示)
- **位置**:`CardExpandReveal.kt:596-631`(clipToBounds 598、geometry 599、onGloballyPositioned 600-603、fraction>0 组合门 610、graphicsLayer alpha 613-615、drawWithContent clipRect 616-626)
- **职责**:revealTopY 实测(闭环对账的物理基准)+B 阶段零布局揭示(#425:展开方向布局多 pass 振荡,故布局一次到位、揭示纯绘制)。
- **渲染前计算**:锚点实测(onGloballyPositioned)+ 绘制相裁剪分数。
- **风险**:热区。
- **接入**:接口保留(anchorY/reportH 回调正是 spec 事务模型字段),组件侧 ~30 行中等。

### C6-C8 纯函数三件套
- **位置**:episodeEndCorrection `644-653`(#424 闭环位置恢复判定)、closedLoopCommand `672-673`(指令=目标−账本,钳 300px)、dispatchClosedLoop `680-718`(执行+崩溃守卫 692+账本+departure)
- **渲染前计算**:守恒数学本体(「未消费残量经目标−账本下帧重试」——spec §3 明言继承)。
- **风险**:热区但有单测(纯函数可单测是历次修复的产物)。
- **接入**:**算法原样搬入协调器**(spec 背书),~50 行迁移非重写。

### C9 settleUntilContentStable 内容沉降
- **位置**:`CardExpandReveal.kt:730-752`(判据:连续 2 帧 measureCount 不增,上限 600ms)
- **职责**:展开 tween 前等待内容驱动重测静止(表格 containerWidth 两拍收敛、asyncParse 完成),防缓存窗口锁死过期 H。
- **渲染前计算**:**预测量稳定判据**(G3 是其输入源之一)。
- **风险**:热区(参数是实测校准)。
- **接入**:随 C3 迁移为 Settle 相。

### C10 CardExpandGeometryNode 几何上报节点
- **位置**:`CardExpandReveal.kt:774-849`(缓存四条件 768-772/811-812、epoch 快照读失效 800-806、上报 823-826)
- **职责**:tween 窗口内复用 placeable(测一次放多次,修表格 subcompose 重测风暴),f·H 上报未揭示部分被外层 clip 裁掉。
- **渲染前计算**:**几何缓存窗口**——report 相(帧管线 LAYOUT 相)执行者。
- **风险**:热区(缓存失效条件错一条即「展开只有一小截」)。
- **接入**:随 C1 迁移,~30 行。

### C11 竞态防护网(降级/豁免/departure)
- **位置**:三个 CompositionLocal `73-79`、降级裸 AV `297-308`、departure 阈值触发 `378-381/714-717`(阈值=82 行 100px)、LocalInStreamingTurn 逐 item 提供 `ChatMessageList.kt:2410-2422`
- **职责**:流式 turn 内降级裸 AV(「item 级 COMP-MSG 补偿独占,杜绝双重注入」——**铁律 3 的组件侧表达**);展开位移 >100px 回调关 autoScroll(守卫让位的组件侧信号);任一 local 缺席→出厂行为。
- **风险**:热区(降级矩阵是正确性边界)。
- **接入**:保留;streamingActive 门控未来由协调器 streaming 事务替代(Phase 3),~20 行。

### C12 消费方(13 处,接口不变即零改动)
- **位置**:MessageBubble.kt:218、MessageCardAssistant.kt:355/423/1224、QuestionPartContent.kt:140、EventCard.kt:186、ReasoningBlock.kt:268、CompactionCard.kt:172、InjectionCard.kt:111、GlobToolCard.kt:93、TodoListCard.kt:159、ToolCardScaffold.kt:251;BannerReveal 包装 `ChatMessageList.kt:195-208` + 7 个恒驻横幅调用点(2175 revert/2234+2253 pending 系/2284 tool_progress/2316 step_progress/2336 question_pending/2364 perm_pending)
- **职责**:声明式 `CardExpandReveal(visible)` 消费;BannerReveal = CompositionLocal 注入 + 流式豁免包装。
- **风险**:**冷区**(纯消费)。
- **接入**:**0 行**——这是集中化的核心收益;唯一例外是 spec 要求未来把「声明事务 + 锚点回调」显式化时逐点 +2-3 行(约 40 行,可选)。

---

## 5. 域 D — 协调器本体与滚动运输杂项

### D1 PreRenderCoordinator(已建,零调用)
- **位置**:`ui/screens/chat/scroll/PreRenderCoordinator.kt:24-45`
- **职责**:视口租约(activeCount>0 = 守卫/锚底/拉底让位,spec I3);withEpisode try/finally 防泄漏。
- **独有状态**:activeCount(mutableIntStateOf,快照可观察)。
- **风险**:冷区(新代码);**缺 Consumer 是当前最大缺口**。
- **接入**:本体 0 行;接线 = A3/A4/A5/A6 ~30 行 + C3 包裹 ~5 行(Phase 1 全部)。

### D2 megaDeltaScrollGuard 巨帧分块
- **位置**:`ScrollIsland.kt:24-67`(挂载 `ChatMessageList.kt:2117`)
- **职责**:Initial 隧道趟拦截单帧 >300px 病态增量,100px 切片 dispatchRawDelta 直派;正常手势零触碰。#245 定界探针存疑注记(效果存疑,健康帧零触碰)。
- **渲染前计算**:无(输入域手势整形)。
- **风险**:冷区(独立、DEBUG 可观测)。
- **接入**:0 行(不属渲染前配对域)。

### D3 rememberSafeFlingBehavior 限速 fling
- **位置**:`SafeFlingBehavior.kt:35-133`(挂载 2107)
- **职责**:每帧 ≤视口/8(carry 保留总距离)给预解析/预组合留窗;契约违规(IllegalState)等帧重试≤3 次防 FATAL(2026-08-26 v2);CancellationException 放行。
- **渲染前计算**:无(为预测量层争取时间)。
- **风险**:冷区-热区边缘(崩溃防御语义勿轻易动)。
- **接入**:0 行。

### D4 snapToBottom 强推钉底(+死代码)
- **位置**:`ChatScrollUtils.kt:35-42`(FAB 点击;#256 勘误:到底后 3×120ms 强推对抗延迟测量爆发);`smoothScrollToBottom`(15-21)是登记在册死代码
- **渲染前计算**:锚定校验+周期性重推(与 A5 VERIFY 思想同源)。
- **风险**:冷区。
- **接入**:可选 ~5 行(租约期禁用);死代码删除即可。

---

## 6. 域 E — 跳转定位(视口主权既有实现)

### E1 状态机 + 纯函数
- **位置**:`JumpNavigationController.kt:40-128`(JumpPhase/JumpEvent/jumpTransition、computeGap 65-66、computeGap 顶边偏差 69-70、findJumpTargetItem 83-92 分片前缀匹配)
- **风险**:冷区(纯函数+单测文化最完整的一域)。
- **接入**:0 行(或作为事务模型参照)。

### E2 jumpLock 派生
- **位置**:`JumpNavigationController.kt:175-191`(unlock 缓冲 300ms)、`198-209`(pending lock;#159 收口:手工镜像 4 写点任一遗漏即死锁)
- **职责**:**视口主权的现行租约**——A3/A4 据此让位(走查 #1:守卫与快速定位互搏,GUARD reanchor idx=14→0 循环)。
- **交互**:与 D1 语义同构(两个租约源并存);F2 提交门控直读 phase。
- **风险**:热区(竞态史密集但已收口)。
- **接入**:中等 ~30 行——建议守卫判定改为 `hasActiveTransactions || jumpLockActive` 统一读,或跳转注册为 SWAP 类事务(Phase 2 再议)。

### E3 measureAndSettle 渐进定位
- **位置**:`JumpNavigationController.kt:294-442`(底部定位 scrollToItem 303→渐进小步逼近 scroll{} 332-391:区域签名 4 轮稳定判据 347-356、夹持修复 384-390、节流重定位 392-403→900ms 稳定窗口 gap>8px 静默修正 422-440、用户触摸退出 425-429)
- **渲染前计算**:**锚定校验闭环 + 预测量消费**(「一次定位到最终位置,测量/收敛都在目标位置进行」——根治回收-重解析振荡)。
- **风险**:**热区**(A-F1 连跳写穿/A-F4 反射互斥/幽灵 gap 三轮根修)。
- **接入**:0 行(自有租约已闭环;Phase 2 registerSwap 时复用其稳定判据)。

### E4 pendingJumpTarget 三帧等待跳转
- **位置**:`ChatMessageList.kt:1178-1195`(displayEntryStart 映射补漏 1187-1192;loadAround 失败保护 1203-1227 含 500ms 时序差防御)
- **风险**:冷区。接入:0 行。

### E5 QuickNavigateSheet 等一帧定位
- **位置**:`QuickNavigateSheet.kt:101-121`(withFrameNanos 后 scrollToItem 106/120;effect key 不含 currentMsgId 防 SSE 期反复重启 96-100)
- **风险**:冷区。接入:0 行。

---

## 7. 域 F — 预测量/渲染供给(消除高度变化源,不配对)

### F1 RenderReadinessRegistry
- **位置**:`RenderReadiness.kt:63-144`(ConcurrentHashMap+StateFlow,2026-08-20 重组风暴根修注释 64-70;preParse 后台解析+归一化 96-125;#98 remove 防无界增长)
- **职责**:part 级渲染就绪唯一真相源(Pending→Parsing→Parsed)。
- **风险**:冷区(已收拢、单测覆盖)。
- **接入**:0 行——**架构先例**:PreRenderCoordinator 应复用其「注册表+协调器+Compose 桥」三层形态。

### F2 RenderSupplyCoordinator
- **位置**:`RenderSupplyCoordinator.kt:37-602`(分片计划 pending/提交门控:视口外防线+跳转相位+终点 2s 窗口;F2 冷热区分 100-107/488/560;±6 定标 433-439;#246 五轮部分快照根治 82-91)
- **职责**:视口前方渲染资源预备决策唯一决策点(预解析/分片/分段时机安全)。
- **交互**:读 E2 phase(共享 StateFlow,`ChatMessageList.kt:733-738` 桥);其提交门控与渲染前配对是**互补而非重叠**。
- **风险**:冷区-中(复杂但已集中化+602 行注释存档)。
- **接入**:0 行;其 lastJumpEndAtMillis 自记模式(109-125)是 D1 应学习的「跨 effect 时间戳耦合消灭」先例。

### F3 ChatMessageList 桥
- **位置**:`ChatMessageList.kt:733-742`(构造)、`943-957`(onWorldArrived 世界到达)、`958-970 段`(snapshotFlow 视口采集)
- **风险**:冷区。接入:0 行。

### F4 ScrollSpeedPrefetchStrategy
- **位置**:`ScrollSpeedPrefetchStrategy.kt:161`(onNestedPrefetch 方向预测预组合);装配 `ChatViewModel.kt:669-679`(原 cacheWindow 1.5/1.5 已由方向预测替代——铁律 7 历史见 sse-scroll-stability-iron-laws.md)
- **风险**:冷区。接入:0 行。

### F5 MarkdownChunking 双向索引
- **位置**:`MarkdownChunking.kt:250`(LazyColumn index ↔ displayItems 单一真相源);消费 buildChatEntries `ChatMessageList.kt:748-757`
- **职责**:分片发射表——跳转/锚点/可见项反查的坐标系统(E4 的 1187-1192 补漏即此)。
- **风险**:冷区(承重索引,注释自称「保守性决策」)。
- **接入**:0 行(协调器锚定键匹配将依赖它,Phase 2)。

---

## 8. 域 G — 外围消费方(冷区,非列表视口配对域)

| # | 位置 | 职责 | 渲染前计算 | 接入 |
|---|------|------|-----------|------|
| G1 | QuestionPartContent.kt:322-403(onGloballyPositioned 385-388,derivedStateOf 插值 328-338) | Pager 页高实测+滑动插值(先按上限截断) | 预测量(页高账本 pageHeights) | 0 行(独立容器,无视口配对) |
| G2 | ChatScreenBottomBar.kt:721-740 | busy 气泡 onSizeChanged→Popup offset(首帧估值 265 消闪烁) | 预测量(高度→位移换算) | 0 行 |
| G3 | MarkdownTable.kt:142-151 | containerWidth onSizeChanged 回写(两拍收敛) | 预测量——**C9 settle 判据的输入源** | 0 行 |
| G4 | SessionTreeList.kt:68-139(纯函数 68-75;组合期读 firstVisibleItemIndex 132;scrollToItem(0) 136/148) | 前插揭示裁决(head id+顶部判定,无竞态信号原则) | 锚定预测(独立列表,非聊天视口) | 0 行(可选统一锚定 API 时 ~10 行) |
| G5 | AppPickerList.kt:50 | 选中项 scrollToItem | 无 | 0 行 |
| G6 | DiffView.kt:76 | animateScrollToItem(visibleIndex) | 无 | 0 行 |
| G7 | ChatFabMenu.kt:144 | FAB 菜单几何锚点实测 | 预测量 | 0 行 |

---

## 9. 域 H — 大组硬切换缺口(Phase 2 目标)

### H1 StepGroup 大组展开拆条目
- **位置**:`ChatMessageList.kt:450-479`(expandedLargeStepGroups 快照)、`756`(buildChatEntries 消费)、MessageCardAssistant.kt:383-384(skipStepGroupItem 分叉)、**`ChatMessageList.kt:759-764`(#430 锚定撤除存档:五轮真机迭代均在反向布局 scrollToItem/dispatchRawDelta 语义上落错位——视口被甩到无关区域,比不锚更糟)**
- **职责**:大折叠组展开→1 item 裂变为 Head/Body 独立 LazyItem;结构变化**零配对零锚定**(折叠行随展开飞出,#422 已知缺口)。
- **风险**:**热区**(失败五轮;教训明令「反向布局滚动语义必须先写校准单测再上真机」)。
- **接入**:Phase 2 registerSwap 重写 >100 行(配对+锚定一并,spec §5;前置=反向布局滚动语义校准单测)。

---

## 10. 竞态交互矩阵(基于代码阅读的推断)

| 交互双方 | 机制 | 证据/历史 |
|---------|------|----------|
| A4 守卫 ↔ C3 episode | 共享视口互搏:episode dispatchRawDelta 位移 vs 守卫 requestScrollToItem(0),±H 战争 | spec 背景「十一轮收口实证」;C3 当前**未注册租约**,唯一防护=departure 回调关 autoScroll(C11)>100px 阈值——漂移 <100px 时守卫仍可插入 |
| A3 MSGEFFECT ↔ B 域流式增长 | 锚底后内容继续长高(600ms-数秒)→ 渐进顶离贴底 → A4 守卫再拉回循环 | ChatScrollController 185-195 注释(实测漂移 190-444px) |
| A2 再武装 ↔ A4 守卫 | 再武装与守卫组成自持拉底循环(拉底→贴底→再武装→闪断帧守卫再拉底) | #301 LEAP 0↔2000px 弹跳,AutoScrollArbiter 去抖收口 |
| B2 揭示 ↔ B4 排空 | 同帧重测插队→揭示先于位移跳变 | #258 shiftApplied 竞态门(108-110) |
| B3 ↔ 用户 fling | 注入残量撞 fling scrollBy 契约 | #239 holdReveal 第三条路;D3 retries 等帧防御 v2 |
| C2 drain ↔ 多卡片并发 drain | 每组件 onGloballyPositioned 各自为政,共享视口逐帧互搏 | spec §2「关键差异」;#430 战争 |
| E3 渐进定位 ↔ F2 分片提交 | 跳转期间裂变=视口内容被替换 | F2 提交门控直读 phase+终点 2s 窗口 |
| A9 横幅插入 ↔ isAtBottom | 插入抬高锚 index→isAtBottom 翻假→自我闭锁 | ChatMessageList 687-696(门控用 autoScroll 而非 isAtBottom) |
| H1 拆条目 ↔ E3 跳转 | 拆分改变 item key/index→跳转目标重定位 | findJumpTargetItem 分片前缀匹配(#394/E1) |

## 11. 汇总

### 接入点总数
- **盘点代码点:52 个**(A 域 11 + B 域 8 + C 域 12 + D 域 4 + E 域 5 + F 域 5 + G 域 7 + H 域 1;C12 的 13 个消费方计 1 点)
- **与 PreRenderCoordinator 接入直接相关:19 个**(A3-A6、A9、B2-B7、C1-C5、C9-C11、D1 接线、E2、H1)
- **明确不接入:33 个**(消费方/外围/预测量层/手势域)

### 风险分级分布

| 分级 | 点数 | 代表 |
|------|------|------|
| 热区(复杂+bug 史) | 22 | A3-A5、B2-B7、C1-C5、C9-C11、E2-E3、H1 |
| 铁律豁免区(AGENTS.md 明文) | 9 | B1-B8、A8(与热区部分重叠) |
| 冷区(简单消费/纯函数/独立域) | 21 | A1-A2、A6-A7、A9-A11、C12、D1-D4、E1、E4-E5、F1-F5、G1-G7 |

(注:部分点跨级——如 B2-B7 既是热区又是铁律豁免;D1 本体冷区但其接线是当前最高优先。)

### 总改动量粗估(行级)

| 阶段 | 范围 | 估算 |
|------|------|------|
| Phase 1 接线 | A3/A4/A5/A6 租约让位 + C3 withEpisode 包裹 + D1(已有) | **~50 行(纯适配)** |
| Phase 1 引擎迁移 | C1-C5、C9-C10 迁入事务模型(reconcile 单点化、arming/end-restore 废除);C6-C8 算法搬移;C12 消费方零改动 | **~300-400 行(重写级,但 13 消费方不动)** |
| Phase 2 | H1 registerSwap(+E2 租约统一 ~30 行) | **>100 行(前置:反向布局滚动语义校准单测)** |
| Phase 3 | B 域 registerStreaming 退役 COMP-MSG(A8/A9/B1-B7 摘除) | **~200 行(重写级,前置:铁律矩阵迁移验证)** |
| 合计 | | **≈ 650-780 行当量;风险集中度:Phase 1 引擎迁移占大头,消费方与外围零改动** |

### 接入优先序建议(仅评估,非实现)
1. **立即可做(低风险高收益)**:D1 接线——A3/A4/A5/A6 加 `hasActiveTransactions` 让位 + C3 包 withEpisode(~50 行)。这是 spec Phase 1 已声明而未落地的部分,直接消解「守卫 vs episode」现存竞态窗(当前唯一防护=departure>100px 回调)。
2. **Phase 1 主体**:C 域引擎迁移——收益=多事务并发有序化(#430 类战争从构造上消失)+每组件调和收拢;风险=十轮收口的隐性契约(整数守恒/缓存窗口/两阶段)需逐条迁验。
3. **暂缓**:B 域(铁律豁免,收益/风险比最差)、H1(先补校准单测)、E 域(自有租约已闭环)。
4. **顺手项**:贴底阈值 100px×3 处常量化(A1 / CardExpandReveal:82 / PreRenderShiftChannel:101-102 的 120px——后者语义不同需确认)。
