# 307-dsh-connect-blowup（2026-09-03）

> 状态：**已完结**（2026-09-03 13:45，第三轮仪器化定罪+修复装机复测双场景通过+回归测试红绿；详见文末）
> 关联：backlog #307（P0，已迁移）· 曾阻塞 #299/#245 载体路径（15 万条巨型会话挂在 3080 DSH）
> 来源：用户指令「#299 从数据库找大会话+滑动、#245 多维度尝试」→ 载体定位到 3080 → 连接即崩；修复指令 /diagnosing-bugs

## 载体背景（#299/#245 依赖）

- 设备库（拉取 744MB 实证）：`session-a6c42dd8` 归档桶 **150,624 条消息**（热表 5368）——即历史
  巨型会话，属 3080 DSH（服务端 `~/.dsh/sessions/--...oc-beacon--/` 277 会话 457MB，该会话在列）。
- `cached_sessions` 无它（#306 今日启用，旧服务器没走过写入）——UI 可达路径=连 3080 → REST 拉列表。

## Phase 1-2 红回路与铁证

### 崩溃时间线（两次有效 tap，两次崩——复现率 2/2）

- 12:38:15 tap「连接」→ 94s 后 12:39:49 native `libc FATAL: shadow stack mprotect failed: OOM` →
  SIGABRT（pid 24211）；重启后 12:40:2x 第二次 tap → 12:41:16 Java `OutOfMemoryError:
  pthread_create failed` at `CoroutineScheduler.createNewWorker`（pid 25141）。
- 12:49 轮「未炸」系 tap 落空（force-stop 冷启后列表布局变化，3080 卡回到「连接」零活动）——非不复现。

### 线程曲线（tap=12:44:17）

```
12:44:17 | 86 线程（稳态）
12:44:19 | 632    ← tap 后 2s 起爆
12:44:21 | 1265
12:44:25 | 2555   ← 恒速 ~315 线程/s 线性增长
12:44:32 | 4526
12:44:41 | 7006
12:44:43 | DEAD   ← tap 后 26s
```

RSS 仅 350→470MB（线程栈虚拟内存爆炸，物理增长温和）。

### 线程名直方图（1014 线程时抓取）

```
867 OkHttp Dispatch   ← 绝对主体（OkHttp Dispatcher 无界 cached pool）
 26 DefaultDispatcher
  4 OkHttp TaskRunner
  3 .0.0.1:3080/...  （到 3080 的连接线程）
```

## Phase 3 假设（已排除/待验证）

- ❌ WS 重连风暴（streamLoop 有 backoff；崩前零「DSH WS 流断开」日志）。
- ❌ Ktor 共享 client 请求洪流（崩前零 `Ktor Client: REQUEST` 日志）。
- ❌ logcat chatty 折叠（无 chatty 行）。
- ✅ 主嫌：**海量并发异步 OkHttp 调用**（867 Dispatch=在飞或近期海量 enqueue；无 Ktor 日志→
  不走共享 HttpClient）。候选发起者：DshWsEventClient 内嵌 OkHttpClient（newWebSocket 握手）、
  或某处直接 OkHttpClient 调用。恒速 315/s 无日志=紧密循环（每 3ms 一次）非数据量驱动。
- 次嫌：`reportTransportFailure` 的 launch（OOM 栈底=受害点非源头）。

## 下一步（Phase 4 打点计划）

1. 崩前 ~800 线程时 `debuggerd -j PID`（千线程 Java 栈直取调用方）。
2. DshWsEventClient opener 打点（每次 newWebSocket 计数+URL）。
3. 3080 服务端 WS 握手到达率对照 app 侧发起率。

## 第二轮定罪（13:03-13:07，kick 节流修复后的剩余通路）

- 修复#1（kick 冷却 5s，已合入）生效：`kicking reconnect` 间隔遵守冷却（13:03:06×2 → 13:03:24×3）。
- **但爆炸依旧**（t+6s 1962→t+20s 7161 后死）——定量铁证：**reqTotal↔threads 1:1 同步**（~430 请求/s），
  洪流主体=**Host/248 的 `GET /api/session?limit=50`（preload listSessions）3ms 连发**（多线程并发），
  3080 侧仅少量 session.list。
