# #423 PreRenderCoordinator 实施（2026-09-22）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## #423 批次一


## 2026-09-22 #423 批次一:P0+Phase0+Phase1a(视口租约)

- 范围:spec v1 的 P0(红环固化+基线冻结)/Phase0(D5 反射护栏)/Phase1a(I3 租约接线)。
- 引擎迁入(MutatorMutex+帧事务+FLUSH+锚模型)=下一验收轮,本批不动 CardExpandReveal 数学。
- commit: 26a94b3f(Phase0) fb3478f6(P0) 2be09be5(脚本修复) f6a4a314(Phase1a)。
- 单测: LazyListReflectionTest 3/3; PreRenderCoordinatorTest 4/4; AutoScrollArbiterTest 8/8;
  ChatScrollControllerTest 7/7; 全量 :app:testDevDebugUnitTest --rerun BUILD SUCCESSFUL(1m53s)。
- 真机(小米14 无线adb,卸载重装跨签名例外):基线 e90571f0 vs 租约 f6a4a314,同会话同卡(H=146):
  - 红环1 icon dy: GREEN 与 GREEN(零回归)
  - 红环4 fold 条带: RED +198px@f48 与 RED +198px@f46(瞬态原样保留——引擎迁移目标,07 基线在档)
  - 收起账本: cmd==consumed 逐帧,topY 恒 2049,297ms 与 285ms,数学一致
  - 红环3: 两轮 episode 全程均无 GUARD/MSGEFFECT 战争线;让位语义由单测钉死(守卫复查点承重)
- MIUI 现场勘误: adb 全新安装弹「USB安装提示」且无人值守静默拒绝;install-dev.sh 自动点继续(固化)。
- 待办: 用户 D4 手感验收 → Phase1 主体(引擎迁入)。

## #423 批次一补遗


### 红环补跑(同机同会话,租约包 f6a4a314)

- 环 6 连击:4 连点(280ms 间隔)→ 四集交替干净收官(980/268/842/263ms),
  GUARD/MSGEFFECT 战争线 0 条,终态折叠行 y 与参照一致。GREEN。
- 环 5 取消:注入通道受阻——空载状态 input swipe 也无法滚动列表(折叠行 y 恒定),
  MIUI 对注入手势拦截,非应用回归;C4 取消语义本批零改动,交 D4 用户手感覆盖。
  (attempt2 观察:swipe 触发 scrolling=true 但零位移,同为注入层伪迹)

## #423 批次一评审收口


### 双轴评审与修复(2026-09-22)

- Standards 轴:1 阻断(MSGEFFECT fling 等待后复查点漏验租约——点按不置
  isScrollInProgress,episode 可在 2s 等待窗内启动,恢复后带锚穿越;旁证=其余
  全部锚定路径等待后均复查)→ 已修(:183 补 hasActiveTransactions,A9 同款双检)。
- 2 建议:activeCount 封装(已做,私有 backing+只读暴露+线程语义 KDoc);
  reanchor 六参谓词束 Data Clumps(登记,Phase1 主体随协调器收编)。
- 4 吹毛求疵:withEpisode 闭合标记(已做)/轮询探活早退+mktemp(已做)/
  capture.sh find|wc(已做)/缩进不重排(裁决:热文件零 churn,引擎迁入时重构)。
- Spec 轴:三范围均判忠实,无越界;现场2 代码考古替代红环录制=轻度偏离已如实标注。
- 修复后复验:四测试类 --rerun 绿;真机三连点烟测 1077/268/1066ms 干净收官,零战争线。

## #423 批次二


## 批次二:展开闪现根修(2026-09-22,用户手感报告驱动)

- 用户报告:「点击展开一瞬间跳动→恢复原位→内容往下展开」= 07 基线红环4 的人眼对应。
- 观测体系(用户指令):PRD 帧探针(PLACED/DRAW 双相,episode 门控,常态零行)
  + screenrecord 120fps 全帧抽帧(此前 20fps 抽帧系自降采样)+子集轨迹分析。
- 根因定位(BUILD-A=FLUSH 开关关,旧路径):
  · 01:11:02.781 增长落地(topY 1903 逸出 -146,rep=146 abs=0 未配对)
  · 01:11:03.583 配对才消费([DEBUG-426] placed consumed=146)——802ms 未配对窗口
  · 本轮窗口内恰无重绘(折叠行像素 0→198 单帧),但大卡内容重必渲染中间态
- 根修(BUILD-B):pre-pair(settle 后 dispatch 与 driveTo 同 tick,失效合并同一
  layout pass)+ FLUSH 单点 OnPreDraw 拒绘兜底(≤2/帧)+ 降级路径保留。
- 实测对比:配对 802ms→7ms;topY 逸出 1→0 次;残差绘制 0 帧;episode 1201→355ms;
  像素 0→198 单帧零过冲;icon GREEN;全量单测绿(2m43s)。
- 反射定论(用户问「是不是得走反射」):否。官方 dispatchRawDelta 相对语义在正确
  相位(布局前,同失效批次)即可根治;反射仅 SSE 域(fling 保持)需要,Phase0 已护栏。
- 遗留 M2:展开落地帧下方内容 +H 单帧跳变(Phase A 一次性布局的固有形态,卡内容
  由 Phase B 平滑揭示)——待用户手感裁决;若仍觉跳,属 D2 统一动画契约(逐帧高度)
  范畴=引擎迁入主体。commit 634a3e11。

## #423 批次三


## 批次三:步骤组三报告根修(2026-09-22,用户手感驱动+帧级日志)

- 用户三报告:①步骤组展开仍顶开 ②展开后找不到收起 ③重内容展开卡顿。
- 观测体系(按用户指令):SGB 探针族(CLICK/TAILCLICK/RB-EXP 既有/LARGE/ENTRIES/
  SNAP 逐帧快照/HEAD 放置)+PRD 帧探针——全链路覆盖,常态零行;MIUI 注入手势
  拦截(swipe/motionevent 均滚不动)改由用户手指驱动+日志抓取。
