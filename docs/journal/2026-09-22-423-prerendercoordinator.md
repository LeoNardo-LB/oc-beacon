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