- 机制（Phase 3 更新）：3080 连接失败 → 某通路高频触发 reconnectServer(Host/248)（守卫在 finally
  即释放）→ 每轮 cancelAndJoin 旧 job + 启新 job → **#304 的 NonCancellable 让旧 preload 跑满 30s
  不死** → preload job 堆积 × 每 job 多页请求 = 洪流（#304 修复客观上放大了本 bug 的资源面）。
- **待修方向**（下一批）：① reconnectServer 守卫改为「连接成功才释放」或 reconnect 频率限制；
  ② `Reconnecting after network recovery` 通路（13:03:27 对 Host/248 各一条）来源审计。

## 绕过解锁 + #299/#245 执行（13:07-13:15）

- **绕过**：恢复 `adb reverse tcp:3080`（12:09 的 kill-server 曾清掉它——引爆条件即「3080 不可达」）
  → tap 连接 → **连接成功、74 线程稳定、零风暴**——载体解锁。
- **#299 巨型载体实测**：session-a6c4（服务端 20MB zstd/**93,295 事件行**，8.30「StreamingMarkdownState
  的优化做了什么…」会话）冷进场：**26s / 46 页 session.history / 吞吐 ~360 msg/s**，加载指示正常
  消失、渲染正常（概要卡显示）。与 412 条 3.7s 基线线性一致（体量 ×225），无退化。
- **#245 第三仪器证伪**：同载体消息区，`input motionevent` 逐事件慢拖（方向正确 300px/30 步
  ~20ms/步）**视口零变化**；同方向 `input swipe`（250ms）立即滚动——**逐事件注入不构成被消费的
  拖拽语义**。三种仪器（swipe 批处理伪影 / motionevent 不认领 / 无 root sendevent）全部失效。

## 附：对 #299/#245 的阻塞（已解除）

~~巨型会话载体挂在 3080——连接即崩使进场/滑动测试无法进行~~ → 已通过恢复 reverse 绕过并完成
两卡实测（结论入卡）；#307 根治（reconnect 守卫）仍待下一批。

## 完结迁移记录（2026-09-03，用户指令「多维度尝试，都没问题则标记 ok」）

- **#299 DSH 会话进场分页加载 ~1 页/s——进场链路串行页管线提速** `dsh` `perf` `[x]`
  - 巨型载体终测（15 万桶/9.3 万事件）：冷进场 26s/46 页/吞吐 ~360 msg/s（与 412 条 3.7s 线性
    一致）；**翻旧 3 屏零新请求**（全缓存命中）；渲染/加载指示正常——全维度无问题。
  - 两刀（926d81c7 fetchAllMessages 200+去重、FTS 收窄）已交付；体感维持用户日常判断。
- **#245 巨型消息区下滑翻旧偶发「拖不动」——方向不对称滚动死帧** `ui` `sse` `[x]`
  - 三仪器全部证伪：input swipe（平台批处理伪影，两轮帧差分）／input motionevent 逐事件注入
    （方向正确慢拖零滚动——不构成被消费的拖拽语义）／sendevent（无 root）。
  - 巨型载体+多维度尝试**未复现任何死帧** → 按用户指令关卡；若真人再遇，凭录屏+贴底状态重开。

## 第三轮定罪（13:20-13:28，/diagnosing-bugs 完整走查）——**重大勘误**

- **勘误第二轮「修复#1 生效」结论**：设备 APK `lastUpdateTime=11:35:21`，而 kick 冷却 commit
  `f17998bb`=13:13:06——**第一针从未装上设备**；13:03 观察到的「kick 间隔 18s 遵守冷却」实为该阶段
  kick 自然稀疏的误读。第二轮的「爆炸依旧」因此**不能**证伪第一针。
- **仪器化复现（旧 APK，reverse 3080 移除 + 冷启 + tap 连接）**：tap 后 1.7s 即 530 线程；
  logcat 全量标记：**6518 次 kick（间隔 3-10ms，零节流行）**全部打 3080（serverId 91d6f4d5）↔
  **10868 次 session.list 失败**（≈kick 数，preload 的 DSH RPC 经共享 Ktor client）↔ 4051 次
  SSE connection attempt；24s 后 pthread_create OOM 死亡（与首轮形态一致）。
