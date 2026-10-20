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


## ## 10. 九层判别链闭合——根因定谳（2026-10-20 白天）

soak 终报：1545 轮 801 冻结（51.8%），801 样本零偏差（RESUMED/focus/attached 全一致）。

分层探针判别链（L1-L9，全部 DEBUG 门控广播/纯观察零扰动）：
- L1 sentinel（广播 bump→组合根 snapshotFlow）：**Recomposer ALIVE**（4ms reacted）
- L2 NavGraph 层：活（reacted+backStack 打印正常执行）
- L3 chatScreen destination 层：**死**（never reacted）
- L4 栈快照：**backStack=[] current=null**（连 graph 根都没了）
- L5 栈序列：[?,home]→[?,home,sessions]→[?,home]→[] 
- L6 destination 逐次回调（listener 不 conflate）：chat 曾入栈（延迟 2.2s）后被弹
- L8 graph 指纹：全程同一对象（925b17b）——**setGraph 重设排除**
- L9 全部 popBackStack 调用点打点：**POP#8（SessionListRoute.onNavigateBack）连环 7 次**

### 完整因果链（定谳）

1. tap 会话行 → navigate(chat) 在忙主线程排队 **2.2s** 后入栈（IME 首波×列表测量风暴）
2. **MIUI input text 注入协议含 BACK**（收 IME 用）——在 chat 入栈后到达 → 系统 BACK 语义弹掉 chat（无 L9 打点=非程序回调）
3. IME 收起后**焦点回退到 sessions 返回 IconButton**——洪流中 **~9 个空格（KEYCODE_SPACE）** 逐个到达，Space 对 focused Button=click → **onNavigateBack 连环 7 次**（50.19-50.52，间隔 ~0.1s）
4. sessions pop → home pop → **空栈**（graph 根也被弹）
5. NavHost 对空栈渲染空 → 无失效源无帧请求无 ANR → **永久冻结**（主线程/RenderThread 双 epoll 空闲=§8.2 实证）

### 三大谜团全部解开

- 「手工几乎不可达」：真手指不会 0.5s 打 10 空格+系统 BACK 注入
- 「模拟器零复现」：AOSP input text 走 IME commitText，无 BACK/SPACE 键路径
- 「无 crash 无 ANR」：空栈是 NavHost 的静默合法渲染（空内容）

### 缺陷本质与修复方向

navigation-compose 假设「start destination 恒在栈」——竞态可打破该不变量且**系统不自愈**。根修=**空栈守护**（NavGraph 检测空栈→自动回 startDestination）：从「永久冻结」降级为「闪回首页」；恢复 NavHost 栈非空不变量。证据 /tmp/545-layer/{l5,l6,l8,l9}-*.log


## ## 11. 根修落地与窗口敏感性实证（2026-10-20 下午）

### 11.1 修复：NavGraph 空栈守护（flow 即时版）

生产代码（非 DEBUG 门控）：collect currentBackStack——空栈发射瞬间 navigate 回 startDestination（seenNonEmpty 防启动瞬态与连环自愈）。函数级 @SuppressLint("RestrictedApi")（currentBackStack 为 androidx.navigation 受限 API——lint 门禁拦截，探针取证+守护即时性必需）。

### 11.2 窗口敏感性（轮询版失守定罪）

- 轮询版（currentBackStackEntry==null 每 500ms 查）：10 轮 1 冻——R5 取证：守护 fired+栈已恢复 home（L6 dest->home 确认）**但屏幕仍空 5s+**——损伤双重（栈被弹空 A + 渲染链死亡 B），**空栈后 NavHost 走空内容渲染路径，~500ms 内渲染链已死，晚到的栈恢复救不回屏幕**。
- flow 即时版（空栈发射 0ms 动作）：15/15+20/20=**0/35 冻结**，守护全触发拦截。修复前基线 51.8%（801/1545）。

### 11.3 验证汇总

| 版本 | 轮数 | 冻结 | 守护触发 |
|---|---|---|---|
| 修复前（soak 夜） | 1545 | 801（51.8%） | — |
| 轮询版（弃用） | 10 | 1 | 全触发但晚 |
| **flow 即时版（交付）** | **35** | **0** | 35/35 |

单测绿（SseConnectionManager/ChatViewModelSend 各出现一次 flaky 单跑复跑均绿——与本批改动无关域）；lintDevDebug 过（7m04s，suppress 生效）。探针终态：FreezeWatchdog（L1/sentinel/广播）+NavGraph L2（简化公开 API）/L6（destination listener）/L9（pop 打点×10）保留 DEBUG 门控；L5/L8（RestrictedApi collect）判读使命完成退役。

