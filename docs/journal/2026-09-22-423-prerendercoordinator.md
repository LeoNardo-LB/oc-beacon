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
