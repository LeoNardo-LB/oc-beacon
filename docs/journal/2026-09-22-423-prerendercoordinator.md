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
