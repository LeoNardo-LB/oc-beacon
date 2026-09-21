# PreRenderCoordinator 调研合成(P2) — 六路交叉验证

> 素材:01 官方源码 / 02 跨框架 / 03 社区 / 04 内部盘点 / 05 串行化原语 / 06 API 边界。
> 本文档只做三件事:收敛判定(多源一致才采信)、勘误台账、开放问题(供拷问轮)。

## 1. 交叉验证收敛表(核心结论按证据强度排序)

| # | 结论 | 证据源 | 强度 |
|---|---|---|---|
| K1 | 单一 flush 点=布局后绘制前,一帧内多变化合并一次净补偿 | B(Chromium 双相位) + E(OnPreDrawListener 官方 KDoc 逐字命中;ViewRootImpl measure→layout→onPreDraw→draw 源码序) + A(scroll{}+forceRemeasure 当帧) + 本仓 #426 实测 | 四源一致 |
| K2 | 单写者=结构化排斥(队列+单执行器),非 OS 锁 | 用户约束 I5 + E(LazyList 三件套:挂起写唯一入口 scroll{}、同步写线程封闭、聚合 applyMeasureResult 单点) + B(调节权唯一化) + C(Flutter #99158 反面同构) + D(五域分散=竞态温床) | 五源一致 |
| K3 | MutatorMutex 是 PUBLIC API(foundation 根包),Default<UserInput<PreventUserInput 优先级取消 + tryMutate() 同步快路径 | E(源码+版本锚定) | 源码实证 |
| K4 | 帧事务=withMutableSnapshot 一帧一 apply(all-or-nothing;异常=abort;冲突=作废回队) | E + 跨域同构(SQLite WAL / Bevy ECS sync point) | 双源 |
| K5 | 快照系统不保证跨协程串行(官方 NOTE 原文)→单写者必须上层协议强制 | E(Snapshot.kt:830-832) | 负面结论,防走弯路 |
| K6 | 锚=itemKey+index+offset(key 映射吸收锚前增删;锚后尺寸变化折算已消费滚动) | A + B(Chromium SerializedAnchor 同构) + C(key 不稳定=跳动案) | 三源一致 |
| K7 | 用户意图优先=优先级取消(手势 UserInput>程序 Default) | E(Scrollable 源码) + B(kAnchoring) + 本仓 I4 | 三源一致 |
| K8 | 渲染前改滚动位无公开直通 API;公开组合可达:scroll{}+forceRemeasure+Modifier.layout 上报+OnPreDrawListener | F(三通道核验) + A | 双源 |
| K9 | 反射仅剩 SSE 遗留,且活在 value-class unboxed 表示巧合上(定时炸弹) | F(1.12.0 javap 实证) + D | 双源 |
| K10 | requestScrollToItem 兼具取消在途写者+itemAnimator.reset 不触发 placement 动画 | C(LazyListState.kt:469-499) + A | 双源 |
| K11 | 布局回调里写快照状态=官方点名反模式(一帧滞后) | A(OnRemeasuredModifier KDoc) + E(相位不倒退) | 双源 |
| K12 | 原位 delta 补偿是社区空白(官方 animateItem 不做 size) | C(全开源扫描) + A(b/150812265) | 双源,立项依据 |

## 2. 勘误台账

| 原始说法 | 修正 | 出处 |
|---|---|---|
| copyWithScrollDeltaWithoutRemeasure 在 LazyLayoutIntervalContent/LazyLayoutScrollPosition | 实为 internal LazyListMeasureResult:100;LazyLayoutScrollPosition 类型名不存在 | F 纠 A |
| Modifier.onRemeasurementAvailable 扩展 | 是 RemeasurementModifier 接口方法 | F 纠 A |
| SnapFlingBus | 不存在,应为 SnapFlingBehavior | E 纠提示词 |
| Flutter #125701 | engine roll 公告;正主 #99158 | B 自纠 |
| 本仓 requestScrollToItem @ExperimentalFoundationApi | API 已公开非实验 | F 纠本仓注释 |
| #43 注释声称 BOM 2026.05.01=MutableState[Unit] | 当天即已不符(1.6.0 起已 value class) | F 纠本仓 |

## 3. 合成后的架构定案(供 spec v1)

```
意图层(并行)  tap×N/滚动/流式 → Intent(key,action,priority)
                 │ MutatorMutex.mutate(priority) — K3/K7
事务层(串行)   单写者协议 — K2/K5:
                 帧首(ANIMATION):withFrameNanos 汇合,构建帧批
                 withMutableSnapshot{ 批内所有 fraction/时钟写 } — K4,异常=abort
                 布局相:几何节点只上报(Modifier.layout 公开上报 — K8/K11)
                 FLUSH:列表根单点 OnPreDrawListener — K1
                   按确定序:scroll{} 配对 dispatch → 实测锚(key+offset — K6)
                   → 残差当帧闭环;必要时 return false 请求重排
执行层          requestScrollToItem(跳变场景 — K10) / forceRemeasure(公开 — K8)
反射层          仅 SSE 遗留:探测降级+keep 三件套,Phase 3 消化 — K9
```

## 4. 开放问题(→ 拷问轮 P3)

- Q1 范围:一期仅视口配对,测量缓存/跳转定位 Phase 2+?
- Q2 动画契约:小卡逐帧平滑+位置钉死双硬指标,是否同样约束大组?
- Q3 流式仲裁:流式增长与展开事务同帧冲突时,换锚(W3C)还是排队?
- Q4 验收标准:仪器绿环=可合入,还是每步人工手感验收?
- Q5 SSE 反射定时炸弹:本次立即加探测降级护栏,还是 Phase 3 一并?
