# PreRenderCoordinator — 渲染前计算模块设计(#432)

> 背景:#420-#431 十一轮收口实证——视口配对逻辑分散在 CardExpandReveal(逐帧
> drain/arming)、ChatScrollController(守卫/MSGEFFECT/autoScroll)、episode 末
> end-restore、大组硬切换(无配对)四处,每处局部正确,组合出战争/震荡/漂移/
> 回跳/闪现五类竞态。本模块把「高度变化→滚动配对」的全部计算集中为单一权威。

## 1. 不变量(所有路径共享,验证环断言对象)

- **I1 位置不变量**:事务锚定内容(卡片顶缘)在视口中的位置,事务全程逐帧
  恒定(图标条 dy=0 判定)。
- **I2 同帧闭合**:每帧 draw 前,当帧全部增长已完成配对(残差=0)。**禁止
  跨帧补偿**——end-restore 类延后纠偏是可见跳变的历史来源。
- **I3 单一视口主权**:事务激活期间,守卫/MSGEFFECT/autoScroll 让位(租约)。
- **I4 用户优先**:用户滚动立即取消事务(snap 到目标),守卫不得回拽。

## 2. 帧管线(固定顺序,每帧一次)

```
动画相(ANIMATION)  协调器推进活动事务虚拟时钟 → 写 fraction/目标
布局相(LAYOUT)     几何节点(cardExpandGeometry)按 fraction 上报高度
调和相(RECONCILE)  列表根 onGloballyPositioned(post-layout, pre-draw)触发
                    唯一入口 reconcile():
                    for tx in active(注册序):
                        dispatch(tx.report - tx.absorbed)   // 配对
                        锚点实测校验 → 残差当帧闭环(≤N 次重布局)
                    全部残差=0 才放行 draw(I2)
```

关键差异(对比现状):调和从**每组件 onGloballyPositioned 各自为政**改为
**列表根单点、有序、可重入校验**——多事务并发时按序配对+每步重测,杜绝
两个 drain 通过共享视口互搏(#430 战争根因)。

## 3. 事务模型

```kotlin
class PreRenderTransaction(
    val key: String,                    // 组件实例键
    val kind: Kind,                     // GEOMETRY / SWAP / STREAMING
    val anchorY: () -> Float,           // 锚定内容实测顶缘(校验 I1)
    val reportH: () -> Int,             // 本帧已上报高度(布局产物)
) {
    var absorbed = 0                    // 已配对位移(账本,唯一)
    var phase: Phase                    // Clock(逐帧) / Settle / Done
}
```

- 账本只记「实际消费」(dispatchRawDelta 返回值),未消费残量经
  「目标−账本」下帧自动重试——继承 #424/#425 已验证的守恒数学。
- 事务完成当帧注销;取消(用户滚动)走 snap 路径,同帧落位。

## 4. 组件接入 API(适配器只声明,不实现动画)

| 接入方 | 声明 | 协调器负责 |
|---|---|---|
| CardExpandReveal(思考/工具/事件/…) | registerGeometry(key) | 时钟驱动、逐帧配对、取消/重定向、I1 校验 |
| 大组硬切换(ChatMessageList) | registerSwap(key, Δ) | 结构变化一次性配对(+锚定,替代失败五轮的手工锚定) |
| SSE 流式高度补偿 | registerStreaming(key) | 流式增长配对(替代 COMP-MSG,迁移中) |
| 守卫/MSGEFFECT/autoScroll | hasActiveTransactions() 查询 | 激活期间让位(I3) |

新组件接入 = 声明事务 + 提供锚点/上报回调;动画、配对、竞态处理零实现。

## 5. 分阶段迁移

- **Phase 1(本次)**:协调器核心 + CardExpandReveal 引擎迁入(两阶段与逐帧
  路径统一为协调器事务;arming/canClose/end-restore 分布式状态废除)+
  守卫/MSGEFFECT 租约让位。
- **Phase 2**:大组硬切换 registerSwap(配对+锚定一并解决,#4 已知缺口)。
- **Phase 3**:SSE COMP-MSG 迁移 registerStreaming,退役 item 级补偿。

## 6. 验证矩阵(每阶段全绿才合入)

1. 位置不变量:展开/收起双向图标条逐帧 dy=0(I1)
2. 同帧闭合:账本残差帧末恒 0;无跨帧纠偏跳变(I2)
3. 贴底武装态:无战争(GUARD 让位验证,I3)
4. T1 大组守恒:上方条带 ±2px 内
5. 用户滚动取消:动画中 fling → snap 落位无回拽(I4)
6. 单测:账本守恒/取消语义/租约仲裁纯函数化部分