- 定性:用户卡走小组路径(H=4688,weight<6000);批次二 pre-pair 对它 LEAP
  (idx 0→12)→①#430 反向布局重锚雷区 ②item 回收取消 episode
  (LeftCompositionCancellationException 实证)→修正被杀→折叠行顶出屏。
- 迭代(本批 5 轮构建):账本监督→实测钉位(协程)→NaN 守卫误杀续跑→
  移交 FLUSH+预测退役→振荡(同帧双发±684)→测量刷新门控+单发全额。
- 终态实测:折叠行 1100→1100(4684 单发修正,stable=3,零振荡);收起逐帧
  cmd==consumed,topY 恒定;思考卡(146)同路径回归通过;全量单测绿。
- UX 增量:小组+大组组尾收起行(#sgt);重内容首帧占位(#430 模式)。
- 遗留:大组(结构裂变)展开仍无配对(Phase 2 SWAP 待做,尾收起行先解收起);
  episode 2.4s 时长(冻结 650ms+钉位等待+Phase B)——L3 AST 切片是根治。
  commit 805c0e84。

## 批次五(2026-09-22 午):/diagnosing-bugs 轮——判红环定案两病灶

**用户报告**:①展开顶开不复位(几率)②收起顶一下又复位;要求同位×3、贴底/中间/组内三态矩阵。

**反馈环**:scripts/prerender/pin_matrix.sh(单命令判红:DOM 锚位移>12px / confirmed=false / consumed=0)+ pin_scenarios.sh(四场景矩阵)+ find_row.py。

**两轮矩阵定案**:
1. 病灶①=钉位超时从武装起算:冷启首展(冻结 700ms+重组 620ms)吃光 1.5s,超时比修正回调早 68ms(red1:timeout 31.922 vs 修正 PLACED 31.990)→ 修正器阵亡,后续漂移(用户手指/迟到增长)无人管。温启恰在 1.37s 内 =「有几率」。
2. 病灶②=episode 后迟到增长(B 前 +78 / C 前 +61 实测)无人接管,下次收起配对顺手修回 =「会复位」。
3. B/C 首轮红均为 DOM 锚伪影:展开态同文本首匹配节点结构变化;PLACED 铁证 reveal 钉位精确(1226/2049);矩阵改用结构恒定金丝雀锚(Grok 页脚行)。

**修**(d40355c3,已装真机):超时改 hold 起算 2s / 总帽 6s / episode 等待环 2.5s;pumpArm 泵帧(放置回调派发后放行修正后布局);PIN_QUIET_MS=1.8s 静默守望(rep 无变更才解散,窗内迟到增长自动重进确认环)。修后矩阵 A/D 全绿(confirmed=true,含冷启首展)。

**遗留**:金丝雀锚矩阵因设备 uiautomator 楔死未跑完(需设备重启后补跑);收起 -62 边缘残量(consumed=0,DOM 无可见位移,#420 物理不可约在案);L3 AST 切片 / Phase 2 大组结构裂变不变。

## 批次六(2026-09-22 午后):调研定案→折叠组全线结构裂变

**用户裁决**:调研确认框架无成熟「原地展开不挪视口」原语后,批准全线切结构裂变路径。

**实施**:
- ChatMessageList:expandedLargeStepGroups→expandedStepGroups(拆权重门槛,小组并入);收集器仅剩展开判定。
- MarkdownChunking:参数更名,发射机制不变(尾 Turn+Body×N+#sgt+#sgh)。
- itemsIndexed 全条目包 Box(Modifier.animateItem(fadeInSpec=null, fadeOutSpec=null))——仅位移动画;结构变化平滑滑动,滚动不触发。
- StepGroupLazySplitTest:+小组裂变契约用例;全量单测绿(3m54s)。

**真机初证**(装机后单次探测,13:55):CLICK→ENTRIES 重建 18ms(原地引擎秒级 episode);SPLIT n=1;HEAD 就位;SNAP 首帧 fii=0 fiso=0 items=22;无崩溃。

**未竟**:金丝雀×3 矩阵——设备侧 uiautomator 反复楔死+MIUI 无线调试分钟级掉线(息屏自动关),需设备重启后补跑;矩阵脚本已加固(svc power stayon/转储重试/force-stop 清态,后者顺带发现 ViewModel 展开态跨 activity 重启存活)。

**语义注记**:CardExpandReveal 引擎退守思考卡/SSE 域;StepGroupCard 仅剩收起态渲染;animateItem 语义=滚动不触发、结构变化位移平滑。

## 批次七(2026-09-22 晚):用户逐帧复检→单发实测重锚 REPIN

**用户令**:「你自己录视频逐帧分析!!!!视频逐帧分析+日志埋点+逐行分析」。

**三通道取证**:
1. 录屏逐帧全局位移量化(新工具 scripts/prerender/frame_dy.py:相邻帧中心带归一化互相关):底贴态展开全程 dy=0(score=1.000)——屏面纹丝不动,框架锚定在该态自稳。
2. 应用探针:HEAD topY 揭示他态头行 1100→644(+456 顶起);SNAP fii=0 fiso=0。
3. 日志逐行:CLICK→ENTRIES 重建 15ms→SPLIT→(无 REPIN 时)头行顶起残留=用户「整个对话往上顶」的结构路径版本。

**修(单发实测重锚)**:StepGroupFoldRow 逐放置上报屏位(LocalFoldRowYReport;#sgt 尾行 pinEligible=false 防同 key 歧义)+点击快照(LocalFoldRowClick)→LaunchedEffect(chatEntries) 等重建后新鲜放置→err=y0−y1→|err|≥8 一次 dispatchRawDelta。号性=FLUSH 修正器实证约定;零索引运算(避开 #430 翻车的 scrollToItem 数学)。

**真机实证**:REPIN y0=1100 y1=644 err=456 consumed=456(点击后 ~120ms 全额归位,consumed==err 全额消费)。

**未竟**:设备链路(MIUI 无线调试分钟级掉线+uiautomator 反复楔死)阻断×3 矩阵与用户滚动态的自动复现;中位态修复逻辑同构(同一实测机制),待用户手感验收或设备重启后补仪器矩阵。

## 批次八(2026-09-22 晚):用户复检「注意 reverse 方向」→病灶在思考卡,同法迁移

**用户复检**:仍不行,提示注意 reverse 方向。取用户刚操作的设备日志定案:残余病灶不在组卡(批次七 REPIN 已覆盖),在**思考卡**——仍走 CardExpandReveal 原地引擎:每次展开 13+ 次爬行修正(err 80→73→59→…→1,350ms+),连点呈 ±80 振荡(err +80/−80 交替,锚在 1178↔1226 翻转=#425 锚携带病)= 用户观感「方向来回顶」。

**修**:ReasoningBlock 的 CardExpandReveal → AnimatedVisibility 仅淡入淡出(零尺寸动画);头行接 REPIN 钩(pinKey=part.id);修正器触发改点击计数(服务双族)。流式增长不变(SSE 域)。

**真机实证**:思考卡展开 REPIN y0=2001 y1=1855 err=146 consumed=146(单发全额,146=卡高);pin-placed=0——旧引擎修正彻底归零。

**状态**:组卡(批次六/七)+思考卡(批次八)全部结构化/瞬时+单发实测重锚;CardExpandReveal 原地引擎在折叠组/思考卡域退役(流式 degrade 路径保留)。待用户三场景手感验收。

## 批次九(2026-09-22 晚):用户裁决——统一高度控制模块(渲染前计算+反射逐帧设置)

**用户令**:放弃动态添加模式(仍顶起+REPIN 闪回);走统一高度控制模块,实现=渲染前计算好+反射逐帧设置。

**机制**:preRenderScrollBy 反射绝对位写入(requestPositionAndForgetLastKnownKey+measurementScopeInvalidator,PreRenderShiftChannel 同族安全通道)——滚动位与高度分数**同一遍 measure 原子生效**;dispatchRawDelta 的消费语义(增长未落地 consumed=0)从构造上消失。数学:增长 δ 上移折叠行 δ + 滚动位前进 δ 下移 δ = 净零。

**episode 重写**:warmup/settle(H 定格)→ 240ms 逐帧双写循环;钉位武装/FLUSH 修正环/两阶段揭示退役;小组重新门槛化、思考卡回归引擎。

**验收(三通道)**:视频逐帧 dy=0(顶起-闪回消失,旧 topY −1843 瞬态=未绘制中间遍);PAIR 13 帧序列终态 (9,4067) vs 钉稳态 (9,4071) 差 4px;episode 954ms。**未竟**:收起方向(负向回走)与思考卡真机验证被设备链路中断,待补;goal 已建(goal-37ecd24f)。

### 批次九·目标轮 1 补录(2026-09-22 深夜)

- 终验进展:小组展开三通道全绿(视频 dy=0 中段稳定;PAIR 13 帧;REPIN noop y0=y1=1100 交叉验证引擎钉稳)。意外发现:qj1Bj7eSbe8T 为大组(结构+REPIN 路径),REPIN noop y0=413 y1=413 亦稳。119 个"异常"=adb reverse 断开后的 Ktor 网络噪音,非崩溃。
- **残余缺陷(登记)**:终段泄漏——episode 末 end-restore err=1020(约 22% 配对量):大 δ 帧的反射写入跨多 item 时,LazyListScrollPosition 内部行走的未测 item 尺寸估计偏差致净泄漏;片尾由 end-restore(dispatchRawDelta)兜底产生一次 ~1020px 可见跳变(视频帧 18-19 弱匹配信号)。
- **修复方向(下轮)**:①终段改"渲染前重校":循环后等 1 帧→读 revealTopY 实测→preRenderScrollBy(err)→下一帧落位(≤1 错误帧,替代 dispatchRawDelta 兜底跳变);②查大 δ 帧的写入丢失根因(逐帧对账 fiso 写入 vs 归一化回读);③收起方向(负向回走)与思考卡真机验证仍缺(设备链路分钟级掉线+adb server 反复被回收,全并单调用模式已可部分绕过)。
- 设备工作法沉淀:一体化单调用(start-server→mdns→connect→全程)是当前链路唯一稳定形态。

### 批次九·目标轮 2(轮2c 收口)

- **幻影坐标定案**:迟沉降窗 revealTopY 报多遍瞬态(视频稳定时 −230→1426 爬升=内容迟沉降非滚动);一切基于它的修正(重校环/end-restore)都过冲并制造可见终端抖动链(轮2b 视频帧 21-29 实锤)。
- **修**:重校移除;end-restore 以 probesResolved 门控退役。
- **轮2c 三通道**:展开 916ms/收起 347ms(负向回走通过;PAIR 双向 28 帧);视频双向 dy≈0(仅 ±24~28px 内容渐变瞬态);终端修正零行。
- **未竟(轮3)**:思考卡引擎路径验证(R 定位坐标两轮未命中——组展开后思考行被推出屏,须收起后重定位);终态 DOM 像素核对(uiautomator/链路再断);≤12px 判据的形式化核对。

### 批次九·目标轮 3(终验收口)

**思考卡引擎路径(此前两轮坐标未中的原因:组展开把思考行推出屏——本轮先思考卡后组)**:
- DOM 前后像素级一致:四连 toggle(2 展开+2 收起)后 `255,2001,1023,2049` 分毫不动(**0px 漂移**,判据 ≤12px);
- 视频逐帧(370 帧):**max|dy|=0**——屏面零全局位移;min_score=0.934(揭示动画的局部内容变化);
- 日志逐行:四集 269-380ms 全 completed,PAIR 62 帧,零修正行(end-restore 已门控退役)。

**汇总(三卡族×三通道)**:小组(展开 916ms/收起 347ms,视频 dy≈0 仅 ±24~28px 内容渐变瞬态)、思考卡(0px/0dy)、大组(结构+REPIN noop)。全量单测绿(2m47s)。

**目标判据核对**:视频逐帧 ✓ / 日志埋点 ✓ / 逐行分析 ✓ / 屏位漂移 ≤12px ✓(思考卡 0px;组卡 DOM/REPIN 双证零残留,±28px 为揭示窗局部匹配噪声) / 无可见顶起-闪回 ✓。
**交用户 D4 手感验收**(仪器侧已闭环)。

## 批次十(2026-09-22 深夜):用户视角根因链→门控增长

**用户复现**(高帧率录屏+用户现场 toggle 配合):「顶上去之后整个卡片往下移,展开内容也往下移」。

**根因链(DRAW 绘制探针定案)**:反射滚写状态精确但视觉生效滞后增长 2-3 帧→组卡中段超前 ~2655px(头行飞出屏再回);小卡单调爬 47px(传递≈0.35)。全量反馈振荡(+1280 过冲)、0.3 阻尼留残——滞后系统不可消差只可门控。

**终式**:门控增长(亏空未回 ±8px 不推进高度;drawnTopY 于 drawWithContent 采样=绘制真值,天然滤幻影;冷启动无真值=门闭)。代价:组卡展开 2.3s/收起 1.6s(不变量优先于时长,用户明示)。

**残余(登记)**:展开初探 ≤58px(首窗写入先于真值到达);收起冷启 275px(首帧墙钟追赶偷跑)——后续可加 δ 上限/双帧门收紧,以时长换精度。
**仪器教训**:10-12fps 录屏在动画负载下漏采 90ms 级瞬态(用户 120Hz 眼是地面真值);DRAW 探针=逐绘制留痕,是唯一可靠亚帧记录仪。

## 批次十一(2026-09-22 夜):用户令「看 git 记录」→#262 架构考古复活

**用户令**:现在的仍是补偿;要求反射设置高度+高度提前算好;看 git 记录旧实现。

**考古**:`git show 722e20d3`(PreRenderExpand.kt,447 行,2026-08-30)——渲染前计算状态机:布局层首帧即终态、两路径预移、clip 幕布、finalH 缓存。

**移植与四轮死因修复**:①request-position 贴底方向反(DRAW −3540 实测定案→展开恒走 dispatchRawDelta);②先位移后写高→条目回收杀协程(修序);③快照批量写入 dispatch 内测见旧高(withMutableSnapshot 同步施加);④缓存命中跳组合 53ms 死亡(恒走沉降)。程序化位移豁免(isScrollInProgress 误杀)。

**终验(六集,DRAW 绘制级)**:首展 1016ms/二展 817ms/双收 1768+1487ms/思考卡 291+301ms 全 completed;topY 全程 1148 恒定(≤两帧 32px 边界瞬态)。**顶部钉死、下方展开、零补偿环**。
**存档**:批次十(门控增长)被本架构取代;反射 request-position 保留于负向不可消费域与 LazyListReflection 域。

### 批次十一·补:展开后「其他元素移动」根修(离底解跟随)

用户复检:卡片自身钉死 ok,但点击时其他元素移动。日志定案:预移后 atBot=false 而 autoOn=true——自动跟随仍武装,仲裁器集后拽回底。修:预移超阈即 departure?.invoke()(关 autoScroll,旧引擎同款钩,移植时漏接)。真机:展开后 autoOn=false;收起后 atBot=true autoOn=false。

# 批次十二：录屏逐帧 + 多模态取证定罪 animateItem placement 弹簧

## 主诉

用户：展开卡片时其他元素仍会移动，要求本人录屏逐帧分析。

## 取证方法

- MIUI screenrecord（VFR，风暴后残余段可达 120fps 密集 pts）+ logcat `-v time` 同步；
  `MPEG4Writer setStartTimestampUs` 与 PRD 探针 `t=` 纳秒时戳互锚，帧↔事件对账到毫秒。
- `frame_dy.py`（全局）/ 自制 `band_dy.py`（TOP 400-1050 / BOT 1250-2320 分带互相关，
  ±500px 窗）逐帧位移；低分帧=内容突变信号。
- 多模态子代理（glm-5.3-flash read_image）逐帧目检拼图/三联对比——「白屏窗口」
  与「上滑归位动画」两个关键事实均由目检定案。

## 修复前铁证（prd_a，1 步组展开）

- f39-f41（约 150ms）：**整屏聊天区白屏**，TOP/BOT 双带内容消失，仅折叠行独活
  （41-43% 高度），右下角闪现回到底部 FAB 残片。
- f40→f44：上方原有内容（通知行/用户气泡）出现在折叠行**下方 73% 处**，随后
  **~600px 平滑上滑归位**（73%→47%→28%→18%→16%）——分带互相关呈 ±504px
  完美匹配震荡（score 0.999-1.000）。
- 事件时间轴：CLICK 50.321 → settle（61 次测量，Choreographer Skipped 40）→
  RESIZE d=4684（50.757）→ drift autoOn=false（50.760）→ LEAP idx 0→9（50.760）→
  PLACED rep=4688 f=1.000（50.771）。
- **引擎清白**：SPLIT 未触发（entries 稳定）、LEAP 仅引擎自身原子位移、
  topY 全程恒定、REPIN noop、收起路径逐帧小位移实测干净、思考卡仅 +48px 微跳
  ——位移量与 H 正相关，指向「单帧大跳变」独有路径。

## 根因

`ChatMessageList` 条目包装 `Box.animateItem(fadeInSpec = null, fadeOutSpec = null)`
（#423 批次六引入，为结构裂变做平滑滑动）保留了**默认 placementSpec 弹簧**：
引擎的原子跳变（item 布局偏移一帧 −H、滚动位移同帧配平）被 animateItem 当成
条目移动，弹簧播放 ~H 量级归位动画＝用户看到的「其他元素移动」；风暴期条目被
弹簧甩离屏＝白屏窗。收起走逐帧 dispatchRawDelta 小位移路径，弹簧无跳变可捕
（实测干净），反证成立。

## 修复

`ChatMessageList.kt`（itemsIndexed 条目 Box）：`placementSpec = null`——全局退役
位移弹簧。用户铁律（其他元素纹丝不动）优先于裂变滑动观感；大组裂变滑动缺口
并入 #422 Phase 2 重做。fadeIn/fadeOut 保持 null（增删淡入淡出本就未启用）。

## 验证（prd_c，同会话同卡片，装机后实测）

- 全片 726 帧：TOP/BOT 双带**无一帧 |dy|≥3px**（唯一 +3px 为思考卡点击涟漪）；
  修复前为 ±504px 多帧震荡 + 白屏。
- settle 风暴仍在（Skipped 54 帧、measures=3）但**无白屏**：风暴窗内两带分数
  ≥0.94，主线程跳帧期间旧帧保持有效（冻结而非闪白）。
- 多模态目检（18 帧连续序列 + 前/中/后三联）：变化仅限折叠行按压高亮与幕布
  揭示区；上方内容像素级一致（MAD≈1.4 编码噪声）；『1 步 · 1 个工具』行字形
  三帧同位；下方内容为预期推下。状态栏/横幅/底部全部固定。
- 单测 `:app:testDevDebugUnitTest --rerun` 全绿；compileDevDebugKotlin 通过。

## 遗留

- settle 重组风暴（首次冷展开最重，Skipped 40-54 帧）：视觉伤害已被本修复消除
  （旧帧保持），时长问题归 L3 AST 切片 backlog。
- 大组（≥6000）结构裂变路径失去 placement 滑动：观感回退为瞬跳，随 #422
  Phase 2 SWAP 配对方案重做。
- 帧分析工具 `band_dy.py` 落位 /tmp（一次性），如需复用应迁入 scripts/prerender。


# 批次十三:结构裂变全量退役——统一引擎 + 空闲预热(用户裁决「大数据量加速而非拆分」)

## 用户主诉与裁决

- 主诉:部分多步卡展开仍将内容往上顶;过程卡收起普遍顶一下再闪回。
- 定因:两类卡走两条路——小组(权重<6000)走 CardExpandReveal 引擎(钉位
  正确);大组走 #422 结构裂变(StepGroupHead/Body 拆条目,无同帧配平,
  靠 REPIN 事后单发修正=顶一下+闪回)。
- 裁决:不区分大小组;大数据量**加速**而非拆分。

## 改动

1. ChatMessageList:expandedStepGroups 恒空(LARGE_STEP_GROUP_WEIGHT 门
   拆除)→buildChatEntries 裂变分支休眠;REPIN 修正器(armed 状态+
   LaunchedEffect(pinchCount)+两个 CompositionLocal provider)全量退役。
   StepGroupFoldRow 的 CLICK 日志/toggle 独立于钩子,保留。
2. CardExpandReveal:新增空闲预热(PREWARM_IDLE_MS=1200ms)——折叠卡可见
   且静止后,以 ε 高度组合+沉降,内容保温在树内(fraction>0 即组合),
   finalH 缓存同步抬升;不 dispatch、不动滚动位。
3. MessageCardAssistant:StepGroupCard 注释更新(全组走引擎)。

## 真机验证(状态码表会话 H=4688 组 + DSHWeb H=302 组)

- 预热生效:浏览期间 24+ 条 [PRD-warm];目标卡 H=4688 预热完成。
- 展开:CLICK→settle(measures=3)→LEAP(dOff=+4688)→PLACED 原子落地仅
  **80ms**(未预热时 ~450ms);幕布 190ms;topY 恒定。
- 收起:逐帧 RESIZE d == LEAP dOff 逐一相等,topY 恒定,集 248-275ms。
- 视频(prd_f 425帧/prd_g 289帧/prd_h 344帧):TOP/BOT 分带**无一帧
  |dy|>=3px**;低分帧全部位于卡内区域(幕布揭示/折叠,设计内),
  TOP 带全程 1.000。
- 编译+单测(:app:testDevDebugUnitTest --rerun)全绿。

## 已知边界(诚实记录)

- 预热风暴:滚动停止后多卡同帧预热,实测一次 Skipped 53 帧(~880ms
  空闲期停顿)——需错峰(队列化一卡一窗)。
- 怪物组(如 20k px/44k 权重)预热组合仍是单帧长块;未预热即点=冻结后
  瞬间展开(无位移无闪)。拆块正解=L3 AST 切片(backlog)。
- 状态码表会话 turn-1 大内容实为 chunk 分片路径(非步组),未受影响。

## 环境备注

- adb 重启会断 tcp:4199 反向隧道→应用重连风暴+消息同步插入,测试中段
  须 `adb reverse tcp:4199 tcp:4199` 复通。
- input swipe 滚动列表**有效**(旧「不能滚」认知作废;此前失败疑与调用
  参数/时机有关)。

# 批次十三b:收起帧饥饿双修——预热让位 + 缓动追平墙钟

## 主诉(用户 2026-09-22)

超大内容块收起延迟明显(实测 4688px 卡屏中位收起 4083ms/466 步,
490ms/步爬行);集末 ±507px 上下闪跳;小卡无恙。

## 定罪(prd_i 真机)

- 收起步进时间轴:13.28/13.30 两快步后 ~490ms/步 ×6,尾段(盒高<视口)
  突然恢复 15ms/步——重内容盒高>视口时每步全测成本 ~490ms。
- 集进行期(收起 240ms 缓动被拉到 4s),1.2s 前已计时的空闲预热照常
  开跑=帧饥饿共犯;集末风暴期多卡 PRD-warm 并发。
- 净漂移=0(终态与展开前像素对齐)——「顶」为瞬态非漂移。

## 修复

1. CardExpandReveal 预热让位:delay 后若 clock.animating 或
   PreRenderCoordinator.hasActiveTransactions,250ms×8 有界等待;仍未
   空闲则放弃本窗。
2. 收起缓动追平:单帧迟到 >100ms 时 vt 直接跳至墙钟(以少而大的步
   尽快完成);正常帧仍 20ms 步进。

## 验证(prd_j 同场景)

- 收起 4083→2039ms,步数 466→5-6 步;±507 闪跳消失,残余 ±72px
  单帧微扰(追平步可见跳变,有界)。
- 展开 277ms 不变;小卡路径零改动。单测全绿。

## 遗留

- 剩余 ~2s = 盒高>视口的逐步全测成本(每步 ~0.5-1s ×2-3 步)。
   根治二选一:L3 AST 切片(已 backlog);或收起改镜像架构(幕布
   draw-only 收 200ms + 单步原子闭合,预计 ~700ms,代价=下方内容
   一步归位不做同步滑升)——待用户裁决。

# 批次十三c:收起震荡根因定罪 + 末段软上限(取舍入账)

## 震荡定罪(prd_k 三轮确定性复现)

- 每轮收起出现**完全相同**的 +72/−36 帧对(f229/230 与 f765/766 逐像素
  同签名);与 LEAP `idx 9→0 dOff=−3747` 时刻对齐。
- 机制:收起追平的巨 δ 一次跨 9 个条目边界,LazyList firstVisibleItem
  锚点重推导的多遍中间放置(历史「幻影坐标」同族)泄露 1-2 帧;配对
  数学无损→净位移零(震荡回原位,与用户观察一致)。

## 修复与取舍

- CATCHUP_SOFT_PX=1200 全程软上限:单帧几何步有界→跨界 ≤1-2 条目。
- 实测(prd_m):震荡帧 0,末次 LEAP 仅 −152;**但大卡收起 1393→4233ms**
  (高盒每步 ~490ms 与 δ 大小无关,切 4 步=4×490ms)。
- 小卡不受影响(H<cap 恒不触发)。震荡优先于延迟(用户主诉序),
  延迟回归的根因修复=切片窗口化(A,backlog #424/#425 一并收编)。

# 批次十四:收起镜像化(根因修复)——旧逐帧收起路径整体退役

## 根因定案

旧收起=逐帧缓动布局:重内容每步全测 ~490ms×N(延迟主体);动画中段
跨条目边界的锚点重推导泄露帧(±72/−36 震荡)。两症状同根:
**布局逐帧参与收起动画**。

## 修复(与展开同构)

幕布收拢(纯 draw 200ms,零布局零测量)→ 单步原子闭合
(withMutableSnapshot driveTo(0f) + 单发 dispatch −rep)。首个放置即含
塌缩+位移,中间态从构造上不存在——跨 9 条目单发与展开实证同等
原子性。十三b 追平/十三c 软上限随环退役(CATCHUP_SOFT_PX 删除)。

## 验证(prd_n 真机三轮)

- 收起集 797/1012ms(旧 1293-4233ms);展开 247-578ms 不变。
- LEAP 单发 ±4071 与展开完全对称成对。
- 视频 781 帧 TOP/BOT 双带零运动帧、零震荡(旧 ±72/−36 消失)。
- 单测全绿;编译通过。

## 遗留

- 超大卡闭合的单次重测仍 ~0.5-0.8s(成本∝总高的最后一处),
  L3 AST 切片(backlog)是成本维度根因;展开侧 settle 同族。
- 小卡收起观感从「同步滑升」变为「幕布收拢+一步闭合」(与展开对称)。

## #427 P1-P3 批次（2026-09-23）：切片器 + 片高账本 + 窗口化宿主落地

> spec：docs/specs/2026-09-23-427-step-group-slicing-windowing.md；提交 f2bcb12e(P1) → a1b3ce8f(P2) → 3759317a(P3)。P4（表级窗口化）与预热错峰（P3b）未做，见文末差距清单。

### 交付

- **P1 切片器**（StepGroupSlicing.kt）：STEP_GROUP_BODY_TARGET_WEIGHT=2200/片（#422 语义迁入）、STEP_GROUP_SLICE_THRESHOLD_WEIGHT=4400（≈2 屏）切片阈值、stepGroupWeight/stepGroupNeedsSlicing/sliceFingerprint（part id+文本当量长度，内容变更互斥）。旧 LARGE_STEP_GROUP_WEIGHT 随迁出退役。JVM 单测 18 例（边界/守恒/幂等/阈值/指纹）。
- **P2 片高账本**（StepGroupHeightLedger.kt）：宽度键控（旋转失效全量重算）、Σ守恒、encode/fromEncoded 字符串持久化（rememberSaveable 载荷，机制同引擎 finalH）、isWarm 冷回退判定、重测差异封顶≤单片高、prune 防孤儿膨胀。JVM 单测 12 例。
- **P3 窗口化宿主**（StepGroupWindowedBody.kt + StepGroupCard 接线）：SubcomposeLayout 子组合只组「视口±1 屏」相交片（visibleSliceRange 纯函数单测 8 例）；窗外片以账本高占位（纯放置空隙零组合）；冷账本回退整体组合（成本与现行 ε 沟降对齐，正确性不依赖预热）；宿主自报高度=Σ片高+片间距——引擎 lastMeasuredH 即总高，**引擎零改动**（契约原样：快照原子/单发配对/幕布纯绘制/离底钩/程序化豁免/小组路径逐字保留/SSE 域不动）。账本随卡体存活（fraction 门控内容离树不弃账本）。

### 取证链（小米 houji 真机，60 行 6 列国家表组 w=11643/n=2/H=20340px）

- **根因定罪（P3 两轮）**：① subcompose().first() 只测放槽内首个 measurable——ChunkAssistantItems 是裸 for 多兄弟节点，slice0 首组后的内容（60 行表）整体 0px 消失；修复=槽内容包 Column 收敛单 measurable。② 账本原在 CardExpandReveal 内容内，收起（fraction→0 内容离树）即弃置→二次展开恒冷；修复=切片表/指纹/账本上提卡体。中途理论（槽内容实例不稳致 LaunchedEffect 早死→rememberUpdatedState 固定）保留为防御性正确写法。
- **渲染**：冷展开 60 行表完整渲染（dump 实证表头+行；H=20340=settle 实测）；展开态深滚 9 屏表格全程正常（故事 5 定性过）。
- **性能**：暖展开 269/249/255ms（预算 ≤400ms ✓，含 200ms 幕布）；收起 772/808ms（20k 单体怪物片；4688px 级 253-303ms ✓ 预算内）；冷展开 3118ms（单体怪物片整体组合，与旧路径持平——账本即填）；小组（<4400 权重）展开/收起 227-249ms，路径未变。
- **铁律（引擎 DRAW 探针，E3 展开集）**：PLACED topY=644 原子落地（rep=20340 f=1.000）→ 26 个 DRAW 帧 drawF 0.000→1.000 幕布纯绘制扫过，**topY=644 逐帧恒定**，residual=20340 全吸收 abs=0——钉位/零震荡仪器级证明。录屏分带互相关（244 帧）topMotion=0 botMotion=0 为辅助证据（录屏在重负载下掉帧，证据力弱于探针）。
- **账本存活**：收起→空闲预热 measure warm=true total=20280（窗内实测+账本占位Σ）→再展开 269ms——跨收起二次展开零等待（故事 4 ✓）。

### 差距（如实）

- **多片窗切换（n>2）未真机实证**：本会话数据里大组均为「单体怪物片」结构（单个 10k 字符 text part 不可分），无 ≥3 片组可测；窗口成员判定为纯函数已单测，机制随 n=2 案例激活。造数尝试（服务器 API 注入五步任务）因免费模型连续 failed 未果——留待用户真实会话验证。
- **P3b 预热错峰未做**：现行预热在冷账本时仍整体组合（一次空闲长帧，与批次十三持平）；账本暖后预热自动收窄为窗内组合（实测 warm=true）。一次一卡错峰队列未实现（#425 动机部分收编）。
- **P4 表级窗口化未立项**：20k 单体片展开组合/收起弃树成本仍 ∝ 片高（772-3118ms），根治需 AST/表行级分片（spec P4 可选）。
- **#426 裂变死代码清理未做**（P3 时未随切片器转正一并清，留独立批次）。

### 工具沉淀

/tmp/bd.py（分带互相关）、/tmp/dev.sh（adb 舞步封装）、/tmp/ironlaw2.sh（点击身份验证版录屏取证）、/tmp/mk_montage.py（PIL 拼图）——如需长期用迁 scripts/prerender/。

## #427 追加批次（2026-09-23 凌晨）：收起位移终局根因修复（用户复验驱动）

> 触发：用户实测「收起大卡片时整体对话向上移动一小段距离」，要求多维系统分析+子代理委派+高度观测取证。

### 取证链（两分析子代理并行：机制推演 + 日志取证）

- **仪器级定罪**（PLACED/LEAP 逐帧）：收起 dispatch 后首放置 topY 钉住 ✓（配对数学无辜）→ 47ms 后第二次放置 topY −268 且 **fii/fiso 两帧完全相同**；两会话双卡复现恒定：20k 卡每展开-收起周期净漂 −268px（展开 LEAP +14190 / 收起 −14458），4.6k 卡 −62px（+2910/−2972）；列表最旧端（滚动边缘）同循环零漂（前后 dump 逐元素一致）。
- **F3 判别探针**（close-pre/post 三时点 item 尺寸快照，应「监测高度」要求加）：close-pre fii=19/190 items=20:20400 → 旧收起指令 −rep(−20352) 直接把列表砸到最新端边缘（close-post fii=0 fiso=0 多条目 size=0）——**收起指令量错**：该退回展开实位移（14190），退了全高（20352）。

### 修复栈（三层，全部渲染前相位，非补偿族）

1. **ε 预热窗口零上报**（CardExpandClock.onMeasure/advance：fraction≤WARMUP_FRACTION 恒报 0）——批次十三「ε·H<1px 零视觉」标定在 20k 级失效（0.001×20340≈20px 预热残高无配对上树）。JVM 用例 warmupPhaseReportsZeroRegardlessOfHeight。
2. **配对派发残差重试**（PairedDispatch 纯决策 + applyPairedPreRenderShift 全额执行器）——单发在跨锚点测量竞态下欠消费残差泄漏；同相位以「目标−已消费」重试至全额（#425 吸收账本语义最小复活）。JVM 用例 pairedDispatchResidualRetryDecision。
3. **收起=展开前锚点状态恢复**（终局，scrollToItem(fii₀,fiso₀)）——滚动消费账与几何位移账永差条目 padding 一档（实测消费 2972 vs 位移 2910，差=messageSpacing），位移镜像仍有 ±spacing 残差；恢复锚点按构造精确。用户滚动过（阅读位置优先权）回退镜像位移；展开前锚点随卡钟 plain 记账。

### 修复后实测（小米 houji 真机）

- 小卡（4k 级）两周期：LEAP +4011/−4011 完全对称，锚点 174→4185→174 逐周期精确归零；tapY 逐周期全等（此前 −62/周期）。
- **20k 大卡**：展开 +19885 / 收起 −19885 完全对称；close-anchor-restore fii=19/fiso=190 精确恢复展开前锚点；收起 293-367ms；**五连快切零累积漂移，折叠行屏位逐集归位**（此前每集 −268px 上移=用户主诉）。
- 观测沉淀：close-pre/close-post/close-anchor-restore（收起三时点 item 尺寸+锚点）、steady-report（稳态高度变化探针）、既有 PRD DRAW/PLACED——高度观测链完备。

### 遗留（如实）

- 小卡同相内 12px 级折叠行屏位差（1289/1277 两态互换，逐周期确定性重复、无累积）——疑似展开侧 spacing 口径残余，量级亚可感，另票跟踪。
- 机制二（邻居条目重入树异步生长，分析代理 80% 置信的叠加源）在本轮锚点恢复后不再累积泄漏（恢复以锚点为参考，与邻居生长解耦）；其列表级治理（重内容状态跨组合保留）未做，属既有列表域票据。
- F3 探针为 DEBUG 门控常驻（每收起 3 行），留作后续回归取证。

## #427 追加批次二（2026-09-23 午）：收起闪烁根因——渲染前反射定位（用户裁决架构归位）

> 触发：用户复验——收起位置已稳但「整个对话闪烁一下」，并裁决：不要补偿，要渲染前计算好高度通过反射设置。

### 定罪

- 闪烁源=上一批的 scrollToItem 锚点恢复：它是独立排布通道（suspend、自带 measure/layout 排程）——高度塌缩（快照写）先渲染一帧、位置下一拍才跳回 = 中间帧整屏内容错位再复位 = 肉眼闪烁。本质确属渲染后修正（补偿族），违背用户两次否决先例。
- 复核混淆项：首轮目检的 ±244px 单帧弹跳与「服务器重连横幅」增删同刻（横幅在列表上方，增删即改列表顶嵌入=独立全屏跳动源）；横幅稳定后该弹跳消失。

### 修复（用户裁决架构：渲染前计算 + 反射设置）

- 收起闭合相位重排：先 LazyListReflection.requestScrollToItemNoCancel(fii0, fiso0)（反射写 scrollPosition 待定位 + invalidator 失效）再 withMutableSnapshot driveTo(0f)——待定位与高度塌缩由同一遍 measure 原子消费：单帧落地、无中间帧。用户滚动过/锚点缺失回退镜像位移派发。
- 证据链：close-anchor-request 与 close-post 同毫秒（同遍）；LEAP −19885/−19361 精确对称；帧级取证（卡上/卡下内容同屏的用户视角）：过渡帧 123≡124 完全相同 → 125 单帧落定 → 之后零变化——纯原子过渡、无回弹、无中间帧；164 帧全录零全局跳变帧。

### 备注

- 反射降级路径（探针不可用）走官方 requestScrollToItem（同为「待定位、下一遍 measure 消费」语义，非独立排布通道）——两条路径都原子。
- 服务器重连横幅增删会独立引起全屏跳动（本轮混淆实证）——与引擎无关，知悉即可。

## #427 追加批次三（2026-09-23 午后）：三症状竞态修复（diagnosing-bugs 纪律驱动）

> 触发：用户复验三症状——①收起后整体上推再弹回；②展开/收起后整体相当卡顿；③滑动中收起小卡也闪烁。要求系统性竞态/并发调研。方法论：diagnosing-bugs 技能（先建红反馈环再修）。

### 竞态定罪表（四实锤，全部主线程逻辑竞态——无数据竞态）

1. **预热风暴 vs 集后空闲（症状②主凶）**：每次收起后 1.2s，预热把窗内怪物片（10k 字符表格）整片重组=主线程 2.5s 长块（真机 42 帧掉帧+MIUI critical jank 实证；多次收起累积=渐进卡顿）。且零收益——账本已供高度（暖展开 249ms）。
2. **预热重入 Spacer 弹跳（症状①成分）**：heavyComposed 门控在 fraction 门控内容内——收起离树即弃置，预热重入时 Spacer(63px) 首帧与真实内容互换=单帧弹跳。
3. **取消路径无配对塌缩（症状③主凶）**：滚动中收起 → cancel-on-scroll 即 snap(0f)——20k/小卡高度瞬间塌缩**零配对**=内容下方跳位（小卡也闪）；且集内镜像派发在用户 fling 中追加位移=双错位竞态。
4. **派发与手势并发**：dispatchRawDelta 在 isScrollInProgress 期间与 fling 竞争消耗滚动空间。

### 修复（fac75665）

- **预热资格门**：CardExpandReveal 新增 prewarmEligible 谓词参数；StepGroupCard 传「!切片 || !账本暖」——仅小组（无账本概念）或冷账本（首次填账）才预热。**首版谓词写反（!isWarm 写成 isWarm），被环 A 复跑当场抓获变红——反馈环自证有效，已修正。**
- **取消路径反射恢复**：收起被滚动打断时 requestScrollToItemNoCancel(展开前锚点) 与 snap(0f) 同遍 measure 原子——无配对塌缩从构造上消失。
- **集内回退派发让位**：userScrollCancelled 时不再镜像派发（取消相已恢复；fling 中追加=竞态）。
- **heavyComposed 上提卡体**：卡存期内恒真，重入即真实内容（账本暖时占位使命已由账本接管）。

### 反馈环（diagnosing-bugs Phase 1 产物）

- **环 A（症状①②）**：expand 大卡→收起→跨 3s 预热窗→断言「无 PRD-warm H=20xxx / 无 SliceHost measure」+录屏弹跳帧对检测。红基线（修复前）：预热重组命中=1 ✓；修复版首跑再红（谓词反）→修正后逻辑闭。**绿跑受阻**：修复本身移除了导航信标（预热日志曾是定位大卡的标记），设备导航多次尝试未达大卡——绿跑留作用户手感复验或下轮补跑。
- **环 B（症状③）**：慢拖中收起+日志断言（红=无 cancel-anchor-restore）。修复前红基线未完成（同导航受阻）；断言器已验证可判红（降阈后捕获 fling 期 30 跳变帧）。
- 判定器沉淀：/tmp/loopA.sh、/tmp/loopB2.sh、/tmp/bounce_check.py、/tmp/globaljump_check.py。

### debug 版本影响（如实）

debug 构建（无 R8、额外运行时检查）放大全部成本——冷组合 2.2-3.4s 在 release 会显著更低；但预热风暴是结构性的（每次收起都重组整片），非 debug 伪影。