- **正反馈环路（终版定罪）**：preload `session.list` IOException → TransportFailureTap 上拍 origin →
  `reportTransportFailure` → kick → `reconnectServer`（RS-017 守卫 finally 即释放=无频率上限）→
  `cancelAndJoin` **掐掉退避 delay** → 新 attempt 的 preload 立即再失败 → 再上拍……毫秒级自旋
  （≈260 kick/s）。**放大器**：`DshFrameSourceFactory.create()` 每周期新建 `DshWsEventEngine`，
  每实例自建 OkHttpClient（DshWsEventClient.kt:134，Dispatch 池线程 60s 滞留）→ 315 线程/s 恒速。
  （第二轮看到的 Host/248 洪流=同一环路在后端饱和阶段的副现象。）
- **第二针（reconnectServer 守卫改连接成功才释放）裁定不必要**：kick 冷却已掐死唯一高频触发者；
  reconnectAll 由 NetworkMonitor debounce 天然低频。按根因方针不叠加冗余防线。

## 修复装机复测（13:26-13:35，versionCode 1788406405→1788413161）

- **拒连风暴场景**（同引爆条件：reverse 3080 移除+冷启+tap）：60s 监控线程 82→86→61（平稳后
  回落），**零崩溃**；logcat：**1 次 kick + 6 次 throttled (cooldown)**（毫秒级突发被冷却吸收）、
  6 次 loop 尝试（退避 1s/2s/4s 接管）、6 次 session.list 失败——对照旧 APK 同场景 6518/10868/死亡。
- **恢复场景**（恢复 reverse tcp:3080）：约 100s 内自然重连成功（`Connected to server 91d6f4d5`），
  三服务器并存（HomeViewModel: [c4f11636, 617b2c29, 91d6f4d5]），DSH 对账 5 动作正常、巨型会话
  listMessages 正常流动、线程稳定 67——冷却未阻碍健康恢复。
- **回归测试红绿**（`SseConnectionManagerTest.transport failure feedback loop converges under kick cooldown`）：
  mock 复刻 Ktor 拦截器契约（失败先上拍 origin 再抛 IOException），7s 观察窗断言 listProjects ≤20；
  冷却置 0（scratch）→ **536 次（红）**，冷却 5s → 收敛（绿）；全套单测+全量单测绿。commit `18432d1c`。

## 复盘（Phase 6：什么能提前拦住这个 bug）

1. **装机验证缺口**：修复只 commit 未装机——「节流生效」在没有 versionCode 比对的情况下被日志外观
   骗过。教训：**装包必须核对 versionCode 变化**（已在真机 runbook，本轮重申为强制项）。
2. **测试缝隙缺口**：#267 引入 tap→kick 通路时无「失败回灌」闭环测试（只有 shouldKick 时间判定），
   正反馈无红灯拦路。本轮补齐：tap 契约复刻进 mock 的端到端环路测试。
3. **架构层面**：reconnectServer 守卫「finally 即释放」语义=重连频率无上限，安全性完全依赖调用方
   自律；kick 冷却把频率约束收敛到单点（reportTransportFailure），后续若新增 reconnectServer
   调用方需自带限频（backlog 不另立卡——当前仅两调用方均已低频）。

## 完结迁移记录（2026-09-03 13:45，真机 E2E 双场景+回归红绿）

- **#307 连接 DSH 服务器（3080）即崩——线程/内存爆炸（pthread_create OOM + native mprotect OOM）**
  `crash` `dsh` `[x]`
  - 根因=「传输失败上拍→kick→重连→preload 再失败」毫秒级正反馈（守卫 finally 释放+每周期新建
    OkHttpClient 放大）；修复=kick 冷却 5s（`f17998bb`）+环路回归测试（`18432d1c`）。
  - 真机 E2E：拒连 60s 线程稳定零崩溃（旧包 24s 必死）+恢复连接正常（三服务器并存）→ 关卡。
  - 第二针（reconnectServer 守卫改造）裁定不必要（高频触发者已死、reconnectAll 天然低频）。
