# 07 · 行为基线冻结(#423 P0,2026-09-22)

> spec §4:迁移期间不改语义,差异需显式裁决。本文 = Phase 0/1 落地**前**的现状留档。
> 采集环境:HEAD e90571f0(app 代码 = 7dbb4189),dev debug 包,真机 Xiaomi 14(1200x2670@3x),
> 会话「**第一步:常见 HTTP 状态码表**」(十一轮主战场会话),贴底跟随态。
> 复现资产:scripts/prerender/(帧=录屏 20fps 抽取;判定标准见 README)。

## 现场1:贴底小卡(思考卡,H=146px)收起→展开循环

驱动:tap 折叠行(展开)→4.5s→tap(收起)→5s;录屏 210 帧 + logcat 同窗。

| 环 | 结果 | 证据 |
|---|---|---|
| 1 位置不变量 | **GREEN**(思考行图标逐帧 dy=0) | icon_track 置信帧 57 帧 MOVED:0 |
| 2 同帧闭合(墨水) | GREEN(无涨-塌-复现) | flick_track FLICKER_FRAMES:none |
| 4 守恒(瞬态) | **RED** | strip_track fold 条带峰值 **+198px@frame48 后归零**——展开落地帧下方内容瞬态下坠再回弹,即用户否决的「闪现一下」;Phase 1 主体(FLUSH 同帧闭合)的消灭对象 |
| 3 贴底武装态 | 无战争线(非租约功劳) | episode 全程 0 条 GUARD reanchor/MSGEFFECT;成因=展开位移 146px>100px 触发 departure → autoScroll=false → 守卫哑火(atBot=false autoOn=false @00:19:18.125)。**结构性窗口仍在**:settle(≤600ms)+PhaseB(240ms)静默期 > 守卫 250ms 去抖,若 autoScroll 存活(位移<100px 卡)去抖到期即插入——I3 租约从构造上关闭 |

logcat 关键时间线(完整见 journal):
```
00:19:17.238 settle done H=146            ← 展开集:内容沉降
00:19:18.068 placed pending=146 consumed=146 topY=1903  ← A 阶段排干同帧配对
00:19:18.125 atBot=false autoOn=false      ← departure 已关跟随
00:19:18.387 episode done f=1.000 (1208ms)
00:19:25.366→25.637 收起集:cmd==consumed 逐帧,topY 恒 2049,absorbed 143→0,297ms 干净收官
00:19:25.579 atBot=true idx=0 off=0        ← 贴底几何恢复
```

## 现场2:守卫/再武装现状(代码+历史证据,未新录)

| 冻结项(spec §4) | 现状 | 证据源 |
|---|---|---|
| 快速反向 toggle 重定向 | 新集携带 carriedAnchor,f 从当前值回摆 | #425 真机 24 连点净漂 -73px 根修;journal #425 |
| 用户滚动取消 snap | isScrollInProgress 上升沿 → snap 当前 visible 目标(读当下值,防空白卡死态) | 代码 C4;#420 追修复 |
| 冷组合 snap 不重播 | 初值即目标(fraction==target)不进动画;滑出回收/滑回不重播 | 代码 LaunchedEffect 入口判定 |
| 流式 turn 降级裸 AV | LocalInStreamingTurn 逐 item 提供;COMP-MSG 补偿独占 | 代码 C11;铁律 3 |
| 回收复用重入 settle | content 离树重入同样 warmup+settle(lastMeasuredH==0 判定已移除) | #422 注释 |
| 守卫贴底再武装去抖 | 稳定非滚动 ≥250ms+复查(collectLatest 取消语义) | AutoScrollArbiterTest 六用例 |
| GUARD 让位条件 | **仅 jumpLockActive**(E2);本增量将 +hasActiveTransactions(I3 租约)——**显式变更**,D3 裁决背书 | ChatScrollController.kt:204/417 |

## 仪器勘误(本轮新学)

- detect_state 的「第一步:常见/382ms」双标记在本会话(多思考卡)会跨卡误判——
  状态判定必须绑定**目标卡**的折叠行全文+其展开内容标记(README 纪律 3 的补充)。
- 安装通道:MIUI 对 adb 全新安装弹「USB安装提示」且静默拒绝(INSTALL_FAILED_USER_RESTRICTED),
  固化 scripts/prerender/install-dev.sh 自动点「继续安装」;覆盖安装(-r 同签名)不受影响。
