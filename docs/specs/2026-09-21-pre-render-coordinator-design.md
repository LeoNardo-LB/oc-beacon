# PreRenderCoordinator — 渲染前计算模块 Spec v1(#432)

> v0 被本版取代。依据:六路调研(docs/research/pre-render-coordinator/00-synthesis.md 的 K1-K12)+ 用户五项裁决(P3 拷问轮)。
> 状态:**待用户评审签收**——签收前不动任何实现代码。

## 0. 决策日志(P3 裁决,不反复)

| # | 裁决 | 内容 |
|---|---|---|
| D1 | 范围=全量 | 视口配对+跳转租约统一+流式入队+测量缓存(RenderReadiness/RenderSupply)收编,分 Phase 到齐 |
| D2 | 动画契约=统一 | 全部卡片(含大组)逐帧平滑高度动画+位置钉死双硬指标 |
| D3 | 流式=入队(根因修复) | Phase 1 租约让位防互搏+行为基线冻结;Phase 3 整体收编入队(含换锚语义) |
| D4 | 验收=每步人工 | 每小步仪器绿环+用户真机手感签收才合入下一步;步的大小按验收节奏切 |
| D5 | 反射炸弹=Phase 0 | 立即单独提交探测降级护栏(反射失败→公开路径降级+日志),与重构解耦 |

## 1. 不变量(每条绑定验证环)

- **I1 位置钉死**:事务锚定内容(卡片顶缘)视口位置全程逐帧恒定(图标条 dy=0 判定)。
- **I2 同帧闭合**:每帧 draw 前当帧全部增长配对完成,残差=0;禁止跨帧补偿(K1,四源收敛)。
- **I3 视口租约**:事务激活期间守卫/MSGEFFECT/锚底/拉底让位(K2)。
- **I4 用户优先**:用户滚动=UserInput 优先级,自动取消事务并 snap(K7)。
- **I5 单写者串行**:底层高度/滚动写全系统唯一路径;上层意图并行,底层帧事务串行(K2/K5;用户硬约束)。

## 2. 架构(K1-K8 落地)

```
意图层(并行)   tap×N/滚动/流式到达 → Intent(key, action, priority)
                  入口: MutatorMutex.mutate(priority) [PUBLIC API,K3]
                  优先级: 手势 UserInput > 程序 Default (K7)
单写者协议(K2/K5)─ 唯一执行器,以下三相每帧一批:
  ANIMATE  withFrameNanos 帧首汇合(Choreographer ANIMATION 相,严格早于 traversal)
          构建帧批 → withMutableSnapshot{ 全部 fraction/时钟写 } [K4]
          异常=abort 无痕;冲突=本帧作废意图回队;块内禁挂起(官方 KDoc)
  LAYOUT   几何节点只上报(Modifier.layout 公开上报,K8);禁在布局回调写状态(K11 一帧滞后)
  FLUSH    列表根单点 OnPreDrawListener(K1,官方 KDoc 钦定场景;可 return false 请求重排)
          按确定序逐事务: scroll{} 配对 dispatch → 实测锚(key+offset,K6) → 残差当帧闭环
锚模型(K6)      (itemKey, offsetInItem);key 映射吸收锚前增删;锚后尺寸变化折算已消费滚动
跳变场景(K10)   requestScrollToItem(取消在途写者+不触发 placement 动画的官方保证)
反射层(K9)      仅 SSE 遗留:Phase 0 护栏(探测降级+keep);Phase 3 随收编消亡
```

## 3. 统一动画契约(D2)

所有卡片展开/收起:逐帧几何 tween(240ms 虚拟时钟+单帧钳制,继承已验证账本数学)+ 每帧同帧配对(FLUSH 相) + alpha 随 fraction 淡入。大组(如 2852px)与小卡同契约;#425 振荡恐惧由 I5 单写者+FLUSH 单点排解(历史上振荡源于多写者互搏,非逐帧本身)。分片硬切换(weight≥6000)在 Phase 2 收编为 SWAP 事务:结构变化一次性入账,同样走配对+锚定,消「内容上顶」。

## 4. 行为基线冻结(重构期间不改语义,差异需显式裁决)

快速反向 toggle 重定向 / 用户滚动取消 snap / 冷组合 snap 不重播 / 流式 turn 降级裸 AV / 回收复用重入 settle / 守卫贴底再武装去抖 / GUARD 让位条件。每条在迁移对应 Phase 前用红环固化现状,迁移后必须复绿(或显式变更并记录)。

## 5. 验证矩阵(P0 红环集,先于一切实现固化)

1. 位置不变量:展开/收起双向,各尺寸卡片+大组,图标条逐帧 dy=0
2. 同帧闭合:账本残差帧末=0(debug 断言);无跨帧纠偏
3. 贴底武装态:展开/收起全程无 GUARD/MSGEFFECT 行(logcat)
4. 守恒:T1 大组上方条带 ±2px 内
5. 取消:动画中 fling → snap 落位,无回拽
6. 连击:短时间思考+过程连点,最终布局正确且无战争
7. 行为基线:第 4 节每条的现状固化回放
8. 性能:gfxinfo 帧时间不劣化;FLUSH 零分配

## 6. 性能预算(硬指标)

FLUSH 相 O(活动事务数),帧内零对象分配;仪器(BuildConfig.DEBUG)可关;单事务批 ≤8 次重布局收敛(继承 drain 边界);红环 8 持续监测。

## 7. 迁移计划(步边界=用户手感验收轮,D4)

| Phase | 内容 | 量级 | 验收 |
|---|---|---|---|
| 0 | SSE 反射探测降级护栏(独立提交,D5) | ~20 行 | 反射失效模拟下走降级+日志 |
| 1 | 协调器核心(MutatorMutex+帧事务+OnPreDraw FLUSH+锚模型)+CardExpandReveal 引擎迁入(全卡型统一契约)+守卫/MSGEFFECT 租约 | ~400-500 行 | 红环 1-6+手感 |
| 2 | 大组硬切换 SWAP 事务+JumpNavigation 租约统一(消双租约) | ~150 行 | 红环 1-6+大组手感 |
| 3 | 流式收编入队(行为基线冻结迁移+换锚语义;铁律域逐条裁决) | ~200 行 | 红环全量+SSE 专项 |
| 4 | 测量缓存/RenderSupply 收编(D1 全量收尾) | ~100 行 | 渲染就绪无回退 |

## 8. 风险与红线

- 反射三件套纪律:新增反射=keep 规则+探测降级+release 冒烟(K9/F 路);BOM 升级=javap 字节码核销。
- 快照 KDoc:withMutableSnapshot 块禁挂起——帧批构建与执行分离。
- OnPreDraw return false 慎用:仅 FLUSH 残差未闭时,防无限重排(≤2 次/帧)。
- SSE 铁律域:Phase 3 前一行不改;Phase 3 中每条铁律迁移=独立红环。
