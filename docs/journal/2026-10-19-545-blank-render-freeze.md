# 545-blank-render-freeze（2026-10-19）

> 状态：进行中
> 关联：（spec 路径，若有）·（issue 编号，若有）
> 来源：用户反馈 / grilling / E2E / 顺带发现

<!-- 过程中的取证/验证证据直接写本文件；backlog.md 只留 ≤3 行卡片。 -->

## ## 8. 复现复核+冻结现场取证（2026-10-19 深夜轮，真机 houji）

### 8.1 RED 确认（当前 HEAD 仍复现）

- HEAD devDebug 覆盖装+debug intent 起点（v2:4096），配方数字化：**force-stop→start→tap 列表顶会话→0.4s→整串 input text（%s 编码 68 字符）→sleep 5→uiautomator dump**。
- 判据：dump=2627B 空树（与 10-10 原发现精确同尺寸）+进程存活+focus 持有。
- 复现率：**4/7**（R3/R5/R6/R8 冻；delay 0.2/0.4/0.8 均有命中——概率性竞态，时序非唯一要素）。R4-R7 首轮无效（BACK 链路被 MIUI 全局搜索偷焦点，全部注入到 quicksearchbox——MIUI 桌面热区坑，教训：复现循环必须 force-stop 干净起点+焦点校验）。
- 模拟器 0.2-1.5s 八档 8 轮**零复现**——真机（MIUI）特有。

### 8.2 冻结现场系统级取证（R8，pid 18606）

- **主线程 syscall=22（epoll_pwait）+State=S+utime 2s 采样零增长**——非死锁非自旋，**Looper 空闲等消息**。
- **RenderThread syscall=22 同样空闲**——零 draw 帧请求（渲染管线无驱动，非 RenderThread 卡死）。
- SigQ=0（无 pending 输入）→ 系统侧无 ANR 依据；主动 tap 派发后仍冻（dump 仍 2627B）且无 ANR——输入通道活着但 UI 不再生效。
- 空树结构：window 装饰壳完整（FrameLayout>LinearLayout>…>空 android.view.View）——**ComposeView 位置无 semantics 内容**=Compose 语义树未被提供。
- 截图 37KB（大面积纯色佐证）。

### 8.3 冻结前最后一帧的完整时间线（logcat 18606）

| 时刻 | 事件 |
|---|---|
| 45.036 | Choreographer Skipped 34（进会话+历史加载开始） |
| 45.47 | Rss 307MB→384MB（+77MB 历史加载） |
| 45.58 | JIT 编译 SessionRow（9860KB code cache） |
| 45.69 | Choreographer Skipped 41 |
| 45.787 | input text KeyEvent 洪流开始（SHIFT_LEFT down） |
| 45.811 | WindowManager Unhandled key: SHIFT_LEFT（composer 未就绪吞键） |
| 46.289 | R 键 ACTION_UP 才被处理（洪流排队 0.5s） |
| 46.29 | **StrictMode 栈：Choreographer.doFrame→performDraw→AndroidComposeView.dispatchDraw:2224→measureAndLayout:1883→forceMeasureTheSubtree:1917→SnapshotStateObserver.observeReads**（IME 每字符强制测量风暴实证） |
| 50.6 | sticky GC（风暴余波） |
| 54.6 | a11y dump 响应：AccessibilityNodeInfo.setSelection hiddenapi denied×3（Compose 在构建节点但**返回空树**） |
| 54.69 | FrameInsert open fail（MIUI RenderThread 特性，非 app 代码，grep 0 命中） |
| 之后 | 完全寂静——无重组、无帧、无 a11y 内容 |

### 8.4 取证通道战报（免 root 边界）

tombstoned trace（SIGQUIT 后 written to trace_01→/data/anr 可列**不可读**）、debuggerd -b（root required）、/data/tombstones trace_00 缺失、无线 JDWP jdb attach 握手死（8 分钟零输出放弃）、run-as /proc 通道**有效**（syscall/status/task 扫描全通）。

### 8.5 假设收窄

**「组合本体挂起」**：semantics 树空+永不重组+主线程/RenderThread 双闲+无 ANR——最像 Recomposer 挂起（withFrameNanos 协程死）或 ViewTreeLifecycleOwner 断链（组合被 dispose 但 ViewRoot 装饰壳仍活着）。候选成因窗：IME 洪流×历史加载×窗口重建三叠。模拟器不复现指向 MIUI IME/显示层参与。
**下一步（收口必经）**：app 内黑匣子探针（DEBUG 门控 main 源集，repo [DEBUG-xxx] 先例文化）——帧间隙>5s 时自动打印：主线程栈+ViewTreeLifecycleOwner.currentState+AndroidComposeView attach 态+子树深度；夜间 soak 收割。


## ## 9. 黑匣子探针三连进化+组合内容真空定罪（2026-10-19 深夜二段）

### 9.1 观测者效应实证（探针设计学费）

| 版本 | 探测机制 | 复现 | 结论 |
|---|---|---|---|
| v1 | 主线程 500ms 心跳 post | 0/6 | 心跳扰动消息时序 |
| v2 | 被动 Choreographer 帧链+watchdog 读 /proc | 0/8 | **帧链让每帧有 pending callback——vsync 永不停，冻结条件被破坏**（冻结本质=帧停） |
| 对照 | 无探针同构建同时段 | 1/2 冻 | 实锤观测者效应非环境漂移 |
| v3 | **广播触发式**（零周期任务/零帧接触，仅注册 receiver） | 1/4+1/2+1/1 冻 | **零扰动成立** |

v3 触发命令：`adb shell am broadcast -a dev.ocbeacon.DEBUG_FREEZE_DUMP [--ez invalidate true]`（targetSdk 34+ 必须 RECEIVER_EXPORTED——实测 SecurityException 被吞的坑）。冻结态主线程 epoll 空闲可处理广播=免费进程内栈通道（tap 实验预证）。

### 9.2 冻结现场进程内快照（v3 收割，/tmp/545-probe4/frozen-4.log）

- 主线程栈=广播处理链本身（Looper→onReceive→report）——**消息通道完全健康**
- activityLifecycle=RESUMED、hasWindowFocus=true
- contentTree：ComposeView attached → **AndroidComposeView attached w=1200 h=2670**（有尺寸）→ 内部三件套（View 0x0/RippleContainer/AndroidViewsHandler）在——**但组合内容整体缺席**（semantics 空，与 uiautomator 2627B 空树对齐）

### 9.3 invalidate 定罪实验（/tmp/545-inv）

冻结态广播 `--ez invalidate true` 强行请求重绘：**frames 135→137（新帧正常产出）+ dump 2627→2627（内容依旧空）**。

**定罪收口**：Choreographer/traversal/SurfaceFlinger/主线程全部无罪；**冻结=Compose 组合内容真空**——组合在竞态窗内被清空/dispose，UI 之后「正常地」渲染空内容，故无 crash 无 ANR 无帧请求（空树无失效源）、force-stop 重启才恢复。

### 9.4 剩余嫌疑面（下一步 sentinel state 实验判别）

组合为何空而 Activity/ComposeView 活着：①Recomposer 被取消（协程域异常传播）②detach/reattach 竞态中旧组合销毁新组合未建（重建步骤被吞）③组合 apply 异常被消化。判别法：MainActivity setContent 外层挂 DEBUG 门控 sentinel `mutableStateOf`——冻结态广播改值，组合层观察者有反应=Recomposer 活（组合空但可重组→②dispose 路径）；无反应=Recomposer 死（→①③）。

